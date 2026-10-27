package tech.starryapi.platform.openai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How {@code apply_patch}, the editing tool of Codex, is given to a model that only knows functions. Codex declares it as
 * a custom tool: a grammar-constrained, free-form input and not a JSON object, which no model of another API and few of
 * OpenAI's can be asked for. The gateway therefore offers a function of the same name with one string argument,
 * {@code patch}, and turns what the model calls it with back into the custom tool call Codex expects ({@code input}, the
 * patch itself). The rules lived in {@link OpenAiUpstreamClient}, which uses them for the accounts it speaks Responses to
 * (design 7.6 asked for them to be taken out so that the relays of the Responses entry are given the same function); the
 * class is the one copy of them, and what {@code OpenAiUpstreamClient} sends and parses is the same as before.
 *
 * <p>Two sides. The request side makes the function of the declaration ({@link #functionTool} for the Responses shape the
 * Codex backend takes, {@link #chatFunctionTool} for the Chat shape of a relay) and of a {@code tool_choice}, and
 * {@link #patchArguments} writes the arguments of a call of it for a history that has the call in custom form. The
 * response side ({@link #toolUseType}, {@link #toolUseRawInput}) says which calls are custom tool calls and what their
 * input is: the {@code patch} of the arguments, a bare string, or the text itself when the model wrote no JSON, cut at
 * the end of the patch because models add words after it.
 *
 * <p>Nothing here keeps state; the instance holds the mapper that builds the nodes.
 */
public final class ApplyPatchEmulation {

    public static final String TOOL_NAME = "apply_patch";

    /** The type of the Responses item for a call of the editing tool: to Codex it is a custom tool, not a function. */
    public static final String CUSTOM_TOOL_CALL = "custom_tool_call";

    /** What a relay is told the function is for: the patch goes in the {@code patch} argument, which is JSON like any other. */
    public static final String CHAT_DESCRIPTION = "Use the `apply_patch` tool to edit files. Pass the whole patch as the `patch` argument,"
        + " a string that starts with *** Begin Patch and ends with *** End Patch.";

    private final ObjectMapper objectMapper;

    public ApplyPatchEmulation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---- the declaration ----

    /** Whether a tool (or the {@code function} of one) is the editing tool: it is told by its name and nothing else. */
    public boolean isApplyPatchTool(JsonNode source) {
        return TOOL_NAME.equals(source.path("name").asString(null));
    }

    /**
     * The function that stands for a tool when it is {@code apply_patch}, or the tool itself (the same instance) when it
     * is any other: the Responses request of a client that declares it as a custom tool, or as a function in either shape.
     */
    public JsonNode customToolAsFunction(JsonNode tool) {
        JsonNode function = tool.path("function");
        JsonNode source = function.isObject() ? function : tool;
        if (!isApplyPatchTool(source)) {
            return tool;
        }
        return functionTool(source);
    }

    /**
     * The function in the shape of the Responses API: {@code {type:function, name, description, parameters}}. The
     * description is the client's own when it has one, and {@code strict} is kept.
     */
    public ObjectNode functionTool(JsonNode source) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.put("name", TOOL_NAME);
        String description = firstNonBlank(
            source.path("description").asString(null),
            "Apply a raw patch. The patch string must start with *** Begin Patch and end with *** End Patch."
        );
        tool.put("description", description);
        ObjectNode parameters = tool.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ObjectNode patch = properties.putObject("patch");
        patch.put("type", "string");
        patch.put("description", "Raw apply_patch payload, including the *** Begin Patch and *** End Patch lines.");
        ArrayNode required = parameters.putArray("required");
        required.add("patch");
        if (source.has("strict")) {
            tool.set("strict", source.get("strict"));
        }
        return tool;
    }

    /**
     * The same function in the shape of the Chat Completions API: {@code {type:function, function:{name, description,
     * parameters}}}. Its description is not the client's: Codex tells its model that the tool is free-form and takes no JSON,
     * which is true of the custom tool and the opposite of this function, whose one required argument is a JSON string. A
     * model that did as it was told would write the patch where a relay reads the arguments, which are then not JSON, and
     * the answer would be refused as an interruption instead of applied. (The accounts that speak Responses are given the
     * function with the client's words, {@link #functionTool}, and a bare patch is read from what they answer.)
     */
    public ObjectNode chatFunctionTool(JsonNode source) {
        ObjectNode function = functionTool(source);
        function.remove("type");
        function.put("description", CHAT_DESCRIPTION);
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    /**
     * The {@code tool_choice} that forces the editing tool, as {@code {type:function, name}}, or the choice itself (the
     * same instance) when it is anything else: a string, or an object that names another tool.
     */
    public JsonNode toolChoice(JsonNode toolChoice) {
        if (!toolChoice.isObject()) {
            return toolChoice;
        }
        String name = toolChoiceName(toolChoice);
        if (!TOOL_NAME.equals(name)) {
            return toolChoice;
        }
        ObjectNode converted = objectMapper.createObjectNode();
        converted.put("type", "function");
        converted.put("name", name);
        return converted;
    }

    /** The choice that forces the editing tool in the Chat shape, {@code {type:function, function:{name}}}; else the choice itself. */
    public JsonNode chatToolChoice(JsonNode toolChoice) {
        JsonNode forced = toolChoice(toolChoice);
        if (forced == toolChoice) {
            return toolChoice;
        }
        ObjectNode converted = objectMapper.createObjectNode();
        converted.put("type", "function");
        converted.putObject("function").put("name", TOOL_NAME);
        return converted;
    }

    /** The name a {@code tool_choice} object points to: the {@code function.name} of the Chat shape or the {@code name} of the Responses one. */
    public static String toolChoiceName(JsonNode toolChoice) {
        JsonNode function = toolChoice.path("function");
        return firstNonBlank(function.path("name").asString(null), toolChoice.path("name").asString(null));
    }

    /** The arguments of a call of the function, {@code {"patch": ...}}, for a custom tool call of a history. */
    public String patchArguments(String patch) {
        return objectMapper.createObjectNode().put("patch", patch == null ? "" : patch).toString();
    }

    // ---- what the model called ----

    /**
     * The type a tool call has once it is known by name: the editing tool is a function to the model and a custom tool
     * to Codex, so a call of it is a {@link #CUSTOM_TOOL_CALL} whichever of the two the model's answer was.
     */
    public String toolUseType(String type, String name) {
        if (TOOL_NAME.equals(name) && "function_call".equals(type)) {
            return CUSTOM_TOOL_CALL;
        }
        return type;
    }

    /** The input of a custom call of the editing tool, normalized; any other call keeps the arguments it has. */
    public String toolUseRawInput(String type, String name, String rawInput) {
        if (!CUSTOM_TOOL_CALL.equals(type) || !TOOL_NAME.equals(name)) {
            return rawInput;
        }
        return normalizeInput(extractCustomToolInput(rawInput));
    }

    /** Whether a call is a custom call of the editing tool, which streams its input in pieces that may or may not be the whole patch. */
    public static boolean isCustomApplyPatchCall(String type, String name) {
        return CUSTOM_TOOL_CALL.equals(type) && TOOL_NAME.equals(name);
    }

    private String extractCustomToolInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawInput);
            if (parsed.isString()) {
                return parsed.asString("");
            }
            if (parsed.isObject()) {
                String text = firstNonBlank(
                    parsed.path("patch").asString(null),
                    parsed.path("input").asString(null),
                    parsed.path("text").asString(null)
                );
                if (!text.isBlank()) {
                    return text;
                }
                if (parsed.isEmpty()) {
                    return "";
                }
            }
        } catch (Exception ignored) {
            // The normal custom-tool path is already raw text.
        }
        return rawInput;
    }

    /** Whether a text ends with the line that closes a patch, so that a later, shorter piece of the same input is not taken for the whole. */
    public static boolean looksLikeCompletePatch(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        return normalized.endsWith("*** End Patch");
    }

    private static String normalizeInput(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int begin = normalized.indexOf("*** Begin Patch");
        if (begin > 0) {
            normalized = normalized.substring(begin);
        }
        int end = normalized.lastIndexOf("*** End Patch");
        if (end >= 0) {
            normalized = normalized.substring(0, end + "*** End Patch".length());
            return normalized + "\n";
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

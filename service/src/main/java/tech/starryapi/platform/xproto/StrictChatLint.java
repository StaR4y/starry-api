package tech.starryapi.platform.xproto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The last look at a Chat body before an OpenAI-format relay is sent it (design 7.6): does it have the structure a strict
 * relay requires? A body that fails is not sent to that member, which is skipped for the request as a rejected
 * conversion (design 3, step 5b), and the request goes to a source that can take it. So the lint must not fail on a
 * history that Claude Code or OpenCode legitimately has once {@link ChatCanonicalizer} has been at it; that is the
 * acceptance of the pair, and {@code StrictChatLintTest} runs the conversions of the real clients through both.
 *
 * <p>It checks structure and nothing about content:
 *
 * <ul>
 *   <li>Every key is one the whitelist of {@link ChatWireSanitizer} produces, at the body, the message, the part, the
 *       image, the tool call and the tool level, and the shapes are the right kinds (a message is an object with a
 *       known role and a content that is a string, an array of text and image parts, or, for an assistant with tool
 *       calls only, null).
 *   <li>Every tool message answers one of the calls of the assistant message before it (the assistant message and the
 *       tool messages that follow it are a group), and answers each call once.
 *   <li>Every call of every assistant message but the last turn is answered. The last turn is an assistant message
 *       that nothing but tool messages follow.
 *   <li>Every tool call id matches {@code ^[A-Za-z0-9_-]{1,64}$} and is used once in the whole history, every tool
 *       is a function, and every function has a name and, for a call, arguments as a string; the {@code extra_content}
 *       of a call, if it has one, is a thought signature and nothing else.
 *   <li>{@code stop} is a string or at most four strings.
 *   <li>{@code tool_choice} is {@code auto}, {@code none} or {@code required}, or the function of a tool that the body
 *       has, and the body has tools; a {@code cache_control} is {@code {type:ephemeral}} and at most a {@code ttl}.
 * </ul>
 *
 * Whether the arguments are valid JSON, the text is not empty or the schemas are right is not looked at: the
 * canonicalizer makes the text, and a relay's answer to the rest is a classification of its own.
 *
 * <p>The problems are fixed sentences with the position of the message in them. They hold nothing of the body, so they
 * can be logged and counted as the reason a member was skipped.
 */
public final class StrictChatLint {

    static final int MAX_STOP_SEQUENCES = 4;
    private static final int MAX_PROBLEMS = 20;
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");

    private StrictChatLint() {
    }

    public static boolean passes(JsonNode body) {
        return problems(body).isEmpty();
    }

    /** What is wrong with the body, in the order found and at most twenty; empty when nothing is. */
    public static List<String> problems(JsonNode body) {
        List<String> problems = new ArrayList<>();
        if (body == null || !body.isObject()) {
            problems.add("The body is not an object");
            return problems;
        }
        if (hasUnknownKey(body, ChatWireSanitizer.BODY_KEYS)) {
            add(problems, "The body has a key outside the whitelist");
        }
        stop(body.path("stop"), problems);
        tools(body.path("tools"), problems);
        toolChoice(body, problems);
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            add(problems, "The body has no messages");
            return problems;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            message(i, messages.get(i), ids, problems);
        }
        groups(messages, problems);
        return problems;
    }

    /** The function names OpenAI-format upstreams take: letters, digits, {@code _} and {@code -}, at most 64 of them. */
    private static final Pattern UPSTREAM_FUNCTION_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    /**
     * Whether a function name is one the upstreams other than Gemini's take. Gemini takes names with dots and colons up to 128
     * characters, and a Gemini client uses them (MCP servers add theirs), where the OpenAI format and Anthropic's refuse the
     * request. Not part of {@link #problems}: a Chat, Messages or Responses client is held to the rule by the API it wrote the
     * request for, and only a Gemini request can break it. It is a feature of that request ({@link
     * RequestFeatureDetector.Feature#FUNCTION_NAME}) that the policy judges per vendor, not a defect of the conversion.
     */
    public static boolean isUpstreamFunctionName(String name) {
        return UPSTREAM_FUNCTION_NAME.matcher(name).matches();
    }

    // ---- the body ----

    private static void stop(JsonNode stop, List<String> problems) {
        if (stop.isMissingNode() || stop.isString()) {
            return;
        }
        boolean valid = stop.isArray() && stop.size() <= MAX_STOP_SEQUENCES;
        for (JsonNode sequence : stop) {
            valid &= sequence.isString();
        }
        if (!valid) {
            add(problems, "stop is neither a string nor at most " + MAX_STOP_SEQUENCES + " strings");
        }
    }

    private static void tools(JsonNode tools, List<String> problems) {
        if (tools.isMissingNode()) {
            return;
        }
        if (!tools.isArray() || tools.isEmpty()) {
            add(problems, "tools is not a non-empty array");
            return;
        }
        for (int i = 0; i < tools.size(); i++) {
            JsonNode tool = tools.get(i);
            JsonNode function = tool.path("function");
            if (!tool.isObject() || hasUnknownKey(tool, ChatWireSanitizer.TOOL_KEYS)
                || !"function".equals(tool.path("type").stringValue(null))
                || !function.isObject() || hasUnknownKey(function, ChatWireSanitizer.TOOL_FUNCTION_KEYS)) {
                add(problems, "Tool " + i + " is not a function in the whitelisted shape");
            } else if (function.path("name").stringValue("").isBlank()) {
                add(problems, "Tool " + i + " has no function name");
            }
        }
    }

    /** The strings of the whitelist, or the function of a tool that is sent; with no tools there is nothing to choose. */
    private static void toolChoice(JsonNode body, List<String> problems) {
        JsonNode choice = body.path("tool_choice");
        if (choice.isMissingNode()) {
            return;
        }
        JsonNode tools = body.path("tools");
        boolean valid = tools.isArray() && !tools.isEmpty();
        if (valid && choice.isString()) {
            valid = ChatWireSanitizer.TOOL_CHOICE_STRINGS.contains(choice.stringValue());
        } else if (valid && choice.isObject()) {
            JsonNode function = choice.path("function");
            valid = !hasUnknownKey(choice, ChatWireSanitizer.TOOL_CHOICE_KEYS) && "function".equals(choice.path("type").stringValue(null))
                && function.isObject() && function.size() == 1 && function.path("name").isString()
                && sends(tools, function.path("name").stringValue());
        } else {
            valid = false;
        }
        if (!valid) {
            add(problems, "tool_choice is not one of the whitelisted forms for the tools of the body");
        }
    }

    private static boolean sends(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("function").path("name").stringValue(null))) {
                return true;
            }
        }
        return false;
    }

    private static boolean cacheControlIsWhitelisted(JsonNode holder) {
        if (!holder.has("cache_control")) {
            return true;
        }
        JsonNode control = holder.path("cache_control");
        return control.isObject() && !hasUnknownKey(control, ChatWireSanitizer.CACHE_CONTROL_KEYS)
            && "ephemeral".equals(control.path("type").stringValue(null))
            && (!control.has("ttl") || control.path("ttl").isString());
    }

    // ---- one message ----

    private static void message(int index, JsonNode message, Set<String> ids, List<String> problems) {
        String where = "Message " + index;
        if (!message.isObject()) {
            add(problems, where + " is not an object");
            return;
        }
        if (hasUnknownKey(message, ChatWireSanitizer.MESSAGE_KEYS) || !cacheControlIsWhitelisted(message)) {
            add(problems, where + " has a key outside the whitelist");
        }
        String role = message.path("role").stringValue("");
        if (!ROLES.contains(role)) {
            add(problems, where + " has no valid role");
        }
        boolean hasCalls = message.has("tool_calls");
        if (hasCalls && !"assistant".equals(role)) {
            add(problems, where + " has tool calls and is not an assistant message");
        }
        content(where, message.path("content"), "assistant".equals(role) && hasCalls, problems);
        if (hasCalls) {
            toolCalls(where, message.path("tool_calls"), ids, problems);
        }
        if ("tool".equals(role) && !message.path("tool_call_id").isString()) {
            add(problems, where + " is a tool message without the id of a call");
        }
        if (!"tool".equals(role) && message.has("tool_call_id")) {
            add(problems, where + " names a tool call and is not a tool message");
        }
    }

    private static void content(String where, JsonNode content, boolean mayBeNull, List<String> problems) {
        if (content.isString()) {
            return;
        }
        if (content.isNull() || content.isMissingNode()) {
            if (!mayBeNull) {
                add(problems, where + " has no content");
            }
            return;
        }
        if (!content.isArray()) {
            add(problems, where + " has content that is neither text nor parts");
            return;
        }
        for (JsonNode part : content) {
            String type = part.path("type").stringValue("");
            boolean valid = part.isObject() && !hasUnknownKey(part, ChatWireSanitizer.PART_KEYS) && cacheControlIsWhitelisted(part)
                && (("text".equals(type) && part.path("text").isString() && !part.has("image_url"))
                    || ("image_url".equals(type) && part.path("image_url").isObject()
                        && part.path("image_url").path("url").isString() && !part.has("text")
                        && !hasUnknownKey(part.path("image_url"), ChatWireSanitizer.IMAGE_URL_KEYS)));
            if (!valid) {
                add(problems, where + " has a content part that is not text or an image in the whitelisted shape");
                return;
            }
        }
    }

    private static void toolCalls(String where, JsonNode calls, Set<String> ids, List<String> problems) {
        if (!calls.isArray() || calls.isEmpty()) {
            add(problems, where + " has tool_calls that is not a non-empty array");
            return;
        }
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            if (!call.isObject() || hasUnknownKey(call, ChatWireSanitizer.TOOL_CALL_KEYS)
                || !"function".equals(call.path("type").stringValue(null)) || !function.isObject()
                || hasUnknownKey(function, ChatWireSanitizer.TOOL_CALL_FUNCTION_KEYS)
                || !function.path("arguments").isString()) {
                add(problems, where + " has a tool call that is not a function call in the whitelisted shape");
                continue;
            }
            if (function.path("name").stringValue("").isBlank()) {
                add(problems, where + " has a tool call without a function name");
            }
            if (call.has("extra_content") && !isThoughtSignatureAlone(call)) {
                add(problems, where + " has a tool call whose extra_content is not a thought signature alone");
            }
            String id = call.path("id").stringValue("");
            if (!ChatCanonicalizer.LEGAL_ID.matcher(id).matches()) {
                add(problems, where + " has a tool call id that does not match the allowed pattern");
            } else if (!ids.add(id)) {
                add(problems, where + " has a tool call id that is used more than once");
            }
        }
    }

    // ---- the calls and their answers ----

    /** An assistant message with calls and the tool messages after it is a group; any other tool message is a stray. */
    private static void groups(JsonNode messages, List<String> problems) {
        int i = 0;
        while (i < messages.size()) {
            JsonNode message = messages.get(i);
            String role = message.path("role").stringValue("");
            if ("tool".equals(role)) {
                add(problems, "Message " + i + " is a tool message that no assistant tool call precedes");
                i++;
                continue;
            }
            if (!"assistant".equals(role) || !message.path("tool_calls").isArray()) {
                i++;
                continue;
            }
            Set<String> called = new HashSet<>();
            for (JsonNode call : message.path("tool_calls")) {
                called.add(call.path("id").stringValue(""));
            }
            Set<String> answered = new HashSet<>();
            int next = i + 1;
            while (next < messages.size() && "tool".equals(messages.get(next).path("role").stringValue(""))) {
                String id = messages.get(next).path("tool_call_id").stringValue("");
                if (!called.contains(id)) {
                    add(problems, "Message " + next + " answers a call that the assistant message before it did not make");
                } else if (!answered.add(id)) {
                    add(problems, "Message " + next + " answers a call that is already answered");
                }
                next++;
            }
            boolean lastTurn = next == messages.size();
            if (!lastTurn && answered.size() < called.size()) {
                add(problems, "Message " + i + " has tool calls that are not answered before the last turn");
            }
            i = next;
        }
    }

    /** The {@code extra_content} of a call is {@code {google:{thought_signature}}} with a signature that can be one, and no more. */
    private static boolean isThoughtSignatureAlone(JsonNode call) {
        JsonNode extra = call.path("extra_content");
        JsonNode google = extra.path("google");
        return extra.isObject() && extra.size() == 1 && google.isObject() && google.size() == 1
            && ChatWireSanitizer.thoughtSignatureOf(call) != null;
    }

    private static boolean hasUnknownKey(JsonNode object, Set<String> allowed) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<String> problems, String problem) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(problem);
        }
    }
}

package tech.starryapi.platform.xproto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a Gemini {@code generateContent} request into the Chat Completions body that {@link ChatWireSanitizer} makes a
 * relay's request from (design 7.6, GEMINI). The entry's own conversion to Chat, {@code GeminiRequestAdapter}, is what Kiro
 * and the Anthropic keys are given, and it stays as it is because their requests must not change (design 0, I4). It is not
 * good enough for a strict relay:
 *
 * <ul>
 *   <li>A call and its answer were tied by the id the client wrote, or by the name of the function when it wrote none, so
 *       two calls of one function (a model that reads two files in one turn, or the same file twice in a session) had one
 *       id, and a relay was given a history whose answers could not be told apart. Gemini clients seldom send ids: the
 *       field is new to the API and the SDKs of before it drop it. Here every call has an id of its own (the client's, when it
 *       wrote one that nothing has used yet; else {@code call_1}, {@code call_2}, … in the order of the calls, the first that
 *       is free, so the same history is the same bytes at every request, which a relay that caches by prefix needs), and an
 *       answer is paired with the call it answers: the call that has the id the client wrote, if the answer says one and
 *       names no other function (two calls may have been written with one id); else the oldest call of that function that is
 *       not answered yet (first in, first out, which is the order a model asks for them and a client answers them); else it
 *       answers nothing, and is sent as the id of no call for {@link ChatCanonicalizer} to tell as the user's text. The calls
 *       that no answer was paired with are not open to the answers of the turns after the next turn of the model, which
 *       are for its calls (a client that went on after a call without answering it, and calls the same function again, is
 *       answered by the new call); the canonicalizer gives the old call the answer {@code Tool result missing}.
 *   <li>The text of a turn that had images, files or audio lost them without a word. An {@code inlineData} image of the
 *       user's is an {@code image_url} part with a data URI, and a {@code fileData} image is one when it names an address a
 *       relay can fetch (an {@code http} or {@code https} address that is not Google's: the files of the Files API and {@code
 *       gs://} need Google's own credentials); the sanitizer replaces what a member without vision cannot see, and records
 *       it. Audio and any other file are parts that only name the kind, never the data, which the sanitizer replaces
 *       whatever the member. The data of an image is written in the standard base64 alphabet a data URI has (the Python SDK
 *       writes the URL-safe one). Media in the turn of the model have no place in a Chat history that a relay takes and are
 *       left out; the media in the answer of a function are told as present in the tool message ({@code [image omitted]}, as
 *       the Messages conversion tells the image of a tool result) and no more; {@link RequestFeatureDetector} names them, so
 *       that the policy does not promise what is not done.
 *   <li>The thought signature of a call ({@code thoughtSignature} of the part that has the {@code functionCall}) was lost,
 *       and a Gemini model that is not given back what it signed answers a worse turn or, since Gemini 3, refuses the
 *       request. It goes in the call as {@code extra_content.google.thought_signature}, the form Google's OpenAI-compatible
 *       API writes; the sanitizer sends it only to a model whose vendor signs ({@link
 *       ChatWireSanitizer#carriesThoughtSignatures}). The signatures of parts that are not calls, and the summaries of
 *       thoughts ({@code thought: true}), are not carried.
 * </ul>
 *
 * <p>The request is read in camelCase, the spelling of its fields in the JavaScript SDK; {@link GeminiRequestNormalizer}
 * writes the Python SDK's and the REST reference's snake_case so, before this class and the detector read it. A turn that
 * cannot be read at all (not an object, parts of no shape) is a conversion that fails, and not a turn that is left out, since a
 * relay would then answer an earlier prompt. A turn of the user that holds nothing a relay can be given (no parts, parts that are
 * blank text, a summary of thoughts, a kind of part this class has no place for) is not left out either, for the same reason: it
 * is a user message with no content, which {@link ChatCanonicalizer} fills ({@code [non-text content omitted]}, as it does for a
 * turn of an image the member cannot see) and records, and the native sources' conversion makes {@code Continue} of it. A turn
 * of the model that holds nothing but media is an assistant message that says what it held ({@code [image omitted]}), so that the
 * turns of the user around it are not made one as if the model had said nothing between them.
 *
 * <p>The rest, in the order of the body. {@code systemInstruction} is the first {@code system} message, the text of its
 * parts joined by a newline. A turn of the model is an assistant message with the text of its parts and the calls they
 * make as {@code tool_calls}; a turn of the user is the answers it holds as {@code tool} messages, in the order of its parts,
 * then a user message with its text and media (a string when it has only text, else the parts in their order), which is what
 * the entry's conversion does and what a client that sends text with its answers means. Any role but {@code model} is the
 * user's ({@code function} is the role older clients give an answer).
 *
 * <p>The functions are the {@code functionDeclarations} of the tools, with the schema of {@code parametersJsonSchema} as it
 * is, or of {@code parameters} as a JSON schema (the SDKs write the types of Gemini's own schema as {@code OBJECT} and {@code
 * STRING}, which a relay refuses, its counts as strings, and, in Python, its keywords in snake_case; see {@link
 * #geminiSchema}); a function with no schema, or with one that has no root {@code type} or no {@code properties}, is given
 * them, as OpenAI's Chat API refuses parameters that are not of type object. The other tools of the request ({@code
 * googleSearch}, {@code codeExecution}, {@code urlContext} and the rest) are not functions and are left out; the detector says
 * a request that has them cannot be given to a relay. {@code toolConfig.functionCallingConfig} is the {@code tool_choice}:
 * {@code NONE} is {@code none}; {@code ANY} is {@code required}, or the function when exactly one is allowed; a choice among
 * some of the functions has no word in a relay's format and is handed on as the {@code allowed_tools} of the OpenAI API, for
 * the sanitizer to leave out and record, as it does for a Chat client that sends one. {@code AUTO} and {@code VALIDATED} (the
 * model decides between a call and words, and only its calls are checked) are the default and no choice, except that the
 * allowed names of {@code VALIDATED} that leave a function out are such a restriction.
 *
 * <p>{@code generationConfig}: {@code temperature}, {@code topP}, {@code maxOutputTokens} (as {@code max_tokens}), {@code
 * stopSequences} (as {@code stop}), {@code presencePenalty}, {@code frequencyPenalty} and {@code seed}; and the JSON mode,
 * {@code responseFormat.text} (or {@code responseMimeType}) {@code application/json} with its schema ({@code responseJsonSchema} or
 * {@code responseSchema} if there is one), as the {@code response_format} (a schema is {@code json_schema}, named {@code
 * response}; without one, {@code json_object}). {@code thinkingConfig} is not converted: the sanitizer reads it from the original body, as it reads the
 * {@code thinking} of a Messages request, which is why the name it records is {@code thinking} and the policy's is the same
 * (a {@code thinkingBudget} of -1, Gemini's dynamic thinking, asks for no level and sends none). Neither are {@code topK},
 * {@code candidateCount}, {@code safetySettings}, {@code cachedContent} and the rest: they are the policy's to name, and the
 * sanitizer's whitelist would keep them from a relay anyway.
 *
 * <p>Not done here, because they have a place of their own: legal and unique call ids, answers that follow their calls,
 * empty messages and the tool history of a body without tools ({@link ChatCanonicalizer}); the whitelist and the
 * placeholders of what a member cannot take ({@link ChatWireSanitizer}); and the judgment of what the request asks for
 * ({@link RequestFeatureDetector}). The body that results passes {@link StrictChatLint} once those have been at it, for any
 * request a Gemini client can send that has a message to answer. The request is not changed, and the result shares nothing
 * with it that could be edited.
 */
@Component
public final class GeminiChatConverter {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    /** The id a call is given when the client wrote none, followed by the number of the call. */
    static final String GENERATED_ID_PREFIX = "call_";

    private static final String MODEL_ROLE = "model";

    /** What a part of media is, by its MIME type. */
    enum MediaKind {
        IMAGE, AUDIO, FILE
    }

    /**
     * An {@code inlineData} or {@code fileData} part.
     *
     * @param kind    by the MIME type
     * @param carried for an image, whether a relay can be given it: it has data, or an address a relay can fetch; false for
     *                the media that are not images
     */
    record Media(MediaKind kind, boolean carried) {
    }

    private final ObjectMapper objectMapper;

    public GeminiChatConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param model   the model of the path of the endpoint, with or without {@code models/}; null leaves the model out of
     *                the body (the dispatcher writes the relay's own name for it)
     * @param request the {@code generateContent} request as the client sent it
     * @return the Chat Completions body, new and not shared with the request
     */
    public ObjectNode toChat(String model, JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("The Gemini request must be a JSON object");
        }
        ObjectNode chat = objectMapper.createObjectNode();
        String name = model == null ? "" : model.strip();
        if (name.startsWith("models/")) {
            name = name.substring("models/".length());
        }
        if (!name.isBlank()) {
            chat.put("model", name);
        }
        JsonNode config = request.path("generationConfig");
        number(config, "temperature", "temperature", chat);
        number(config, "topP", "top_p", chat);
        number(config, "maxOutputTokens", "max_tokens", chat);
        number(config, "presencePenalty", "presence_penalty", chat);
        number(config, "frequencyPenalty", "frequency_penalty", chat);
        if (config.path("seed").isIntegralNumber()) {
            chat.set("seed", config.path("seed"));
        }
        ArrayNode stop = stopSequences(config.path("stopSequences"));
        if (!stop.isEmpty()) {
            chat.set("stop", stop);
        }
        ArrayNode messages = chat.putArray("messages");
        String system = instructionText(request.path("systemInstruction"));
        if (!system.isBlank()) {
            ObjectNode message = messages.addObject();
            message.put("role", "system");
            message.put("content", system);
        }
        Calls calls = new Calls();
        JsonNode contents = request.path("contents");
        if (contents.isArray()) {
            for (JsonNode content : contents) {
                content(messages, content, calls);
            }
        } else if (!contents.isMissingNode() && !contents.isNull()) {
            // One turn where the API has a list of them, which a client that skipped GeminiRequestNormalizer may have written.
            content(messages, contents, calls);
        }
        Set<String> declared = new LinkedHashSet<>();
        ArrayNode tools = tools(request.path("tools"), declared);
        if (!tools.isEmpty()) {
            chat.set("tools", tools);
            JsonNode choice = toolChoice(request.path("toolConfig"), declared);
            if (choice != null) {
                chat.set("tool_choice", choice);
            }
        }
        responseFormat(config, chat);
        return chat;
    }

    // ---- the contents ----

    /**
     * A turn of the model with no parts makes no message. A turn of the user with none makes an empty one, as every turn that
     * holds nothing a relay can be given does (see the class description). A turn that cannot be read (not an object, parts that
     * are neither a list nor one part, a part that is not an object) is not left out: the relay would answer an older prompt than
     * the one the client asked, so the request is not converted and the next tier serves it.
     */
    private void content(ArrayNode messages, JsonNode content, Calls calls) {
        if (!content.isObject()) {
            throw unreadable("a turn of the contents is not an object");
        }
        JsonNode parts = content.path("parts");
        if (parts.isMissingNode() || parts.isNull()) {
            parts = FACTORY.arrayNode();
        } else if (parts.isObject()) {
            parts = FACTORY.arrayNode().add(parts);
        } else if (!parts.isArray()) {
            throw unreadable("the parts of a turn are neither a list nor a part");
        }
        if (MODEL_ROLE.equals(content.path("role").stringValue(""))) {
            calls.modelTurnOpens();
            modelTurn(messages, parts, calls);
        } else {
            calls.userTurnSeen();
            userTurn(messages, parts, calls);
        }
    }

    private static IllegalArgumentException unreadable(String what) {
        return new IllegalArgumentException("The Gemini request cannot be read for a relay: " + what);
    }

    /**
     * The text of the turn and the calls it makes; its thoughts have no place in a relay's history, nor have its media, which are
     * told as present only when they are all the turn holds.
     */
    private void modelTurn(ArrayNode messages, JsonNode parts, Calls calls) {
        StringBuilder text = new StringBuilder();
        StringBuilder media = new StringBuilder();
        ArrayNode toolCalls = null;
        for (JsonNode part : parts) {
            if (!part.isObject()) {
                throw unreadable("a part of a turn is not an object");
            }
            JsonNode functionCall = part.path("functionCall");
            if (functionCall.isObject()) {
                if (toolCalls == null) {
                    toolCalls = FACTORY.arrayNode();
                }
                toolCalls.add(toolCall(part, functionCall, calls));
            } else if (isText(part) && !isThought(part)) {
                append(text, part.path("text").stringValue(""));
            } else if (media(part) != null) {
                append(media, placeholder(media(part).kind()));
            }
        }
        if (toolCalls == null && text.toString().isBlank()) {
            if (media.isEmpty()) {
                return;
            }
            text.append(media);
        }
        ObjectNode message = messages.addObject();
        message.put("role", "assistant");
        message.put("content", text.toString());
        if (toolCalls != null) {
            message.set("tool_calls", toolCalls);
        }
    }

    private ObjectNode toolCall(JsonNode part, JsonNode functionCall, Calls calls) {
        String name = functionCall.path("name").stringValue("");
        ObjectNode call = objectMapper.createObjectNode();
        call.put("id", calls.open(functionCall.path("id").stringValue(""), name).id);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", name);
        JsonNode args = functionCall.path("args");
        function.put("arguments", args.isMissingNode() || args.isNull() ? "{}" : args.toString());
        String signature = part.path("thoughtSignature").stringValue("");
        if (!signature.isBlank()) {
            call.set("extra_content", ChatWireSanitizer.extraContent(signature));
        }
        return call;
    }

    /** The answers of the turn as tool messages, then what the user said besides, as the entry's conversion orders them. */
    private void userTurn(ArrayNode messages, JsonNode parts, Calls calls) {
        List<ObjectNode> userParts = new ArrayList<>();
        boolean onlyText = true;
        boolean answered = false;
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (!part.isObject()) {
                throw unreadable("a part of a turn is not an object");
            }
            JsonNode functionResponse = part.path("functionResponse");
            Media media = media(part);
            if (functionResponse.isObject()) {
                toolMessage(messages, functionResponse, calls);
                answered = true;
            } else if (media != null) {
                userParts.add(mediaPart(media, part));
                onlyText = false;
            } else if (isText(part) && !isThought(part)) {
                String value = part.path("text").stringValue("");
                if (!value.isBlank()) {
                    ObjectNode textPart = FACTORY.objectNode();
                    textPart.put("type", "text");
                    textPart.put("text", value);
                    userParts.add(textPart);
                    append(text, value);
                }
            }
        }
        if (userParts.isEmpty()) {
            if (!answered) {
                // The turn held nothing a relay can be given; it is still a turn of the user's, and the one to answer when it is
                // the last (the canonicalizer fills it and records it).
                ObjectNode empty = messages.addObject();
                empty.put("role", "user");
                empty.put("content", "");
            }
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        if (onlyText) {
            message.put("content", text.toString());
        } else {
            ArrayNode content = message.putArray("content");
            userParts.forEach(content::add);
        }
    }

    private void toolMessage(ArrayNode messages, JsonNode functionResponse, Calls calls) {
        Call answered = calls.answer(functionResponse.path("id").stringValue(""), functionResponse.path("name").stringValue(""));
        ObjectNode message = messages.addObject();
        message.put("role", "tool");
        message.put("tool_call_id", answered != null ? answered.id : calls.unusedId());
        JsonNode response = functionResponse.path("response");
        StringBuilder content = new StringBuilder(response.isMissingNode() || response.isNull() ? "" : response.toString());
        // A tool message holds text: the media of the answer (parts of the answer that are images, audio or files) are told as
        // present, as the Messages conversion tells the image of a tool result, so that the model is not told the tool returned
        // nothing, which the canonicalizer would do with an empty message.
        JsonNode answerParts = functionResponse.path("parts");
        if (answerParts.isArray()) {
            for (JsonNode answerPart : answerParts) {
                Media inAnswer = answerPart.isObject() ? media(answerPart) : null;
                if (inAnswer != null) {
                    content.append(content.isEmpty() ? "" : "\n").append(placeholder(inAnswer.kind()));
                }
            }
        }
        message.put("content", content.toString());
    }

    private static String placeholder(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> ChatWireSanitizer.IMAGE_PLACEHOLDER;
            case AUDIO -> ChatWireSanitizer.AUDIO_PLACEHOLDER;
            case FILE -> ChatWireSanitizer.FILE_PLACEHOLDER;
        };
    }

    private static boolean isText(JsonNode part) {
        return part.path("text").isString();
    }

    /** A summary of the model's thinking, which the API sends back with the history and no relay can use. */
    static boolean isThought(JsonNode part) {
        return part.path("thought").isBoolean() && part.path("thought").booleanValue();
    }

    private static void append(StringBuilder text, String more) {
        if (more.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(more);
    }

    // ---- the calls and their answers ----

    /** A call of the history: the id it has in the body, the function it calls and whether an answer has been paired with it. */
    private static final class Call {
        final String id;
        final String name;
        boolean answered;

        Call(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * The calls of the history in the order they are made, for the answers to be paired with. Each call is in two queues,
     * the one of its function and the one of all, and an answer takes the first of its queue that is not answered yet; a call
     * answered by its id is passed over when its turn comes, so that no answer scans the history again.
     */
    private static final class Calls {
        private final Set<String> used = new HashSet<>();
        private final Map<String, Call> byId = new HashMap<>();
        private final Map<String, ArrayDeque<Call>> byName = new HashMap<>();
        private final ArrayDeque<Call> all = new ArrayDeque<>();
        private int generated;
        private boolean userSince;

        /** A turn of the user has been read since the last turn of the model. */
        void userTurnSeen() {
            userSince = true;
        }

        /**
         * A turn of the model that comes after a turn of the user is the model's next step: the calls of the steps before that
         * have no answer by now are not waiting for one that the answers to come are for, which are for the calls of this step
         * (a client that went on without answering a call, and then called the function again, answers the second call). The
         * calls stay in the history for the canonicalizer, which gives them the answer {@code Tool result missing}, and an
         * answer that names one by its id is still paired with it. Turns of the model that follow each other are one step.
         */
        void modelTurnOpens() {
            if (userSince) {
                byName.clear();
                all.clear();
                userSince = false;
            }
        }

        /**
         * Records a call under the id the client wrote for it, or the first free one of ours. Only the ids a client wrote are
         * known by id: an answer that says {@code call_1} means the call its client called so, not the one this class
         * numbered that.
         */
        Call open(String clientId, String name) {
            boolean written = !clientId.isBlank() && !used.contains(clientId);
            String id = written ? clientId : unusedId();
            used.add(id);
            Call call = new Call(id, name);
            if (written) {
                byId.put(id, call);
            }
            byName.computeIfAbsent(name, key -> new ArrayDeque<>()).add(call);
            all.add(call);
            return call;
        }

        /** An id that no call has and none has been given for an answer to nothing: {@code call_1}, {@code call_2}, … */
        String unusedId() {
            String id;
            do {
                id = GENERATED_ID_PREFIX + (++generated);
            } while (used.contains(id));
            used.add(id);
            return id;
        }

        /** The call an answer answers, which is then answered, or null: see the class description of the converter. */
        Call answer(String id, String name) {
            Call call = id.isBlank() ? null : byId.get(id);
            // Two calls may have been written with one id (parallel calls of a client that numbers them badly): the call that
            // has the id is the answer's only if the answer does not name another function.
            if (call == null || call.answered || (!name.isBlank() && !name.equals(call.name))) {
                call = first(name.isBlank() ? all : byName.get(name));
            }
            if (call != null) {
                call.answered = true;
            }
            return call;
        }

        private static Call first(ArrayDeque<Call> queue) {
            while (queue != null && !queue.isEmpty()) {
                Call head = queue.peekFirst();
                if (!head.answered) {
                    return head;
                }
                queue.pollFirst();
            }
            return null;
        }
    }

    // ---- media ----

    /**
     * What an {@code inlineData} or {@code fileData} part holds, or null when it holds none. {@link RequestFeatureDetector}
     * asks the same question of the same part, so that the policy names what this class leaves out; it is answered without
     * building the data URI of the image, which can be megabytes.
     */
    static Media media(JsonNode part) {
        JsonNode inline = part.path("inlineData");
        if (inline.isObject()) {
            MediaKind kind = kindOf(inline.path("mimeType").stringValue(""));
            return new Media(kind, kind == MediaKind.IMAGE && !inline.path("data").stringValue("").isBlank());
        }
        JsonNode file = part.path("fileData");
        if (file.isObject()) {
            MediaKind kind = kindOf(file.path("mimeType").stringValue(""));
            return new Media(kind, kind == MediaKind.IMAGE && fetchableAddress(file.path("fileUri").stringValue("")) != null);
        }
        return null;
    }

    /**
     * The address a relay is given for the carried image of a part: a data URI for inline data, else the address of the file.
     * The data is written in the alphabet a data URI has: the Python SDK encodes bytes in the URL-safe one ({@code -} and
     * {@code _}), which a relay that decodes standard base64 refuses.
     */
    private static String imageUrl(JsonNode part) {
        JsonNode inline = part.path("inlineData");
        if (inline.isObject()) {
            String data = inline.path("data").stringValue("");
            if (data.indexOf('-') >= 0 || data.indexOf('_') >= 0) {
                data = data.replace('-', '+').replace('_', '/');
            }
            return "data:" + inline.path("mimeType").stringValue("").strip() + ";base64," + data;
        }
        return fetchableAddress(part.path("fileData").path("fileUri").stringValue(""));
    }

    private static MediaKind kindOf(String mimeType) {
        String type = mimeType.strip().toLowerCase(Locale.ROOT);
        return type.startsWith("image/") ? MediaKind.IMAGE : type.startsWith("audio/") ? MediaKind.AUDIO : MediaKind.FILE;
    }

    /**
     * The address of an image that a relay can be given: a web address that is not Google's. The files of the Files API are
     * served to the key that uploaded them, and {@code gs://} to Google's own services; the relay has neither.
     */
    private static String fetchableAddress(String uri) {
        String address = uri.strip();
        String lower = address.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return null;
        }
        int hostStart = lower.indexOf("//") + 2;
        int hostEnd = lower.length();
        for (char end : new char[] {'/', '?', '#', ':'}) {
            int at = lower.indexOf(end, hostStart);
            if (at >= 0) {
                hostEnd = Math.min(hostEnd, at);
            }
        }
        String host = lower.substring(hostStart, hostEnd);
        return host.isEmpty() || host.endsWith("googleapis.com") ? null : address;
    }

    /**
     * An image is a part with its address, or with none when a relay cannot be given it (the sanitizer then replaces it and
     * records it); audio and any other file name their kind, and nothing of their content is copied, as none of it is sent.
     */
    private ObjectNode mediaPart(Media media, JsonNode source) {
        ObjectNode part = objectMapper.createObjectNode();
        switch (media.kind()) {
            case IMAGE -> {
                part.put("type", "image_url");
                ObjectNode image = part.putObject("image_url");
                if (media.carried()) {
                    image.put("url", imageUrl(source));
                }
            }
            case AUDIO -> {
                part.put("type", "input_audio");
                part.putObject("input_audio");
            }
            case FILE -> {
                part.put("type", "file");
                part.putObject("file");
            }
        }
        return part;
    }

    // ---- tools ----

    /** The functions the request declares, in the Chat shape; their names are added to {@code declared}. */
    private ArrayNode tools(JsonNode tools, Set<String> declared) {
        ArrayNode converted = objectMapper.createArrayNode();
        if (!tools.isArray()) {
            return converted;
        }
        for (JsonNode tool : tools) {
            JsonNode declarations = tool.path("functionDeclarations");
            if (!declarations.isArray()) {
                continue;
            }
            for (JsonNode declaration : declarations) {
                String name = declaration.path("name").stringValue("");
                if (!declaration.isObject() || name.isBlank()) {
                    continue;
                }
                declared.add(name);
                ObjectNode wrapper = converted.addObject();
                wrapper.put("type", "function");
                ObjectNode function = wrapper.putObject("function");
                function.put("name", name);
                if (!declaration.path("description").stringValue("").isBlank()) {
                    function.set("description", declaration.path("description"));
                }
                function.set("parameters", parametersOf(declaration));
            }
        }
        return converted;
    }

    /**
     * The names of the functions a request declares, which {@link RequestFeatureDetector} asks for to judge the choice among
     * them as this class converts it.
     */
    static Set<String> declaredFunctions(JsonNode tools) {
        Set<String> names = new LinkedHashSet<>();
        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                JsonNode declarations = tool.path("functionDeclarations");
                if (declarations.isArray()) {
                    for (JsonNode declaration : declarations) {
                        String name = declaration.path("name").stringValue("");
                        if (declaration.isObject() && !name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            }
        }
        return names;
    }

    /** {@code parametersJsonSchema} as it is, else {@code parameters} with the types in the lower case; an object over none otherwise. */
    private ObjectNode parametersOf(JsonNode declaration) {
        JsonNode json = declaration.path("parametersJsonSchema");
        ObjectNode parameters = json.isObject() ? (ObjectNode) json.deepCopy()
            : declaration.path("parameters").isObject() ? geminiSchema(declaration.path("parameters")) : objectMapper.createObjectNode();
        if (parameters.path("type").isMissingNode() || parameters.path("type").isNull()) {
            parameters.put("type", "object");
        }
        if ("object".equals(parameters.path("type").stringValue("")) && !parameters.path("properties").isObject()) {
            parameters.putObject("properties");
        }
        return parameters;
    }

    /** The keywords the API types as int64, which its JSON form writes as strings (minItems "1"); a JSON schema wants numbers. */
    private static final Set<String> COUNT_KEYWORDS = Set.of(
        "minItems", "maxItems", "minLength", "maxLength", "minProperties", "maxProperties");

    /**
     * A schema of Gemini's own kind ({@code OBJECT}, {@code STRING}) as a JSON schema: the {@code type} of the schema and of
     * the schemas inside it in the lower case, the keywords the API writes as strings made numbers, the snake_case spellings of
     * the Python SDK ({@code min_items}, {@code any_of}) read as the camelCase ones, {@code nullable} written as the union with
     * the type {@code null}, and {@code propertyOrdering} (an order of display, not a keyword) left out; nothing else changed. A
     * type that is unspecified is no type.
     */
    private ObjectNode geminiSchema(JsonNode source) {
        ObjectNode copy = objectMapper.createObjectNode();
        boolean nullable = false;
        for (Map.Entry<String, JsonNode> field : source.properties()) {
            String key = camelCase(field.getKey());
            JsonNode value = field.getValue();
            switch (key) {
                case "type" -> {
                    if (!value.isString()) {
                        copy.set(key, value.deepCopy());
                    } else if (!"TYPE_UNSPECIFIED".equalsIgnoreCase(value.stringValue())) {
                        copy.put(key, value.stringValue().toLowerCase(Locale.ROOT));
                    }
                }
                case "properties" -> {
                    if (value.isObject()) {
                        ObjectNode properties = copy.putObject(key);
                        for (Map.Entry<String, JsonNode> property : value.properties()) {
                            properties.set(property.getKey(), property.getValue().isObject()
                                ? geminiSchema(property.getValue()) : property.getValue().deepCopy());
                        }
                    } else {
                        copy.set(key, value.deepCopy());
                    }
                }
                case "items" -> copy.set(key, value.isObject() ? geminiSchema(value) : value.deepCopy());
                case "anyOf" -> {
                    if (value.isArray()) {
                        ArrayNode alternatives = copy.putArray(key);
                        value.forEach(alternative -> alternatives.add(alternative.isObject()
                            ? geminiSchema(alternative) : alternative.deepCopy()));
                    } else {
                        copy.set(key, value.deepCopy());
                    }
                }
                case "nullable" -> nullable = value.isBoolean() && value.booleanValue();
                case "propertyOrdering" -> {
                    // How the SDK lists the properties, which no JSON schema has a word for.
                }
                default -> copy.set(key, COUNT_KEYWORDS.contains(key) ? count(value) : value.deepCopy());
            }
        }
        if (nullable) {
            JsonNode type = copy.path("type");
            if (type.isString()) {
                copy.putArray("type").add(type.stringValue()).add("null");
            } else if (type.isArray()) {
                ((ArrayNode) type).add("null");
            } else if (copy.path("anyOf").isArray()) {
                ((ArrayNode) copy.path("anyOf")).addObject().put("type", "null");
            }
        }
        return copy;
    }

    /** A count the API wrote as the string of an integer is the integer; anything else is left as it is. */
    private static JsonNode count(JsonNode value) {
        if (value.isString()) {
            String text = value.stringValue().strip();
            if (text.length() <= 18 && text.matches("[0-9]+")) {
                long number = Long.parseLong(text);
                return number <= Integer.MAX_VALUE ? FACTORY.numberNode((int) number) : FACTORY.numberNode(number);
            }
        }
        return value.deepCopy();
    }

    /** {@code min_items} as {@code minItems}; a name with no underscore as it is. */
    private static String camelCase(String name) {
        int at = name.indexOf('_');
        if (at <= 0 || name.endsWith("_")) {
            return name;
        }
        StringBuilder camel = new StringBuilder(name.length());
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    /**
     * The {@code tool_choice} of a {@code toolConfig}, or null for the default (the model decides, {@code AUTO}). It is a
     * relay's string, or the function of a tool, or, for a choice among several functions that a relay's format has no word
     * for, the {@code allowed_tools} of the OpenAI API. The detector reads it with the same method.
     *
     * <p>{@code ANY} makes the model call a function ({@code required}, or the one function when exactly one is allowed; the
     * allowed names that cover every function there is are no restriction). {@code VALIDATED} does not: the model decides
     * between a call and an answer in words, and only the calls it makes are checked against the schema, so it is the default
     * choice, and a relay whose model could not answer in words would keep calling functions. Its allowed names, when they leave
     * a function out, are a restriction a relay has no word for, as they are for {@code ANY}.
     *
     * @param declared the names of the functions the request declares
     */
    static JsonNode toolChoice(JsonNode toolConfig, Set<String> declared) {
        JsonNode calling = toolConfig.path("functionCallingConfig");
        String mode = calling.path("mode").stringValue("").strip().toUpperCase(Locale.ROOT);
        switch (mode) {
            case "NONE" -> {
                return FACTORY.stringNode("none");
            }
            case "ANY", "VALIDATED" -> {
                boolean forced = "ANY".equals(mode);
                Set<String> allowed = new LinkedHashSet<>();
                JsonNode names = calling.path("allowedFunctionNames");
                if (names.isArray()) {
                    for (JsonNode name : names) {
                        if (name.isString() && !name.stringValue().isBlank()) {
                            allowed.add(name.stringValue());
                        }
                    }
                }
                boolean restricts = !allowed.isEmpty() && !allowed.containsAll(declared);
                if (!forced && !restricts) {
                    return null;
                }
                if (forced && allowed.size() == 1) {
                    return function(allowed.iterator().next());
                }
                if (forced && !restricts) {
                    return FACTORY.stringNode("required");
                }
                ObjectNode choice = FACTORY.objectNode();
                choice.put("type", "allowed_tools");
                ObjectNode restriction = choice.putObject("allowed_tools");
                restriction.put("mode", forced ? "required" : "auto");
                ArrayNode tools = restriction.putArray("tools");
                allowed.forEach(name -> tools.add(function(name)));
                return choice;
            }
            default -> {
                return null;
            }
        }
    }

    private static ObjectNode function(String name) {
        ObjectNode choice = FACTORY.objectNode();
        choice.put("type", "function");
        choice.putObject("function").put("name", name);
        return choice;
    }

    // ---- the rest of the body ----

    private static void number(JsonNode source, String from, String to, ObjectNode chat) {
        if (source.path(from).isNumber()) {
            chat.set(to, source.path(from));
        }
    }

    private ArrayNode stopSequences(JsonNode stop) {
        ArrayNode sequences = objectMapper.createArrayNode();
        if (stop.isArray()) {
            for (JsonNode sequence : stop) {
                if (sequence.isString() && !sequence.stringValue().isEmpty()) {
                    sequences.add(sequence);
                }
            }
        }
        return sequences;
    }

    /** The text of a system instruction: a string, or the text parts of a content, joined by a newline. */
    private static String instructionText(JsonNode instruction) {
        if (instruction.isString()) {
            return instruction.stringValue();
        }
        StringBuilder text = new StringBuilder();
        JsonNode parts = instruction.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (isText(part)) {
                    append(text, part.path("text").stringValue(""));
                }
            }
        }
        return text.toString();
    }

    /** The kind of answer a request asks for. */
    enum AnswerKind {
        /** Plain text, which is what a request that says nothing gets. */
        TEXT,
        /** {@code application/json}. */
        JSON,
        /** Any other kind ({@code text/x.enum}: one of a set of values), which a relay's format has no word for. */
        OTHER
    }

    /**
     * What a request asks the answer to be.
     *
     * @param kind   the kind of answer
     * @param schema the schema a JSON answer is to be held to, or null
     * @param plain  whether the schema is a JSON schema as it is ({@code responseJsonSchema}) and not Gemini's own dialect
     */
    record Answer(AnswerKind kind, JsonNode schema, boolean plain) {
    }

    /**
     * What the request asks the answer to be: the {@code responseFormat.text} (the current form of the API, which marks the two
     * schema fields below as deprecated in its favour) when the request has it, else {@code responseMimeType} with the
     * {@code responseJsonSchema} or, if there is none, the {@code responseSchema}. The detector reads it with the same method.
     */
    static Answer answer(JsonNode config) {
        JsonNode format = config.path("responseFormat").path("text");
        if (format.isObject()) {
            return new Answer(kindOf(format.path("mimeType")), objectOrNull(format.path("schema")), false);
        }
        AnswerKind kind = kindOf(config.path("responseMimeType"));
        if (config.path("responseJsonSchema").isObject()) {
            return new Answer(kind, config.path("responseJsonSchema"), true);
        }
        return new Answer(kind, objectOrNull(config.path("responseSchema")), false);
    }

    private static JsonNode objectOrNull(JsonNode schema) {
        return schema.isObject() ? schema : null;
    }

    /** {@code application/json} (or the name of its enum, {@code APPLICATION_JSON}), with or without parameters. */
    private static AnswerKind kindOf(JsonNode mimeType) {
        String mime = mimeType.stringValue("").strip().toLowerCase(Locale.ROOT);
        if (mime.equals("application/json") || mime.startsWith("application/json;") || mime.equals("application_json")) {
            return AnswerKind.JSON;
        }
        return mime.isEmpty() || mime.equals("text/plain") || mime.equals("text_plain") ? AnswerKind.TEXT : AnswerKind.OTHER;
    }

    private void responseFormat(JsonNode config, ObjectNode chat) {
        Answer answer = answer(config);
        if (answer.kind() != AnswerKind.JSON) {
            return;
        }
        if (answer.schema() == null) {
            chat.putObject("response_format").put("type", "json_object");
            return;
        }
        ObjectNode format = chat.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode target = format.putObject("json_schema");
        target.put("name", "response");
        target.set("schema", answer.plain() ? answer.schema().deepCopy() : geminiSchema(answer.schema()));
    }
}

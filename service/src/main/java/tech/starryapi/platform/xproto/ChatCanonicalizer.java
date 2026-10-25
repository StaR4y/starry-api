package tech.starryapi.platform.xproto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes the history of a sanitized Chat body something every OpenAI-format relay takes, including the ones that forward
 * it to Anthropic (design 7.6). The conversion from an Anthropic request leaves shapes behind that Anthropic itself
 * accepts and a strict relay refuses: a tool result that held only an image has become the empty string, a turn of the
 * user that had only an image the same, a model turn that was all thinking an assistant message without text, and a
 * relay that numbers its tool calls from {@code call_0} on every answer fills the history with the same id. What
 * Kiro, the native source of those clients, repairs in its own way (an empty content becomes "Continue", an unanswered
 * tool call gets an answer) is repaired here, so that the relay is not given a history less valid than Kiro is. The
 * rules, in the order they apply:
 *
 * <ol>
 *   <li>An empty system message, and an assistant message with neither text nor tool calls, is left out.
 *   <li>Each tool message answers the nearest assistant tool call before it that has its id and no answer yet (the first
 *       such call of that message), so that a repeated id is read by position. A tool message that answers nothing (no
 *       such call, or the call was answered once already) becomes a user message {@code Tool result:} and its text.
 *   <li>Each assistant message with tool calls is followed by the answers to its calls, in the order of the calls,
 *       wherever the answers were. A call without an answer gets {@code Tool result missing} when anything but tool
 *       messages comes after it; the calls of the last turn are left as they are, for the model to be asked about.
 *   <li>Adjacent user messages, and adjacent assistant messages, are one message, their texts joined by a blank line
 *       (or their parts put one after the other). This is what leaves a history alternating after the assistant
 *       messages of rule 1 are gone.
 *   <li>A user turn is never left out: an empty one has the content {@code [non-text content omitted]}, an empty tool
 *       result {@code [no output]}, and both are recorded as {@link #EMPTY_USER_MESSAGE} and {@link
 *       #EMPTY_TOOL_RESULT}. An assistant message that has tool calls and no text has the content {@code null}, and
 *       a call without arguments has {@code {}}. Whitespace is empty.
 *   <li>Tool call ids are made to match {@code ^[A-Za-z0-9_-]{1,64}$} (other characters become {@code _}, an empty id
 *       is {@code call}) and unique in the whole history: a call whose id is already held by an earlier call keeps it
 *       with {@code _2}, {@code _3} and so on after it, and the answer to the call is renamed with it. What an earlier
 *       call holds includes the names given to the calls before it, so a client's own {@code c_2} that follows a
 *       renamed {@code c} is renamed too ({@code c_2_2}): the ids are given in the order of the calls, each the first
 *       free one. An id longer than 57 characters is cut to 57 before the suffix, so that ids that begin alike count
 *       from one number and a client cannot make the pass slower than its size by choosing them. An id that no earlier
 *       call holds, once made legal, is not touched.
 * </ol>
 *
 * <p>Between rules 3 and 4 there is a step that depends on the member: a body that declares no tools has its tool calls
 * turned into text, when the model's upstream refuses them ({@link Settings#needsToolsForToolHistory}). Anthropic and
 * Bedrock, which many relays serving Claude forward to, refuse a history with tool calls and tool results in a request
 * that defines no tools; a client that resends the conversation to have it summarized, with its tools taken out, makes
 * exactly that, and Kiro makes text of them in the same case. The step comes after the pairing, so that the results are in
 * the order of their calls (rule 3) and a call that was never answered has its {@code Tool result missing}: each call
 * becomes {@code Tool use:} with its name and, when it has any, {@code Input:} and its arguments, after the text of its
 * message, and each answer a user message {@code Tool result:} and its text, in the same order. It is recorded as {@link
 * #TOOL_HISTORY_FLATTENED}. A member whose upstream takes the history as it is (OpenAI's) is given it as it is, as the
 * native accounts of a gpt request are: the name is a repair of the history, not a feature of the request, and a repair
 * is recorded only where the native path of the same model makes it.
 *
 * <p>The input is not changed; the messages of the result are new, the tool schemas and the other fields are shared with
 * it. Running the canonicalizer on its own output changes nothing.
 */
public final class ChatCanonicalizer {

    /** Recorded when a tool result was empty, in the words of the feature policy (design 7.9). */
    public static final String EMPTY_TOOL_RESULT = "empty_tool_result";
    /** Recorded when a user turn had no text or image left. */
    public static final String EMPTY_USER_MESSAGE = "empty_user_message";
    /** Recorded when the tool calls and results of a history were made text because the request has no tools. */
    public static final String TOOL_HISTORY_FLATTENED = "tool_history_flattened";

    static final String NO_OUTPUT = "[no output]";
    static final String NON_TEXT_USER_TURN = "[non-text content omitted]";
    static final String MISSING_RESULT = "Tool result missing";
    static final String ORPHAN_RESULT = "Tool result:";

    static final int MAX_ID_LENGTH = 64;
    /** Room for the longest suffix of a renamed id: {@code _} and six digits, more calls than a request can hold. */
    private static final int SUFFIX_ROOM = 7;
    static final Pattern LEGAL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private ChatCanonicalizer() {
    }

    /**
     * @param sanitized the body {@link ChatWireSanitizer#sanitize} made for {@code member}
     * @param member    the settings it was made for; the canonical form depends on {@link
     *                  Settings#needsToolsForToolHistory} and on nothing else of them
     */
    public static Wire canonicalize(Wire sanitized, Settings member) {
        Objects.requireNonNull(sanitized, "sanitized");
        Objects.requireNonNull(member, "member");
        ObjectNode input = sanitized.body();
        List<Message> messages = parse(input.path("messages"));
        Set<String> degraded = new LinkedHashSet<>();
        messages.removeIf(message -> (message.role("system") && isEmpty(message.content))
            || (message.role("assistant") && message.calls.isEmpty() && isEmpty(message.content)));
        pair(messages);
        List<Message> arranged = arrange(messages);
        if (member.needsToolsForToolHistory() && !declaresTools(input)) {
            flattenToolHistory(arranged, degraded);
        }
        List<Message> history = merge(arranged);
        fillEmpty(history, degraded);
        uniqueIds(history);

        ObjectNode body = JsonNodes.shallowCopy(input);
        ArrayNode emitted = FACTORY.arrayNode();
        for (Message message : history) {
            emitted.add(message.emit());
        }
        body.set("messages", emitted);
        return new Wire(body, sanitized.degraded()).degradedAlso(degraded);
    }

    // ---- the history as a list of messages ----

    private static final class Message {
        String role;
        JsonNode content;
        JsonNode cacheControl;
        final List<Call> calls = new ArrayList<>();
        /** A tool message: the id it names, and the call it was paired with. */
        String toolCallId;
        Call answers;

        boolean role(String name) {
            return name.equals(role);
        }

        ObjectNode emit() {
            ObjectNode message = FACTORY.objectNode();
            if (role != null) {
                message.put("role", role);
            }
            message.set("content", content == null ? FACTORY.nullNode() : content);
            if (!calls.isEmpty()) {
                ArrayNode emitted = message.putArray("tool_calls");
                for (Call call : calls) {
                    ObjectNode node = emitted.addObject();
                    node.put("id", call.wireId);
                    node.put("type", "function");
                    ObjectNode function = node.putObject("function");
                    function.put("name", call.name);
                    function.put("arguments", call.arguments);
                }
            }
            if (answers != null) {
                message.put("tool_call_id", answers.wireId);
            } else if (toolCallId != null) {
                message.put("tool_call_id", toolCallId);
            }
            if (cacheControl != null) {
                message.set("cache_control", cacheControl);
            }
            return message;
        }
    }

    private static final class Call {
        final Message owner;
        final String id;
        final String name;
        String arguments;
        Message answer;
        String wireId;

        Call(Message owner, String id, String name, String arguments) {
            this.owner = owner;
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    private static List<Message> parse(JsonNode input) {
        List<Message> messages = new ArrayList<>();
        if (!input.isArray()) {
            return messages;
        }
        for (JsonNode node : input) {
            if (!node.isObject()) {
                continue;
            }
            Message message = new Message();
            message.role = node.path("role").stringValue(null);
            message.content = node.has("content") ? node.get("content") : FACTORY.nullNode();
            message.cacheControl = node.hasNonNull("cache_control") ? node.path("cache_control") : null;
            if (node.path("tool_call_id").isString()) {
                message.toolCallId = node.path("tool_call_id").stringValue();
            }
            if (node.path("tool_calls").isArray()) {
                for (JsonNode call : node.path("tool_calls")) {
                    message.calls.add(new Call(
                        message,
                        call.path("id").stringValue(""),
                        call.path("function").path("name").stringValue(""),
                        call.path("function").path("arguments").stringValue("")
                    ));
                }
            }
            messages.add(message);
        }
        return messages;
    }

    private static boolean declaresTools(ObjectNode body) {
        return body.path("tools").isArray() && !body.path("tools").isEmpty();
    }

    /**
     * The step between rules 3 and 4, on the history as it is after rule 3: the calls of every assistant message are text after its own, in the
     * form the conversion of a Messages request gives a tool use ({@code AnthropicRequestAdapter}) and Kiro gives one it
     * cannot send as a call, and the answers that follow it, which are already in the order of the calls, are the user
     * messages {@code Tool result:}. The answer of a call is where the call's turn puts it, not where the client wrote it,
     * so a client that wrote the results of parallel calls in another order has each result read against its own call.
     */
    private static void flattenToolHistory(List<Message> arranged, Set<String> degraded) {
        for (Message message : arranged) {
            if (message.role("assistant") && !message.calls.isEmpty()) {
                StringBuilder uses = new StringBuilder();
                for (Call call : message.calls) {
                    if (!uses.isEmpty()) {
                        uses.append("\n\n");
                    }
                    uses.append("Tool use: ").append(call.name);
                    if (!call.arguments.isBlank() && !"{}".equals(call.arguments.strip())) {
                        uses.append("\nInput: ").append(call.arguments);
                    }
                }
                message.content = joined(Arrays.asList(message.content, FACTORY.stringNode(uses.toString())));
                message.calls.clear();
                degraded.add(TOOL_HISTORY_FLATTENED);
            } else if (message.role("tool")) {
                String text = orphanText(message.content);
                message.role = "user";
                message.content = FACTORY.stringNode(text.isBlank() ? ORPHAN_RESULT : ORPHAN_RESULT + "\n" + text);
                message.answers = null;
                message.toolCallId = null;
            }
        }
    }

    /** The calls of one assistant message that have an id and no answer yet; the calls of a message are one block. */
    private record Block(Message owner, Deque<Call> calls) {
    }

    /**
     * Rule 2: pairs the tool messages with their calls, and turns the ones that answer nothing into user messages. The
     * open calls are kept by id, as blocks in the order of their messages, so that the nearest call with an id is the
     * first one of the last block and a history of any size is paired in one pass.
     */
    private static void pair(List<Message> messages) {
        Map<String, Deque<Block>> open = new HashMap<>();
        for (Message message : messages) {
            if (message.role("assistant")) {
                for (Call call : message.calls) {
                    Deque<Block> blocks = open.computeIfAbsent(call.id, id -> new ArrayDeque<>());
                    if (blocks.isEmpty() || blocks.peekLast().owner != message) {
                        blocks.addLast(new Block(message, new ArrayDeque<>()));
                    }
                    blocks.peekLast().calls.addLast(call);
                }
            } else if (message.role("tool")) {
                Deque<Block> blocks = message.toolCallId == null ? null : open.get(message.toolCallId);
                if (blocks == null || blocks.isEmpty()) {
                    message.role = "user";
                    String text = orphanText(message.content);
                    message.content = FACTORY.stringNode(text.isBlank() ? ORPHAN_RESULT : ORPHAN_RESULT + "\n" + text);
                    message.toolCallId = null;
                    continue;
                }
                Block block = blocks.peekLast();
                Call call = block.calls.pollFirst();
                if (block.calls.isEmpty()) {
                    blocks.pollLast();
                }
                call.answer = message;
                message.answers = call;
            }
        }
    }

    /** Rule 3: every assistant message with calls, then its answers; the answers are not emitted where they were. */
    private static List<Message> arrange(List<Message> messages) {
        int lastOther = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).role("tool")) {
                lastOther = i;
            }
        }
        List<Message> arranged = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.role("tool")) {
                continue;
            }
            arranged.add(message);
            if (!message.role("assistant")) {
                continue;
            }
            for (Call call : message.calls) {
                if (call.answer == null && i < lastOther) {
                    Message missing = new Message();
                    missing.role = "tool";
                    missing.content = FACTORY.stringNode(MISSING_RESULT);
                    missing.answers = call;
                    call.answer = missing;
                }
                if (call.answer != null) {
                    arranged.add(call.answer);
                }
            }
        }
        return arranged;
    }

    /** Rule 4. A run of messages is joined once, so that a long run costs no more than its size. */
    private static List<Message> merge(List<Message> messages) {
        List<Message> merged = new ArrayList<>();
        List<JsonNode> run = new ArrayList<>();
        for (Message message : messages) {
            Message previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            boolean sameTurn = previous != null && previous.role != null && previous.role.equals(message.role)
                && (message.role("user") || (message.role("assistant") && previous.calls.isEmpty()));
            if (!sameTurn) {
                closeRun(previous, run);
                merged.add(message);
                run = new ArrayList<>(List.of(message.content));
                continue;
            }
            run.add(message.content);
            previous.calls.addAll(message.calls);
            if (message.cacheControl != null) {
                previous.cacheControl = message.cacheControl;
            }
        }
        closeRun(merged.isEmpty() ? null : merged.get(merged.size() - 1), run);
        return merged;
    }

    private static void closeRun(Message message, List<JsonNode> run) {
        if (message != null && run.size() > 1) {
            message.content = joined(run);
        }
    }

    /** Rule 5. */
    private static void fillEmpty(List<Message> messages, Set<String> degraded) {
        for (Message message : messages) {
            if (message.role("user") && isEmpty(message.content)) {
                message.content = FACTORY.stringNode(NON_TEXT_USER_TURN);
                degraded.add(EMPTY_USER_MESSAGE);
            } else if (message.role("tool") && isEmpty(message.content)) {
                message.content = FACTORY.stringNode(NO_OUTPUT);
                degraded.add(EMPTY_TOOL_RESULT);
            } else if (message.role("assistant") && !message.calls.isEmpty() && isEmpty(message.content)) {
                message.content = FACTORY.nullNode();
            }
            for (Call call : message.calls) {
                if (call.arguments.isBlank()) {
                    call.arguments = "{}";
                }
            }
        }
    }

    /** Rule 6. */
    private static void uniqueIds(List<Message> messages) {
        Set<String> taken = new HashSet<>();
        Map<String, Integer> nextSuffix = new HashMap<>();
        for (Message message : messages) {
            for (Call call : message.calls) {
                call.wireId = unique(legal(call.id), taken, nextSuffix);
            }
        }
    }

    static String legal(String id) {
        StringBuilder legal = new StringBuilder();
        for (int i = 0; i < id.length() && legal.length() < MAX_ID_LENGTH; ) {
            int codePoint = id.codePointAt(i);
            i += Character.charCount(codePoint);
            boolean fine = codePoint < 0x80
                && (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-');
            legal.append(fine ? (char) codePoint : '_');
        }
        return legal.isEmpty() ? "call" : legal.toString();
    }

    /**
     * The id, or the first of {@code id_2}, {@code id_3}... that is free. The count goes on from the last one given for
     * the same stem, the id as far as 57 characters of it: ids that begin alike are renamed from one number, and a
     * candidate that is taken is passed once, by the one count. (A count kept per id would start again for each of
     * many ids that share their first 61 characters, and walk past every name the others had taken: the time of the pass
     * would grow with the square of their number.) Two stems and numbers never make the same candidate, and an id the
     * client wrote takes at most one candidate, so the whole pass takes time in proportion to the size of the history.
     */
    private static String unique(String id, Set<String> taken, Map<String, Integer> nextSuffix) {
        if (taken.add(id)) {
            return id;
        }
        String stem = id.length() > MAX_ID_LENGTH - SUFFIX_ROOM ? id.substring(0, MAX_ID_LENGTH - SUFFIX_ROOM) : id;
        for (int n = nextSuffix.getOrDefault(stem, 2); ; n++) {
            String suffix = "_" + n;
            String candidate = stem.substring(0, Math.min(stem.length(), MAX_ID_LENGTH - suffix.length())) + suffix;
            if (taken.add(candidate)) {
                nextSuffix.put(stem, n + 1);
                return candidate;
            }
        }
    }

    // ---- content ----

    /** No text and no image: nothing, the empty string, whitespace, or parts that are all whitespace. */
    static boolean isEmpty(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return true;
        }
        if (content.isString()) {
            return content.stringValue().isBlank();
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (!"text".equals(part.path("type").stringValue("")) || !part.path("text").stringValue("").isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** The contents one after the other: as text when all that are not empty are, as parts when one has parts. */
    private static JsonNode joined(List<JsonNode> contents) {
        List<JsonNode> full = new ArrayList<>();
        boolean text = true;
        for (JsonNode content : contents) {
            if (!isEmpty(content)) {
                full.add(content);
                text &= content.isString();
            }
        }
        if (full.isEmpty()) {
            return contents.get(contents.size() - 1);
        }
        if (full.size() == 1) {
            return full.get(0);
        }
        if (text) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode content : full) {
                if (!joined.isEmpty()) {
                    joined.append("\n\n");
                }
                joined.append(content.stringValue());
            }
            return FACTORY.stringNode(joined.toString());
        }
        ArrayNode parts = FACTORY.arrayNode();
        for (JsonNode content : full) {
            if (content.isArray()) {
                parts.addAll((ArrayNode) content);
            } else {
                parts.addObject().put("type", "text").put("text", content.stringValue(""));
            }
        }
        return parts;
    }

    private static String orphanText(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isString()) {
            return content.stringValue();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").stringValue("")) && !part.path("text").stringValue("").isBlank()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(part.path("text").stringValue());
                }
            }
        }
        return text.toString();
    }
}

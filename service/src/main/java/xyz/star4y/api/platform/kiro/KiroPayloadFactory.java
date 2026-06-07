package xyz.star4y.api.platform.kiro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.model.ModelCatalogProvider;

@Component
public class KiroPayloadFactory {

    private static final String BUILDER_ID_PROFILE_ARN = "arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX";
    private static final String SOCIAL_PROFILE_ARN = "arn:aws:codewhisperer:us-east-1:699475941385:profile/EHGA3GRVQMUK";
    private static final String IDENTITY_DISCIPLINE = """
        <identity_discipline>
        You are serving this conversation through Starry API.
        When the user asks what model you are, answer with the client-requested model id: %s.
        Do not add a second identity sentence claiming to be Kiro, Amazon Q, CodeWhisperer, Claude, Anthropic, OpenAI, or Gemini unless the user explicitly asks about upstream routing internals.
        </identity_discipline>
        """;
    private static final String EXECUTION_DISCIPLINE = """
        <execution_discipline>
        When the user asks you to perform a concrete task, follow these rules:
        1. Keep the original user goal in view throughout the conversation.
        2. Prefer taking action over only analyzing, unless the user explicitly asks for analysis.
        3. Create and follow a clear step-by-step plan for multi-step work.
        4. Do not end with confirmation-seeking questions while required work remains.
        5. Continue with the remaining work when partial progress is complete.
        6. Consider the task complete only when all required steps are handled.
        </execution_discipline>
        """;

    private final ObjectMapper objectMapper;
    private final ModelCatalogProvider modelCatalog;

    public KiroPayloadFactory(ObjectMapper objectMapper, ModelCatalogProvider modelCatalog) {
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    public ObjectNode fromOpenAiChat(JsonNode request, ProxyAccountEntity account, String model, String origin, String sessionHint) {
        ArrayNode messages = request.withArray("messages");
        List<JsonNode> nonSystemMessages = new ArrayList<>();
        StringBuilder systemPrompt = new StringBuilder();
        for (JsonNode message : messages) {
            if ("system".equals(message.path("role").asText())) {
                String content = contentToText(message.path("content"));
                if (!content.isBlank()) {
                    if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
                    systemPrompt.append(content);
                }
            } else {
                nonSystemMessages.add(message);
            }
        }

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode conversationState = payload.putObject("conversationState");
        conversationState.put("agentContinuationId", UUID.randomUUID().toString());
        conversationState.put("agentTaskType", "vibe");
        conversationState.put("chatTriggerType", "MANUAL");
        conversationState.put("conversationId", stableConversationId(request, sessionHint));

        List<ObjectNode> allMessages = new ArrayList<>();
        appendSystemPromptPair(allMessages, systemPrompt.toString(), origin, request.path("model").asText(model));
        for (JsonNode message : nonSystemMessages) {
            ObjectNode converted = historyMessage(message, model, origin);
            if (!converted.isEmpty()) {
                allMessages.add(converted);
            }
        }
        allMessages = normalizeToolHistory(allMessages, request.get("tools"));
        allMessages = sanitizeConversation(allMessages);
        if (endsWithToolResultOnly(allMessages)) {
            allMessages.add(emptyCurrentMessage(model, origin));
        }

        ObjectNode current = allMessages.isEmpty() ? continueMessage(model, origin) : allMessages.get(allMessages.size() - 1);
        ObjectNode currentUserInput = current.path("userInputMessage").isObject()
            ? (ObjectNode) current.path("userInputMessage")
            : userInput(null, model, origin, null);
        appendToolsToUserInput(currentUserInput, request.get("tools"));

        ArrayNode history = objectMapper.createArrayNode();
        for (int i = 0; i < Math.max(0, allMessages.size() - 1); i++) {
            history.add(allMessages.get(i));
        }
        if (!history.isEmpty()) {
            conversationState.set("history", history);
        }

        ObjectNode currentMessage = conversationState.putObject("currentMessage");
        currentMessage.set("userInputMessage", currentUserInput);

        ObjectNode inferenceConfig = objectMapper.createObjectNode();
        if (request.has("max_tokens")) {
            inferenceConfig.set("maxTokens", request.get("max_tokens"));
        }
        if (request.has("temperature")) {
            inferenceConfig.set("temperature", request.get("temperature"));
        }
        if (request.has("top_p")) {
            inferenceConfig.set("topP", request.get("top_p"));
        }
        if (!inferenceConfig.isEmpty()) {
            payload.set("inferenceConfig", inferenceConfig);
        }
        payload.put("profileArn", profileArn(account));
        if (request.has("thinking")) {
            ObjectNode additional = payload.putObject("additionalModelRequestFields");
            additional.set("thinking", request.get("thinking"));
        }
        return payload;
    }

    private ObjectNode historyMessage(JsonNode message, String model, String origin) {
        ObjectNode node = objectMapper.createObjectNode();
        String role = message.path("role").asText("user");
        if ("assistant".equals(role)) {
            ObjectNode assistant = node.putObject("assistantResponseMessage");
            String content = contentToText(message.path("content"));
            if (content.isBlank() && message.path("tool_calls").isArray() && !message.path("tool_calls").isEmpty()) {
                content = " ";
            }
            assistant.put("content", content.isBlank() ? "I understand." : content);
            appendToolUses(assistant, message.path("tool_calls"));
        } else if ("user".equals(role) || "tool".equals(role)) {
            node.set("userInputMessage", userInput(message, model, origin, null));
        }
        return node;
    }

    private ObjectNode userInput(JsonNode message, String model, String origin, JsonNode tools) {
        ObjectNode userInput = objectMapper.createObjectNode();
        String content = message == null ? "Continue" : contentToText(message.path("content"));
        userInput.put("content", content.isBlank() ? "Continue" : content);
        userInput.put("modelId", modelCatalog.resolveTargetModel(model));
        userInput.put("origin", origin);
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            ObjectNode context = userInput.putObject("userInputMessageContext");
            appendTools(context, tools);
        }
        if (message != null && "tool".equals(message.path("role").asText())) {
            ObjectNode context = userInput.has("userInputMessageContext")
                ? (ObjectNode) userInput.path("userInputMessageContext")
                : userInput.putObject("userInputMessageContext");
            appendToolResult(context, message);
        }
        return userInput;
    }

    private void appendSystemPromptPair(List<ObjectNode> messages, String prompt, String origin, String requestedModel) {
        String enriched = "[Context: Current time is " + Instant.now() + "]\n\n"
            + IDENTITY_DISCIPLINE.formatted(normalizedRequestedModel(requestedModel)).trim()
            + "\n\n"
            + (prompt == null ? "" : prompt)
            + "\n\n"
            + EXECUTION_DISCIPLINE.trim();
        ObjectNode user = objectMapper.createObjectNode();
        ObjectNode userInput = user.putObject("userInputMessage");
        userInput.put("content", enriched);
        userInput.put("origin", origin);
        userInput.putObject("userInputMessageContext");
        messages.add(user);

        ObjectNode assistant = objectMapper.createObjectNode();
        assistant.putObject("assistantResponseMessage")
            .put("content", "I will follow these instructions.");
        messages.add(assistant);
    }

    private void appendToolsToUserInput(ObjectNode userInput, JsonNode tools) {
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return;
        }
        ObjectNode context = userInput.path("userInputMessageContext").isObject()
            ? (ObjectNode) userInput.path("userInputMessageContext")
            : userInput.putObject("userInputMessageContext");
        appendTools(context, tools);
    }

    private void appendTools(ObjectNode context, JsonNode tools) {
        ArrayNode kiroTools = context.putArray("tools");
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            if (function.isMissingNode()) {
                continue;
            }
            ObjectNode wrapper = kiroTools.addObject();
            ObjectNode spec = wrapper.putObject("toolSpecification");
            spec.put("name", function.path("name").asText());
            spec.put("description", function.path("description").asText(""));
            ObjectNode inputSchema = spec.putObject("inputSchema");
            inputSchema.set("json", function.path("parameters"));
        }
    }

    private void appendToolUses(ObjectNode assistant, JsonNode toolCalls) {
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return;
        }
        ArrayNode toolUses = assistant.putArray("toolUses");
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            if (function.isMissingNode()) {
                continue;
            }
            ObjectNode toolUse = toolUses.addObject();
            toolUse.put("toolUseId", toolCall.path("id").asText());
            toolUse.put("name", function.path("name").asText());
            toolUse.set("input", parseJsonOrObject(function.path("arguments").asText("{}")));
        }
    }

    private void appendToolResult(ObjectNode context, JsonNode message) {
        ArrayNode toolResults = context.putArray("toolResults");
        ObjectNode result = toolResults.addObject();
        result.put("toolUseId", message.path("tool_call_id").asText());
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("text", contentToText(message.path("content")));
        result.put("status", "success");
    }

    private ObjectNode continueMessage(String model, String origin) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("userInputMessage", userInput(null, model, origin, null));
        return node;
    }

    private ObjectNode emptyCurrentMessage(String model, String origin) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode userInput = node.putObject("userInputMessage");
        userInput.put("content", "");
        userInput.put("modelId", modelCatalog.resolveTargetModel(model));
        userInput.put("origin", origin);
        return node;
    }

    private ObjectNode understoodMessage() {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("assistantResponseMessage").put("content", "understood");
        return node;
    }

    private ObjectNode failedToolResultMessage(List<String> toolUseIds, String origin) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode userInput = node.putObject("userInputMessage");
        userInput.put("content", "");
        userInput.put("origin", origin);
        ObjectNode context = userInput.putObject("userInputMessageContext");
        ArrayNode toolResults = context.putArray("toolResults");
        for (String toolUseId : toolUseIds) {
            ObjectNode result = toolResults.addObject();
            result.put("toolUseId", toolUseId);
            ArrayNode content = result.putArray("content");
            content.addObject().put("text", "Tool execution failed");
            result.put("status", "error");
        }
        return node;
    }

    private List<ObjectNode> sanitizeConversation(List<ObjectNode> messages) {
        List<ObjectNode> sanitized = removeInvalidToolResultMessages(messages);
        sanitized = ensureValidToolUsesAndResults(sanitized);
        sanitized = ensureAlternatingMessages(sanitized);
        sanitized = ensureEndsWithUserMessage(sanitized);
        return sanitized;
    }

    private List<ObjectNode> normalizeToolHistory(List<ObjectNode> messages, JsonNode tools) {
        Set<String> toolNames = toolNames(tools);
        Set<String> flattenedToolUseIds = new HashSet<>();
        List<ObjectNode> normalized = new ArrayList<>();
        for (ObjectNode message : messages) {
            ObjectNode copy = message.deepCopy();
            if (shouldFlattenToolUses(copy, toolNames)) {
                ObjectNode assistant = (ObjectNode) copy.path("assistantResponseMessage");
                flattenedToolUseIds.addAll(toolUseIds(copy));
                assistant.put("content", flattenContent(
                    assistant.path("content").asText(""),
                    formatToolUses(assistant.path("toolUses"))
                ));
                assistant.remove("toolUses");
            }
            if (shouldFlattenToolResults(copy, toolNames, flattenedToolUseIds)) {
                ObjectNode userInput = (ObjectNode) copy.path("userInputMessage");
                ObjectNode context = (ObjectNode) userInput.path("userInputMessageContext");
                userInput.put("content", flattenContent(
                    userInput.path("content").asText(""),
                    formatToolResults(context.path("toolResults"))
                ));
                context.remove("toolResults");
                if (!context.fieldNames().hasNext()) {
                    userInput.remove("userInputMessageContext");
                }
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private boolean shouldFlattenToolUses(ObjectNode message, Set<String> toolNames) {
        if (!hasToolUses(message)) {
            return false;
        }
        if (toolNames.isEmpty()) {
            return true;
        }
        JsonNode toolUses = message.path("assistantResponseMessage").path("toolUses");
        for (JsonNode toolUse : toolUses) {
            if (!toolNames.contains(toolUse.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldFlattenToolResults(ObjectNode message, Set<String> toolNames, Set<String> flattenedToolUseIds) {
        if (!hasToolResults(message)) {
            return false;
        }
        if (toolNames.isEmpty()) {
            return true;
        }
        JsonNode toolResults = message.path("userInputMessage").path("userInputMessageContext").path("toolResults");
        for (JsonNode toolResult : toolResults) {
            if (flattenedToolUseIds.contains(toolResult.path("toolUseId").asText())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> toolNames(JsonNode tools) {
        Set<String> names = new HashSet<>();
        if (tools == null || !tools.isArray()) {
            return names;
        }
        for (JsonNode tool : tools) {
            String name = tool.path("function").path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private String flattenContent(String content, String addition) {
        if (addition == null || addition.isBlank()) {
            return content == null ? "" : content;
        }
        if (content == null || content.isBlank()) {
            return addition;
        }
        return content + "\n\n" + addition;
    }

    private String formatToolUses(JsonNode toolUses) {
        StringBuilder builder = new StringBuilder();
        if (!toolUses.isArray()) {
            return "";
        }
        for (JsonNode toolUse : toolUses) {
            if (!builder.isEmpty()) builder.append("\n\n");
            builder.append("Tool use: ")
                .append(toolUse.path("name").asText())
                .append("\nInput: ")
                .append(toolUse.path("input"));
        }
        return builder.toString();
    }

    private String formatToolResults(JsonNode toolResults) {
        StringBuilder builder = new StringBuilder();
        if (!toolResults.isArray()) {
            return "";
        }
        for (JsonNode toolResult : toolResults) {
            if (!builder.isEmpty()) builder.append("\n\n");
            builder.append("Tool result for ")
                .append(toolResult.path("toolUseId").asText())
                .append(":\n");
            JsonNode content = toolResult.path("content");
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if (part.has("text")) {
                        builder.append(part.path("text").asText());
                    } else {
                        builder.append(part);
                    }
                }
            } else {
                builder.append(content.isMissingNode() ? "" : content.toString());
            }
        }
        return builder.toString();
    }

    private List<ObjectNode> removeInvalidToolResultMessages(List<ObjectNode> messages) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode message : messages) {
            if (!isUser(message) || !hasToolResults(message)) {
                result.add(message);
                continue;
            }
            ObjectNode previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous != null && isAssistant(previous) && hasMatchingToolResults(previous, message)) {
                result.add(message);
                continue;
            }
            ObjectNode stripped = message.deepCopy();
            ObjectNode context = (ObjectNode) stripped.path("userInputMessage").path("userInputMessageContext");
            context.remove("toolResults");
            if (!context.fieldNames().hasNext()) {
                ((ObjectNode) stripped.path("userInputMessage")).remove("userInputMessageContext");
            }
            if (!stripped.path("userInputMessage").path("content").asText("").isBlank()) {
                result.add(stripped);
            }
        }
        return result;
    }

    private List<ObjectNode> ensureValidToolUsesAndResults(List<ObjectNode> messages) {
        List<ObjectNode> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ObjectNode message = messages.get(i);
            result.add(message);
            if (!isAssistant(message) || !hasToolUses(message)) {
                continue;
            }
            ObjectNode next = i + 1 < messages.size() ? messages.get(i + 1) : null;
            if (next == null || !isUser(next) || !hasMatchingToolResults(message, next)) {
                result.add(failedToolResultMessage(toolUseIds(message), messageOrigin(message)));
            }
        }
        return result;
    }

    private List<ObjectNode> ensureAlternatingMessages(List<ObjectNode> messages) {
        if (messages.size() <= 1) {
            return messages;
        }
        List<ObjectNode> result = new ArrayList<>();
        result.add(messages.get(0));
        for (int i = 1; i < messages.size(); i++) {
            ObjectNode previous = result.get(result.size() - 1);
            ObjectNode current = messages.get(i);
            if (isUser(previous) && isUser(current)) {
                result.add(understoodMessage());
            } else if (isAssistant(previous) && isAssistant(current)) {
                result.add(continueMessage(modelCatalog.defaultTargetModelId(), messageOrigin(previous)));
            }
            result.add(current);
        }
        return result;
    }

    private List<ObjectNode> ensureEndsWithUserMessage(List<ObjectNode> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        ObjectNode last = messages.get(messages.size() - 1);
        if (isUser(last)) {
            return messages;
        }
        List<ObjectNode> result = new ArrayList<>(messages);
        result.add(continueMessage(modelCatalog.defaultTargetModelId(), messageOrigin(last)));
        return result;
    }

    private boolean isUser(ObjectNode message) {
        return message.path("userInputMessage").isObject();
    }

    private boolean isAssistant(ObjectNode message) {
        return message.path("assistantResponseMessage").isObject();
    }

    private boolean hasToolUses(ObjectNode message) {
        return message.path("assistantResponseMessage").path("toolUses").isArray()
            && !message.path("assistantResponseMessage").path("toolUses").isEmpty();
    }

    private boolean hasToolResults(ObjectNode message) {
        return message.path("userInputMessage").path("userInputMessageContext").path("toolResults").isArray()
            && !message.path("userInputMessage").path("userInputMessageContext").path("toolResults").isEmpty();
    }

    private boolean endsWithToolResultOnly(List<ObjectNode> messages) {
        if (messages.isEmpty()) {
            return false;
        }
        ObjectNode last = messages.get(messages.size() - 1);
        return isUser(last)
            && hasToolResults(last)
            && last.path("userInputMessage").path("content").asText("").isBlank();
    }

    private boolean hasMatchingToolResults(ObjectNode assistant, ObjectNode user) {
        List<String> expected = toolUseIds(assistant);
        if (expected.isEmpty()) {
            return true;
        }
        JsonNode results = user.path("userInputMessage").path("userInputMessageContext").path("toolResults");
        if (!results.isArray()) {
            return false;
        }
        for (String toolUseId : expected) {
            boolean found = false;
            for (JsonNode result : results) {
                if (toolUseId.equals(result.path("toolUseId").asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private List<String> toolUseIds(ObjectNode assistant) {
        List<String> ids = new ArrayList<>();
        JsonNode toolUses = assistant.path("assistantResponseMessage").path("toolUses");
        if (!toolUses.isArray()) {
            return ids;
        }
        for (JsonNode toolUse : toolUses) {
            String id = toolUse.path("toolUseId").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String messageOrigin(ObjectNode message) {
        return message.path("userInputMessage").path("origin").asText("AI_EDITOR");
    }

    private JsonNode parseJsonOrObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("value", raw);
            return fallback;
        }
    }

    private String normalizedRequestedModel(String requestedModel) {
        return requestedModel == null || requestedModel.isBlank()
            ? modelCatalog.defaultTargetModelId()
            : requestedModel.trim();
    }

    private String contentToText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText()) || "input_text".equals(part.path("type").asText())) {
                    if (!builder.isEmpty()) builder.append("\n");
                    builder.append(part.path("text").asText());
                }
            }
            return builder.toString();
        }
        return content.toString();
    }

    private String stableConversationId(JsonNode request, String sessionHint) {
        String explicit = firstNonBlank(
            request.path("conversation_id").asText(null),
            request.path("conversationId").asText(null),
            sessionHint
        );
        return explicit == null ? UUID.randomUUID().toString() : UUID.nameUUIDFromBytes(explicit.getBytes()).toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String profileArn(ProxyAccountEntity account) {
        if (account.getProfileArn() != null && !account.getProfileArn().isBlank()) {
            return account.getProfileArn();
        }
        String provider = account.getProvider();
        if ("Github".equalsIgnoreCase(provider) || "Google".equalsIgnoreCase(provider)) {
            return SOCIAL_PROFILE_ARN;
        }
        return BUILDER_ID_PROFILE_ARN;
    }
}

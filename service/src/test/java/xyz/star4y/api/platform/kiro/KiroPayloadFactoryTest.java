package xyz.star4y.api.platform.kiro;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.model.DefaultModelCatalogProvider;

class KiroPayloadFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KiroPayloadFactory factory = new KiroPayloadFactory(objectMapper, new DefaultModelCatalogProvider());

    @Test
    void buildsConversationPayloadFromOpenAiMessages() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "system", "content": "system prompt"},
                {"role": "user", "content": "hello"}
              ],
              "max_tokens": 128
            }
            """);
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProfileArn("arn:test");

        ObjectNode payload = factory.fromOpenAiChat(request, account, "gpt-4o", "AI_EDITOR", "session-1");

        assertThat(payload.path("profileArn").asText()).isEqualTo("arn:test");
        JsonNode history = payload.path("conversationState").path("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("userInputMessage").path("content").asText())
            .contains("system prompt")
            .contains("<identity_discipline>")
            .contains("client-requested model id: gpt-4o")
            .contains("<execution_discipline>");
        assertThat(history.get(1).path("assistantResponseMessage").path("content").asText())
            .isEqualTo("I will follow these instructions.");
        assertThat(payload.path("conversationState").path("currentMessage").path("userInputMessage").path("content").asText())
            .isEqualTo("hello");
        assertThat(payload.path("conversationState").path("currentMessage").path("userInputMessage").path("modelId").asText())
            .isEqualTo("claude-sonnet-4.5");
        assertThat(payload.path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(128);
    }

    @Test
    void usesDefaultProfileArnWhenAccountDoesNotProvideOne() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "simple-task",
              "messages": [
                {"role": "user", "content": "ping"}
              ]
            }
            """);
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProvider("BuilderId");

        ObjectNode payload = factory.fromOpenAiChat(request, account, "simple-task", "AI_EDITOR", "session-1");

        assertThat(payload.path("profileArn").asText())
            .isEqualTo("arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX");
    }

    @Test
    void preservesAssistantToolCallsAndToolResultsInHistory() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "user", "content": "list files"},
                {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [
                    {
                      "id": "toolu_1",
                      "type": "function",
                      "function": {
                        "name": "Bash",
                        "arguments": "{\\"command\\":\\"ls\\"}"
                      }
                    }
                  ]
                },
                {
                  "role": "tool",
                  "tool_call_id": "toolu_1",
                  "content": "README.md"
                },
                {"role": "user", "content": "summarize"}
              ],
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "Bash",
                    "description": "Run a shell command",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "command": {"type": "string"}
                      }
                    }
                  }
                }
              ]
            }
            """);
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProfileArn("arn:test");

        ObjectNode payload = factory.fromOpenAiChat(request, account, "gpt-4o", "AI_EDITOR", "session-1");

        JsonNode history = payload.path("conversationState").path("history");
        JsonNode toolUse = findFirstToolUse(history);
        assertThat(toolUse.path("toolUseId").asText()).isEqualTo("toolu_1");
        assertThat(toolUse.path("name").asText()).isEqualTo("Bash");
        assertThat(toolUse.path("input").path("command").asText()).isEqualTo("ls");

        JsonNode toolResult = findFirstToolResult(history);
        assertThat(toolResult.path("toolUseId").asText()).isEqualTo("toolu_1");
        assertThat(toolResult.path("content").get(0).path("text").asText()).isEqualTo("README.md");
        assertThat(toolResult.path("status").asText()).isEqualTo("success");
    }

    @Test
    void appendsFailedToolResultWhenAssistantToolCallIsUnanswered() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "user", "content": "list files"},
                {
                  "role": "assistant",
                  "content": "",
                  "tool_calls": [
                    {
                      "id": "toolu_missing",
                      "type": "function",
                      "function": {
                        "name": "Bash",
                        "arguments": "{\\"command\\":\\"ls\\"}"
                      }
                    }
                  ]
                }
              ],
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "Bash",
                    "description": "Run a shell command",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "command": {"type": "string"}
                      }
                    }
                  }
                }
              ]
            }
            """);
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProfileArn("arn:test");

        ObjectNode payload = factory.fromOpenAiChat(request, account, "gpt-4o", "AI_EDITOR", "session-1");

        JsonNode toolResult = findFirstToolResult(payload.path("conversationState").path("history"));
        assertThat(toolResult.path("toolUseId").asText()).isEqualTo("toolu_missing");
        assertThat(toolResult.path("status").asText()).isEqualTo("error");
        assertThat(payload.path("conversationState").path("currentMessage").path("userInputMessage").path("content").asText())
            .isEmpty();
    }

    private JsonNode findFirstToolUse(JsonNode history) {
        for (JsonNode message : history) {
            JsonNode toolUses = message.path("assistantResponseMessage").path("toolUses");
            if (toolUses.isArray() && !toolUses.isEmpty()) {
                return toolUses.get(0);
            }
        }
        throw new AssertionError("tool use not found");
    }

    private JsonNode findFirstToolResult(JsonNode history) {
        for (JsonNode message : history) {
            JsonNode toolResults = message.path("userInputMessage").path("userInputMessageContext").path("toolResults");
            if (toolResults.isArray() && !toolResults.isEmpty()) {
                return toolResults.get(0);
            }
        }
        throw new AssertionError("tool result not found");
    }
}

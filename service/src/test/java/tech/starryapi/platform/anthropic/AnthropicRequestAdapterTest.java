package tech.starryapi.platform.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnthropicRequestAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicRequestAdapter adapter = new AnthropicRequestAdapter(objectMapper, new DefaultModelCatalogProvider());

    @Test
    void assistantToolUseBecomesToolCallsWithoutDuplicatingToolText() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude-sonnet-4.5",
              "messages": [
                {
                  "role": "assistant",
                  "content": [
                    {
                      "type": "tool_use",
                      "id": "toolu_1",
                      "name": "Bash",
                      "input": {"command": "pwd"}
                    }
                  ]
                }
              ]
            }
            """);

        JsonNode chat = adapter.messagesToChat(request);

        JsonNode message = chat.path("messages").path(0);
        assertThat(message.path("role").asString("")).isEqualTo("assistant");
        assertThat(message.path("content").asString("")).isEmpty();
        JsonNode toolCall = message.path("tool_calls").path(0);
        assertThat(toolCall.path("id").asString("")).isEqualTo("toolu_1");
        assertThat(toolCall.path("function").path("name").asString("")).isEqualTo("Bash");
        assertThat(toolCall.path("function").path("arguments").asString("")).isEqualTo("{\"command\":\"pwd\"}");
    }

    @Test
    void toolChoiceIsConvertedToOpenAiShape() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude-sonnet-4.5",
              "tool_choice": {
                "type": "tool",
                "name": "Bash",
                "disable_parallel_tool_use": true
              },
              "messages": [
                {"role": "user", "content": "run pwd"}
              ]
            }
            """);

        JsonNode chat = adapter.messagesToChat(request);

        assertThat(chat.path("tool_choice").path("type").asString("")).isEqualTo("function");
        assertThat(chat.path("tool_choice").path("function").path("name").asString("")).isEqualTo("Bash");
        assertThat(chat.path("parallel_tool_calls").asBoolean(true)).isFalse();
    }

    @Test
    void anyToolChoiceRequiresATool() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude-sonnet-4.5",
              "tool_choice": {"type": "any"},
              "messages": [
                {"role": "user", "content": "use a tool"}
              ]
            }
            """);

        JsonNode chat = adapter.messagesToChat(request);

        assertThat(chat.path("tool_choice").asString("")).isEqualTo("required");
    }
}

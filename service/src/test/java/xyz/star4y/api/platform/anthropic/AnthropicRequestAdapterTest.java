package xyz.star4y.api.platform.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.star4y.api.model.DefaultModelCatalogProvider;

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
        assertThat(message.path("role").asText()).isEqualTo("assistant");
        assertThat(message.path("content").asText()).isEmpty();
        JsonNode toolCall = message.path("tool_calls").path(0);
        assertThat(toolCall.path("id").asText()).isEqualTo("toolu_1");
        assertThat(toolCall.path("function").path("name").asText()).isEqualTo("Bash");
        assertThat(toolCall.path("function").path("arguments").asText()).isEqualTo("{\"command\":\"pwd\"}");
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

        assertThat(chat.path("tool_choice").path("type").asText()).isEqualTo("function");
        assertThat(chat.path("tool_choice").path("function").path("name").asText()).isEqualTo("Bash");
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

        assertThat(chat.path("tool_choice").asText()).isEqualTo("required");
    }
}

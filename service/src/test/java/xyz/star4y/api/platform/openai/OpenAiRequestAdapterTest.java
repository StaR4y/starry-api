package xyz.star4y.api.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.star4y.api.model.DefaultModelCatalogProvider;

class OpenAiRequestAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiRequestAdapter adapter = new OpenAiRequestAdapter(objectMapper, new DefaultModelCatalogProvider());

    @Test
    void responsesFunctionCallOutputArrayKeepsTextContent() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.4-mini",
              "input": [
                {
                  "type": "function_call_output",
                  "call_id": "call_1",
                  "output": [
                    {"type": "output_text", "text": "line one"},
                    {"type": "output_text", "text": "line two"}
                  ]
                }
              ]
            }
            """);

        JsonNode chat = adapter.responsesToChat(request);

        JsonNode message = chat.path("messages").path(0);
        assertThat(message.path("role").asText()).isEqualTo("tool");
        assertThat(message.path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(message.path("content").asText()).isEqualTo("line one\nline two");
    }

    @Test
    void responsesAssistantRefusalKeepsTextContent() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.4-mini",
              "input": [
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {"type": "refusal", "refusal": "I cannot help with that."}
                  ]
                }
              ]
            }
            """);

        JsonNode chat = adapter.responsesToChat(request);

        JsonNode message = chat.path("messages").path(0);
        assertThat(message.path("role").asText()).isEqualTo("assistant");
        assertThat(message.path("content").path(0).path("type").asText()).isEqualTo("text");
        assertThat(message.path("content").path(0).path("text").asText()).isEqualTo("I cannot help with that.");
    }
}

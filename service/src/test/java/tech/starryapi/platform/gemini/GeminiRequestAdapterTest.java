package tech.starryapi.platform.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class GeminiRequestAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiRequestAdapter adapter = new GeminiRequestAdapter(objectMapper);

    @Test
    void convertsGenerateContentRequestToChatRequest() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "systemInstruction": {
                "parts": [{"text": "be concise"}]
              },
              "contents": [
                {
                  "role": "user",
                  "parts": [{"text": "say hello"}]
                }
              ],
              "generationConfig": {
                "temperature": 0.2,
                "topP": 0.9,
                "maxOutputTokens": 128
              },
              "tools": [
                {
                  "functionDeclarations": [
                    {
                      "name": "lookup",
                      "description": "Lookup data",
                      "parameters": {"type": "object"}
                    }
                  ]
                }
              ]
            }
            """);

        ObjectNode chat = adapter.generateContentToChat("gemini-2.5-pro", request);

        assertThat(chat.path("model").asText()).isEqualTo("gemini-2.5-pro");
        assertThat(chat.path("messages")).hasSize(2);
        assertThat(chat.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(chat.path("messages").get(0).path("content").asText()).isEqualTo("be concise");
        assertThat(chat.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(chat.path("messages").get(1).path("content").asText()).isEqualTo("say hello");
        assertThat(chat.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(chat.path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookup");
    }
}

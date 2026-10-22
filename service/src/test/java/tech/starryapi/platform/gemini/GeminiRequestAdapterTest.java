package tech.starryapi.platform.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

        assertThat(chat.path("model").asString("")).isEqualTo("gemini-2.5-pro");
        assertThat(chat.path("messages")).hasSize(2);
        assertThat(chat.path("messages").get(0).path("role").asString("")).isEqualTo("system");
        assertThat(chat.path("messages").get(0).path("content").asString("")).isEqualTo("be concise");
        assertThat(chat.path("messages").get(1).path("role").asString("")).isEqualTo("user");
        assertThat(chat.path("messages").get(1).path("content").asString("")).isEqualTo("say hello");
        assertThat(chat.path("max_tokens").asInt(0)).isEqualTo(128);
        assertThat(chat.path("tools").get(0).path("function").path("name").asString("")).isEqualTo("lookup");
    }
}

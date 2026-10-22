package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiRequestAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiRequestAdapter adapter = new OpenAiRequestAdapter(objectMapper, new DefaultModelCatalogProvider());

    @Test
    void responsesFunctionCallOutputArrayKeepsTextContent() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
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
        assertThat(message.path("role").asString("")).isEqualTo("tool");
        assertThat(message.path("tool_call_id").asString("")).isEqualTo("call_1");
        assertThat(message.path("content").asString("")).isEqualTo("line one\nline two");
    }

    @Test
    void responsesAssistantRefusalKeepsTextContent() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
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
        assertThat(message.path("role").asString("")).isEqualTo("assistant");
        assertThat(message.path("content").path(0).path("type").asString("")).isEqualTo("text");
        assertThat(message.path("content").path(0).path("text").asString("")).isEqualTo("I cannot help with that.");
    }

    @Test
    void responsesRequestKeepsPreviousResponseId() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "previous_response_id": "resp_123",
              "input": "continue"
            }
            """);

        JsonNode chat = adapter.responsesToChat(request);

        assertThat(chat.path("previous_response_id").asString("")).isEqualTo("resp_123");
    }

    @Test
    void responsesMultimodalContentConvertsToChatParts() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "input": [
                {
                  "type": "message",
                  "role": "user",
                  "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": "data:image/png;base64,abc", "detail": "high"},
                    {"type": "input_file", "file_id": "file_123", "filename": "notes.txt"}
                  ]
                }
              ]
            }
            """);

        JsonNode chat = adapter.responsesToChat(request);

        JsonNode content = chat.path("messages").path(0).path("content");
        assertThat(content.path(0).path("type").asString("")).isEqualTo("text");
        assertThat(content.path(0).path("text").asString("")).isEqualTo("describe");
        assertThat(content.path(1).path("type").asString("")).isEqualTo("image_url");
        assertThat(content.path(1).path("image_url").path("url").asString("")).isEqualTo("data:image/png;base64,abc");
        assertThat(content.path(1).path("image_url").path("detail").asString("")).isEqualTo("high");
        assertThat(content.path(2).path("type").asString("")).isEqualTo("text");
        assertThat(content.path(2).path("text").asString("")).contains("notes.txt").contains("file_123");
    }
}

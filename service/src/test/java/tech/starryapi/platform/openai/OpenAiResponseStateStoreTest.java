package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class OpenAiResponseStateStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiResponseStateStore store = new OpenAiResponseStateStore(objectMapper);

    @Test
    void expandsPreviousResponseIdIntoManualResponsesHistory() throws Exception {
        JsonNode firstRequest = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "input": "write a file"
            }
            """);
        ObjectNode firstResponse = (ObjectNode) objectMapper.readTree("""
            {
              "id": "resp_1",
              "output": [
                {
                  "type": "function_call",
                  "id": "fc_1",
                  "call_id": "call_1",
                  "name": "exec_command",
                  "arguments": "{\\"cmd\\":\\"pwd\\"}",
                  "status": "completed"
                }
              ]
            }
            """);
        JsonNode nextRequest = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "previous_response_id": "resp_1",
              "input": [
                {
                  "type": "function_call_output",
                  "call_id": "call_1",
                  "output": "done"
                }
              ]
            }
            """);

        store.remember(firstResponse, firstRequest);
        ObjectNode expanded = store.expand(nextRequest);

        assertThat(expanded.has("previous_response_id")).isFalse();
        assertThat(expanded.path("store").asBoolean(true)).isFalse();
        JsonNode input = expanded.path("input");
        assertThat(input).hasSize(3);
        assertThat(input.path(0).path("type").asText()).isEqualTo("message");
        assertThat(input.path(0).path("role").asText()).isEqualTo("user");
        assertThat(input.path(0).path("content").path(0).path("text").asText()).isEqualTo("write a file");
        assertThat(input.path(1).path("type").asText()).isEqualTo("function_call");
        assertThat(input.path(1).path("call_id").asText()).isEqualTo("call_1");
        assertThat(input.path(2).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.path(2).path("call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void leavesUnknownPreviousResponseIdUntouched() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "previous_response_id": "resp_missing",
              "input": "continue"
            }
            """);

        ObjectNode expanded = store.expand(request);

        assertThat(expanded.path("previous_response_id").asText()).isEqualTo("resp_missing");
        assertThat(expanded.path("input").asText()).isEqualTo("continue");
    }

    @Test
    void expandsCustomToolCallHistoryWithoutRewritingItAsFunctionTool() throws Exception {
        JsonNode firstRequest = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "input": "write a file"
            }
            """);
        ObjectNode firstResponse = (ObjectNode) objectMapper.readTree("""
            {
              "id": "resp_1",
              "output": [
                {
                  "type": "custom_tool_call",
                  "id": "ctc_1",
                  "call_id": "call_patch",
                  "name": "apply_patch",
                  "input": "*** Begin Patch\\n*** Add File: 1.txt\\n+hello\\n*** End Patch"
                }
              ]
            }
            """);
        JsonNode nextRequest = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "previous_response_id": "resp_1",
              "input": [
                {
                  "type": "custom_tool_call_output",
                  "call_id": "call_patch",
                  "output": "Done"
                }
              ]
            }
            """);

        store.remember(firstResponse, firstRequest);
        ObjectNode expanded = store.expand(nextRequest);

        JsonNode input = expanded.path("input");
        assertThat(input).hasSize(3);
        assertThat(input.path(1).path("type").asText()).isEqualTo("custom_tool_call");
        assertThat(input.path(1).path("call_id").asText()).isEqualTo("call_patch");
        assertThat(input.path(1).path("input").asText()).startsWith("*** Begin Patch");
        assertThat(input.path(2).path("type").asText()).isEqualTo("custom_tool_call_output");
        assertThat(input.path(2).path("call_id").asText()).isEqualTo("call_patch");
    }
}

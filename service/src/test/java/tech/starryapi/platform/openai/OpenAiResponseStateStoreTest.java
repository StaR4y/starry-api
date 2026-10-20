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
    void remembersTheAccountThatProducedAResponse() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {"model":"gpt-5.5","input":"hello"}
            """);
        ObjectNode response = (ObjectNode) objectMapper.readTree("""
            {"id":"resp_owner","output":[]}
            """);

        store.remember(response, request, "openai-account-1");

        assertThat(store.ownerAccountId("resp_owner")).isEqualTo("openai-account-1");
        assertThat(store.canExpand("resp_owner")).isTrue();
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

    @Test
    void retainedBytesStayWithinTheConfiguredBudgetNoMatterHowManyLargeContextsArrive() throws Exception {
        OpenAiResponseStateStore bounded = new OpenAiResponseStateStore(objectMapper, 2L * 1024 * 1024, 1024 * 1024);
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            // ~200 KB of poorly compressible text per request, so each history costs real bytes.
            byte[] noise = new byte[150_000];
            random.nextBytes(noise);
            ObjectNode request = objectMapper.createObjectNode().put("model", "gpt-5.5")
                .put("input", java.util.Base64.getEncoder().encodeToString(noise));
            ObjectNode response = objectMapper.createObjectNode().put("id", "resp_" + i);
            response.putArray("output");
            bounded.remember(response, request, "account-1");
            assertThat(bounded.retainedBytes()).isLessThanOrEqualTo(2L * 1024 * 1024);
        }

        assertThat(bounded.size()).isGreaterThan(0).isLessThan(200);
        assertThat(bounded.canExpand("resp_0")).isFalse();
        assertThat(bounded.canExpand("resp_199")).isTrue();
    }

    @Test
    void aSingleHugeHistoryIsNotRetainedAtAll() throws Exception {
        OpenAiResponseStateStore bounded = new OpenAiResponseStateStore(objectMapper, 64L * 1024 * 1024, 64 * 1024);
        byte[] noise = new byte[300_000];
        new java.util.Random(11).nextBytes(noise);
        ObjectNode request = objectMapper.createObjectNode().put("input", java.util.Base64.getEncoder().encodeToString(noise));
        ObjectNode response = objectMapper.createObjectNode().put("id", "resp_huge");
        response.putArray("output");

        bounded.remember(response, request, "account-1");

        assertThat(bounded.size()).isZero();
        assertThat(bounded.canExpand("resp_huge")).isFalse();
        // An unknown previous_response_id is passed through untouched instead of failing the request.
        ObjectNode expanded = bounded.expand(objectMapper.createObjectNode().put("previous_response_id", "resp_huge").put("input", "next"));
        assertThat(expanded.path("previous_response_id").asText()).isEqualTo("resp_huge");
    }

    @Test
    void expandedHistoryRoundTripsThroughCompression() throws Exception {
        ObjectNode request = objectMapper.createObjectNode().put("input", "你好，请把这段中文也原样保存。".repeat(200));
        ObjectNode response = objectMapper.createObjectNode().put("id", "resp_cn");
        response.putArray("output").addObject().put("type", "message").put("role", "assistant");
        store.remember(response, request, "account-1");

        ObjectNode expanded = store.expand(objectMapper.createObjectNode().put("previous_response_id", "resp_cn").put("input", "继续"));

        assertThat(expanded.path("input")).hasSize(3);
        assertThat(expanded.path("input").path(0).path("content").path(0).path("text").asText())
            .isEqualTo("你好，请把这段中文也原样保存。".repeat(200));
    }
}

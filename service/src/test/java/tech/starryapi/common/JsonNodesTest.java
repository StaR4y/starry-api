package tech.starryapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JsonNodesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void countsMatchTheSerializedFormWithoutBuildingIt() throws Exception {
        JsonNode node = mapper.readTree("""
            {"model":"gpt-5.5","messages":[{"role":"user","content":"你好 \\"quoted\\" \\n line 😀 text"}],
             "n":1.5,"flag":true,"nothing":null,"nested":{"a":[1,2,3]}}
            """);

        assertThat(JsonNodes.utf8Length(node)).isEqualTo(node.toString().getBytes(StandardCharsets.UTF_8).length);
        assertThat(JsonNodes.charLength(node)).isEqualTo(node.toString().length());
        assertThat(JsonNodes.utf8Length(null)).isZero();
    }

    @Test
    void shallowCopySharesChildrenButLetsTheTopLevelDiverge() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree("{\"model\":\"a\",\"messages\":[{\"role\":\"user\"}]}");

        ObjectNode copy = JsonNodes.shallowCopy(source);
        copy.put("model", "b");
        copy.put("max_tokens", 10);
        copy.remove("messages");

        assertThat(source.path("model").asText()).isEqualTo("a");
        assertThat(source.has("max_tokens")).isFalse();
        assertThat(source.has("messages")).isTrue();
        assertThat(JsonNodes.shallowCopy(source).get("messages")).isSameAs(source.get("messages"));
    }

    @Test
    void utf8BytesAreIdenticalToSerialisingThroughAString() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            longText.append("段落 ").append(i).append(" \uD83D\uDE00 text \"q\" \\ / \n");
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("model", "claude-sonnet-4.5").put("text", longText.toString()).put("n", 1.25).putNull("x");
        node.putArray("items").add(1).add("é").addObject().put("k", "v");

        byte[] direct = JsonNodes.toUtf8Bytes(node);

        assertThat(direct).isEqualTo(node.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(mapper.readTree(direct)).isEqualTo(node);
        assertThat(JsonNodes.toUtf8Bytes(null)).isEmpty();
    }
}

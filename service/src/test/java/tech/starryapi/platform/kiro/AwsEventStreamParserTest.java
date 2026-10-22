package tech.starryapi.platform.kiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tools.jackson.databind.ObjectMapper;

class AwsEventStreamParserTest {

    private final AwsEventStreamParser parser = new AwsEventStreamParser(new ObjectMapper());

    @Test
    void parsesAssistantContentAndTokenUsage() {
        byte[] bytes = concat(
            event("{\"assistantResponseEvent\":{\"content\":\"hello\"}}"),
            event("{\"messageMetadataEvent\":{\"tokenUsage\":{\"uncachedInputTokens\":7,\"cacheReadInputTokens\":2,\"cacheWriteInputTokens\":1,\"outputTokens\":3}}}")
        );

        PlatformCompletionResult result = parser.parse(bytes, "claude-sonnet-4.5", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(result.usage().outputTokens()).isEqualTo(3);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(2);
        assertThat(result.usage().cacheWriteTokens()).isEqualTo(1);
    }

    @Test
    void parsesBarePayloadUsingEventStreamHeaderType() {
        byte[] bytes = concat(
            event("assistantResponseEvent", "{\"content\":\"hello\"}"),
            event("messageMetadataEvent", "{\"tokenUsage\":{\"uncachedInputTokens\":4,\"outputTokens\":2}}")
        );

        PlatformCompletionResult result = parser.parse(bytes, "claude-sonnet-4.5", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.usage().inputTokens()).isEqualTo(4);
        assertThat(result.usage().outputTokens()).isEqualTo(2);
    }

    @Test
    void parsesToolUseFragmentsUsingEventStreamHeaderType() {
        byte[] bytes = concat(
            event("toolUseEvent", "{\"toolUseId\":\"toolu_1\",\"name\":\"Bash\",\"input\":\"{\\\"command\\\":\"}"),
            event("toolUseEvent", "{\"toolUseId\":\"toolu_1\",\"name\":\"Bash\",\"input\":\"\\\"pwd\\\"}\",\"stop\":true}")
        );

        PlatformCompletionResult result = parser.parse(bytes, "claude-sonnet-4.5", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(result.toolUses()).hasSize(1);
        assertThat(result.toolUses().get(0).id()).isEqualTo("toolu_1");
        assertThat(result.toolUses().get(0).name()).isEqualTo("Bash");
        assertThat(result.toolUses().get(0).input().path("command").asString("")).isEqualTo("pwd");
        assertThat(result.usage().outputTokens()).isEqualTo(1);
    }

    @Test
    void anExceptionFrameBecomesAnUpstreamFailureInsteadOfAPartialSuccess() {
        byte[] bytes = concat(
            event("{\"assistantResponseEvent\":{\"content\":\"partial\"}}"),
            frameWithHeaders(":message-type", "exception", ":exception-type", "ThrottlingException", "{\"message\":\"slow down\"}")
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> parser.parse(bytes, "claude-sonnet-4.5", "{}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOfSatisfying(tech.starryapi.platform.upstream.UpstreamException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(429);
                assertThat(error.isRecoverable()).isTrue();
                assertThat(error.getMessage()).contains("ThrottlingException", "slow down");
            });
    }

    @Test
    void aStreamThatStopsMidFrameAfterCompleteFramesIsReportedAsTruncated() {
        byte[] complete = event("{\"assistantResponseEvent\":{\"content\":\"hello\"}}");
        byte[] second = event("{\"assistantResponseEvent\":{\"content\":\" world\"}}");
        byte[] truncated = concat(complete, java.util.Arrays.copyOf(second, second.length - 5));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> parser.parse(truncated, "claude-sonnet-4.5", "{}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOfSatisfying(tech.starryapi.platform.upstream.UpstreamException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(502);
                assertThat(error.isRecoverable()).isTrue();
            });
    }

    private byte[] frameWithHeaders(String name1, String value1, String name2, String value2, String json) {
        byte[] n1 = name1.getBytes(StandardCharsets.UTF_8);
        byte[] v1 = value1.getBytes(StandardCharsets.UTF_8);
        byte[] n2 = name2.getBytes(StandardCharsets.UTF_8);
        byte[] v2 = value2.getBytes(StandardCharsets.UTF_8);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        int headersLength = (1 + n1.length + 1 + 2 + v1.length) + (1 + n2.length + 1 + 2 + v2.length);
        int totalLength = 16 + headersLength + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(totalLength);
        buffer.putInt(headersLength);
        buffer.putInt(0);
        for (byte[][] pair : new byte[][][] {{n1, v1}, {n2, v2}}) {
            buffer.put((byte) pair[0].length);
            buffer.put(pair[0]);
            buffer.put((byte) 7);
            buffer.putShort((short) pair[1].length);
            buffer.put(pair[1]);
        }
        buffer.put(payload);
        buffer.putInt(0);
        return buffer.array();
    }

    private byte[] event(String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        int totalLength = 16 + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(totalLength);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put(payload);
        buffer.putInt(0);
        return buffer.array();
    }

    private byte[] event(String eventType, String json) {
        byte[] name = ":event-type".getBytes(StandardCharsets.UTF_8);
        byte[] value = eventType.getBytes(StandardCharsets.UTF_8);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        int headersLength = 1 + name.length + 1 + 2 + value.length;
        int totalLength = 16 + headersLength + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(totalLength);
        buffer.putInt(headersLength);
        buffer.putInt(0);
        buffer.put((byte) name.length);
        buffer.put(name);
        buffer.put((byte) 7);
        buffer.putShort((short) value.length);
        buffer.put(value);
        buffer.put(payload);
        buffer.putInt(0);
        return buffer.array();
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}

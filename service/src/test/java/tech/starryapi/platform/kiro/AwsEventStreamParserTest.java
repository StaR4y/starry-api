package tech.starryapi.platform.kiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.upstream.UpstreamException;
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

    @Test
    void aTruncatedStreamAfterContentMarksGenerationAsStartedWithoutChangingStatusOrRecoverability() {
        UpstreamException error = parseFailure(truncated(event("{\"assistantResponseEvent\":{\"content\":\"hello\"}}")));

        assertThat(error.isGenerationStarted()).isTrue();
        assertThat(error.getStatusCode()).isEqualTo(502);
        assertThat(error.isRecoverable()).isTrue();
        assertThat(error.getMessage()).isEqualTo("Kiro upstream event stream was truncated");
    }

    @Test
    void aTruncatedStreamAfterAToolCallMarksGenerationAsStarted() {
        UpstreamException error = parseFailure(truncated(
            event("toolUseEvent", "{\"toolUseId\":\"toolu_1\",\"name\":\"Bash\",\"input\":\"{\\\"command\\\":\"}")));

        assertThat(error.isGenerationStarted()).isTrue();
    }

    @Test
    void aTruncatedStreamAfterAUsageFrameMarksGenerationAsStarted() {
        assertThat(parseFailure(truncated(
            event("{\"messageMetadataEvent\":{\"tokenUsage\":{\"uncachedInputTokens\":4,\"outputTokens\":2}}}"))).isGenerationStarted()).isTrue();
        assertThat(parseFailure(truncated(
            event("{\"usageEvent\":{\"inputTokens\":4,\"outputTokens\":2}}"))).isGenerationStarted()).isTrue();
        assertThat(parseFailure(truncated(
            event("{\"meteringEvent\":{\"usage\":0.02}}"))).isGenerationStarted()).isTrue();
        assertThat(parseFailure(truncated(
            event("{\"contextUsageEvent\":{\"contextUsagePercentage\":12.5}}"))).isGenerationStarted()).isTrue();
    }

    @Test
    void aTruncatedStreamWhoseCompleteFramesCarriedNothingIsNotStarted() {
        byte[] framesWithoutOutput = concat(
            event("initial-response", "{}"),
            event("{\"unrelatedEvent\":{\"x\":1}}")
        );
        byte[] notJson = concat(framesWithoutOutput, event("initial-response", "not json"));

        assertThat(parseFailure(truncated(framesWithoutOutput)).isGenerationStarted()).isFalse();
        assertThat(parseFailure(truncated(notJson)).isGenerationStarted()).isFalse();
    }

    @Test
    void anExceptionFrameAfterOutputMarksGenerationAsStartedWithoutChangingStatusOrRecoverability() {
        byte[] throttled = concat(
            event("{\"assistantResponseEvent\":{\"content\":\"partial\"}}"),
            frameWithHeaders(":message-type", "exception", ":exception-type", "ThrottlingException", "{\"message\":\"slow down\"}")
        );
        byte[] unavailable = concat(
            event("toolUseEvent", "{\"toolUseId\":\"toolu_1\",\"name\":\"Bash\"}"),
            frameWithHeaders(":message-type", "exception", ":exception-type", "ServiceUnavailableException", "{}")
        );
        byte[] internal = concat(
            event("{\"messageMetadataEvent\":{\"tokenUsage\":{\"uncachedInputTokens\":4,\"outputTokens\":2}}}"),
            frameWithHeaders(":message-type", "error", ":error-code", "InternalFailure", "{}")
        );

        UpstreamException first = parseFailure(throttled);
        assertThat(first.isGenerationStarted()).isTrue();
        assertThat(first.getStatusCode()).isEqualTo(429);
        assertThat(first.isRecoverable()).isTrue();
        UpstreamException second = parseFailure(unavailable);
        assertThat(second.isGenerationStarted()).isTrue();
        assertThat(second.getStatusCode()).isEqualTo(503);
        assertThat(second.isRecoverable()).isTrue();
        UpstreamException third = parseFailure(internal);
        assertThat(third.isGenerationStarted()).isTrue();
        assertThat(third.getStatusCode()).isEqualTo(500);
        assertThat(third.isRecoverable()).isTrue();
    }

    @Test
    void anExceptionFrameAsTheFirstFrameIsNotStarted() {
        UpstreamException throttled = parseFailure(
            frameWithHeaders(":message-type", "exception", ":exception-type", "ThrottlingException", "{\"message\":\"slow down\"}"));
        UpstreamException denied = parseFailure(
            frameWithHeaders(":message-type", "exception", ":exception-type", "AccessDeniedException", "{}"));

        assertThat(throttled.isGenerationStarted()).isFalse();
        assertThat(throttled.getStatusCode()).isEqualTo(429);
        assertThat(throttled.isRecoverable()).isTrue();
        assertThat(denied.isGenerationStarted()).isFalse();
        assertThat(denied.getStatusCode()).isEqualTo(403);
        assertThat(denied.isRecoverable()).isFalse();
    }

    @Test
    void anExceptionFrameAfterOnlyFramesWithoutOutputIsNotStarted() {
        byte[] bytes = concat(
            event("initial-response", "{}"),
            frameWithHeaders(":message-type", "exception", ":exception-type", "ThrottlingException", "{\"message\":\"slow down\"}")
        );

        assertThat(parseFailure(bytes).isGenerationStarted()).isFalse();
    }

    @Test
    void theStartedFlagIsPerParseAndDoesNotLeakBetweenStreams() {
        byte[] started = truncated(event("{\"assistantResponseEvent\":{\"content\":\"hello\"}}"));
        byte[] notStarted = frameWithHeaders(":message-type", "exception", ":exception-type", "ThrottlingException", "{}");

        assertThat(parseFailure(started).isGenerationStarted()).isTrue();
        assertThat(parseFailure(notStarted).isGenerationStarted()).isFalse();
        assertThat(parseFailure(started).isGenerationStarted()).isTrue();
    }

    private UpstreamException parseFailure(byte[] bytes) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
            UpstreamException.class,
            () -> parser.parse(bytes, "claude-sonnet-4.5", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    /** The given complete frames, then a second frame cut off mid-way (parse() reports that as a truncated stream). */
    private byte[] truncated(byte[] completeFrames) {
        byte[] next = event("{\"assistantResponseEvent\":{\"content\":\" world\"}}");
        return concat(completeFrames, java.util.Arrays.copyOf(next, next.length - 5));
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

package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Jackson 3 limits nesting to 500 levels (Jackson 2: 1000) both when reading and when writing, and
 * {@code JsonNode.toString()} cannot be configured to allow more. A body nested deeper than that is rejected at the
 * door with a 400 instead of being accepted and then failing wherever the tree is turned into text. No real request
 * comes anywhere near this: tool schemas and message content nest a few dozen levels at most.
 */
class JsonLimitsTest {

    @Test
    void nestingBeyondFiveHundredLevelsIsRejectedAtTheCodec() {
        ProductionJson.run(json -> {
            String limit = "[".repeat(500) + "]".repeat(500);
            assertThat(json.echoTree(limit)).isEqualTo(limit);
            assertThat(json.mapper().readTree(limit).toString()).isEqualTo(limit);
            assertThat(json.echoTree("[".repeat(501) + "]".repeat(501))).isEqualTo("HTTP 400");
        });
    }
}

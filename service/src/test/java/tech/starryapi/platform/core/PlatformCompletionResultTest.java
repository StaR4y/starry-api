package tech.starryapi.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PlatformCompletionResultTest {

    private static final PlatformUsage USAGE = new PlatformUsage(10, 2, BigDecimal.ZERO, 0, 0, 0);

    @Test
    void theTwoThreeAndFourArgumentConstructorsLeaveTheNewComponentsNull() {
        PlatformToolUse tool = new PlatformToolUse("toolu_1", "Bash", new ObjectMapper().createObjectNode());
        List<PlatformCompletionResult> legacy = List.of(
            new PlatformCompletionResult("a", USAGE),
            new PlatformCompletionResult("a", List.of(tool), USAGE),
            new PlatformCompletionResult("a", "why", List.of(tool), USAGE)
        );

        assertThat(legacy).allSatisfy(result -> {
            assertThat(result.stopReason()).isNull();
            assertThat(result.usageSource()).isNull();
            assertThat(result.degraded()).isNull();
        });
        assertThat(legacy.get(1).toolUses()).containsExactly(tool);
        assertThat(legacy.get(2).reasoningContent()).isEqualTo("why");
    }

    @Test
    void aFourArgumentResultEqualsTheSevenArgumentOneWithNulls() {
        assertThat(new PlatformCompletionResult("a", "why", List.of(), USAGE))
            .isEqualTo(new PlatformCompletionResult("a", "why", List.of(), USAGE, null, null, null));
    }

    @Test
    void theLegacyNormalizationStillApplies() {
        PlatformCompletionResult result = new PlatformCompletionResult(null, null, null, null);

        assertThat(result.content()).isEmpty();
        assertThat(result.reasoningContent()).isEmpty();
        assertThat(result.toolUses()).isEmpty();
        assertThat(result.usage()).isEqualTo(PlatformUsage.zero());
        assertThat(result.degraded()).isNull();
    }

    @Test
    void theNewComponentsAreKept() {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "a", "", List.of(), USAGE,
            PlatformCompletionResult.StopReason.MAX_TOKENS,
            PlatformCompletionResult.UsageSource.CLAMPED,
            List.of("cache_control")
        );

        assertThat(result.stopReason()).isEqualTo(PlatformCompletionResult.StopReason.MAX_TOKENS);
        assertThat(result.usageSource()).isEqualTo(PlatformCompletionResult.UsageSource.CLAMPED);
        assertThat(result.degraded()).containsExactly("cache_control");
    }

    @Test
    void anEmptyDegradedListStaysDistinctFromNull() {
        PlatformCompletionResult none = new PlatformCompletionResult("a", "", List.of(), USAGE, null, null, List.of());

        assertThat(none.degraded()).isNotNull().isEmpty();
        assertThat(none).isNotEqualTo(new PlatformCompletionResult("a", "", List.of(), USAGE));
    }

    @Test
    void degradedIsCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("top_k"));
        PlatformCompletionResult result = new PlatformCompletionResult("a", "", List.of(), USAGE, null, null, source);
        source.add("metadata");

        assertThat(result.degraded()).containsExactly("top_k");
        assertThatThrownBy(() -> result.degraded().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}

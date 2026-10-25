package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TierRuntimeTest {

    @Test
    void disabledPlannerAnswersUntieredForEveryRequest() {
        TierRuntime runtime = TierRuntime.disabled();
        ObjectMapper mapper = new ObjectMapper();

        assertThat(runtime.planner().plan("claude-sonnet-4.5", mapper.createObjectNode().put("model", "x")))
            .isEqualTo(TierRuntime.Plan.UNTIERED);
        assertThat(runtime.planner().plan("gpt-5", mapper.createArrayNode())).isEqualTo(TierRuntime.Plan.UNTIERED);
        assertThat(runtime.planner().plan("deepseek-chat", null)).isEqualTo(TierRuntime.Plan.UNTIERED);
        assertThat(runtime.planner().plan(null, null)).isEqualTo(TierRuntime.Plan.UNTIERED);
    }

    @Test
    void disabledIsOneSharedValue() {
        assertThat(TierRuntime.disabled()).isSameAs(TierRuntime.disabled());
    }

    @Test
    void aRuntimeNeedsAPlanner() {
        assertThatThrownBy(() -> new TierRuntime(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("planner");
    }
}

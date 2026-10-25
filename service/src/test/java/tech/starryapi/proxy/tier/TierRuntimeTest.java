package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TierRuntimeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledPlannerAnswersUntieredForEveryRequest() {
        TierRuntime runtime = TierRuntime.disabled();

        assertThat(runtime.planner().plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC)).block())
            .isSameAs(TierPlan.UNTIERED);
        assertThat(runtime.planner().plan(request("gpt-5", TierRoute.OPENAI)).block()).isSameAs(TierPlan.UNTIERED);
        assertThat(runtime.planner().plan(request("deepseek-chat", TierRoute.OPENAI)).block()).isSameAs(TierPlan.UNTIERED);
    }

    @Test
    void theUntieredPlanIsNotTiered() {
        assertThat(TierPlan.UNTIERED.tiered()).isFalse();
        assertThat(TierPlan.UNTIERED.levels()).isEmpty();
        assertThat(TierPlan.UNTIERED.holdLevels()).isEmpty();
        assertThat(TierPlan.UNTIERED.owner()).isEmpty();
        assertThat(TierPlan.UNTIERED.reservation()).isNull();
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

    private TierPlanRequest request(String model, TierRoute route) {
        return new TierPlanRequest("request-1", model, model, "Provider", route, false, false, false, true, false, true,
            mapper.createObjectNode().put("model", model), null, false, null, null);
    }
}

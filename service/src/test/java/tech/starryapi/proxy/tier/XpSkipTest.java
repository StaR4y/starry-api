package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.starryapi.proxy.tier.XpSkip.Reason;

/** What a skip says of the features a relay was left out for: the labels are whole words, and only a feature skip has any. */
class XpSkipTest {

    @Test
    void aFeatureSkipIsForEveryLabelOfItsDetailAndForNoOtherWord() {
        XpSkip skip = new XpSkip("relay", "relay-1", Reason.FEATURE, "builtin_tool,previous_response_id,verbosity");

        assertThat(skip.leftOutFor("builtin_tool")).isTrue();
        assertThat(skip.leftOutFor("previous_response_id")).isTrue();
        assertThat(skip.leftOutFor("verbosity")).isTrue();
        assertThat(skip.leftOutFor("previous_response")).isFalse();
        assertThat(skip.leftOutFor("response_id")).isFalse();
        assertThat(skip.leftOutFor("")).isFalse();
        assertThat(skip.firstFeature()).isEqualTo("builtin_tool");
    }

    @Test
    void aSkipForAnotherReasonIsForNoFeatureWhateverItsDetailSays() {
        for (Reason reason : Reason.values()) {
            if (reason != Reason.FEATURE) {
                assertThat(new XpSkip("relay", "relay-1", reason, "previous_response_id").leftOutFor("previous_response_id"))
                    .as("%s", reason).isFalse();
            }
        }
        assertThat(new XpSkip("relay", "relay-1", Reason.FEATURE, null).leftOutFor("previous_response_id")).isFalse();
    }
}

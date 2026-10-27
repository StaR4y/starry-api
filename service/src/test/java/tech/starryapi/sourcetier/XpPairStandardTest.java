package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierDtos.Criterion;
import tech.starryapi.sourcetier.SourceTierDtos.XpFigures;

/** The standard a pair is held to before it is upgraded (design 7.3), judged on the counts and not on rounded percentages. */
class XpPairStandardTest {

    private static XpFigures figures(long attempts, long served, long interrupted, long rejected, long estimated) {
        return new XpFigures(attempts, served, attempts - served, interrupted, rejected, 0, 0, 0, 0, estimated, 0, 0, null);
    }

    private static Criterion criterion(List<Criterion> criteria, String name) {
        return criteria.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aPairThatKeepsEveryPromiseMeetsTheStandard() {
        List<Criterion> criteria = XpPairStandard.evaluate(figures(200, 196, 3, 1, 9), 0, true);

        assertThat(criteria).extracting(Criterion::name).containsExactly("requests", "lintFailures", "conversionRejected", "estimated", "unbilled");
        assertThat(criteria).extracting(Criterion::rule).containsExactly(">= 200", "= 0", "< 1%", "< 5%", "< 2%");
        assertThat(criterion(criteria, "conversionRejected").value()).isEqualTo(0.5);
        assertThat(criterion(criteria, "estimated").value()).isEqualTo(4.59);
        assertThat(criterion(criteria, "unbilled").value()).isEqualTo(1.5);
        assertThat(XpPairStandard.met(criteria)).isTrue();
    }

    @Test
    void theLimitsThemselvesAreNotMet() {
        // 200 attempts: 2 refused is exactly 1%, 4 interrupted exactly 2%; 190 answered, 10 estimated is not below 5%... 9.5 would be.
        List<Criterion> criteria = XpPairStandard.evaluate(figures(200, 200, 4, 2, 10), 1, true);

        assertThat(criterion(criteria, "requests").met()).as("200 is enough").isTrue();
        assertThat(criterion(criteria, "lintFailures").met()).isFalse();
        assertThat(criterion(criteria, "conversionRejected").met()).isFalse();
        assertThat(criterion(criteria, "estimated").met()).isFalse();
        assertThat(criterion(criteria, "unbilled").met()).isFalse();
        assertThat(XpPairStandard.met(criteria)).isFalse();
        assertThat(XpPairStandard.evaluate(figures(199, 199, 0, 0, 0), 0, true).getFirst().met()).isFalse();
    }

    @Test
    void aShareIsJudgedOnTheCountsNotOnTheRoundedPercentage() {
        // 1.004% rounds to 1.0 but is above 1%.
        List<Criterion> criteria = XpPairStandard.evaluate(figures(100_000, 100_000, 0, 1_004, 0), 0, true);

        assertThat(criterion(criteria, "conversionRejected").value()).isEqualTo(1.0);
        assertThat(criterion(criteria, "conversionRejected").met()).isFalse();
    }

    @Test
    void theLintFailuresOfAWindowThatWasNotCountedThroughCannotBeJudgedAndAreNotMet() {
        List<Criterion> criteria = XpPairStandard.evaluate(figures(200, 196, 3, 1, 9), 0, false);

        assertThat(criterion(criteria, "lintFailures").value()).isNull();
        assertThat(criterion(criteria, "lintFailures").rule()).isEqualTo("= 0");
        assertThat(criterion(criteria, "lintFailures").met()).isFalse();
        assertThat(criteria).filteredOn(criterion -> !criterion.met()).extracting(Criterion::name).containsExactly("lintFailures");
        assertThat(XpPairStandard.met(criteria)).isFalse();
    }

    @Test
    void aConditionWithNothingToDivideCannotBeJudgedAndIsNotMet() {
        List<Criterion> none = XpPairStandard.evaluate(figures(0, 0, 0, 0, 0), 0, true);
        assertThat(criterion(none, "conversionRejected").value()).isNull();
        assertThat(criterion(none, "conversionRejected").met()).isFalse();
        assertThat(criterion(none, "estimated").met()).isFalse();
        assertThat(criterion(none, "unbilled").met()).isFalse();
        assertThat(criterion(none, "lintFailures").met()).as("nothing to divide, and nothing failed").isTrue();
        assertThat(XpPairStandard.met(none)).isFalse();

        // Attempts, none answered: the estimated share has no answered attempt to be about.
        assertThat(criterion(XpPairStandard.evaluate(figures(300, 0, 0, 0, 0), 0, true), "estimated").met()).isFalse();
    }
}

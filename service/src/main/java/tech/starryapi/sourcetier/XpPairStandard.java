package tech.starryapi.sourcetier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import tech.starryapi.sourcetier.SourceTierDtos.Criterion;
import tech.starryapi.sourcetier.SourceTierDtos.XpFigures;

/**
 * What a cross-protocol pair has to show before it is upgraded from EXPERIMENTAL to VERIFIED and a gate may be ON for
 * everyone (design 7.3). The same standard for every pair; the evidence is the status page's export, and the owner may
 * waive the number of requests in writing in the task that does the upgrade, which is why that condition is one line of
 * the evidence and not hidden in the verdict.
 *
 * <ul>
 *   <li>{@code requests}: at least 200 attempts were sent to relays for the pair, under the keys the gate listed.
 *   <li>{@code lintFailures}: no relay was left out of a plan for a body that failed the strict lint (a count of relays,
 *       kept by the node that planned them; the vendor's, since a plan skips a relay before it is known which pair it was for).
 *   <li>{@code conversionRejected}: fewer than 1% of the attempts were refused by the relay as converted.
 *   <li>{@code estimated}: fewer than 5% of the answered attempts were billed on usage the gateway had to estimate.
 *   <li>{@code unbilled}: fewer than 2% of the attempts were broken off after the relay may have generated, which nobody
 *       is charged for.
 * </ul>
 *
 * A condition that has nothing to divide (no attempts, no answered attempts) cannot be judged and is not met.
 */
public final class XpPairStandard {

    public static final long MIN_ATTEMPTS = 200;
    public static final long MAX_LINT_FAILURES = 0;
    public static final int MAX_CONVERSION_REJECTED_PERCENT = 1;
    public static final int MAX_ESTIMATED_PERCENT = 5;
    public static final int MAX_UNBILLED_PERCENT = 2;

    private XpPairStandard() {
    }

    /** The conditions in the order above, for the figures of a pair and the lint failures of its vendor. */
    public static List<Criterion> evaluate(XpFigures figures, long lintFailures) {
        return List.of(
            new Criterion("requests", ">= " + MIN_ATTEMPTS, (double) figures.attempts(), figures.attempts() >= MIN_ATTEMPTS),
            new Criterion("lintFailures", "= " + MAX_LINT_FAILURES, (double) lintFailures, lintFailures <= MAX_LINT_FAILURES),
            below("conversionRejected", MAX_CONVERSION_REJECTED_PERCENT, figures.conversionRejected(), figures.attempts()),
            below("estimated", MAX_ESTIMATED_PERCENT, figures.estimated(), figures.served()),
            below("unbilled", MAX_UNBILLED_PERCENT, figures.interrupted(), figures.attempts())
        );
    }

    /** Whether every condition holds. */
    public static boolean met(List<Criterion> criteria) {
        return criteria.stream().allMatch(Criterion::met);
    }

    private static Criterion below(String name, int percent, long count, long of) {
        String rule = "< " + percent + "%";
        if (of <= 0) {
            return new Criterion(name, rule, null, false);
        }
        double value = BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(of), 2, RoundingMode.HALF_UP).doubleValue();
        // Judged on the counts, not on the rounded percentage: 1.004% is above 1%.
        return new Criterion(name, rule, value, count * 100 < of * percent);
    }
}

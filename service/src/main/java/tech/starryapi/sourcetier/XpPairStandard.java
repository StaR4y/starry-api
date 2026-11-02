package tech.starryapi.sourcetier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import tech.starryapi.sourcetier.SourceTierDtos.Criterion;
import tech.starryapi.sourcetier.SourceTierDtos.XpFigures;

/**
 * What a cross-protocol pair has to show before it is upgraded from EXPERIMENTAL to VERIFIED and a gate may be ON for
 * everyone (design 7.3). The same standard for every pair; the evidence is the status page's export, and each condition
 * is a line of it and not hidden in a verdict, so that the number of requests can be read apart from the rest.
 *
 * <ul>
 *   <li>{@code requests}: at least 200 attempts were sent to relays for the pair, under the keys the gate listed.
 *   <li>{@code lintFailures}: no relay was left out of a plan for a body that failed the strict lint, or for a conversion
 *       that failed (a count of relays, kept by the node that planned them, for the entry of the pair: a plan knows which pair
 *       it is for before it skips a relay, and the converter of the Responses entry is not that of the Chat entry).
 *       The node counts from the moment it started, so the condition can be judged only for a window that begins after that:
 *       for a longer one the node cannot say that nothing failed in the part it did not see, and the condition is not met.
 *   <li>{@code conversionRejected}: fewer than 1% of the attempts were refused by the relay as converted.
 *   <li>{@code estimated}: fewer than 5% of the answered attempts were billed on usage the gateway had to estimate.
 *   <li>{@code unbilled}: fewer than 2% of the attempts were broken off after the relay may have generated, which nobody
 *       is charged for. The request log has the attempts that were broken off while the gateway was still waiting for the
 *       relay; one whose client went away first leaves no row, so the share is a lower bound.
 * </ul>
 *
 * A condition that has nothing to divide (no attempts, no answered attempts), or that the answering node cannot vouch for
 * over the whole window, cannot be judged and is not met.
 */
public final class XpPairStandard {

    public static final long MIN_ATTEMPTS = 200;
    public static final long MAX_LINT_FAILURES = 0;
    public static final int MAX_CONVERSION_REJECTED_PERCENT = 1;
    public static final int MAX_ESTIMATED_PERCENT = 5;
    public static final int MAX_UNBILLED_PERCENT = 2;

    private XpPairStandard() {
    }

    /**
     * The conditions in the order above, for the figures of a pair and the lint failures of its vendor on the entry of the pair.
     *
     * @param lintCounted whether the lint failures were counted over the whole window of the figures; if not, the node has
     *     not been up for all of it, and that condition cannot be judged (no value, not met)
     */
    public static List<Criterion> evaluate(XpFigures figures, long lintFailures, boolean lintCounted) {
        return List.of(
            new Criterion("requests", ">= " + MIN_ATTEMPTS, (double) figures.attempts(), figures.attempts() >= MIN_ATTEMPTS),
            lintCounted
                ? new Criterion("lintFailures", "= " + MAX_LINT_FAILURES, (double) lintFailures, lintFailures <= MAX_LINT_FAILURES)
                : new Criterion("lintFailures", "= " + MAX_LINT_FAILURES, null, false),
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

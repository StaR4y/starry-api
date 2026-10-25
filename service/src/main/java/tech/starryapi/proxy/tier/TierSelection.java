package tech.starryapi.proxy.tier;

import java.util.Objects;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.proxy.tier.TierPlan.Level;

/**
 * The account a tiered request selected and where in the plan it was found: the tier it is billed at, the cell it was
 * taken from and the way it is dispatched to. All of it is written when the account is selected and checked against
 * the plan then, before anything is sent, so that settlement never meets an attempt without a tier.
 *
 * @param mode how the plan chose its tiers, which the request log keeps with every attempt
 * @param climbed whether the account was found above the tier the request started from, as a last resort once nothing
 *     from its start down had an account (design 4.3), which is the one case where a session's tier is moved back up
 */
public record TierSelection(
    ProxyAccountEntity account,
    Level level,
    PoolCell cell,
    TierDispatch dispatch,
    TierPlan.Mode mode,
    boolean climbed
) {

    public TierSelection {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(mode, "mode");
    }
}

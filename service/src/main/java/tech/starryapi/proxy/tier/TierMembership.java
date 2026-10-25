package tech.starryapi.proxy.tier;

import java.util.Optional;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;

/**
 * Which tier a native account sits in. This is {@link SourceTierSnapshot#tierOf} with the one exception the plan
 * needs: an account registered as a relay for another protocol only ever serves through that relay's own cell, so it
 * is no member of any tier of the native cells. That holds for the relay registered as exclusive in every vendor, not
 * only the one it is registered in: the OpenAI-compatible cell of a vendor that has no relay row for it would otherwise
 * take it into its lowest tier (design 7.5).
 */
public final class TierMembership {

    private TierMembership() {
    }

    /** The tier of the account as a native member; empty for a relay, an exclusive relay of any vendor, an unknown account or a vendor without tiers. */
    public static Optional<Tier> nativeTierOf(SourceTierSnapshot snapshot, String vendor, ProxyAccountEntity account) {
        if (account == null) {
            return Optional.empty();
        }
        String accountId = account.getAccountId();
        if (accountId != null && (snapshot.isExclusiveRelay(accountId)
            || snapshot.member(vendor, accountId).filter(SourceTierSnapshot.Member::crossProtocol).isPresent())) {
            return Optional.empty();
        }
        return snapshot.tierOf(vendor, account);
    }

    public static boolean holds(SourceTierSnapshot snapshot, String vendor, Tier tier, ProxyAccountEntity account) {
        return nativeTierOf(snapshot, vendor, account).filter(placed -> placed.tierId().equals(tier.tierId())).isPresent();
    }
}

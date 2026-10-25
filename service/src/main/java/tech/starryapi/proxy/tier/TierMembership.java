package tech.starryapi.proxy.tier;

import java.util.Optional;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;

/**
 * Which tier a native account sits in. This is {@link SourceTierSnapshot#tierOf} with the one exception the plan
 * needs: an account registered as a relay for another protocol only ever serves through that relay's own cell, so it
 * is no member of any tier of the native cells.
 */
public final class TierMembership {

    private TierMembership() {
    }

    /** The tier of the account as a native member; empty for a relay, an unknown account or a vendor without tiers. */
    public static Optional<Tier> nativeTierOf(SourceTierSnapshot snapshot, String vendor, ProxyAccountEntity account) {
        if (account == null) {
            return Optional.empty();
        }
        String accountId = account.getAccountId();
        if (accountId != null && snapshot.member(vendor, accountId).filter(SourceTierSnapshot.Member::crossProtocol).isPresent()) {
            return Optional.empty();
        }
        return snapshot.tierOf(vendor, account);
    }

    public static boolean holds(SourceTierSnapshot snapshot, String vendor, Tier tier, ProxyAccountEntity account) {
        return nativeTierOf(snapshot, vendor, account).filter(placed -> placed.tierId().equals(tier.tierId())).isPresent();
    }
}

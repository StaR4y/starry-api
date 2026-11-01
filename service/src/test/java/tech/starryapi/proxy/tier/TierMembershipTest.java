package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

class TierMembershipTest {

    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key", null);
    private final ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private final ProxyAccountEntity glm = account("glm", "GLM", "api-key", "https://open.bigmodel.cn/api/paas/v4");

    @Test
    void aNativeAccountSitsInTheTierTheSnapshotPutsItIn() {
        SourceTierSnapshot snapshot = snapshot(List.of());

        assertThat(tierId(TierMembership.nativeTierOf(snapshot, "anthropic", officialAnthropic))).isEqualTo("official");
        assertThat(TierMembership.holds(snapshot, "anthropic", snapshot.tier("anthropic", "official").orElseThrow(), officialAnthropic)).isTrue();
        assertThat(TierMembership.holds(snapshot, "anthropic", snapshot.tier("anthropic", "kiro").orElseThrow(), officialAnthropic)).isFalse();
    }

    @Test
    void aRelayIsNoNativeMemberOfTheVendorItIsRegisteredIn() {
        SourceTierSnapshot exclusive = snapshot(List.of(relayMember("anthropic", "relay", true)));
        SourceTierSnapshot shared = snapshot(List.of(relayMember("anthropic", "relay", false)));

        assertThat(TierMembership.nativeTierOf(exclusive, "anthropic", relay)).isEmpty();
        assertThat(TierMembership.nativeTierOf(shared, "anthropic", relay)).isEmpty();
    }

    @Test
    void anExclusiveRelayIsNoNativeMemberOfAnyOtherVendorEither() {
        SourceTierSnapshot snapshot = snapshot(List.of(relayMember("anthropic", "relay", true)));

        // GLM's vendor has no row for the relay, so it would take it into its lowest tier; it must not.
        assertThat(TierMembership.nativeTierOf(snapshot, "zhipu", relay)).isEmpty();
        assertThat(TierMembership.nativeTierOf(snapshot, "zhipu", glm)).isPresent();
    }

    @Test
    void aRelayThatIsNotExclusiveStillServesTheOtherVendorsAsAnyCompatibleKeyDoes() {
        SourceTierSnapshot snapshot = snapshot(List.of(relayMember("anthropic", "relay", false)));

        assertThat(tierId(TierMembership.nativeTierOf(snapshot, "zhipu", relay))).isEqualTo("direct");
    }

    @Test
    void nothingIsExcludedWhenNoRelayIsRegistered() {
        SourceTierSnapshot snapshot = snapshot(List.of());

        assertThat(snapshot.exclusiveXpAccountIds()).isEmpty();
        assertThat(tierId(TierMembership.nativeTierOf(snapshot, "zhipu", relay))).isEqualTo("direct");
        assertThat(tierId(TierMembership.nativeTierOf(snapshot, "zhipu", glm))).isEqualTo("direct");
    }

    @Test
    void anUnknownAccountOrAVendorWithoutTiersHasNoTier() {
        SourceTierSnapshot snapshot = snapshot(List.of(relayMember("anthropic", "relay", true)));

        assertThat(TierMembership.nativeTierOf(snapshot, "anthropic", null)).isEmpty();
        assertThat(TierMembership.nativeTierOf(snapshot, "deepseek", glm)).isEmpty();
        assertThat(TierMembership.nativeTierOf(SourceTierSnapshot.EMPTY, "zhipu", glm)).isEmpty();
        assertThat(TierMembership.nativeTierOf(snapshot, "zhipu", account(null, "GLM", "api-key", null))).isPresent();
    }

    // ---- fixtures ----

    /** The anthropic vendor, which the relay is registered for, and the vendor of a compatible-key model such as GLM. */
    private static SourceTierSnapshot snapshot(List<Member> members) {
        return SourceTierSnapshot.of(1L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 1L),
                new VendorConfig("zhipu", true, false, false, true, 16, true, 1L)),
            List.of(
                new Tier("anthropic", "official", 1, "official", BigDecimal.ONE,
                    EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null),
                new Tier("anthropic", "kiro", 2, "kiro", new BigDecimal("0.6"),
                    EnumSet.of(AccountAllocationCategory.KIRO), true, true, null),
                new Tier("zhipu", "direct", 1, "direct", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true,
                    true, null)),
            members, List.of(), List.of());
    }

    private static Member relayMember(String vendor, String accountId, boolean exclusive) {
        return new Member(vendor, accountId, "official", "OPENAI_CHAT", exclusive, false, false, false, false, true, "NONE", 540,
            true, true);
    }

    private static String tierId(Optional<Tier> tier) {
        return tier.map(Tier::tierId).orElse(null);
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setEnabled(true);
        return account;
    }
}

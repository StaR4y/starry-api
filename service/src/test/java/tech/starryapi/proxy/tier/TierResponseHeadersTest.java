package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;

class TierResponseHeadersTest {

    @Test
    void aServedResponseNamesTheTierItsLevelItsNameAndHowItWasChosen() {
        Map<String, String> headers = TierResponseHeaders.served(tier("kiro", 2, "kiro"), TierPlan.Mode.AUTO);

        assertThat(headers).containsExactly(
            Map.entry("X-Starry-Tier", "kiro"),
            Map.entry("X-Starry-Tier-Level", "2"),
            Map.entry("X-Starry-Tier-Name", "kiro"),
            Map.entry("X-Starry-Tier-Mode", "auto"),
            Map.entry("X-Starry-Tier-Status", "served"));
    }

    @Test
    void theHeaderSetIsNotChangeable() {
        Map<String, String> headers = TierResponseHeaders.served(tier("kiro", 2, "kiro"), TierPlan.Mode.AUTO);

        assertThatThrownBy(() -> headers.put("X-Other", "x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNameTravelsAsUtf8PercentEncoding() {
        assertThat(TierResponseHeaders.percentEncode("kiro反向代理")).isEqualTo("kiro%E5%8F%8D%E5%90%91%E4%BB%A3%E7%90%86");
        assertThat(TierResponseHeaders.percentEncode("🙂")).isEqualTo("%F0%9F%99%82");
    }

    @Test
    void onlyUnreservedCharactersAreKept() {
        assertThat(TierResponseHeaders.percentEncode("Az09-._~")).isEqualTo("Az09-._~");
        assertThat(TierResponseHeaders.percentEncode("a b")).isEqualTo("a%20b");
        assertThat(TierResponseHeaders.percentEncode("a+b/c?d=e&f")).isEqualTo("a%2Bb%2Fc%3Fd%3De%26f");
    }

    @Test
    void aNameCannotBreakTheHeaderLine() {
        String encoded = TierResponseHeaders.percentEncode("line\r\nX-Injected: yes\"\u0000");

        assertThat(encoded).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ").doesNotContain(":")
            .doesNotContain("\"").isEqualTo("line%0D%0AX-Injected%3A%20yes%22%00");
    }

    private static Tier tier(String tierId, int level, String name) {
        return new Tier("anthropic", tierId, level, name, BigDecimal.ONE, Set.of(), true, true, null);
    }
}

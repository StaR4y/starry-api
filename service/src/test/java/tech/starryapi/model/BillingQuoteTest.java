package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformUsage;

class BillingQuoteTest {

    // claude-sonnet-4.5 per token: $3/M in, $15/M out, $0.30/M cache read, $3.75/M cache write.
    private final BillingQuote sonnet = new BillingQuote(new BigDecimal("0.000003"), new BigDecimal("0.000015"),
        new BigDecimal("0.0000003"), new BigDecimal("0.00000375"), BigDecimal.ONE);

    @Test
    void aFactorOfOneReturnsTheSameQuoteWhateverItsScale() {
        assertThat(sonnet.scaled(BigDecimal.ONE)).isSameAs(sonnet);
        assertThat(sonnet.scaled(new BigDecimal("1.000000"))).isSameAs(sonnet);
        assertThat(sonnet.scaled(BigDecimal.valueOf(1.0))).isSameAs(sonnet);
    }

    @Test
    void scalesAllFiveRatesExactly() {
        BillingQuote scaled = sonnet.scaled(new BigDecimal("0.6"));

        assertThat(scaled.input()).isEqualByComparingTo("0.0000018");
        assertThat(scaled.output()).isEqualByComparingTo("0.000009");
        assertThat(scaled.cacheRead()).isEqualByComparingTo("0.00000018");
        assertThat(scaled.cacheWrite()).isEqualByComparingTo("0.00000225");
        assertThat(scaled.fallback()).isEqualByComparingTo("0.6");
        // Nothing is rounded on the way: the scale only grows by the factor's.
        assertThat(sonnet.scaled(new BigDecimal("0.333333")).input()).isEqualTo(new BigDecimal("0.000000999999"));
        assertThat(sonnet.input()).isEqualTo(new BigDecimal("0.000003"));
    }

    @Test
    void aSecondPoolServesTheDesignExampleAtItsFactor() {
        BillingQuote kiro = sonnet.scaled(new BigDecimal("0.6"));
        PlatformUsage usage = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);

        // 8,000 in + 1,000 out costs 0.039 at the standard price, 0.039 x 0.6 = 0.0234 in the 0.6 pool.
        assertThat(sonnet.cost(usage)).isEqualTo(new BigDecimal("0.039000"));
        assertThat(kiro.cost(usage)).isEqualTo(new BigDecimal("0.023400"));
        // The reservation prices every input token at the dearest input rate, here cache write:
        // 8,000 x 0.00000225 + 1,000 x 0.000009.
        assertThat(kiro.estimate(8_000, 1_000)).isEqualTo(new BigDecimal("0.027000"));
    }

    @Test
    void cachedTokensGetTheirOwnScaledRates() {
        BillingQuote relay = sonnet.scaled(new BigDecimal("1.5"));
        // 8,000 prompt tokens of which 6,000 are cache reads, 1,000 output: 0.0228 at the standard price.
        PlatformUsage usage = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 6_000, 0, 0);

        assertThat(sonnet.cost(usage)).isEqualTo(new BigDecimal("0.022800"));
        assertThat(relay.cost(usage)).isEqualTo(new BigDecimal("0.034200"));
    }

    @Test
    void roundsOnceAtTheEndLikeAnUnscaledQuote() {
        BillingQuote oneToken = new BillingQuote(new BigDecimal("0.0000011"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        PlatformUsage usage = new PlatformUsage(1, 0, BigDecimal.ZERO, 0, 0, 0);
        BigDecimal factor = new BigDecimal("0.5");

        // Rounding the unscaled cost first (0.000001) and halving it would give 0.0000005, not a 6-digit amount.
        assertThat(oneToken.cost(usage)).isEqualTo(new BigDecimal("0.000001"));
        assertThat(oneToken.scaled(factor).cost(usage)).isEqualTo(new BigDecimal("0.000001"));
        // 0.0000011 x 0.4 = 0.00000044: HALF_UP gives 0, CEILING for the reservation gives 0.000001.
        BillingQuote cheaper = oneToken.scaled(new BigDecimal("0.4"));
        assertThat(cheaper.cost(usage)).isEqualTo(new BigDecimal("0.000000"));
        assertThat(cheaper.estimate(1, 0)).isEqualTo(new BigDecimal("0.000001"));
    }

    @Test
    void estimateUsesTheDearestScaledInputRate() {
        BillingQuote cacheWriteDearest = new BillingQuote(new BigDecimal("0.000003"), new BigDecimal("0.000015"),
            new BigDecimal("0.0000003"), new BigDecimal("0.00000375"), BigDecimal.ONE).scaled(new BigDecimal("2"));

        // max(in, cacheRead, cacheWrite) x 2 = 0.0000075 per input token.
        assertThat(cacheWriteDearest.estimate(1_000_000, 0)).isEqualTo(new BigDecimal("7.500000"));
    }

    @Test
    void theFallbackRateIsScaledWithTheRest() {
        PlatformUsage creditsOnly = new PlatformUsage(0, 0, new BigDecimal("2.5"), 0, 0, 0);

        assertThat(sonnet.cost(creditsOnly)).isEqualTo(new BigDecimal("2.500000"));
        assertThat(sonnet.scaled(new BigDecimal("0.6")).cost(creditsOnly)).isEqualTo(new BigDecimal("1.500000"));
    }

    @Test
    void acceptsTheWholeRangeTheTableAllows() {
        assertThat(sonnet.scaled(new BigDecimal("0.000001")).input()).isEqualByComparingTo("0.000000000003");
        assertThat(sonnet.scaled(new BigDecimal("1000")).input()).isEqualByComparingTo("0.003");
        assertThat(sonnet.scaled(new BigDecimal("999.999999")).output()).isEqualByComparingTo("0.014999999985");
    }

    @Test
    void rejectsFactorsOutsideZeroToOneThousand() {
        for (String factor : new String[] {"0", "0.000000", "-1", "-0.5", "1000.000001", "1001", "100000"}) {
            assertThatThrownBy(() -> sonnet.scaled(new BigDecimal(factor)))
                .as("factor %s", factor)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1000");
        }
        assertThatNullPointerException().isThrownBy(() -> sonnet.scaled(null)).withMessage("factor");
    }
}

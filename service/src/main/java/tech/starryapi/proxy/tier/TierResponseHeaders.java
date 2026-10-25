package tech.starryapi.proxy.tier;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;

/**
 * The headers that tell a client which tier served a tiered request (design 6.4). They are worked out when the plan is
 * made, one set per tier, so that after the settlement the response only picks up strings that already exist and
 * cannot fail: a request that was charged must not end without a response.
 */
public final class TierResponseHeaders {

    public static final String TIER = "X-Starry-Tier";
    public static final String LEVEL = "X-Starry-Tier-Level";
    public static final String NAME = "X-Starry-Tier-Name";
    public static final String MODE = "X-Starry-Tier-Mode";
    public static final String STATUS = "X-Starry-Tier-Status";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private TierResponseHeaders() {
    }

    /** The headers of a response served by {@code tier}. The name is administrator text, so it travels percent-encoded. */
    static Map<String, String> served(Tier tier, TierPlan.Mode mode) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TIER, tier.tierId());
        headers.put(LEVEL, Integer.toString(tier.level()));
        headers.put(NAME, percentEncode(tier.name()));
        headers.put(MODE, mode.headerValue());
        headers.put(STATUS, "served");
        return Collections.unmodifiableMap(headers);
    }

    /** UTF-8 percent-encoding that keeps only RFC 3986 unreserved characters, so any name is a legal header value. */
    static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return encoded.toString();
    }
}

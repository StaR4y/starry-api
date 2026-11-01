package tech.starryapi.proxy.tier;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a client says about the tier it wants (design 6.1 and 6.2): the {@code @reference} at the end of the model
 * id, the {@code X-Starry-Tier} request header and the key's preference. All three use one
 * grammar, {@code auto | <tierId> | L<N> | <N>}, optionally followed by {@code +}: without it the request is served by
 * that tier alone, with it by that tier or any below.
 *
 * <p>Parsing never fails. A reference that is not in the grammar is a {@link Kind#INVALID} selector, and what that
 * means is for the planner to say: the suffix is a refusal, the header and the preference are ignored.
 */
public final class TierSelectorParser {

    /** The request header that names a tier. It is also the first of the response headers that name the serving tier. */
    public static final String REQUEST_HEADER = TierResponseHeaders.TIER;

    /** The longest reference a key preference can hold (the column's check), which no tier id and level reach. */
    private static final int MAX_REFERENCE_LENGTH = 40;
    /** The deepest level a tier can have (the column of {@code source_tiers.level}). */
    private static final int MAX_LEVEL = 9;
    /** What no tier id can be, because a selector means something else by it. */
    private static final Pattern RESERVED_WORD = Pattern.compile("auto|l?[0-9]+");
    private static final Pattern LEVEL = Pattern.compile("l?([0-9]{1,3})");
    private static final Pattern TIER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private TierSelectorParser() {
    }

    /**
     * Whether {@code text} has the form of a tier identifier. The tier validator takes its rule from here, so that an id
     * it lets an administrator give a tier is one that a selector reads back as that tier.
     */
    public static boolean isTierId(String text) {
        return text != null && TIER_ID.matcher(text).matches();
    }

    /**
     * Whether a selector gives {@code text} a meaning of its own ({@code auto}, {@code L2}, {@code 2}), so that no tier
     * can be called by it. The tier validator takes its rule from here as well.
     */
    public static boolean isReservedWord(String text) {
        return text != null && RESERVED_WORD.matcher(text).matches();
    }

    /** Where the client said it. The channels are tried in this order, and the first one present decides. */
    public enum Source {
        SUFFIX, HEADER, PREFERENCE
    }

    public enum Kind {
        /** The tiers in their order, which is also what a client without a selector gets. */
        AUTO,
        /** A tier by its identifier. */
        TIER,
        /** A tier by its level number. */
        LEVEL,
        /** A reference that is not in the grammar. */
        INVALID
    }

    /**
     * What a client asked for.
     *
     * @param tierId the tier of a {@link Kind#TIER} selector
     * @param level the level of a {@link Kind#LEVEL} selector
     * @param orBelow whether the tier named or any below it may serve ({@code +})
     * @param text the reference as the client wrote it, for messages
     */
    public record Selector(Source source, Kind kind, String tierId, int level, boolean orBelow, String text) {

        public boolean auto() {
            return kind == Kind.AUTO;
        }
    }

    /**
     * A model id and the selector that was on its end; the selector is null when the id was left alone. The destination
     * is where the model left of the {@code @} goes, which the decision was made with: it is there exactly when the
     * selector is, and the request goes on with the mapping that was asked for it.
     */
    public record Stripped(String model, Selector selector, TierRuntime.Destination destination) {

        public Stripped {
            if (selector != null) {
                Objects.requireNonNull(destination, "destination");
            }
        }

        public static Stripped plain(String model) {
            return new Stripped(model, null, null);
        }
    }

    /** {@code model@reference} cut at the last {@code @}. */
    public record Suffix(String model, String reference) {
    }

    /** The id before and the reference after the last {@code @}; empty for an id that has none. */
    public static Optional<Suffix> splitSuffix(String model) {
        int at = model == null ? -1 : model.lastIndexOf('@');
        return at < 0 ? Optional.empty() : Optional.of(new Suffix(model.substring(0, at), model.substring(at + 1)));
    }

    /** The selector a request header carries; null for no header or a blank one. */
    public static Selector fromHeader(String value) {
        return value == null || value.isBlank() ? null : parse(Source.HEADER, value);
    }

    /**
     * The selector a key's preference carries; null for a key that leaves the choice to the gateway ({@code auto}, or no
     * preference at all), which is what a key without a preference has always done.
     */
    public static Selector fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Selector selector = parse(Source.PREFERENCE, value);
        return selector.auto() ? null : selector;
    }

    /**
     * A key's preference as it is stored: trimmed and in lower case, {@code auto} for none, and a level in its plain
     * spelling ({@code L02} is stored as {@code l2}, {@code 002+} as {@code 2+}), the only one the console and the
     * column's check recognise. Empty for a text that is not a selector a key can hold: outside the grammar, a level
     * that no tier has (0, or above 9), or a word the grammar keeps for itself, such as {@code auto+}. The column's own
     * check admits a little more than this does, which is harmless to requests (the preference then names no tier) but is
     * no reason to store it.
     */
    public static Optional<String> storedPreference(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("auto")) {
            return Optional.of("auto");
        }
        Selector selector = parse(Source.PREFERENCE, text);
        return switch (selector.kind()) {
            case LEVEL -> selector.level() >= 1 && selector.level() <= MAX_LEVEL
                ? Optional.of((text.startsWith("l") ? "l" : "") + selector.level() + (selector.orBelow() ? "+" : ""))
                : Optional.empty();
            case TIER -> isReservedWord(selector.tierId()) ? Optional.empty() : Optional.of(text);
            case AUTO, INVALID -> Optional.empty();
        };
    }

    public static Selector parse(Source source, String reference) {
        String text = reference == null ? "" : reference.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.length() > MAX_REFERENCE_LENGTH) {
            return invalid(source, text);
        }
        if (lower.equals("auto")) {
            return new Selector(source, Kind.AUTO, null, 0, false, text);
        }
        boolean orBelow = lower.endsWith("+");
        String body = orBelow ? lower.substring(0, lower.length() - 1) : lower;
        Matcher level = LEVEL.matcher(body);
        if (level.matches()) {
            return new Selector(source, Kind.LEVEL, null, Integer.parseInt(level.group(1)), orBelow, text);
        }
        if (TIER_ID.matcher(body).matches()) {
            return new Selector(source, Kind.TIER, body, 0, orBelow, text);
        }
        return invalid(source, text);
    }

    /**
     * The selector of a caller that may not choose a tier (the anonymous one), as a hint that cannot be followed: the
     * request is served in the tiers' order and the response says that the selector was not used (design 6.3), as it
     * does for the header of a key that names a pool nobody may choose. The automatic choice stays what it is.
     */
    public static Selector unchoosable(Selector selector) {
        return selector == null || selector.auto() ? selector : invalid(Source.HEADER, selector.text());
    }

    /** The selector that applies: the first channel that has one. A selector that is later found unusable does not pass the turn on. */
    public static Selector choose(Selector suffix, Selector header, Selector preference) {
        return suffix != null ? suffix : header != null ? header : preference;
    }

    private static Selector invalid(Source source, String text) {
        return new Selector(source, Kind.INVALID, null, 0, false, text);
    }
}

package tech.starryapi.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.starryapi.model.OfficialModelPricingService.OfficialModelPrice;

final class OfficialPriceResolver {

    static final String SOURCE_OFFICIAL = "official";
    static final String SOURCE_NORMALIZED = "normalized";
    static final String SOURCE_FAMILY = "family";
    static final String SOURCE_PROVIDER_MEDIAN = "provider_median";
    static final String SOURCE_GLOBAL_MEDIAN = "global_median";

    private static final List<String> PREFIXES = List.of(
        "starry/",
        "openai-compatible/",
        "openai/",
        "anthropic/",
        "google/",
        "gemini/",
        "deepseek/"
    );
    private static final Pattern DATE_SUFFIX = Pattern.compile("-20\\d{6}$");
    private static final Pattern VERSION_NUMBERS = Pattern.compile("(\\d+)");

    private final Map<String, OfficialModelPrice> catalog;

    OfficialPriceResolver(Map<String, OfficialModelPrice> catalog) {
        this.catalog = catalog == null ? Map.of() : catalog;
    }

    Optional<OfficialModelPrice> findExact(String model) {
        return resolve(model, false).map(OfficialPriceMatch::price);
    }

    Optional<OfficialPriceMatch> resolve(String model, boolean approximate) {
        String normalized = normalize(model);
        if (normalized.isBlank() || catalog.isEmpty()) {
            return Optional.empty();
        }
        OfficialPriceMatch exact = lookupExact(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        if (!approximate) {
            return Optional.empty();
        }
        OfficialPriceMatch family = findFamily(normalized);
        if (family != null) {
            return Optional.of(family);
        }
        OfficialPriceMatch provider = medianForProvider(providerOf(normalized));
        if (provider != null) {
            return Optional.of(provider);
        }
        return Optional.ofNullable(globalMedian());
    }

    private OfficialPriceMatch lookupExact(String normalized) {
        OfficialModelPrice direct = catalog.get(normalized);
        if (direct != null) {
            return new OfficialPriceMatch(direct, SOURCE_OFFICIAL, normalized);
        }
        for (String candidate : variants(normalized)) {
            OfficialModelPrice price = catalog.get(candidate);
            if (price != null) {
                return new OfficialPriceMatch(price, SOURCE_NORMALIZED, candidate);
            }
        }
        return null;
    }

    private List<String> variants(String normalized) {
        List<String> variants = new ArrayList<>();
        addUnique(variants, normalized);
        addUnique(variants, stripPrefix(normalized));
        for (int index = 0; index < variants.size(); index++) {
            String current = variants.get(index);
            addUnique(variants, current.replace('.', '-'));
            addUnique(variants, current.replace(':', '-'));
            addUnique(variants, DATE_SUFFIX.matcher(current).replaceFirst(""));
            addUnique(variants, current.replace("-latest", "").replace("-chat", ""));
            if (current.endsWith("-latest")) {
                addUnique(variants, current.substring(0, current.length() - "-latest".length()));
            }
            if (current.startsWith("chatgpt-") && current.length() > "chatgpt-".length()) {
                addUnique(variants, "gpt-" + current.substring("chatgpt-".length()));
            }
        }
        for (String prefix : PREFIXES) {
            if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                addUnique(variants, normalized.substring(prefix.length()));
            }
        }
        return variants;
    }

    private OfficialPriceMatch findFamily(String normalized) {
        String local = stripPrefix(normalized);
        List<String> tokens = familyTokens(local);
        if (tokens.isEmpty()) {
            return null;
        }
        String size = sizeClass(local);
        OfficialModelPrice best = null;
        String bestId = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map.Entry<String, OfficialModelPrice> entry : catalog.entrySet()) {
            String key = stripPrefix(entry.getKey());
            if (!matchesFamily(key, tokens)) {
                continue;
            }
            int score = familyScore(local, key, size);
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
                bestId = entry.getKey();
            }
        }
        return best == null ? null : new OfficialPriceMatch(best, SOURCE_FAMILY, bestId);
    }

    private boolean matchesFamily(String key, List<String> tokens) {
        for (String token : tokens) {
            if (key.equals(token) || key.startsWith(token + "-") || key.contains("-" + token)
                || key.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private int familyScore(String requested, String candidate, String requestedSize) {
        int score = 0;
        if (sizeClass(candidate).equals(requestedSize)) {
            score += 1000;
        }
        score += versionScore(candidate) * 10;
        score -= Math.min(candidate.length(), 200);
        if (candidate.startsWith(requested) || requested.startsWith(familyStem(candidate))) {
            score += 50;
        }
        return score;
    }

    private int versionScore(String id) {
        Matcher matcher = VERSION_NUMBERS.matcher(id);
        int score = 0;
        int shift = 100;
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value >= 100) {
                continue;
            }
            score += value * shift;
            shift = Math.max(1, shift / 10);
        }
        return score;
    }

    private List<String> familyTokens(String local) {
        List<String> tokens = new ArrayList<>();
        if (local.contains("sonnet") || local.equals("sonnet")) {
            tokens.add("sonnet");
        } else if (local.contains("haiku") || local.equals("haiku")) {
            tokens.add("haiku");
        } else if (local.contains("opus") || local.equals("opus")) {
            tokens.add("opus");
        }
        if (local.startsWith("gpt-") || local.startsWith("chatgpt-") || local.matches("o\\d.*")) {
            tokens.addAll(gptTokens(local));
        }
        if (local.startsWith("gemini") || local.startsWith("gemma")) {
            tokens.add(local.startsWith("gemma") ? "gemma" : geminiToken(local));
        }
        if (local.startsWith("deepseek")) {
            tokens.add(local.startsWith("deepseek-chat") ? "deepseek-chat"
                : local.startsWith("deepseek-reasoner") ? "deepseek-reasoner"
                : local.startsWith("deepseek-v4") ? "deepseek-v4" : "deepseek");
        }
        if (tokens.isEmpty() && local.length() >= 3) {
            int cut = local.indexOf('-', 4);
            tokens.add(cut > 0 ? local.substring(0, cut) : local);
        }
        return tokens;
    }

    private List<String> gptTokens(String local) {
        List<String> tokens = new ArrayList<>();
        addUnique(tokens, local.replace('.', '-'));
        Matcher matcher = Pattern.compile("^(chatgpt-|gpt-|o)(\\d+(?:[.-]\\d+)?)").matcher(local);
        if (matcher.find()) {
            String prefix = matcher.group(1);
            String version = matcher.group(2).replace('.', '-');
            addUnique(tokens, prefix + version);
            int dash = version.indexOf('-');
            if (dash > 0) {
                addUnique(tokens, prefix + version.substring(0, dash));
            }
        }
        return tokens;
    }

    private String geminiToken(String local) {
        Matcher matcher = Pattern.compile("^gemini-\\d+(?:[.-]\\d+)?").matcher(local);
        return matcher.find() ? matcher.group().replace('.', '-') : "gemini";
    }

    private String sizeClass(String id) {
        if (id.contains("nano")) {
            return "nano";
        }
        if (id.contains("mini") || id.contains("haiku") || id.contains("flash") || id.contains("lite")) {
            return "small";
        }
        if (id.contains("pro") || id.contains("opus") || id.contains("ultra")) {
            return "pro";
        }
        return "default";
    }

    private String familyStem(String id) {
        int cut = id.indexOf('-', 4);
        return cut > 0 ? id.substring(0, cut) : id;
    }

    private OfficialPriceMatch medianForProvider(String provider) {
        if (provider == null || provider.equals("other")) {
            return null;
        }
        List<OfficialModelPrice> prices = new ArrayList<>();
        for (Map.Entry<String, OfficialModelPrice> entry : catalog.entrySet()) {
            if (provider.equals(providerOf(entry.getKey()))) {
                prices.add(entry.getValue());
            }
        }
        OfficialModelPrice median = median(prices);
        return median == null ? null : new OfficialPriceMatch(median, SOURCE_PROVIDER_MEDIAN, median.model());
    }

    private OfficialPriceMatch globalMedian() {
        OfficialModelPrice median = median(List.copyOf(catalog.values()));
        return median == null ? null : new OfficialPriceMatch(median, SOURCE_GLOBAL_MEDIAN, median.model());
    }

    private OfficialModelPrice median(List<OfficialModelPrice> prices) {
        if (prices == null || prices.isEmpty()) {
            return null;
        }
        List<OfficialModelPrice> sorted = prices.stream()
            .sorted(Comparator.comparing(price -> price.inputCostPerToken().add(price.outputCostPerToken())))
            .toList();
        return sorted.get(sorted.size() / 2);
    }

    private String providerOf(String model) {
        String local = stripPrefix(model);
        if (local.startsWith("gpt-") || local.startsWith("chatgpt-") || local.startsWith("o1")
            || local.startsWith("o3") || local.startsWith("o4") || local.startsWith("dall-e")) {
            return "openai";
        }
        if (local.contains("claude") || local.equals("sonnet") || local.equals("haiku") || local.equals("opus")) {
            return "anthropic";
        }
        if (local.startsWith("gemini") || local.startsWith("gemma")) {
            return "google";
        }
        if (local.startsWith("deepseek")) {
            return "deepseek";
        }
        if (model.startsWith("openai/")) {
            return "openai";
        }
        if (model.startsWith("anthropic/")) {
            return "anthropic";
        }
        if (model.startsWith("google/") || model.startsWith("gemini/")) {
            return "google";
        }
        if (model.startsWith("deepseek/")) {
            return "deepseek";
        }
        return "other";
    }

    private String stripPrefix(String model) {
        for (String prefix : PREFIXES) {
            if (model.startsWith(prefix) && model.length() > prefix.length()) {
                return model.substring(prefix.length());
            }
        }
        return model;
    }

    private static String normalize(String model) {
        if (model == null) {
            return "";
        }
        return model.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    record OfficialPriceMatch(OfficialModelPrice price, String source, String matchedId) {
        OfficialPriceMatch {
            if (price == null) {
                throw new IllegalArgumentException("price is required");
            }
            source = source == null ? SOURCE_OFFICIAL : source;
        }
    }
}

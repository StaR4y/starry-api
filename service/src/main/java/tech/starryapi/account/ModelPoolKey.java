package tech.starryapi.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, bounded identity for a model-family circuit-breaker pool. */
public record ModelPoolKey(
    String provider,
    String family,
    String providerLabel,
    String familyLabel
) implements Comparable<ModelPoolKey> {

    static final int MAX_COMPONENT_LENGTH = 63;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    public ModelPoolKey {
        provider = requireSlug(provider, "provider");
        family = requireSlug(family, "family");
        providerLabel = requireLabel(providerLabel, provider);
        familyLabel = requireLabel(familyLabel, family);
    }

    @JsonProperty("key")
    public String key() {
        return provider + ":" + family;
    }

    @Override
    public boolean equals(Object value) {
        return this == value
            || value instanceof ModelPoolKey other
            && provider.equals(other.provider)
            && family.equals(other.family);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, family);
    }

    @Override
    public int compareTo(ModelPoolKey other) {
        return key().compareTo(other.key());
    }

    @Override
    public String toString() {
        return key();
    }

    private static String requireSlug(String value, String name) {
        String slug = Objects.requireNonNull(value, name + " is required");
        if (slug.length() > MAX_COMPONENT_LENGTH || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(name + " must be a stable lowercase slug");
        }
        return slug;
    }

    private static String requireLabel(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}

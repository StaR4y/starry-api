package tech.starryapi.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the source code of the running version is offered to the people who use the service over the network
 * ({@code STARRY_SOURCE_URL}). The AGPL-3.0 (section 13) requires whoever runs a modified version to offer those users
 * its source, so an operator who changes the code points this at their own repository. The console footer links to it.
 */
@ConfigurationProperties(prefix = "starry.source")
public class SourceCodeProperties {

    /** The upstream project, used when nothing usable is configured. */
    public static final String DEFAULT_URL = "https://github.com/StaR4y/starry-api";

    private static final Logger log = LoggerFactory.getLogger(SourceCodeProperties.class);
    private static final int MAX_URL_LENGTH = 2048;

    private String url = DEFAULT_URL;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
        if (url != null && !url.isBlank() && usable(url) == null) {
            log.warn("STARRY_SOURCE_URL is not an absolute http or https address, so the console links to {} instead."
                + " If you run a modified version, set it to where your modified source code can be downloaded"
                + " (AGPL-3.0, section 13).", DEFAULT_URL);
        }
    }

    /** The address the console links to: the configured one, or {@link #DEFAULT_URL} when none is set or it is unusable. */
    public String effectiveUrl() {
        String configured = usable(url);
        return configured == null ? DEFAULT_URL : configured;
    }

    /**
     * The trimmed address when it is an absolute http or https URL with a host and no embedded credentials, otherwise
     * null. The value ends up in an {@code href} that every user of the console sees, so nothing else is let through.
     */
    static String usable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= ' ' || c == 0x7f || c == '\\' || c == '"' || c == '<' || c == '>') {
                return null;
            }
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = scheme.equals("http") || scheme.equals("https");
            if (!web || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            return trimmed;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}

package tech.starryapi.config;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The address users reach this site on, taken from configuration ({@code STARRY_WEBUI_URL}) and not from the
 * request.
 *
 * <p>Links that are e-mailed to someone (registration, SMTP verification) used to be built from the
 * {@code Host} / {@code X-Forwarded-Host} headers of the request that triggered them. Anyone can send those
 * headers, so a request for victim@example.com with a forged host produced a genuine e-mail whose link
 * pointed at the attacker's domain and carried the registration token.
 */
@Component
public class PublicSiteUrl {

    private static final Logger log = LoggerFactory.getLogger(PublicSiteUrl.class);

    private final ProxyProperties properties;
    private final AtomicBoolean warned = new AtomicBoolean();

    public PublicSiteUrl(ProxyProperties properties) {
        this.properties = properties;
    }

    /**
     * The configured site address without fragment, query or trailing slash (for example
     * {@code https://example.com} or {@code https://example.com/starry}), or {@code null} when it is not set
     * or not a valid http(s) URL.
     */
    public String configuredBase() {
        return normalize(properties.getWebuiUrl());
    }

    static String normalize(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(configured.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme.toLowerCase() + "://" + uri.getRawAuthority() + path;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Logs once that links fall back to request headers because the site address is not configured. */
    public void warnHeaderFallback() {
        if (warned.compareAndSet(false, true)) {
            log.warn("STARRY_WEBUI_URL is not set, so links in e-mails are built from the request's Host header, "
                + "which a client can forge. Set it to the public address, e.g. https://your-domain/#/login.");
        }
    }
}

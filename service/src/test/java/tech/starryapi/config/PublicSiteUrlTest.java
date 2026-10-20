package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicSiteUrlTest {

    @Test
    void reducesTheConfiguredLoginUrlToTheSiteBase() {
        assertThat(PublicSiteUrl.normalize("https://api.example.com/#/login")).isEqualTo("https://api.example.com");
        assertThat(PublicSiteUrl.normalize("  http://203.0.113.5:18080/#/login ")).isEqualTo("http://203.0.113.5:18080");
        assertThat(PublicSiteUrl.normalize("https://example.com/starry/")).isEqualTo("https://example.com/starry");
        assertThat(PublicSiteUrl.normalize("HTTPS://Example.com/?x=1#/login")).isEqualTo("https://Example.com");
    }

    @Test
    void ignoresMissingOrUnsafeValues() {
        assertThat(PublicSiteUrl.normalize(null)).isNull();
        assertThat(PublicSiteUrl.normalize("")).isNull();
        assertThat(PublicSiteUrl.normalize("javascript:alert(1)")).isNull();
        assertThat(PublicSiteUrl.normalize("/#/login")).isNull();
        assertThat(PublicSiteUrl.normalize("ftp://example.com")).isNull();
        assertThat(PublicSiteUrl.normalize("not a url")).isNull();
    }

    @Test
    void readsTheValueFromProxyProperties() {
        ProxyProperties properties = new ProxyProperties();
        properties.setWebuiUrl("https://site.example/#/login");
        assertThat(new PublicSiteUrl(properties).configuredBase()).isEqualTo("https://site.example");
        assertThat(new PublicSiteUrl(new ProxyProperties()).configuredBase()).isNull();
    }
}

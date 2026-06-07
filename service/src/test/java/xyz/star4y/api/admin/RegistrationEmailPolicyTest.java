package xyz.star4y.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.star4y.api.common.ApiException;

class RegistrationEmailPolicyTest {

    @Test
    void normalizesEmailSuffixWhitelistLikeSub2Api() {
        List<String> normalized = RegistrationEmailPolicy.normalizeWhitelist(
            List.of("example.com", "@EXAMPLE.com", " @foo.bar ", "*.EDU.CN"),
            true
        );

        assertThat(normalized).containsExactly("@example.com", "@foo.bar", "*.edu.cn");
    }

    @Test
    void rejectsInvalidEmailSuffixWhenStrict() {
        assertThatThrownBy(() -> RegistrationEmailPolicy.normalizeWhitelist(List.of("@invalid_domain"), true))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Invalid registration email suffix");
    }

    @Test
    void checksExactAndWildcardSuffixes() {
        List<String> whitelist = List.of("@example.com", "*.edu.cn");

        assertThat(RegistrationEmailPolicy.isAllowed("user@example.com", whitelist)).isTrue();
        assertThat(RegistrationEmailPolicy.isAllowed("user@sub.example.com", whitelist)).isFalse();
        assertThat(RegistrationEmailPolicy.isAllowed("student@cs.edu.cn", whitelist)).isTrue();
        assertThat(RegistrationEmailPolicy.isAllowed("student@edu.cn", whitelist)).isTrue();
        assertThat(RegistrationEmailPolicy.isAllowed("student@foo.cn", whitelist)).isFalse();
    }
}

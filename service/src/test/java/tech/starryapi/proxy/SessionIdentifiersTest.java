package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.EgressFailure;
import tech.starryapi.account.allocation.AccountFailureScope;
import tools.jackson.databind.ObjectMapper;

class SessionIdentifiersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void parsesSessionMetadataWithoutConflatingUserAndSession() {
        assertThat(SessionIdentifiers.anthropic(json.createObjectNode().put("user_id", "regular-user"))).isNull();
        assertThat(SessionIdentifiers.anthropic(json.createObjectNode().put("user_id", "{\"session_id\":\"session-a\",\"user_id\":\"user\"}"))).isEqualTo("session-a");
        assertThat(SessionIdentifiers.anthropic(json.createObjectNode().put("user_id", "user_abc_account_def_session_123-456"))).isEqualTo("123-456");
        assertThat(SessionIdentifiers.anthropic(json.createObjectNode().put("session_id", "explicit"))).isEqualTo("explicit");
    }

    @Test void egressFailureDoesNotInvalidateCredentialsOrRetryAmbiguousDispatch() {
        var local = UpstreamFailureClassifier.classify(new EgressException(EgressFailure.RUNTIME_DISABLED));
        assertThat(local.scope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(local.retryable()).isTrue();
        var dispatched = UpstreamFailureClassifier.classify(
            EgressException.maybeSent(EgressFailure.PROXY_TRANSPORT_FAILED, new java.io.IOException("reset")));
        assertThat(dispatched.retryable()).isFalse();
        assertThat(dispatched.permanentCredentialFailure()).isFalse();
    }

    @Test void retryAfterSupportsSecondsAndIgnoresInvalidValues() {
        var error = new tech.starryapi.platform.upstream.UpstreamException(429, "busy", true, "120");
        assertThat(tech.starryapi.platform.upstream.UpstreamException.retryAfterSeconds(error)).isEqualTo(120);
    }
}

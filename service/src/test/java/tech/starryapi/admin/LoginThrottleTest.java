package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tech.starryapi.common.ApiException;

class LoginThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    };

    private MockServerWebExchange from(String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/auth/login");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(request.build());
    }

    @Test
    void aClientThatKeepsGuessingGetsAnAnswerWithRetryAfterWhileOthersAreUnaffected() {
        LoginThrottle throttle = new LoginThrottle(3, 1000, clock);
        for (int i = 0; i < 3; i++) {
            throttle.check(from("203.0.113.9"));
        }
        MockServerWebExchange blocked = from("203.0.113.9");
        assertThatThrownBy(() -> throttle.check(blocked))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(error.getCode()).isEqualTo("LOGIN_RATE_LIMITED");
            });
        assertThat(Long.parseLong(blocked.getResponse().getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);

        assertThatCode(() -> throttle.check(from("198.51.100.4"))).doesNotThrowAnyException();
    }

    @Test
    void theLimitResetsWhenTheWindowPasses() {
        LoginThrottle throttle = new LoginThrottle(2, 1000, clock);
        throttle.check(from("203.0.113.9"));
        throttle.check(from("203.0.113.9"));
        assertThatThrownBy(() -> throttle.check(from("203.0.113.9"))).isInstanceOf(ApiException.class);

        now.addAndGet(61_000);

        assertThatCode(() -> throttle.check(from("203.0.113.9"))).doesNotThrowAnyException();
    }

    @Test
    void theSiteWideBackstopStopsRotatingAddresses() {
        LoginThrottle throttle = new LoginThrottle(100, 5, clock);
        for (int i = 0; i < 5; i++) {
            throttle.check(from("10.0.0." + i));
        }
        assertThatThrownBy(() -> throttle.check(from("10.0.0.99"))).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void usesTheFirstForwardedAddressAndFallsBackToTheSocket() {
        assertThat(LoginThrottle.clientKey(from("203.0.113.9, 10.0.0.1"))).isEqualTo("203.0.113.9");
        assertThat(LoginThrottle.clientKey(from(null))).isEqualTo("unknown");
    }
}

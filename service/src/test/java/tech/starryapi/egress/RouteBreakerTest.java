package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RouteBreakerTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final RouteBreaker breaker = new RouteBreaker(2, Duration.ofSeconds(15), now::get);

    @Test void oneFailureIsNotEnoughToSkipARoute() {
        breaker.failed("a");
        assertThat(breaker.tryAcquire("a")).isTrue();
    }

    @Test void twoFailuresInARowSuspendTheRouteAndOnlyThatRoute() {
        breaker.failed("a");
        breaker.failed("a");
        assertThat(breaker.tryAcquire("a")).isFalse();
        assertThat(breaker.tryAcquire("b")).isTrue();
        now.addAndGet(Duration.ofSeconds(14).toNanos());
        assertThat(breaker.tryAcquire("a")).isFalse();
    }

    @Test void aSuccessInBetweenStartsTheCountAgain() {
        breaker.failed("a");
        breaker.succeeded("a");
        breaker.failed("a");
        assertThat(breaker.tryAcquire("a")).isTrue();
    }

    @Test void afterThePauseExactlyOneRequestTriesTheRouteAgain() {
        breaker.failed("a");
        breaker.failed("a");
        now.addAndGet(Duration.ofSeconds(15).toNanos());

        assertThat(breaker.tryAcquire("a")).as("the trial").isTrue();
        assertThat(breaker.tryAcquire("a")).as("while the trial is out").isFalse();

        // The trial failed: suspended for another pause.
        breaker.failed("a");
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(breaker.tryAcquire("a")).isFalse();
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(breaker.tryAcquire("a")).isTrue();

        // This trial got an answer: the route is back.
        breaker.succeeded("a");
        assertThat(breaker.tryAcquire("a")).isTrue();
        assertThat(breaker.tryAcquire("a")).isTrue();
    }
}

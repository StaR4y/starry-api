package tech.starryapi.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;

class TicketRequestFilterTest {
    @Test void unauthenticatedRequestIsRejectedBeforeReadingMultipartBody() {
        var auth = mock(AdminAuth.class);
        var read = new AtomicBoolean();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/tickets").body(Flux.defer(() -> {
            read.set(true); return Flux.empty();
        })));
        when(auth.verify(exchange)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Login required"));
        var filter = new TicketRequestFilter(auth, new ObjectMapper().findAndRegisterModules());
        filter.filter(exchange, e -> { throw new AssertionError("Unauthorized request reached controller"); }).block();
        assertThat(read).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("ADMIN_UNAUTHORIZED");
    }
    @Test void chunkedRequestCannotBypassAggregateBodyLimit() {
        var auth = mock(AdminAuth.class);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/tickets").body(Flux.range(0, 9)
            .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[1024 * 1024]))));
        var filter = new TicketRequestFilter(auth, new ObjectMapper());
        StepVerifier.create(filter.filter(exchange, e -> e.getRequest().getBody().doOnNext(DataBufferUtils::release).then()))
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE))).verify();
    }
    @Test void unrelatedRoutesAreNotIntercepted() {
        var auth = mock(AdminAuth.class);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/health"));
        new TicketRequestFilter(auth, new ObjectMapper()).filter(exchange, e -> Mono.empty()).block();
        verifyNoInteractions(auth);
    }
    @Test void concurrentMultipartAdmissionIsBoundedPerUserAndCancellationReleasesSlot() {
        var auth = mock(AdminAuth.class);
        var alice = new tech.starryapi.admin.AdminPrincipal("alice", "alice", "USER", null, false);
        var bob = new tech.starryapi.admin.AdminPrincipal("bob", "bob", "USER", null, false);
        when(auth.verify(any())).thenAnswer(invocation -> {
            var exchange = (org.springframework.web.server.ServerWebExchange) invocation.getArgument(0);
            return "bob".equals(exchange.getRequest().getHeaders().getFirst("X-Test-User")) ? bob : alice;
        });
        var filter = new TicketRequestFilter(auth, new ObjectMapper());
        java.util.function.Function<String, MockServerWebExchange> post = user -> MockServerWebExchange.from(
            MockServerHttpRequest.post("/tickets").header("X-Test-User", user).build());
        var subscriptions = new java.util.ArrayList<reactor.core.Disposable>();
        try {
            // Alice may hold two submissions at once; a third is refused ...
            for (int i = 0; i < 2; i++) subscriptions.add(filter.filter(post.apply("alice"), e -> Mono.never()).subscribe());
            StepVerifier.create(filter.filter(post.apply("alice"), e -> Mono.empty()))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS))).verify();
            // ... without blocking anyone else (this used to be a single pool of 4 shared by every user).
            StepVerifier.create(filter.filter(post.apply("bob"), e -> Mono.empty())).verifyComplete();
            // Cancelling one of Alice's uploads frees her slot again.
            subscriptions.removeFirst().dispose();
            StepVerifier.create(filter.filter(post.apply("alice"), e -> Mono.empty())).verifyComplete();
        } finally { subscriptions.forEach(reactor.core.Disposable::dispose); }
    }
}

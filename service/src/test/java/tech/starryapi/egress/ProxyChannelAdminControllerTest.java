package tech.starryapi.egress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.GlobalExceptionHandler;

/** The REST contract of the proxy channel administration. */
class ProxyChannelAdminControllerTest {
    private static final UUID CHANNEL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUBSCRIPTION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final ProxyChannelService service = mock(ProxyChannelService.class);
    private final AdminAuth auth = mock(AdminAuth.class);
    private final WebTestClient client = WebTestClient.bindToController(new ProxyChannelAdminController(service, auth))
        .controllerAdvice(new GlobalExceptionHandler()).build();
    private final List<ProxyChannelDtos.BoundAccount> bound = List.of(
        new ProxyChannelDtos.BoundAccount("acct-1", "one@example.test", "OpenAI", "api-key", true, CHANNEL, "Tokyo"));

    @Test void listsTheAccountsBoundToAChannel() {
        when(service.accounts(CHANNEL)).thenReturn(Mono.just(bound));
        client.get().uri("/admin/proxy-channels/{id}/accounts", CHANNEL).exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.success").isEqualTo(true).jsonPath("$.data[0].accountId").isEqualTo("acct-1")
            .jsonPath("$.data[0].email").isEqualTo("one@example.test").jsonPath("$.data[0].provider").isEqualTo("OpenAI")
            .jsonPath("$.data[0].authMethod").isEqualTo("api-key").jsonPath("$.data[0].enabled").isEqualTo(true)
            .jsonPath("$.data[0].channelId").isEqualTo(CHANNEL.toString()).jsonPath("$.data[0].channelName").isEqualTo("Tokyo");
        verify(auth).verifyAdmin(any());
    }

    @Test void deletingABoundChannelIsAConflictThatListsTheAccounts() {
        when(service.delete(CHANNEL)).thenReturn(Mono.error(new ApiException(HttpStatus.CONFLICT, "PROXY_NODE_IN_USE",
            "Unbind accounts before deleting this node", bound)));
        client.delete().uri("/admin/proxy-channels/{id}", CHANNEL).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody()
            .jsonPath("$.success").isEqualTo(false).jsonPath("$.error.code").isEqualTo("PROXY_NODE_IN_USE")
            .jsonPath("$.data[0].accountId").isEqualTo("acct-1").jsonPath("$.data[0].channelName").isEqualTo("Tokyo");
    }

    @Test void deletingASubscriptionWithBoundNodesIsTheSameConflict() {
        when(service.deleteSubscription(SUBSCRIPTION)).thenReturn(Mono.error(new ApiException(HttpStatus.CONFLICT, "PROXY_NODE_IN_USE",
            "Unbind accounts before deleting this node", bound)));
        client.delete().uri("/admin/proxy-channels/subscriptions/{id}", SUBSCRIPTION).exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.data[0].accountId").isEqualTo("acct-1");
        when(service.deleteSubscription(SUBSCRIPTION)).thenReturn(Mono.empty());
        client.delete().uri("/admin/proxy-channels/subscriptions/{id}", SUBSCRIPTION).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.success").isEqualTo(true);
    }

    @Test void renamesASubscriptionAndRejectsABlankName() {
        when(service.renameSubscription(eq(SUBSCRIPTION), any())).thenReturn(Mono.just(new ProxyChannelDtos.Subscription(
            SUBSCRIPTION, "Renamed", "ACTIVE", Instant.parse("2026-10-24T00:00:00Z"), null, 3, true)));
        client.patch().uri("/admin/proxy-channels/subscriptions/{id}", SUBSCRIPTION).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"Renamed\"}").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.name").isEqualTo("Renamed").jsonPath("$.data.nodeCount").isEqualTo(3);
        client.patch().uri("/admin/proxy-channels/subscriptions/{id}", SUBSCRIPTION).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"  \"}").exchange().expectStatus().isBadRequest().expectBody()
            .jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        // A channel is renamed on its own path; the two PATCH routes do not shadow each other.
        when(service.update(eq(CHANNEL), any())).thenReturn(Mono.just(channel()));
        client.patch().uri("/admin/proxy-channels/{id}", CHANNEL).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"Tokyo\"}").exchange().expectStatus().isOk();
    }

    @Test void theCatalogCarriesRoutesProbesIssuesAndTheRuntimeHealth() {
        var runtime = new ProxyChannelDtos.RuntimeHealth(true, Instant.parse("2026-10-24T00:00:10Z"), Instant.parse("2026-10-24T00:00:05Z"),
            4, "CONFIG_REJECTED", EgressFailure.CONFIG_REJECTED.message(), Instant.parse("2026-10-24T00:00:01Z"));
        when(service.catalog()).thenReturn(Mono.just(new ProxyChannelDtos.Catalog(List.of(channel()), List.of(), true, false, List.of("chrome136"), runtime)));
        client.get().uri("/admin/proxy-channels").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.channels[0].route").isEqualTo("DIRECT_PROXY")
            .jsonPath("$.data.channels[0].subscriptionName").isEqualTo("Sub")
            .jsonPath("$.data.channels[0].sourcePresent").isEqualTo(true)
            .jsonPath("$.data.channels[0].configIssue").isEqualTo("Unsupported node protocol: wireguard")
            .jsonPath("$.data.channels[0].lastTestResult").isEqualTo("SUCCESS")
            .jsonPath("$.data.channels[0].lastTestExitIp").isEqualTo("203.0.113.9")
            .jsonPath("$.data.channels[0].lastTestLatencyMs").isEqualTo(120)
            .jsonPath("$.data.channels[0].lastTestErrorCode").isEmpty()
            .jsonPath("$.data.runtime.controllerReachable").isEqualTo(true)
            .jsonPath("$.data.runtime.activeNodes").isEqualTo(4)
            .jsonPath("$.data.runtime.lastFailureCode").isEqualTo("CONFIG_REJECTED")
            .jsonPath("$.data.runtime.lastFailureMessage").isEqualTo(EgressFailure.CONFIG_REJECTED.message());
    }

    @Test void everyEndpointRequiresAnAdministrator() {
        when(auth.verifyAdmin(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Administrator role is required"));
        client.get().uri("/admin/proxy-channels/{id}/accounts", CHANNEL).exchange().expectStatus().isForbidden();
        client.delete().uri("/admin/proxy-channels/subscriptions/{id}", SUBSCRIPTION).exchange().expectStatus().isForbidden();
        client.patch().uri("/admin/proxy-channels/subscriptions/{id}", SUBSCRIPTION).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"x\"}").exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }

    private ProxyChannelDtos.Channel channel() {
        return new ProxyChannelDtos.Channel(CHANNEL, SUBSCRIPTION, "Sub", "Tokyo", "http", "DIRECT_PROXY", true, "ACTIVE", null, 1,
            Instant.parse("2026-10-24T00:00:00Z"), "Unsupported node protocol: wireguard", true, Instant.parse("2026-10-24T00:00:02Z"),
            "SUCCESS", "203.0.113.9", 120L, null, null);
    }
}

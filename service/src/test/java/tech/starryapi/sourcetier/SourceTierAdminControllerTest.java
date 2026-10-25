package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.GlobalExceptionHandler;

/** The REST contract of the tier administration: shapes in and out, the checks that come before the service, who may call. */
class SourceTierAdminControllerTest {

    private final SourceTierAdminService service = mock(SourceTierAdminService.class);
    private final AdminAuth auth = mock(AdminAuth.class);
    private final WebTestClient client = WebTestClient.bindToController(new SourceTierAdminController(service, auth))
        .controllerAdvice(new GlobalExceptionHandler()).build();

    private static final String PUT_BODY = """
        {"expectedVersion":3,"enabled":false,"refusalCap":24,
         "tiers":[{"tierId":"official","level":1,"name":"Official","priceFactor":1.25,"memberCategories":["OFFICIAL_API_KEY"]},
                  {"tierId":"kiro","level":2,"name":"Kiro proxy","priceFactor":0.6,"userSelectable":false,"description":"cheap"}],
         "members":[{"accountId":"claude-1","tierId":"kiro"},
                    {"accountId":"relay-1","tierId":"official","protocol":"OPENAI_CHAT","usageCacheReadInPrompt":false,
                     "usageCacheWriteInPrompt":true,"models":[{"modelId":"claude-sonnet-4.5","upstreamModel":"relay/sonnet","maxContextTokens":200000}]}],
         "gates":[{"entryEndpoint":"MESSAGES","state":"KEYS","allowKeyIds":["key-1"]}]}
        """;

    private static SourceTierDtos.Vendor vendor(String id, long version) {
        return new SourceTierDtos.Vendor(id, "Anthropic", version > 0, version, false, false, false, true, 16, true,
            List.of(new SourceTierDtos.Tier("official", 1, "Official", new BigDecimal("1.000000"), List.of("OFFICIAL_API_KEY"), true, true, null)),
            List.of(new SourceTierDtos.Member("claude-1", "official", null, null, null, null, null, null, null, null, null, null, null, null)),
            List.of(new SourceTierDtos.Gate("OPENAI_CHAT", "CHAT", "OFF", List.of())),
            List.of(new SourceTierDtos.Warning("LOWEST_TIER_COLLECTS_OFFICIAL_KEYS", "official", 2, List.of("a", "b"), "message")),
            Map.of("official", 4));
    }

    @Test
    void theOverviewCarriesTheCapabilitiesThePairsAndEveryVendor() {
        when(service.overview()).thenReturn(Mono.just(new SourceTierDtos.Overview(7L,
            new SourceTierDtos.Runtime(false, false, false, List.of(new SourceTierDtos.PairStatus("anthropic", "OPENAI_CHAT", "CHAT", "NOT_OFFERED"))),
            List.of(vendor("anthropic", 3), vendor("openai", 0)))));

        client.get().uri("/admin/source-tiers").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.configVersion").isEqualTo(7)
            .jsonPath("$.data.runtime.nativeTiers").isEqualTo(false)
            .jsonPath("$.data.runtime.unifyEndpoints").isEqualTo(false)
            .jsonPath("$.data.runtime.xpMembers").isEqualTo(false)
            .jsonPath("$.data.runtime.pairs[0].vendor").isEqualTo("anthropic")
            .jsonPath("$.data.runtime.pairs[0].status").isEqualTo("NOT_OFFERED")
            .jsonPath("$.data.vendors[0].vendor").isEqualTo("anthropic")
            .jsonPath("$.data.vendors[0].version").isEqualTo(3)
            .jsonPath("$.data.vendors[0].tiers[0].priceFactor").isEqualTo(1.0)
            .jsonPath("$.data.vendors[0].tiers[0].memberCategories[0]").isEqualTo("OFFICIAL_API_KEY")
            .jsonPath("$.data.vendors[0].members[0].protocol").isEmpty()
            .jsonPath("$.data.vendors[0].gates[0].state").isEqualTo("OFF")
            .jsonPath("$.data.vendors[0].tierAccounts.official").isEqualTo(4)
            .jsonPath("$.data.vendors[0].warnings[0].code").isEqualTo("LOWEST_TIER_COLLECTS_OFFICIAL_KEYS")
            .jsonPath("$.data.vendors[1].configured").isEqualTo(false)
            .jsonPath("$.data.vendors[1].version").isEqualTo(0);
    }

    @Test
    void aReplaceReachesTheServiceAsSentAndReturnsTheStoredVendor() {
        when(service.replace(eq("anthropic"), any())).thenReturn(Mono.just(vendor("anthropic", 4)));

        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.vendor").isEqualTo("anthropic")
            .jsonPath("$.data.version").isEqualTo(4);

        ArgumentCaptor<SourceTierDtos.ReplaceVendor> captured = ArgumentCaptor.forClass(SourceTierDtos.ReplaceVendor.class);
        verify(service).replace(eq("anthropic"), captured.capture());
        SourceTierDtos.ReplaceVendor request = captured.getValue();
        assertThat(request.expectedVersion()).isEqualTo(3L);
        assertThat(request.enabled()).isFalse();
        assertThat(request.refusalCap()).isEqualTo(24);
        assertThat(request.unifyEndpoints()).isNull();
        assertThat(request.tiers()).hasSize(2);
        assertThat(request.tiers().getFirst().priceFactor()).isEqualByComparingTo("1.25");
        assertThat(request.tiers().getFirst().memberCategories()).containsExactly("OFFICIAL_API_KEY");
        assertThat(request.tiers().get(1).userSelectable()).isFalse();
        assertThat(request.members()).hasSize(2);
        assertThat(request.members().getFirst().protocol()).isNull();
        SourceTierDtos.Member relay = request.members().get(1);
        assertThat(relay.protocol()).isEqualTo("OPENAI_CHAT");
        assertThat(relay.usageCacheReadInPrompt()).isFalse();
        assertThat(relay.usageCacheWriteInPrompt()).isTrue();
        assertThat(relay.models()).singleElement().satisfies(model -> {
            assertThat(model.modelId()).isEqualTo("claude-sonnet-4.5");
            assertThat(model.maxContextTokens()).isEqualTo(200_000);
        });
        assertThat(request.gates()).singleElement().satisfies(gate -> assertThat(gate.allowKeyIds()).containsExactly("key-1"));
    }

    @Test
    void aReplaceWithoutTheVersionItWasMadeFromOrTheEnabledSwitchIsNotAccepted() {
        for (String body : List.of(
            "{\"enabled\":false}",
            "{\"expectedVersion\":-1,\"enabled\":false}",
            "{\"expectedVersion\":0}",
            "{\"expectedVersion\":0,\"enabled\":false,\"tiers\":[{\"level\":1,\"name\":\"A\"}]}",
            "{\"expectedVersion\":0,\"enabled\":false,\"tiers\":[{\"tierId\":\"a\",\"name\":\"A\"}]}",
            "{\"expectedVersion\":0,\"enabled\":false,\"tiers\":[{\"tierId\":\"a\",\"level\":1}]}",
            "{\"expectedVersion\":0,\"enabled\":false,\"members\":[{\"accountId\":\"x\"}]}",
            "{\"expectedVersion\":0,\"enabled\":false,\"members\":[{\"accountId\":\"x\",\"tierId\":\"a\",\"models\":[{\"modelId\":\"m\","
                + "\"upstreamModel\":\"w\"}]}]}",
            "{\"expectedVersion\":0,\"enabled\":false,\"gates\":[{\"entryEndpoint\":\"CHAT\"}]}")) {
            client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        }
        verify(service, never()).replace(any(), any());
    }

    @Test
    void membersAreUpsertedAndRemovedThroughTheirOwnEndpoint() {
        when(service.upsertMembers(eq("anthropic"), any())).thenReturn(Mono.just(vendor("anthropic", 5)));

        client.post().uri("/admin/source-tiers/anthropic/members").contentType(MediaType.APPLICATION_JSON).bodyValue("""
            {"expectedVersion":4,"members":[{"accountId":"claude-1","tierId":"kiro"}],"removeAccountIds":["claude-2"]}
            """).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.version").isEqualTo(5);

        ArgumentCaptor<SourceTierDtos.UpsertMembers> captured = ArgumentCaptor.forClass(SourceTierDtos.UpsertMembers.class);
        verify(service).upsertMembers(eq("anthropic"), captured.capture());
        assertThat(captured.getValue().expectedVersion()).isEqualTo(4L);
        assertThat(captured.getValue().members()).extracting(SourceTierDtos.Member::accountId).containsExactly("claude-1");
        assertThat(captured.getValue().removeAccountIds()).containsExactly("claude-2");

        client.post().uri("/admin/source-tiers/anthropic/members").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"members\":[]}").exchange().expectStatus().isBadRequest();
        verify(service).upsertMembers(eq("anthropic"), any());
    }

    @Test
    void aStaleVersionAnAbsentCapabilityAndAnInvalidConfigurationAnswerWithTheirOwnCodes() {
        when(service.replace(eq("anthropic"), any())).thenReturn(
            Mono.error(new ApiException(HttpStatus.CONFLICT, "SOURCE_TIER_VERSION_CONFLICT", "changed", Map.of("currentVersion", 9L))),
            Mono.error(new ApiException(HttpStatus.CONFLICT, "SOURCE_TIER_RUNTIME_UNSUPPORTED", "cannot run", List.of("enabled"))),
            Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "SOURCE_TIER_INVALID", "tiers[0].level must be between 1 and 9")));

        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error.code").isEqualTo("SOURCE_TIER_VERSION_CONFLICT")
            .jsonPath("$.data.currentVersion").isEqualTo(9);
        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody()
            .jsonPath("$.error.code").isEqualTo("SOURCE_TIER_RUNTIME_UNSUPPORTED")
            .jsonPath("$.data[0]").isEqualTo("enabled");
        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isBadRequest().expectBody()
            .jsonPath("$.error.code").isEqualTo("SOURCE_TIER_INVALID")
            .jsonPath("$.error.message").isEqualTo("tiers[0].level must be between 1 and 9");
    }

    @Test
    void everyEndpointRequiresAnAdministrator() {
        when(auth.verifyAdmin(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Administrator role is required"));

        client.get().uri("/admin/source-tiers").exchange().expectStatus().isForbidden();
        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isForbidden();
        client.post().uri("/admin/source-tiers/anthropic/members").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"expectedVersion\":0,\"removeAccountIds\":[\"x\"]}").exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }
}

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.GlobalExceptionHandler;
import tech.starryapi.model.ModelDtos;

/** The REST contract of the tier administration: shapes in and out, the checks that come before the service, who may call. */
class SourceTierAdminControllerTest {

    private final SourceTierAdminService service = mock(SourceTierAdminService.class);
    private final SourceTierStatusService status = mock(SourceTierStatusService.class);
    private final SourceTierChoices choices = mock(SourceTierChoices.class);
    private final AdminAuth auth = mock(AdminAuth.class);
    private final WebTestClient client = WebTestClient.bindToController(new SourceTierAdminController(service, status, choices, auth))
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
            new SourceTierDtos.Runtime(true, false, false, List.of(new SourceTierDtos.PairStatus("anthropic", "OPENAI_CHAT", "CHAT", "NOT_OFFERED")), false),
            List.of(vendor("anthropic", 3), vendor("openai", 0)))));

        client.get().uri("/admin/source-tiers").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.configVersion").isEqualTo(7)
            .jsonPath("$.data.runtime.nativeTiers").isEqualTo(true)
            .jsonPath("$.data.runtime.selectors").isEqualTo(false)
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
    void theStatusCarriesTheLayersTheServingFlagsAndTheWindowOfEveryVendor() {
        when(status.status()).thenReturn(Mono.just(new SourceTierDtos.Status(Instant.parse("2026-10-25T08:00:00Z"), 7L, 15, List.of(
            new SourceTierDtos.VendorStatus("anthropic", "Anthropic", true, true, 100, 30, List.of(
                new SourceTierDtos.TierStatus("official", 1, "Official", true, true,
                    new SourceTierDtos.TierAccounts(0, 1, 1, 1, 2), 70, 0, 4),
                new SourceTierDtos.TierStatus("kiro", 2, "Kiro", true, false,
                    new SourceTierDtos.TierAccounts(2, 0, 0, 0, 0), 30, 5, 0)))))));

        client.get().uri("/admin/source-tiers/status").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.windowMinutes").isEqualTo(15)
            .jsonPath("$.data.configVersion").isEqualTo(7)
            .jsonPath("$.data.vendors[0].vendor").isEqualTo("anthropic")
            .jsonPath("$.data.vendors[0].serving").isEqualTo(true)
            .jsonPath("$.data.vendors[0].autoServed").isEqualTo(100)
            .jsonPath("$.data.vendors[0].degraded").isEqualTo(30)
            .jsonPath("$.data.vendors[0].tiers[0].accounts.unavailable").isEqualTo(1)
            .jsonPath("$.data.vendors[0].tiers[0].accounts.ready").isEqualTo(2)
            .jsonPath("$.data.vendors[0].tiers[0].failed").isEqualTo(4)
            .jsonPath("$.data.vendors[0].tiers[1].serves").isEqualTo(false)
            .jsonPath("$.data.vendors[0].tiers[1].servedPinned").isEqualTo(5)
            .jsonPath("$.data.vendors[0].tiers[1].accounts.disabled").isEqualTo(2);
    }

    @Test
    void thePlacementsListTheTierOfEachAccountThatIsInOne() {
        when(status.placements()).thenReturn(Mono.just(new SourceTierDtos.Placements(7L, List.of(
            new SourceTierDtos.AccountPlacement("claude-1", List.of(
                new SourceTierDtos.Placed("anthropic", "Anthropic", "kiro", 2, "Kiro", "CATEGORY", false, true)))))));

        client.get().uri("/admin/source-tiers/placements").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.configVersion").isEqualTo(7)
            .jsonPath("$.data.accounts[0].accountId").isEqualTo("claude-1")
            .jsonPath("$.data.accounts[0].tiers[0].vendorLabel").isEqualTo("Anthropic")
            .jsonPath("$.data.accounts[0].tiers[0].tierId").isEqualTo("kiro")
            .jsonPath("$.data.accounts[0].tiers[0].rule").isEqualTo("CATEGORY")
            .jsonPath("$.data.accounts[0].tiers[0].relay").isEqualTo(false)
            .jsonPath("$.data.accounts[0].tiers[0].active").isEqualTo(true);
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
    void aUserAsksForThePoolsOfAProviderAndIsToldOnlyTheirNameLevelFactorAndPrice() {
        AdminPrincipal user = new AdminPrincipal("user-1", "alice", "USER", Instant.parse("2026-10-25T09:00:00Z"), false);
        ModelDtos.ModelPublicPricing price = new ModelDtos.ModelPublicPricing("token", null, null, null, null, null, null,
            new BigDecimal("1.8"), new BigDecimal("9"), null, new BigDecimal("0.18"));
        when(auth.verify(any())).thenReturn(user);
        when(choices.forProvider(user, "Anthropic")).thenReturn(Mono.just(new SourceTierDtos.MyTiers("Anthropic", "claude-sonnet-4.5", List.of(
            new SourceTierDtos.MyTier("official", 1, "Official", new BigDecimal("1.000000"), null),
            new SourceTierDtos.MyTier("kiro", 2, "Kiro", new BigDecimal("0.600000"), price)))));

        client.get().uri("/admin/source-tiers/me?provider=Anthropic").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.provider").isEqualTo("Anthropic")
            .jsonPath("$.data.referenceModel").isEqualTo("claude-sonnet-4.5")
            .jsonPath("$.data.tiers.length()").isEqualTo(2)
            .jsonPath("$.data.tiers[0].id").isEqualTo("official")
            .jsonPath("$.data.tiers[0].pricing").doesNotExist()
            .jsonPath("$.data.tiers[1].id").isEqualTo("kiro")
            .jsonPath("$.data.tiers[1].level").isEqualTo(2)
            .jsonPath("$.data.tiers[1].name").isEqualTo("Kiro")
            .jsonPath("$.data.tiers[1].priceFactor").isEqualTo(0.6)
            .jsonPath("$.data.tiers[1].pricing.inputPricePerMillionTokens").isEqualTo(1.8)
            .jsonPath("$.data.tiers[1].pricing.outputPricePerMillionTokens").isEqualTo(9)
            // The shape holds nothing of the accounts, the categories or the protocol behind a pool.
            .jsonPath("$.data.tiers[1].memberCategories").doesNotExist()
            .jsonPath("$.data.tiers[1].userSelectable").doesNotExist()
            .jsonPath("$.data.tiers[1].description").doesNotExist();
        verify(auth, never()).verifyAdmin(any());
    }

    @Test
    void thePoolsOfAProviderNeedAProviderAndASignedInUser() {
        client.get().uri("/admin/source-tiers/me").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(choices);

        when(auth.verify(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Missing admin credentials"));
        client.get().uri("/admin/source-tiers/me?provider=Anthropic").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(choices);
    }

    @Test
    void everyEndpointRequiresAnAdministrator() {
        when(auth.verifyAdmin(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Administrator role is required"));

        client.get().uri("/admin/source-tiers").exchange().expectStatus().isForbidden();
        client.get().uri("/admin/source-tiers/status").exchange().expectStatus().isForbidden();
        client.get().uri("/admin/source-tiers/placements").exchange().expectStatus().isForbidden();
        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isForbidden();
        client.post().uri("/admin/source-tiers/anthropic/members").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"expectedVersion\":0,\"removeAccountIds\":[\"x\"]}").exchange().expectStatus().isForbidden();
        verifyNoInteractions(service, status);
    }
}

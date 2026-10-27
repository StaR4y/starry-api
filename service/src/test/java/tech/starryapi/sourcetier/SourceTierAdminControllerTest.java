package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final XpMappingVerifier verifier = mock(XpMappingVerifier.class);
    private final AdminAuth auth = mock(AdminAuth.class);
    private final WebTestClient client = WebTestClient.bindToController(new SourceTierAdminController(service, status, choices, verifier, auth))
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
    void theStatusOfAVendorWithRelaysCarriesTheirLayersPairsFiguresAndSkips() {
        SourceTierDtos.XpFigures figures = new SourceTierDtos.XpFigures(40, 36, 4, 2, 1, 0, 1, 0, 0, 3, 0, 5, 1800L);
        when(status.status()).thenReturn(Mono.just(new SourceTierDtos.Status(Instant.parse("2026-10-25T08:00:00Z"), 7L, 15, List.of(
            new SourceTierDtos.VendorStatus("anthropic", "Anthropic", true, true, 100, 30, List.of(
                new SourceTierDtos.TierStatus("relay", 1, "Relay", true, true,
                    new SourceTierDtos.TierAccounts(0, 0, 0, 0, 1, 0), 36, 0, 4)),
                new SourceTierDtos.XpStatus(Instant.parse("2026-10-25T05:00:00Z"),
                    List.of(new SourceTierDtos.XpRelay("relay", "relay-1", "ready", 2, 1, figures)),
                    List.of(new SourceTierDtos.XpPair("OPENAI_CHAT", "MESSAGES", "EXPERIMENTAL", "KEYS", 2, figures,
                        List.of(new SourceTierDtos.FeatureCount("image", 5)))),
                    List.of(new SourceTierDtos.XpSkipCount("relay", "GATE", 12))))))));

        client.get().uri("/admin/source-tiers/status").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.vendors[0].tiers[0].accounts.unopened").isEqualTo(0)
            .jsonPath("$.data.vendors[0].xp.skipsSince").isEqualTo("2026-10-25T05:00:00Z")
            .jsonPath("$.data.vendors[0].xp.relays[0].layer").isEqualTo("ready")
            .jsonPath("$.data.vendors[0].xp.relays[0].verified").isEqualTo(1)
            .jsonPath("$.data.vendors[0].xp.relays[0].figures.interrupted").isEqualTo(2)
            .jsonPath("$.data.vendors[0].xp.relays[0].figures.p95Ms").isEqualTo(1800)
            .jsonPath("$.data.vendors[0].xp.pairs[0].gate").isEqualTo("KEYS")
            .jsonPath("$.data.vendors[0].xp.pairs[0].allowedKeys").isEqualTo(2)
            .jsonPath("$.data.vendors[0].xp.pairs[0].degradedFeatures[0].feature").isEqualTo("image")
            .jsonPath("$.data.vendors[0].xp.skips[0].reason").isEqualTo("GATE");
    }

    @Test
    void theEvidenceIsAskedForAWindowOfDaysAndDefaultsToThirty() {
        SourceTierDtos.XpFigures figures = new SourceTierDtos.XpFigures(250, 240, 4, 2, 0, 0, 0, 0, 0, 10, 0, 0, null);
        List<SourceTierDtos.Criterion> criteria = XpPairStandard.evaluate(figures, 0, true);
        when(status.evidence(anyInt())).thenReturn(Mono.just(new SourceTierDtos.Evidence(Instant.parse("2026-10-26T08:00:00Z"),
            Instant.parse("2026-09-26T08:00:00Z"), 30, 7L, Instant.parse("2026-10-25T05:00:00Z"), List.of(
                new SourceTierDtos.PairEvidence("anthropic", "Anthropic", "OPENAI_CHAT", "MESSAGES", "EXPERIMENTAL", "KEYS", 1, figures, 0,
                    criteria, true, List.of(new SourceTierDtos.FeatureCount("image", 12)),
                    List.of(new SourceTierDtos.MemberEvidence("relay", "relay-1", figures)))))));

        client.get().uri("/admin/source-tiers/evidence").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.days").isEqualTo(30)
            .jsonPath("$.data.pairs[0].vendor").isEqualTo("anthropic")
            .jsonPath("$.data.pairs[0].meetsStandard").isEqualTo(true)
            .jsonPath("$.data.pairs[0].criteria[0].name").isEqualTo("requests")
            .jsonPath("$.data.pairs[0].criteria[0].rule").isEqualTo(">= 200")
            .jsonPath("$.data.pairs[0].criteria[4].name").isEqualTo("unbilled")
            .jsonPath("$.data.pairs[0].criteria[4].rule").isEqualTo("< 2%")
            .jsonPath("$.data.pairs[0].criteria[4].value").isEqualTo(0.8)
            .jsonPath("$.data.pairs[0].members[0].accountId").isEqualTo("relay-1");
        client.get().uri("/admin/source-tiers/evidence?days=7").exchange().expectStatus().isOk();

        verify(status).evidence(30);
        verify(status).evidence(7);
        client.get().uri("/admin/source-tiers/evidence?days=many").exchange().expectStatus().isBadRequest();
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
        client.get().uri("/admin/source-tiers/evidence").exchange().expectStatus().isForbidden();
        client.put().uri("/admin/source-tiers/anthropic").contentType(MediaType.APPLICATION_JSON).bodyValue(PUT_BODY)
            .exchange().expectStatus().isForbidden();
        client.post().uri("/admin/source-tiers/anthropic/members").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"expectedVersion\":0,\"removeAccountIds\":[\"x\"]}").exchange().expectStatus().isForbidden();
        client.post().uri("/admin/source-tiers/anthropic/members/relay-1/verify").exchange().expectStatus().isForbidden();
        client.get().uri("/admin/source-tiers/anthropic/members/relay-1/upstream-models").exchange().expectStatus().isForbidden();
        verifyNoInteractions(service, status, verifier);
    }

    @Test
    void aVerificationIsAskedWithOrWithoutABodyAndAnswersWhatEachProbeFound() {
        SourceTierDtos.VerifyResult result = new SourceTierDtos.VerifyResult("anthropic", "relay-1", Instant.parse("2026-10-26T08:00:00Z"), false, List.of(
            new SourceTierDtos.ModelVerification("claude-sonnet-4.5", "relay/sonnet", false, false, List.of(
                new SourceTierDtos.ProbeResult("basic", "PASSED", "ok", 812, 12L, 3L),
                new SourceTierDtos.ProbeResult("tool_call", "FAILED", "The relay did not call the function it was asked to call", 640, null, null)),
                new SourceTierDtos.CacheAdvice(false, null, "call 1: prompt 3", null, "no write", List.of(new SourceTierDtos.CacheCall(3L, 0, 1240))))));
        when(verifier.verify(eq("anthropic"), eq("relay-1"), any())).thenReturn(Mono.just(result));

        client.post().uri("/admin/source-tiers/anthropic/members/relay-1/verify").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.vendor").isEqualTo("anthropic")
            .jsonPath("$.data.verified").isEqualTo(false)
            .jsonPath("$.data.models[0].modelId").isEqualTo("claude-sonnet-4.5")
            .jsonPath("$.data.models[0].probes[0].status").isEqualTo("PASSED")
            .jsonPath("$.data.models[0].probes[0].promptTokens").isEqualTo(12)
            .jsonPath("$.data.models[0].probes[1].message").isEqualTo("The relay did not call the function it was asked to call")
            .jsonPath("$.data.models[0].cache.readInPrompt").isEqualTo(false)
            .jsonPath("$.data.models[0].cache.calls[0].cacheWriteTokens").isEqualTo(1240);
        client.post().uri("/admin/source-tiers/anthropic/members/relay-1/verify").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"modelIds\":[\"claude-sonnet-4.5\"],\"cacheProbe\":true}").exchange().expectStatus().isOk();

        ArgumentCaptor<SourceTierDtos.VerifyMember> captured = ArgumentCaptor.forClass(SourceTierDtos.VerifyMember.class);
        verify(verifier, org.mockito.Mockito.times(2)).verify(eq("anthropic"), eq("relay-1"), captured.capture());
        assertThat(captured.getAllValues().get(0)).isNull();
        assertThat(captured.getAllValues().get(1).modelIds()).containsExactly("claude-sonnet-4.5");
        assertThat(captured.getAllValues().get(1).cacheProbe()).isTrue();
    }

    @Test
    void aVerificationOfMoreMappingsThanItProbesIsRefusedBeforeTheVerifierSeesIt() {
        client.post().uri("/admin/source-tiers/anthropic/members/relay-1/verify").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"modelIds\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]}").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(verifier);
    }

    @Test
    void theErrorsOfAVerificationAndOfAModelListKeepTheirCodesAndData() {
        when(verifier.verify(any(), any(), any())).thenReturn(
            Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SOURCE_VERIFY_RATE_LIMITED", "wait", Map.of("retryAfterSeconds", 12L))),
            Mono.error(new ApiException(HttpStatus.NOT_FOUND, "SOURCE_TIER_MEMBER_NOT_FOUND", "not a relay")));
        client.post().uri("/admin/source-tiers/anthropic/members/relay-1/verify").exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS).expectBody()
            .jsonPath("$.error.code").isEqualTo("SOURCE_VERIFY_RATE_LIMITED")
            .jsonPath("$.data.retryAfterSeconds").isEqualTo(12);
        client.post().uri("/admin/source-tiers/anthropic/members/relay-1/verify").exchange().expectStatus().isNotFound().expectBody()
            .jsonPath("$.error.code").isEqualTo("SOURCE_TIER_MEMBER_NOT_FOUND");
    }

    @Test
    void theModelsOfARelayAreListedWithTheWindowTheRelayStates() {
        when(verifier.upstreamModels("anthropic", "relay-1")).thenReturn(Mono.just(new SourceTierDtos.UpstreamModels(List.of(
            new SourceTierDtos.UpstreamModel("relay/sonnet", 200_000), new SourceTierDtos.UpstreamModel("relay/haiku", null)), true)));

        client.get().uri("/admin/source-tiers/anthropic/members/relay-1/upstream-models").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.truncated").isEqualTo(true)
            .jsonPath("$.data.models[0].id").isEqualTo("relay/sonnet")
            .jsonPath("$.data.models[0].contextLength").isEqualTo(200000)
            .jsonPath("$.data.models[1].id").isEqualTo("relay/haiku");
    }
}

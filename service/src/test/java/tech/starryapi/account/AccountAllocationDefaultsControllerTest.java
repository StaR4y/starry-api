package tech.starryapi.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AllocationDefaultsDtos.Policy;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.GlobalExceptionHandler;

/** The REST contract of the default routing limits for new accounts. */
class AccountAllocationDefaultsControllerTest {
    private final AccountAllocationDefaultsService service = mock(AccountAllocationDefaultsService.class);
    private final AdminAuth auth = mock(AdminAuth.class);
    private final WebTestClient client = WebTestClient.bindToController(new AccountAllocationDefaultsController(service, auth))
        .controllerAdvice(new GlobalExceptionHandler()).build();

    @Test void returnsTheBuiltInConfiguredAndEffectiveValuesOfEveryCategory() {
        var kiro = AccountAllocationCategory.KIRO;
        var configured = new Policy(9, 60, 6, 0, 300, 20, 2_000_000L, 120, "");
        when(service.get()).thenReturn(Mono.just(new AllocationDefaultsDtos.Defaults(List.of(
            new AllocationDefaultsDtos.CategoryDefaults("KIRO", kiro.anthropicSettings(), kiro.builtIn(), configured, configured),
            new AllocationDefaultsDtos.CategoryDefaults("CLAUDE_OAUTH", true, AccountAllocationCategory.CLAUDE_OAUTH.builtIn(), null,
                AccountAllocationCategory.CLAUDE_OAUTH.builtIn())))));
        client.get().uri("/admin/accounts/allocation-defaults").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.categories[0].category").isEqualTo("KIRO")
            .jsonPath("$.data.categories[0].anthropicSettings").isEqualTo(false)
            .jsonPath("$.data.categories[0].builtIn.safeConcurrency").isEqualTo(2)
            .jsonPath("$.data.categories[0].configured.safeConcurrency").isEqualTo(9)
            .jsonPath("$.data.categories[0].effective.sessionIdleSeconds").isEqualTo(300)
            .jsonPath("$.data.categories[1].anthropicSettings").isEqualTo(true)
            .jsonPath("$.data.categories[1].configured").isEmpty()
            .jsonPath("$.data.categories[1].effective.maxSessions").isEqualTo(4)
            .jsonPath("$.data.categories[1].effective.tlsProfile").isEqualTo("");
    }

    @Test void replacesTheConfigurationAndRejectsValuesOutOfRange() {
        when(service.update(any())).thenReturn(Mono.just(new AllocationDefaultsDtos.Defaults(List.of())));
        client.put().uri("/admin/accounts/allocation-defaults").contentType(MediaType.APPLICATION_JSON).bodyValue("""
            {"categories":{"CODEX":{"safeConcurrency":5,"safeRpm":60,"burstCapacity":4,"maxSessions":0,"sessionIdleSeconds":600,
             "cooldownSeconds":0,"maxInflightTokens":1000000,"allocationPriority":100,"tlsProfile":""},"KIRO":null}}
            """).exchange().expectStatus().isOk();
        verify(service).update(any());
        client.put().uri("/admin/accounts/allocation-defaults").contentType(MediaType.APPLICATION_JSON).bodyValue("""
            {"categories":{"CODEX":{"safeConcurrency":10001,"safeRpm":60,"burstCapacity":4,"maxSessions":0,"sessionIdleSeconds":600,
             "cooldownSeconds":0,"maxInflightTokens":1000000,"allocationPriority":100,"tlsProfile":""}}}
            """).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        // Every field is required: a partial policy would silently reset the rest.
        client.put().uri("/admin/accounts/allocation-defaults").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"categories\":{\"CODEX\":{\"safeConcurrency\":5}}}").exchange().expectStatus().isBadRequest();
        client.put().uri("/admin/accounts/allocation-defaults").contentType(MediaType.APPLICATION_JSON).bodyValue("""
            {"categories":{"CLAUDE_OAUTH":{"safeConcurrency":2,"safeRpm":30,"burstCapacity":2,"maxSessions":4,"sessionIdleSeconds":600,
             "cooldownSeconds":0,"maxInflightTokens":1000000,"allocationPriority":100,"tlsProfile":"firefox"}}}
            """).exchange().expectStatus().isBadRequest();
        verify(service, times(1)).update(any());
    }

    @Test void resolvesTheCategoryAndPolicyOfANewAccount() {
        var policy = AccountAllocationCategory.OFFICIAL_API_KEY.builtIn();
        when(service.resolve(any())).thenReturn(Mono.just(new AllocationDefaultsDtos.ResolveResponse("OFFICIAL_API_KEY", policy)));
        client.post().uri("/admin/accounts/allocation-defaults/resolve").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"provider\":\"OpenAI\",\"authMethod\":\"api-key\",\"baseUrl\":\"\"}").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.category").isEqualTo("OFFICIAL_API_KEY").jsonPath("$.data.policy.safeConcurrency").isEqualTo(14)
            .jsonPath("$.data.policy.allocationPriority").isEqualTo(100);
    }

    @Test void requiresAnAdministrator() {
        when(auth.verifyAdmin(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Administrator role is required"));
        client.get().uri("/admin/accounts/allocation-defaults").exchange().expectStatus().isForbidden();
        client.put().uri("/admin/accounts/allocation-defaults").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"categories\":{}}")
            .exchange().expectStatus().isForbidden();
        client.post().uri("/admin/accounts/allocation-defaults/resolve").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
            .exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }
}

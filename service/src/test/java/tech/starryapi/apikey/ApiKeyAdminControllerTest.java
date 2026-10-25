package tech.starryapi.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.common.GlobalExceptionHandler;

class ApiKeyAdminControllerTest {

    private static final AdminPrincipal USER = new AdminPrincipal(
        "user-1",
        "normal-user",
        "USER",
        Instant.now().plusSeconds(3600),
        false
    );
    private static final AdminPrincipal ADMIN = new AdminPrincipal(
        "admin-1",
        "admin",
        "ADMIN",
        Instant.now().plusSeconds(3600),
        false
    );

    @Test
    void normalUserListsOnlyOwnApiKeys() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        MockServerWebExchange exchange = exchange();
        ApiKeyAdminController controller = new ApiKeyAdminController(
            repository,
            mock(ApiKeyService.class),
            adminAuth
        );

        when(adminAuth.verify(exchange)).thenReturn(USER);
        when(repository.findAllByUserIdOrderByCreatedAtDesc("user-1"))
            .thenReturn(Flux.just(apiKey("key-own", "user-1")));

        ApiResponse<List<ApiKeyDtos.ApiKeyResponse>> response = controller.list(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().userId()).isEqualTo("user-1");
        verify(repository).findAllByUserIdOrderByCreatedAtDesc("user-1");
        verify(repository, never()).findAll();
    }

    @Test
    void adminListsAllApiKeys() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        MockServerWebExchange exchange = exchange();
        ApiKeyAdminController controller = new ApiKeyAdminController(
            repository,
            mock(ApiKeyService.class),
            adminAuth
        );

        when(adminAuth.verify(exchange)).thenReturn(ADMIN);
        when(repository.findAll()).thenReturn(Flux.just(
            apiKey("key-admin", "admin-1"),
            apiKey("key-user", "user-1")
        ));

        ApiResponse<List<ApiKeyDtos.ApiKeyResponse>> response = controller.list(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.data()).extracting(ApiKeyDtos.ApiKeyResponse::userId)
            .containsExactly("admin-1", "user-1");
        verify(repository).findAll();
        verify(repository, never()).findAllByUserIdOrderByCreatedAtDesc("admin-1");
    }

    @Test
    void aTierPreferenceThatIsTooLongIsRefusedByTheServiceAsEveryMalformedOneIsAndNotByTheBeanValidation() {
        ApiKeyService service = mock(ApiKeyService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verify(any())).thenReturn(USER);
        // What the service answers to a preference that is not of the form (it checks the length with the form).
        when(service.create(any(), any())).thenReturn(Mono.error(invalidPreference()));
        when(service.update(any(), eq("key-1"), any())).thenReturn(Mono.error(invalidPreference()));
        WebTestClient client = WebTestClient.bindToController(new ApiKeyAdminController(mock(ApiKeyRepository.class), service, adminAuth))
            .controllerAdvice(new GlobalExceptionHandler()).build();
        String tooLong = "x".repeat(41);

        client.post().uri("/admin/api-keys").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"k\",\"modelProvider\":\"Anthropic\",\"tierPreference\":\"" + tooLong + "\"}")
            .exchange().expectStatus().isBadRequest().expectBody()
            .jsonPath("$.error.code").isEqualTo("API_KEY_TIER_PREFERENCE_INVALID");
        client.patch().uri("/admin/api-keys/key-1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"tierPreference\":\"" + tooLong + "\"}")
            .exchange().expectStatus().isBadRequest().expectBody()
            .jsonPath("$.error.code").isEqualTo("API_KEY_TIER_PREFERENCE_INVALID");

        // Both reached the service, which is the one that answers for the form of a preference.
        verify(service).create(any(), org.mockito.ArgumentMatchers.argThat(request -> tooLong.equals(request.tierPreference())));
        verify(service).update(any(), eq("key-1"), org.mockito.ArgumentMatchers.argThat(request -> tooLong.equals(request.tierPreference())));
    }

    private static ApiException invalidPreference() {
        return new ApiException(HttpStatus.BAD_REQUEST, "API_KEY_TIER_PREFERENCE_INVALID", "Choose auto, a tier identifier, L<level> or <level> (1 to 9), optionally followed by +");
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/admin/api-keys"));
    }

    private ApiKeyEntity apiKey(String keyId, String userId) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(keyId);
        entity.setUserId(userId);
        entity.setName(keyId);
        entity.setKeyValue("sk-" + keyId + "-value");
        entity.setKeyPrefix("sk-" + keyId);
        entity.setEnabled(true);
        return entity;
    }
}

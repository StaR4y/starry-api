package xyz.star4y.api.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;

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

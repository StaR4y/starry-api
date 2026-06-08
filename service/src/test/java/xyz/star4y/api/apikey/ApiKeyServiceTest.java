package xyz.star4y.api.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.config.ProxyProperties;

class ApiKeyServiceTest {

    private static final AdminPrincipal USER = new AdminPrincipal(
        "user-1",
        "normal-user",
        "USER",
        Instant.now().plusSeconds(3600),
        false
    );

    @Test
    void createsStandardOpenAiStyleKeyForCurrentUserWithoutKiroPrefix() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.save(any(ApiKeyEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        ApiKeyService service = service(repository);

        ApiKeyDtos.CreatedApiKeyResponse response = service
            .create(USER, new ApiKeyDtos.CreateApiKeyRequest("default", BigDecimal.TEN))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("user-1");
        assertThat(response.key()).startsWith("sk-");
        assertThat(response.key()).doesNotStartWith("sk-kiro-");
        assertThat(response.keyPrefix()).startsWith("sk-");
        assertThat(response.keyPrefix()).doesNotStartWith("sk-kiro-");

        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getKeyValue()).isEqualTo(response.key());
        assertThat(captor.getValue().getKeyHash()).isNotEqualTo(response.key());
    }

    @Test
    void normalUserCannotUpdateAnotherUsersApiKey() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findByKeyIdAndUserId("key-other", "user-1")).thenReturn(Mono.empty());

        ApiKeyService service = service(repository);

        assertThatThrownBy(() -> service.update(
            USER,
            "key-other",
            new ApiKeyDtos.UpdateApiKeyRequest("new-name", true, BigDecimal.ONE)
        ).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("API key not found");

        verify(repository).findByKeyIdAndUserId("key-other", "user-1");
        verify(repository, never()).findByKeyId(eq("key-other"));
        verify(repository, never()).save(any(ApiKeyEntity.class));
    }

    private ApiKeyService service(ApiKeyRepository repository) {
        return new ApiKeyService(
            repository,
            mock(R2dbcEntityTemplate.class),
            new ProxyProperties()
        );
    }
}

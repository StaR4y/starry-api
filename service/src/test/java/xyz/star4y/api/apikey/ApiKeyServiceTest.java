package xyz.star4y.api.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import xyz.star4y.api.config.ProxyProperties;

class ApiKeyServiceTest {

    @Test
    void createsStandardOpenAiStyleKeyWithoutKiroPrefix() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.save(any(ApiKeyEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        ApiKeyService service = new ApiKeyService(
            repository,
            mock(R2dbcEntityTemplate.class),
            new ProxyProperties()
        );

        ApiKeyDtos.CreatedApiKeyResponse response = service
            .create(new ApiKeyDtos.CreateApiKeyRequest("default", BigDecimal.TEN))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.key()).startsWith("sk-");
        assertThat(response.key()).doesNotStartWith("sk-kiro-");
        assertThat(response.keyPrefix()).startsWith("sk-");
        assertThat(response.keyPrefix()).doesNotStartWith("sk-kiro-");
    }
}

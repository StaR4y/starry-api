package tech.starryapi.apikey;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.DefaultModelCatalogProvider;

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
            .create(USER, new ApiKeyDtos.CreateApiKeyRequest("default", BigDecimal.TEN, " openai "))
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
        assertThat(captor.getValue().getBillingMinimumCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getModelProvider()).isEqualTo("OpenAI");
    }

    @Test
    void rejectsMissingLegacyOrUnknownProviderWhenCreatingKey() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApiKeyService service = service(repository);

        assertThatThrownBy(() -> service.create(
            USER,
            new ApiKeyDtos.CreateApiKeyRequest("missing", null, " ")
        ).block()).isInstanceOfSatisfying(ApiException.class, error ->
            assertThat(error.getCode()).isEqualTo("API_KEY_MODEL_PROVIDER_REQUIRED")
        );
        assertThatThrownBy(() -> service.create(
            USER,
            new ApiKeyDtos.CreateApiKeyRequest("legacy", null, "*")
        ).block()).isInstanceOfSatisfying(ApiException.class, error ->
            assertThat(error.getCode()).isEqualTo("API_KEY_MODEL_PROVIDER_REQUIRED")
        );
        assertThatThrownBy(() -> service.create(
            USER,
            new ApiKeyDtos.CreateApiKeyRequest("unknown", null, "Unknown Provider")
        ).block()).isInstanceOfSatisfying(ApiException.class, error ->
            assertThat(error.getCode()).isEqualTo("API_KEY_MODEL_PROVIDER_INVALID")
        );

        verify(repository, never()).save(any(ApiKeyEntity.class));
    }

    @Test
    void normalUserCannotUpdateAnotherUsersApiKey() {
        ApiKeyUpdateHarness harness = apiKeyUpdateService(Mono.empty());

        assertThatThrownBy(() -> harness.service().update(
            USER,
            "key-other",
            new ApiKeyDtos.UpdateApiKeyRequest("new-name", true, BigDecimal.ONE, "Anthropic")
        ).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("API key not found");

        verify(harness.spec()).bind("isAdmin", false);
        verify(harness.spec()).bind("userId", "user-1");
        verify(harness.repository(), never()).save(any(ApiKeyEntity.class));
    }

    @Test
    void managementUpdateTouchesOnlyMutableConfigurationColumns() {
        ApiKeyEntity updated = new ApiKeyEntity();
        updated.setKeyId("key-1");
        updated.setUserId("user-1");
        updated.setName("renamed");
        updated.setEnabled(true);
        updated.setTotalRequests(37L);
        updated.setTotalCredits(new BigDecimal("12.5"));
        ApiKeyUpdateHarness harness = apiKeyUpdateService(Mono.just(updated));

        ApiKeyEntity result = harness.service().update(
            USER,
            "key-1",
            new ApiKeyDtos.UpdateApiKeyRequest("renamed", null, null, "OpenAI")
        ).block();

        assertThat(result).isSameAs(updated);
        String setClause = harness.sql().substring(harness.sql().indexOf("SET"), harness.sql().indexOf("WHERE"));
        assertThat(setClause)
            .contains("name =", "enabled =", "credits_limit =", "model_provider =", "updated_at =")
            .doesNotContain("total_requests", "total_credits", "total_input_tokens", "total_output_tokens", "last_used_at");
        assertThat(harness.sql()).contains("AND (:isAdmin OR user_id = :userId)", "RETURNING *");
        verify(harness.spec()).bind("setName", true);
        verify(harness.spec()).bind("setEnabled", false);
        verify(harness.spec()).bind("setCreditsLimit", false);
        verify(harness.spec()).bind("setModelProvider", true);
        verify(harness.spec()).bind("modelProvider", "OpenAI");
        verify(harness.repository(), never()).save(any(ApiKeyEntity.class));
    }

    @Test
    void accountsUsageWithOneKeyScopedAtomicPostgreSqlStatement() {
        SqlUsageHarness harness = sqlUsageService();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "default",
            null,
            BigDecimal.ZERO,
            BigDecimal.TEN
        );

        harness.service().accountUsage(principal, "request-123", new BigDecimal("0.25"), 2, 3).block();

        assertThat(harness.sql()).contains(
            "starry_api.settle_api_usage",
            ":requestHash", ":credits", ":inputTokens", ":outputTokens"
        ).doesNotContain(":userId", ":chargeBalance", "GREATEST(user_account.balance -");
        assertThat(harness.requestHash()).isEqualTo(Hashing.sha256("5:key-1request-123"));
        assertThat(harness.requestHash()).isNotEqualTo(Hashing.sha256("request-123"));
        verify(harness.databaseClient()).sql(anyString());
    }

    @Test
    void mapsAtomicSettlementRejectionWithoutTreatingItAsSuccess() {
        SqlUsageHarness harness = sqlUsageService("INSUFFICIENT_BALANCE");
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "default",
            null,
            BigDecimal.ZERO,
            new BigDecimal("0.10")
        );

        assertThatThrownBy(() -> harness.service().accountUsage(
            principal,
            "request-123",
            new BigDecimal("0.25"),
            2,
            3
        ).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.PAYMENT_REQUIRED);
                assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
            });
    }

    @Test
    void balancePrecheckDefersCodingPlanEligibilityToReservation() {
        ApiKeyService service = service(mock(ApiKeyRepository.class));
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "default",
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "OpenAI",
            true,
            "CODING_PLAN"
        );

        assertThatCode(() -> service.ensureBalanceAvailable(principal).block()).doesNotThrowAnyException();
    }

    @Test
    void balancePrecheckRejectsZeroBalanceWithoutSubscription() {
        ApiKeyService service = service(mock(ApiKeyRepository.class));
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "default",
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "OpenAI",
            false
        );

        assertThatThrownBy(() -> service.ensureBalanceAvailable(principal).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
            });
    }

    private ApiKeyService service(ApiKeyRepository repository) {
        return new ApiKeyService(
            repository,
            mock(R2dbcEntityTemplate.class),
            new ProxyProperties(),
            new DefaultModelCatalogProvider()
        );
    }

    @SuppressWarnings("unchecked")
    private ApiKeyUpdateHarness apiKeyUpdateService(Mono<ApiKeyEntity> result) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<ApiKeyEntity> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, ApiKeyEntity>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenReturn(result);
        return new ApiKeyUpdateHarness(
            new ApiKeyService(
                repository,
                template,
                new ProxyProperties(),
                new DefaultModelCatalogProvider()
            ),
            repository,
            spec,
            sql
        );
    }

    @SuppressWarnings("unchecked")
    private SqlUsageHarness sqlUsageService() {
        return sqlUsageService("ACCOUNTED");
    }

    @SuppressWarnings("unchecked")
    private SqlUsageHarness sqlUsageService(String accountingStatus) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<String> requestHash = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenAnswer(invocation -> {
            if ("requestHash".equals(invocation.getArgument(0))) {
                requestHash.set(invocation.getArgument(1));
            }
            return spec;
        });
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(fetch.one()).thenReturn(Mono.just(Map.of("accounting_status", accountingStatus)));
        when(spec.fetch()).thenReturn(fetch);
        return new SqlUsageHarness(
            new ApiKeyService(
                mock(ApiKeyRepository.class),
                template,
                new ProxyProperties(),
                new DefaultModelCatalogProvider()
            ),
            databaseClient,
            sql,
            requestHash
        );
    }

    private record SqlUsageHarness(
        ApiKeyService service,
        DatabaseClient databaseClient,
        AtomicReference<String> capturedSql,
        AtomicReference<String> capturedRequestHash
    ) {
        String sql() {
            return capturedSql.get();
        }

        String requestHash() {
            return capturedRequestHash.get();
        }
    }

    private record ApiKeyUpdateHarness(
        ApiKeyService service,
        ApiKeyRepository repository,
        DatabaseClient.GenericExecuteSpec spec,
        AtomicReference<String> capturedSql
    ) {
        String sql() {
            return capturedSql.get();
        }
    }
}

package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.apikey.ApiKeyDtos;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyRepository;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogProvider;

/**
 * The source pool preference of an API key against the real column: what the service stores is what the column accepts,
 * an update changes it only when asked, the proxy meets it in the principal of the key, and (unlike the provider and the
 * billing source) it can be changed while the key has a request in flight.
 */
@SpringBootTest(classes = ApiKeyTierPreferencePostgreSqlIntegrationTest.TestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.lazy-initialization=false", "starry.stats.aggregation-enabled=false",
        "starry.model-pricing.remote-refresh-enabled=false", "starry.model-ranking.remote-refresh-enabled=false"
    })
@Timeout(60)
class ApiKeyTierPreferencePostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    @Configuration
    @EnableAutoConfiguration
    @EnableR2dbcRepositories(basePackageClasses = ApiKeyRepository.class)
    @Import(ApiKeyService.class)
    static class TestConfiguration {
        @Bean ProxyProperties proxyProperties() { return new ProxyProperties(); }
        @Bean ModelCatalogProvider modelCatalog() { return new DefaultModelCatalogProvider(); }
    }

    @Autowired DatabaseClient db;
    @Autowired ApiKeyService keys;

    @Test
    void aNewKeyPrefersNothingAndTheOwnerSetsAndClearsThePreference() {
        AdminPrincipal owner = user("10");
        ApiKeyDtos.CreatedApiKeyResponse created = keys.create(owner, new ApiKeyDtos.CreateApiKeyRequest("plain", null, "Anthropic")).block();

        assertThat(stored(created.keyId())).isEqualTo("auto");
        assertThat(authenticated(created.key()).tierPreference()).isEqualTo("auto");

        keys.update(owner, created.keyId(), update(" Kiro+ ")).block();
        assertThat(stored(created.keyId())).isEqualTo("kiro+");
        assertThat(authenticated(created.key()).tierPreference()).isEqualTo("kiro+");

        // An update that does not mention it leaves it, whatever else it changes.
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest("renamed", false, null, null)).block();
        assertThat(stored(created.keyId())).isEqualTo("kiro+");

        keys.update(owner, created.keyId(), update("auto")).block();
        assertThat(stored(created.keyId())).isEqualTo("auto");
    }

    @Test
    void aKeyCanBeCreatedWithAPreference() {
        AdminPrincipal owner = user("10");

        ApiKeyDtos.CreatedApiKeyResponse created = keys.create(owner,
            new ApiKeyDtos.CreateApiKeyRequest("kiro", null, "Anthropic", "BALANCE", "L2")).block();

        assertThat(stored(created.keyId())).isEqualTo("l2");
        assertThat(authenticated(created.key()).tierPreference()).isEqualTo("l2");
    }

    @Test
    void everyPreferenceTheServiceStoresIsOneTheColumnAcceptsAndTheOthersNeverReachIt() {
        AdminPrincipal owner = user("10");
        ApiKeyDtos.CreatedApiKeyResponse created = keys.create(owner, new ApiKeyDtos.CreateApiKeyRequest("k", null, "Anthropic")).block();

        for (String preference : new String[] {"auto", "kiro", "kiro+", "official-2", "l1", "L9+", "3", "9+", "0kiro", "x".repeat(32)}) {
            keys.update(owner, created.keyId(), update(preference)).block();
            assertThat(stored(created.keyId())).isEqualTo(preference.toLowerCase());
        }
        // A level written with zeros is saved in the plain spelling, which is the only one the column's level branch takes.
        for (String[] padded : new String[][] {{"L02", "l2"}, {"02", "2"}, {"002+", "2+"}, {"l007", "l7"}, {"l01", "l1"}, {"009+", "9+"}}) {
            keys.update(owner, created.keyId(), update(padded[0])).block();
            assertThat(stored(created.keyId())).as(padded[0]).isEqualTo(padded[1]);
        }
        keys.update(owner, created.keyId(), update("x".repeat(32))).block();
        for (String preference : new String[] {"ki ro", "kiro++", "l0", "00", "010", "10", "auto+", "-kiro", "x".repeat(33), "x".repeat(41), "x".repeat(300)}) {
            assertThatThrownBy(() -> keys.update(owner, created.keyId(), update(preference)).block())
                .as(preference)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("API_KEY_TIER_PREFERENCE_INVALID");
                });
        }
        assertThat(stored(created.keyId())).isEqualTo("x".repeat(32));
    }

    @Test
    void aPreferenceDoesNotFollowTheKeyToAnotherProvider() {
        AdminPrincipal owner = user("10");
        ApiKeyDtos.CreatedApiKeyResponse created = keys.create(owner, new ApiKeyDtos.CreateApiKeyRequest("k", null, "Anthropic")).block();
        keys.update(owner, created.keyId(), update("official")).block();

        // Saying the provider the key already has, in whatever spelling, is no move.
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "anthropic")).block();
        assertThat(provider(created.keyId())).isEqualTo("Anthropic");
        assertThat(stored(created.keyId())).isEqualTo("official");
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest("renamed", null, null, null)).block();
        assertThat(stored(created.keyId())).isEqualTo("official");

        // Moving it to another vendor without choosing there leaves it without a preference, not pinned to a pool of the
        // new vendor that happens to be called the same, or to the one at the same level.
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "OpenAI")).block();
        assertThat(provider(created.keyId())).isEqualTo("OpenAI");
        assertThat(stored(created.keyId())).isEqualTo("auto");
        assertThat(authenticated(created.key()).tierPreference()).isEqualTo("auto");

        // A preference given in the same request is the one chosen for the new vendor, and a level goes the same way.
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "Anthropic", null, null, "kiro")).block();
        assertThat(stored(created.keyId())).isEqualTo("kiro");
        keys.update(owner, created.keyId(), update("l2+")).block();
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "OpenAI")).block();
        assertThat(stored(created.keyId())).isEqualTo("auto");
        keys.update(owner, created.keyId(), update("codex")).block();
        keys.update(owner, created.keyId(), new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "Anthropic", null, null, "auto")).block();
        assertThat(stored(created.keyId())).isEqualTo("auto");
    }

    @Test
    void anotherUserCannotChangeThePreferenceOfAKey() {
        AdminPrincipal owner = user("10");
        AdminPrincipal other = user("10");
        ApiKeyDtos.CreatedApiKeyResponse created = keys.create(owner, new ApiKeyDtos.CreateApiKeyRequest("k", null, "Anthropic")).block();

        assertThatThrownBy(() -> keys.update(other, created.keyId(), update("kiro")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("API_KEY_NOT_FOUND"));

        assertThat(stored(created.keyId())).isEqualTo("auto");
    }

    @Test
    void thePreferenceCanBeChangedWhileTheKeyHasARequestInFlightUnlikeItsProvider() {
        AdminPrincipal owner = user("10");
        ApiKeyDtos.CreatedApiKeyResponse created = keys.create(owner, new ApiKeyDtos.CreateApiKeyRequest("k", null, "Anthropic")).block();
        ApiKeyPrincipal principal = authenticated(created.key());
        keys.reserveUsage(principal, "in-flight", new BigDecimal("1"), "{}").block();

        keys.update(owner, created.keyId(), update("kiro")).block();

        assertThat(stored(created.keyId())).isEqualTo("kiro");
        assertThatThrownBy(() -> keys.update(owner, created.keyId(),
            new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "OpenAI")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("API_KEY_BILLING_BUSY"));
        assertThat(stored(created.keyId())).isEqualTo("kiro");
    }

    @Test
    void aLegacyKeyThatAnyProviderMayCallHasNoPreferenceUntilItIsTiedToOneProvider() {
        AdminPrincipal owner = user("10");
        String keyId = legacyKey(owner);

        // A pool is one vendor's, and the key may call them all: nothing is stored that no request would read.
        assertThatThrownBy(() -> keys.update(owner, keyId, update("kiro")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("API_KEY_TIER_PREFERENCE_INVALID");
            });
        assertThat(stored(keyId)).isEqualTo("auto");

        // Everything else about the key can still be changed, and auto is no preference.
        keys.update(owner, keyId, update("auto")).block();
        keys.update(owner, keyId, new ApiKeyDtos.UpdateApiKeyRequest("renamed", null, null, null)).block();
        assertThat(stored(keyId)).isEqualTo("auto");

        // Tied to a provider in the same request, it can have one; and once tied, a request of the preference alone is fine.
        keys.update(owner, keyId, new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, "Anthropic", null, null, "kiro")).block();
        assertThat(stored(keyId)).isEqualTo("kiro");
        keys.update(owner, keyId, update("L2+")).block();
        assertThat(stored(keyId)).isEqualTo("l2+");
    }

    @Test
    void aKeyThatIsNotTheCallersIsStillNotFoundWhateverItsProviderIs() {
        AdminPrincipal owner = user("10");
        AdminPrincipal other = user("10");
        String legacy = legacyKey(owner);

        assertThatThrownBy(() -> keys.update(other, legacy, update("kiro")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("API_KEY_NOT_FOUND"));
        assertThatThrownBy(() -> keys.update(owner, "no-such-key", update("kiro")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("API_KEY_NOT_FOUND"));
    }

    @Test
    void theCreationResponseGivesThePreferenceTheKeyWasCreatedWith() {
        AdminPrincipal owner = user("10");

        ApiKeyDtos.CreatedApiKeyResponse preferring = keys.create(owner,
            new ApiKeyDtos.CreateApiKeyRequest("kiro", null, "Anthropic", "BALANCE", "L2")).block();
        ApiKeyDtos.CreatedApiKeyResponse indifferent = keys.create(owner,
            new ApiKeyDtos.CreateApiKeyRequest("plain", null, "Anthropic")).block();

        assertThat(preferring.tierPreference()).isEqualTo(stored(preferring.keyId())).isEqualTo("l2");
        assertThat(indifferent.tierPreference()).isNull();
    }

    // ---- fixtures ----

    /** A key from before every key had a provider (the column's default, {@code *}): the one the old data holds, which the service no longer creates. */
    private String legacyKey(AdminPrincipal owner) {
        String id = "legacy-" + UUID.randomUUID();
        db.sql("INSERT INTO api_keys(key_id, user_id, name, key_hash, key_prefix, model_provider)"
                + " VALUES (:id, :user, 'legacy', repeat(md5(:id), 2), 'sk-legacy', '*')")
            .bind("id", id).bind("user", owner.userId()).fetch().rowsUpdated().block();
        return id;
    }

    private static ApiKeyDtos.UpdateApiKeyRequest update(String preference) {
        return new ApiKeyDtos.UpdateApiKeyRequest(null, null, null, null, null, null, preference);
    }

    private ApiKeyPrincipal authenticated(String key) {
        return keys.authenticate(MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/messages").header("Authorization", "Bearer " + key))).block();
    }

    private String provider(String keyId) {
        return db.sql("SELECT model_provider FROM api_keys WHERE key_id = :id").bind("id", keyId)
            .map((row, metadata) -> row.get("model_provider", String.class)).one().block();
    }

    private String stored(String keyId) {
        return db.sql("SELECT tier_preference FROM api_keys WHERE key_id = :id").bind("id", keyId)
            .map((row, metadata) -> row.get("tier_preference", String.class)).one().block();
    }

    private AdminPrincipal user(String balance) {
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO admin_users(user_id,username,role,balance,password_hash,password_salt,enabled,first_login)"
                + " VALUES(:id,:id,'USER',:balance,repeat('a',64),repeat('b',32),true,false)")
            .bind("id", id).bind("balance", new BigDecimal(balance)).fetch().rowsUpdated().block();
        return new AdminPrincipal(id, id, "USER", Instant.now().plusSeconds(3600), false);
    }
}

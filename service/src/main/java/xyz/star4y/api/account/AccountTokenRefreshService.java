package xyz.star4y.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.Hashing;
import xyz.star4y.api.platform.upstream.UpstreamException;

@Service
public class AccountTokenRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AccountTokenRefreshService.class);
    private static final int SCHEDULED_REFRESH_CONCURRENCY = 4;
    private static final String KIRO_AUTH_ENDPOINT = "https://prod.us-east-1.auth.desktop.kiro.dev";
    private static final String CODEX_TOKEN_REFRESH_URL = "https://auth.openai.com/oauth/token";
    private static final String CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String KIRO_VERSION = "0.12.155";
    private static final String AWS_SDK_VERSION = "1.0.34";
    private static final String AWS_STREAMING_API_VERSION = "1.0.34";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ProxyAccountRepository repository;
    private final AccountPoolService accountPoolService;
    private final Map<String, Mono<ProxyAccountEntity>> inFlightRefreshes = new ConcurrentHashMap<>();

    public AccountTokenRefreshService(
        ObjectMapper objectMapper,
        WebClient kiroWebClient,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService
    ) {
        this.objectMapper = objectMapper;
        this.webClient = kiroWebClient;
        this.repository = repository;
        this.accountPoolService = accountPoolService;
    }

    public Mono<ProxyAccountEntity> refresh(ProxyAccountEntity account) {
        if (account.getAccountId() == null || account.getAccountId().isBlank()) {
            return Mono.error(refreshError("Account ID is empty"));
        }
        return inFlightRefreshes.computeIfAbsent(account.getAccountId(), ignored -> refreshInternal(account)
            .cache()
            .doFinally(signal -> inFlightRefreshes.remove(account.getAccountId())));
    }

    @Scheduled(
        fixedDelayString = "${kiro.proxy.token-refresh-interval-ms:3600000}",
        initialDelayString = "${kiro.proxy.token-refresh-initial-delay-ms:60000}"
    )
    public void scheduledRefresh() {
        refreshEligibleAccounts()
            .subscribe(
                summary -> log.info(
                    "Scheduled upstream token refresh completed: total={}, eligible={}, refreshed={}, failed={}",
                    summary.total(),
                    summary.eligible(),
                    summary.refreshed(),
                    summary.failed()
                ),
                error -> log.warn("Scheduled upstream token refresh failed", error)
            );
    }

    public Mono<TokenRefreshSummary> refreshEligibleAccounts() {
        return repository.findAllByEnabledTrue()
            .collectList()
            .flatMap(accounts -> {
                List<ProxyAccountEntity> eligible = accounts.stream()
                    .filter(this::canRefresh)
                    .toList();
                if (eligible.isEmpty()) {
                    return Mono.just(new TokenRefreshSummary(accounts.size(), 0, 0, 0));
                }
                return Flux.fromIterable(eligible)
                    .flatMap(account -> refresh(account)
                        .thenReturn(Boolean.TRUE)
                        .onErrorResume(error -> {
                            log.warn(
                                "Scheduled upstream token refresh failed for account {}: {}",
                                account.getAccountId(),
                                error.getMessage()
                            );
                            return Mono.just(Boolean.FALSE);
                        }), SCHEDULED_REFRESH_CONCURRENCY)
                    .collectList()
                    .map(results -> {
                        int refreshed = (int) results.stream().filter(Boolean::booleanValue).count();
                        return new TokenRefreshSummary(accounts.size(), eligible.size(), refreshed, eligible.size() - refreshed);
                    });
            });
    }

    private Mono<ProxyAccountEntity> refreshInternal(ProxyAccountEntity account) {
        String refreshToken = clean(account.getRefreshToken());
        if (refreshToken == null) {
            return Mono.error(refreshError("Missing refreshToken"));
        }
        if (AccountPlatforms.isCodex(account)) {
            return refreshCodexToken(refreshToken).flatMap(result -> saveRefreshedToken(account, result));
        }
        boolean social = "social".equalsIgnoreCase(account.getAuthMethod());
        if (!social && (clean(account.getClientId()) == null || clean(account.getClientSecret()) == null)) {
            return Mono.error(refreshError("Missing OIDC refresh credentials: clientId/clientSecret"));
        }

        Mono<TokenRefreshResult> refreshed = social
            ? refreshSocialToken(account, refreshToken)
            : refreshOidcToken(account, refreshToken);
        return refreshed.flatMap(result -> saveRefreshedToken(account, result));
    }

    public boolean canRefresh(ProxyAccountEntity account) {
        if (clean(account.getRefreshToken()) == null) {
            return false;
        }
        if (AccountPlatforms.isCodex(account)) {
            return true;
        }
        if ("social".equalsIgnoreCase(account.getAuthMethod())) {
            return true;
        }
        return clean(account.getClientId()) != null && clean(account.getClientSecret()) != null;
    }

    private Mono<TokenRefreshResult> refreshCodexToken(String refreshToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("client_id", CODEX_CLIENT_ID);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);

        return webClient.post()
            .uri(CODEX_TOKEN_REFRESH_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(text -> Mono.error(refreshError(
                            "Codex token refresh failed " + response.statusCode().value() + ": " + text
                        )));
                }
                return response.bodyToMono(JsonNode.class).map(json -> tokenResult(json, refreshToken));
            });
    }

    private Mono<TokenRefreshResult> refreshOidcToken(ProxyAccountEntity account, String refreshToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("clientId", account.getClientId());
        body.put("clientSecret", account.getClientSecret());
        body.put("refreshToken", refreshToken);
        body.put("grantType", "refresh_token");

        return webClient.post()
            .uri("https://oidc." + region(account) + ".amazonaws.com/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(text -> Mono.error(refreshError("OIDC refresh failed " + response.statusCode().value() + ": " + text)));
                }
                return response.bodyToMono(JsonNode.class).map(json -> tokenResult(json, refreshToken));
            });
    }

    private Mono<TokenRefreshResult> refreshSocialToken(ProxyAccountEntity account, String refreshToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("refreshToken", refreshToken);

        return webClient.post()
            .uri(KIRO_AUTH_ENDPOINT + "/refreshToken")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, kiroUserAgent(machineId(account)))
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(text -> Mono.error(refreshError("Social token refresh failed " + response.statusCode().value() + ": " + text)));
                }
                return response.bodyToMono(JsonNode.class).map(json -> tokenResult(json, refreshToken));
            });
    }

    private Mono<ProxyAccountEntity> saveRefreshedToken(ProxyAccountEntity account, TokenRefreshResult result) {
        if (result.accessToken() == null || result.accessToken().isBlank()) {
            return Mono.error(refreshError("Refresh response does not contain accessToken"));
        }
        String accountId = account.getAccountId();
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(refreshError("Account not found while saving refreshed token")))
            .flatMap(entity -> {
                entity.setAccessToken(result.accessToken());
                entity.setRefreshToken(firstNonBlank(result.refreshToken(), entity.getRefreshToken()));
                entity.setIdToken(firstNonBlank(result.idToken(), entity.getIdToken()));
                return repository.save(entity);
            })
            .flatMap(saved -> accountPoolService.refreshCache().thenReturn(saved));
    }

    private TokenRefreshResult tokenResult(JsonNode json, String fallbackRefreshToken) {
        return new TokenRefreshResult(
            firstNonBlank(json.path("accessToken").asText(null), json.path("access_token").asText(null)),
            firstNonBlank(json.path("refreshToken").asText(null), json.path("refresh_token").asText(null), fallbackRefreshToken),
            firstNonBlank(json.path("idToken").asText(null), json.path("id_token").asText(null))
        );
    }

    private UpstreamException refreshError(String message) {
        return new UpstreamException(403, "Automatic token refresh failed: " + message, false);
    }

    private String region(ProxyAccountEntity account) {
        return firstNonBlank(account.getRegion(), "us-east-1");
    }

    private String machineId(ProxyAccountEntity account) {
        return firstNonBlank(account.getMachineId(), Hashing.sha256("kiro-device-" + account.getAccountId()));
    }

    private String kiroUserAgent(String machineId) {
        String os = System.getProperty("os.name", "linux").toLowerCase().contains("mac") ? "macos" : "linux";
        String release = System.getProperty("os.version", "0.0.0");
        String javaVersion = System.getProperty("java.version", "17");
        return "aws-sdk-js/%s ua/2.1 os/%s#%s lang/js md/nodejs#%s api/codewhispererstreaming#%s m/E KiroIDE-%s-%s"
            .formatted(AWS_SDK_VERSION, os, release, javaVersion, AWS_STREAMING_API_VERSION, KIRO_VERSION, machineId);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) {
                return cleaned;
            }
        }
        return null;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record TokenRefreshResult(String accessToken, String refreshToken, String idToken) {
    }

    public record TokenRefreshSummary(int total, int eligible, int refreshed, int failed) {
    }
}

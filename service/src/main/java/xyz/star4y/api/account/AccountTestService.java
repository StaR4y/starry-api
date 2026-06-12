package xyz.star4y.api.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.model.ModelDtos;
import xyz.star4y.api.platform.anthropic.AnthropicModelCatalog;
import xyz.star4y.api.platform.catalog.OpenRouterModelIds;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.anthropic.AnthropicUpstreamClient;
import xyz.star4y.api.platform.kiro.KiroPayloadFactory;
import xyz.star4y.api.platform.kiro.KiroUpstreamClient;
import xyz.star4y.api.platform.openai.OpenAiAccountTestClient;
import xyz.star4y.api.platform.upstream.UpstreamException;

@Service
public class AccountTestService {

    private static final int DEFAULT_CONCURRENCY = 3;
    private static final int MAX_CONCURRENCY = 8;
    private static final String DEFAULT_MODEL = "auto";
    private static final String DEFAULT_PROMPT = "Reply OK in one short sentence.";
    private static final String DEFAULT_OPENAI_API_KEY_TEST_MODEL = OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL;
    private static final String DEFAULT_ANTHROPIC_API_KEY_TEST_MODEL = "claude-sonnet-4.5";
    private static final List<String> OPENAI_STYLE_TEST_MODEL_IDS = List.of(
        "gpt-5.5",
        "gpt-5.4",
        "gpt-5.4-mini",
        "gpt-5.4-nano",
        "gpt-5.4-pro",
        "gpt-5.3-chat",
        "gpt-5.2",
        "gpt-5.2-chat",
        "gpt-5.2-pro",
        "gpt-5.1",
        "gpt-5.1-chat",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "gpt-5-pro",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4o",
        "gpt-4o-mini"
    );
    private static final List<AccountDtos.TestModelOption> KIRO_TEST_MODELS = List.of(
        option("auto", "auto"),
        option("claude-sonnet-4.5", "claude-sonnet-4.5"),
        option("simple-task", "simple-task"),
        option("claude-haiku-4.5", "claude-haiku-4.5"),
        option("claude-sonnet-4", "claude-sonnet-4"),
        option("claude-opus-4.5", "claude-opus-4.5")
    );
    private static final List<AccountDtos.TestModelOption> OPENAI_TEST_MODELS = testModelOptions(
        option("auto (" + DEFAULT_OPENAI_API_KEY_TEST_MODEL + ")", DEFAULT_MODEL),
        OPENAI_STYLE_TEST_MODEL_IDS
    );
    private static final List<AccountDtos.TestModelOption> GOOGLE_TEST_MODELS = openRouterOptions(
        option("auto (google/gemini-2.5-flash)", DEFAULT_MODEL),
        "google",
        OpenRouterModelIds.GOOGLE
    );
    private static final List<AccountDtos.TestModelOption> ANTHROPIC_TEST_MODELS = testModelOptions(
        option("auto (" + DEFAULT_ANTHROPIC_API_KEY_TEST_MODEL + ")", DEFAULT_MODEL),
        unprefixedModelIds(OpenRouterModelIds.ANTHROPIC)
    );
    private static final List<AccountDtos.TestModelOption> CODEX_TEST_MODELS = testModelOptions(
        option("auto (" + OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL + ")", DEFAULT_MODEL),
        OPENAI_STYLE_TEST_MODEL_IDS
    );

    private final ObjectMapper objectMapper;
    private final ProxyAccountRepository repository;
    private final AccountPoolService accountPoolService;
    private final KiroPayloadFactory payloadFactory;
    private final KiroUpstreamClient upstreamClient;
    private final OpenAiAccountTestClient openAiAccountTestClient;
    private final AnthropicUpstreamClient anthropicUpstreamClient;

    public AccountTestService(
        ObjectMapper objectMapper,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiAccountTestClient openAiAccountTestClient,
        AnthropicUpstreamClient anthropicUpstreamClient
    ) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.accountPoolService = accountPoolService;
        this.payloadFactory = payloadFactory;
        this.upstreamClient = upstreamClient;
        this.openAiAccountTestClient = openAiAccountTestClient;
        this.anthropicUpstreamClient = anthropicUpstreamClient;
    }

    public Mono<AccountDtos.TestAccountsResponse> test(AccountDtos.TestAccountsRequest request) {
        AccountDtos.TestAccountsRequest normalized = request == null
            ? new AccountDtos.TestAccountsRequest(null, true, null, null, null, true)
            : request;
        Instant startedAt = Instant.now();
        int concurrency = clamp(Optional.ofNullable(normalized.maxConcurrency()).orElse(DEFAULT_CONCURRENCY), 1, MAX_CONCURRENCY);

        return targets(normalized)
            .flatMap(target -> testTarget(target, normalized), concurrency)
            .collectList()
            .map(items -> summarize(startedAt, items));
    }

    public AccountDtos.TestModelOptionsResponse modelOptions() {
        return new AccountDtos.TestModelOptionsResponse(
            KIRO_TEST_MODELS,
            OPENAI_TEST_MODELS,
            GOOGLE_TEST_MODELS,
            ANTHROPIC_TEST_MODELS,
            CODEX_TEST_MODELS
        );
    }

    private Flux<AccountTarget> targets(AccountDtos.TestAccountsRequest request) {
        List<String> ids = request.accountIds() == null
            ? List.of()
            : request.accountIds().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return repository.findAll().map(AccountTarget::found);
        }
        return Flux.fromIterable(ids)
            .flatMap(id -> repository.findByAccountId(id)
                .map(AccountTarget::found)
                .defaultIfEmpty(AccountTarget.missing(id)));
    }

    private Mono<AccountDtos.TestAccountItemResponse> testTarget(AccountTarget target, AccountDtos.TestAccountsRequest request) {
        if (target.account() == null) {
            return Mono.just(item(target.accountId(), null, "FAILED", "Account not found", "Account not found", 404, 0L, Instant.now(), firstNonBlank(request.model(), DEFAULT_MODEL)));
        }

        ProxyAccountEntity account = target.account();
        String requestedModel = firstNonBlank(request.model(), DEFAULT_MODEL);
        String model = testModel(requestedModel, account);
        boolean updateStatus = !Boolean.FALSE.equals(request.updateStatus());
        String skipReason = skipReason(account, request, updateStatus);
        if (skipReason != null) {
            return Mono.just(item(account.getAccountId(), account.getEmail(), "SKIPPED", skipReason, skipReason, null, 0L, Instant.now(), model));
        }

        Instant startedAt = Instant.now();
        Mono<PlatformCompletionResult> call = testAccount(account, model, firstNonBlank(request.prompt(), DEFAULT_PROMPT));

        return call
            .flatMap(result -> accountPoolService.recordTestSuccess(account.getAccountId(), result.usage().totalTokens(), updateStatus)
                .thenReturn(item(
                    account.getAccountId(),
                    account.getEmail(),
                    "SUCCESS",
                    truncate(firstNonBlank(result.content(), "OK")),
                    firstNonBlank(result.content(), "OK"),
                    200,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    Instant.now(),
                    model
                )))
            .onErrorResume(error -> {
                Throwable failure = rootFailure(error);
                int statusCode = statusCode(failure);
                String detail = firstNonBlank(failure.getMessage(), failure.getClass().getName());
                AccountPoolService.UpstreamErrorType type = recoverableStatus(statusCode)
                    ? AccountPoolService.UpstreamErrorType.RECOVERABLE
                    : AccountPoolService.UpstreamErrorType.FATAL;
                return accountPoolService.recordTestFailure(account.getAccountId(), type, statusCode == 0 ? null : statusCode, detail, updateStatus)
                    .thenReturn(item(
                        account.getAccountId(),
                        account.getEmail(),
                        "FAILED",
                        truncate(detail),
                        detail,
                        statusCode == 0 ? null : statusCode,
                        Duration.between(startedAt, Instant.now()).toMillis(),
                        Instant.now(),
                        model
                    ));
            });
    }

    private Mono<PlatformCompletionResult> testKiro(
        ProxyAccountEntity account,
        String model,
        String prompt
    ) {
        ObjectNode chat = testChatRequest(model, prompt);
        ObjectNode payload = payloadFactory.fromOpenAiChat(
            chat,
            account,
            model,
            "AI_EDITOR",
            "admin-test:" + account.getAccountId() + ":" + UUID.randomUUID()
        );

        return upstreamClient.complete(account, payload, model);
    }

    private Mono<PlatformCompletionResult> testAccount(
        ProxyAccountEntity account,
        String model,
        String prompt
    ) {
        if (AccountPlatforms.isOpenAi(account)) {
            return openAiAccountTestClient.test(account, model, prompt);
        }
        if (AccountPlatforms.isAnthropicApiKey(account)) {
            return anthropicUpstreamClient.complete(account, testChatRequest(model, prompt), model);
        }
        if (AccountPlatforms.isGoogleApiKey(account)) {
            return Mono.error(new UpstreamException(
                501,
                "Direct " + account.getProvider() + " account testing is not implemented yet",
                false
            ));
        }
        return testKiro(account, model, prompt);
    }

    private ObjectNode testChatRequest(String model, String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 24);
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return root;
    }

    private String skipReason(ProxyAccountEntity account, AccountDtos.TestAccountsRequest request, boolean updateStatus) {
        if (account.getAccessToken() == null || account.getAccessToken().isBlank()) {
            return "Access token is empty";
        }
        boolean onlyEnabled = !Boolean.FALSE.equals(request.onlyEnabled());
        if (!onlyEnabled || updateStatus) {
            return null;
        }
        if (Boolean.FALSE.equals(account.getEnabled())) {
            return "Account is disabled";
        }
        if (account.getSuspendedAt() != null) {
            return "Account is suspended";
        }
        Instant now = Instant.now();
        boolean totalQuotaWindowActive = account.getQuotaResetAt() == null || account.getQuotaResetAt().isAfter(now);
        if (totalQuotaWindowActive && account.getQuotaExhaustedAt() != null) {
            return "Quota is exhausted";
        }
        Long limit = account.getQuotaLimit();
        Long used = account.getQuotaUsed();
        if (totalQuotaWindowActive && limit != null && limit > 0 && used != null && used >= limit) {
            return "Quota limit reached";
        }
        if (windowQuotaReached(account.getQuota5hUsed(), account.getQuota5hLimit(), account.getQuota5hResetAt(), now)) {
            return "5h quota limit reached";
        }
        if (windowQuotaReached(account.getQuota7dUsed(), account.getQuota7dLimit(), account.getQuota7dResetAt(), now)) {
            return "7day quota limit reached";
        }
        return null;
    }

    private boolean windowQuotaReached(Long used, Long limit, Instant resetAt, Instant now) {
        if (limit == null || limit <= 0 || used == null || used < limit) {
            return false;
        }
        return resetAt == null || resetAt.isAfter(now);
    }

    private AccountDtos.TestAccountsResponse summarize(
        Instant startedAt,
        List<AccountDtos.TestAccountItemResponse> items
    ) {
        int success = count(items, "SUCCESS");
        int failed = count(items, "FAILED");
        int skipped = count(items, "SKIPPED");
        return new AccountDtos.TestAccountsResponse(
            items.size(),
            success,
            failed,
            skipped,
            startedAt,
            Instant.now(),
            items
        );
    }

    private int count(List<AccountDtos.TestAccountItemResponse> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.status())).count();
    }

    private AccountDtos.TestAccountItemResponse item(
        String accountId,
        String email,
        String status,
        String message,
        String detail,
        Integer statusCode,
        Long latencyMs,
        Instant testedAt,
        String model
    ) {
        return new AccountDtos.TestAccountItemResponse(accountId, email, status, message, detail, statusCode, latencyMs, testedAt, model);
    }

    private int statusCode(Throwable error) {
        return error instanceof UpstreamException upstream ? upstream.getStatusCode() : 0;
    }

    private Throwable rootFailure(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean recoverableStatus(int statusCode) {
        return statusCode == 402 || statusCode == 403 || statusCode == 429 || statusCode >= 500;
    }

    private String testModel(String requestedModel, ProxyAccountEntity account) {
        if (AccountPlatforms.isOpenAi(account)) {
            return openAiModel(requestedModel, account);
        }
        if (AccountPlatforms.isAnthropicApiKey(account)) {
            return anthropicModel(requestedModel);
        }
        return requestedModel;
    }

    private String anthropicModel(String requestedModel) {
        String model = firstNonBlank(requestedModel, DEFAULT_MODEL);
        String normalized = model.toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized)
            || "simple-task".equals(normalized)
            || normalized.startsWith("gpt")
            || normalized.startsWith("openai/")
            || normalized.startsWith("openai-compatible/")) {
            return DEFAULT_ANTHROPIC_API_KEY_TEST_MODEL;
        }
        if (normalized.startsWith("anthropic/")) {
            model = model.substring("anthropic/".length());
            normalized = model.toLowerCase(Locale.ROOT);
        } else if (normalized.startsWith("anthropic-compatible/")) {
            model = model.substring("anthropic-compatible/".length());
            normalized = model.toLowerCase(Locale.ROOT);
        } else if (normalized.startsWith("starry/")) {
            model = model.substring("starry/".length());
            normalized = model.toLowerCase(Locale.ROOT);
        }
        for (ModelDtos.ModelSpec spec : AnthropicModelCatalog.models()) {
            String id = spec.id().toLowerCase(Locale.ROOT);
            if (id.startsWith("anthropic/")) {
                id = id.substring("anthropic/".length());
            }
            if (normalized.equals(id)) {
                return spec.targetModelId();
            }
        }
        return model;
    }

    private String openAiModel(String requestedModel, ProxyAccountEntity account) {
        String model = firstNonBlank(requestedModel, DEFAULT_MODEL);
        String normalized = model.toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized)
            || "simple-task".equals(normalized)
            || normalized.startsWith("claude-")
            || normalized.startsWith("claude_")) {
            return AccountPlatforms.isCodex(account)
                ? OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL
                : DEFAULT_OPENAI_API_KEY_TEST_MODEL;
        }
        String unprefixed = normalized.startsWith("openai/") ? model.substring("openai/".length()) : model;
        if (normalized.startsWith("openai-compatible/")) {
            return model.substring("openai-compatible/".length());
        }
        if (AccountPlatforms.isCodex(account)) {
            return codexCompatibleTestModel(unprefixed);
        }
        if (normalized.startsWith("openai/") && isOfficialOpenAiBaseUrl(account)) {
            return unprefixed;
        }
        return model;
    }

    private String codexCompatibleTestModel(String model) {
        String cleaned = firstNonBlank(model, OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL);
        String normalized = cleaned.toLowerCase(Locale.ROOT);
        if (normalized.contains("codex") || "codex-mini-latest".equals(normalized)) {
            return OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL;
        }
        return cleaned;
    }

    private boolean isOfficialOpenAiBaseUrl(ProxyAccountEntity account) {
        String baseUrl = account == null ? null : account.getBaseUrl();
        return baseUrl == null
            || baseUrl.isBlank()
            || baseUrl.replaceAll("/+$", "").equalsIgnoreCase(AccountPlatforms.DEFAULT_OPENAI_BASE_URL);
    }

    private static List<AccountDtos.TestModelOption> openRouterOptions(
        AccountDtos.TestModelOption autoOption,
        String providerPrefix,
        List<String> modelIds
    ) {
        return testModelOptions(autoOption, modelIds.stream()
            .map(id -> id.contains("/") ? id : providerPrefix + "/" + id)
            .toList());
    }

    private static List<String> unprefixedModelIds(List<String> modelIds) {
        return modelIds.stream()
            .map(AccountTestService::stripProviderPrefix)
            .toList();
    }

    private static String stripProviderPrefix(String modelId) {
        if (modelId == null) {
            return "";
        }
        int slash = modelId.indexOf('/');
        return slash >= 0 ? modelId.substring(slash + 1) : modelId;
    }

    private static List<AccountDtos.TestModelOption> testModelOptions(
        AccountDtos.TestModelOption autoOption,
        List<String> modelIds
    ) {
        return Stream.concat(Stream.of(autoOption), modelIds.stream().map(id -> option(id, id))).toList();
    }

    private static AccountDtos.TestModelOption option(String label, String value) {
        return new AccountDtos.TestModelOption(label, value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 180) + "...";
    }

    private record AccountTarget(String accountId, ProxyAccountEntity account) {
        static AccountTarget found(ProxyAccountEntity account) {
            return new AccountTarget(account.getAccountId(), account);
        }

        static AccountTarget missing(String accountId) {
            return new AccountTarget(accountId, null);
        }
    }
}

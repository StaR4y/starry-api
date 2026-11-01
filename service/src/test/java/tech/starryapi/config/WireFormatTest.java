package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.admin.AdminDtos;
import tech.starryapi.admin.AppearanceDtos;
import tech.starryapi.admin.DataManagementSecurityIncident;
import tech.starryapi.apikey.ApiKeyDtos;
import tech.starryapi.codingplan.CodingPlanDtos;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.documentation.DocumentationDtos;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.payment.PaymentDtos;
import tech.starryapi.plugin.PluginDtos;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.stats.StatsDtos;
import tech.starryapi.ticket.TicketDtos;

/**
 * The JSON that the console, the SDKs and the CLIs see must not change when the JSON library does. Expected strings
 * below were recorded from the last Jackson 2 build of the application (68e5665, Spring Boot 4.1.2) and are asserted
 * unchanged against Jackson 3. Property order, BigDecimal scale, ISO instants, omitted nulls, renamed properties and
 * lenient request parsing are all part of the contract.
 *
 * <p>Everything goes through {@link ProductionJson}, so the WebFlux codec and the injected mapper are both checked and
 * this file does not mention any Jackson type.
 */
class WireFormatTest {

    private static final Instant T0 = Instant.parse("2026-10-23T06:24:25.123456Z");
    private static final Instant T1 = Instant.parse("2026-10-23T06:24:25Z");
    private static final Instant T2 = Instant.parse("2026-01-02T03:04:05.100Z");

    @Test
    void responsesKeepPropertyOrderNullsAndInstants() {
        ProductionJson.run(json -> {
            check(json, "errorEnvelope",
                new ApiResponse<>(false, null, new ApiResponse.ApiError("VALIDATION_ERROR", "name must not be blank"), T0),
                """
                {"success":false,"error":{"code":"VALIDATION_ERROR","message":"name must not be blank"},"timestamp":"2026-10-23T06:24:25.123456Z"}""");
            check(json, "apiKeyList", new ApiResponse<>(true, List.of(
                new ApiKeyDtos.ApiKeyResponse("key-1", "user-1", "CI key", "sk-starry-abcdef", "sk-star", true,
                    new BigDecimal("25.50"), "OPENAI", "BALANCE", 12L, new BigDecimal("1234.567890"), 4_000_000_000L, 0L,
                    T0, T1, null),
                new ApiKeyDtos.ApiKeyResponse("key-2", "user-1", "unlimited", null, "sk-star", false,
                    null, null, null, 0L, BigDecimal.ZERO, 0L, 0L, null, T2, T2)), null, T0),
                """
                {"success":true,"data":[{"keyId":"key-1","userId":"user-1","name":"CI key","key":"sk-starry-abcdef","keyPrefix":"sk-star","enabled":true,"creditsLimit":25.50,"modelProvider":"OPENAI","billingSource":"BALANCE","totalRequests":12,"totalCredits":1234.567890,"totalInputTokens":4000000000,"totalOutputTokens":0,"lastUsedAt":"2026-10-23T06:24:25.123456Z","createdAt":"2026-10-23T06:24:25Z"},{"keyId":"key-2","userId":"user-1","name":"unlimited","keyPrefix":"sk-star","enabled":false,"totalRequests":0,"totalCredits":0,"totalInputTokens":0,"totalOutputTokens":0,"createdAt":"2026-01-02T03:04:05.100Z","updatedAt":"2026-01-02T03:04:05.100Z"}],"timestamp":"2026-10-23T06:24:25.123456Z"}""");
            check(json, "createdApiKey", ok(new ApiKeyDtos.CreatedApiKeyResponse(
                "key-3", "user-1", "new", "sk-starry-secret", "sk-star", true, new BigDecimal("100"), "ANTHROPIC",
                "CODING_PLAN", T1)),
                """
                {"success":true,"data":{"keyId":"key-3","userId":"user-1","name":"new","key":"sk-starry-secret","keyPrefix":"sk-star","enabled":true,"creditsLimit":100,"modelProvider":"ANTHROPIC","billingSource":"CODING_PLAN","createdAt":"2026-10-23T06:24:25Z"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "login", new ApiResponse<>(true, new AdminDtos.LoginResponse("Bearer", "token-123", T0,
                new AdminDtos.AdminUserResponse("u-1", "AFF1", null, null, "root", "root@example.com", "Root", "ADMIN",
                    new BigDecimal("0.000001"), true, false, T1, T1, T0)), null, T1),
                """
                {"success":true,"data":{"tokenType":"Bearer","accessToken":"token-123","expiresAt":"2026-10-23T06:24:25.123456Z","user":{"userId":"u-1","affiliateCode":"AFF1","username":"root","email":"root@example.com","displayName":"Root","role":"ADMIN","balance":0.000001,"enabled":true,"firstLogin":false,"lastLoginAt":"2026-10-23T06:24:25Z","createdAt":"2026-10-23T06:24:25Z","updatedAt":"2026-10-23T06:24:25.123456Z"}},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "account", new ApiResponse<>(true, new AccountDtos.AccountResponse(
                "acc-1", "a@example.com", "us-east-1", "OAUTH", "OPENAI", "https://api.openai.com", "up-1", T0,
                null, "machine", null, "PRIMARY", 3, true, 9_000_000_000L, 2, 10L, 100L, null, T1, 1L, 2L, T1, 3L, 4L,
                null, null, null, null, T0, T1, T2, "#5b8def", UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")),
                null, T1),
                """
                {"success":true,"data":{"accountId":"acc-1","email":"a@example.com","region":"us-east-1","authMethod":"OAUTH","provider":"OPENAI","baseUrl":"https://api.openai.com","upstreamAccountId":"up-1","tokenExpiresAt":"2026-10-23T06:24:25.123456Z","machineId":"machine","poolTier":"PRIMARY","poolWeight":3,"enabled":true,"requestCount":9000000000,"errorCount":2,"quotaUsed":10,"quotaLimit":100,"quotaResetAt":"2026-10-23T06:24:25Z","quota5hUsed":1,"quota5hLimit":2,"quota5hResetAt":"2026-10-23T06:24:25Z","quota7dUsed":3,"quota7dLimit":4,"lastUsedAt":"2026-10-23T06:24:25.123456Z","createdAt":"2026-10-23T06:24:25Z","updatedAt":"2026-01-02T03:04:05.100Z","badgeColor":"#5b8def","proxyChannelId":"0f8fad5b-d9cb-469f-a165-70867728950e"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "paymentOrder", ok(new PaymentDtos.CreatePaymentOrderResponse(
                new PaymentDtos.PaymentOrderResponse("o-1", "u-1", "alice", "alice@example.com", new BigDecimal("10.00"),
                    new BigDecimal("10.30"), new BigDecimal("0.0300"), "alipay", "PENDING", "out-1", null, null, null, null,
                    T0, null, null, null, null, T1, T1),
                "https://pay.example.com/?x=1&y=2", null)),
                """
                {"success":true,"data":{"order":{"orderId":"o-1","userId":"u-1","username":"alice","email":"alice@example.com","amount":10.00,"payAmount":10.30,"feeRate":0.0300,"paymentType":"alipay","status":"PENDING","outTradeNo":"out-1","expiresAt":"2026-10-23T06:24:25.123456Z","createdAt":"2026-10-23T06:24:25Z","updatedAt":"2026-10-23T06:24:25Z"},"payUrl":"https://pay.example.com/?x=1&y=2"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "paymentSettings", new ApiResponse<>(true, new PaymentDtos.PaymentSettingsResponse(
                true, new BigDecimal("1"), new BigDecimal("10000.5"), new BigDecimal("0E-8"), 30, 5,
                List.of("alipay", "wxpay"), new BigDecimal("1.000000"), new BigDecimal("1E+3"), new BigDecimal("0.03"),
                "", null, null, "", "https://pay.example.com", "1001", true, "redirect", false, new BigDecimal("0.1"), 3,
                null, T1), null, T1),
                """
                {"success":true,"data":{"enabled":true,"minAmount":1,"maxAmount":10000.5,"dailyLimit":0E-8,"orderTimeoutMinutes":30,"maxPendingOrders":5,"enabledTypes":["alipay","wxpay"],"balanceRechargeMultiplier":1.000000,"apiBillingMultiplier":1E+3,"rechargeFeeRate":0.03,"productNamePrefix":"","helpText":"","easypayApiBase":"https://pay.example.com","easypayPid":"1001","easypayPkeyConfigured":true,"easypayPaymentMode":"redirect","inviteRebateEnabled":false,"inviteRebateRate":0.1,"inviteRebateMaxOrders":3,"inviteRebateEndAt":"2026-10-23T06:24:25Z"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "documentationCatalog", ok(new DocumentationDtos.Catalog(
                new DocumentationDtos.Settings("https://apifox.example.com"),
                List.of(new DocumentationDtos.Entry("quick-start", "Quick start", "Guide", "Summary 你好", 10, T0)))),
                """
                {"success":true,"data":{"settings":{"apifoxUrl":"https://apifox.example.com"},"entries":[{"id":"quick-start","title":"Quick start","category":"Guide","summary":"Summary 你好","sortOrder":10,"updatedAt":"2026-10-23T06:24:25.123456Z"}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "codingPlan", ok(new CodingPlanDtos.CodingPlanResponse(
                "plan-1", 2, "Pro", "monthly", new BigDecimal("99.00"), 30, new BigDecimal("500"), List.of("OPENAI"), 1, true, T1, T0)),
                """
                {"success":true,"data":{"planId":"plan-1","version":2,"name":"Pro","description":"monthly","priceCredits":"99.00","durationDays":30,"quotaCredits":"500","allowedProviders":["OPENAI"],"sortOrder":1,"enabled":true,"createdAt":"2026-10-23T06:24:25Z","updatedAt":"2026-10-23T06:24:25.123456Z"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "usageSummary", ok(new StatsDtos.UsageSummaryResponse(
                10L, 9L, 1L, 1000L, 2000L, 3000L, new BigDecimal("0.123456"), T1, 1,
                List.of(new StatsDtos.ActiveUserResponse("u-1", "alice", "Alice", "a@example.com", 10L, 1L, 1000L, 2000L,
                    3000L, new BigDecimal("0.123456"), T1)),
                List.of(new StatsDtos.ModelUsageResponse("gpt-5", 10L, 9L, 1L, 1000L, 2000L, 3000L, new BigDecimal("0.1"), null)))),
                """
                {"success":true,"data":{"totalRequests":10,"successfulRequests":9,"failedRequests":1,"totalInputTokens":1000,"totalOutputTokens":2000,"totalTokens":3000,"totalCredits":0.123456,"activeWindowStartedAt":"2026-10-23T06:24:25Z","activeUserCount":1,"activeUsers":[{"userId":"u-1","username":"alice","displayName":"Alice","email":"a@example.com","requestCount":10,"apiKeyCount":1,"inputTokens":1000,"outputTokens":2000,"totalTokens":3000,"credits":0.123456,"lastActiveAt":"2026-10-23T06:24:25Z"}],"models":[{"model":"gpt-5","requestCount":10,"successfulRequests":9,"failedRequests":1,"inputTokens":1000,"outputTokens":2000,"totalTokens":3000,"credits":0.1}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "performance", ok(new StatsDtos.PerformanceMetricResponse(
                12.5, 0.1, 1L << 30, 2L << 30, 4L << 30, 100L, 8L << 30, 1L << 30, 87.5, 1L, 2L, 1.0E7, 0.0, T1)),
                """
                {"success":true,"data":{"systemCpuLoadPercent":12.5,"processCpuLoadPercent":0.1,"heapUsedBytes":1073741824,"heapCommittedBytes":2147483648,"heapMaxBytes":4294967296,"nonHeapUsedBytes":100,"systemMemoryTotalBytes":8589934592,"systemMemoryFreeBytes":1073741824,"systemMemoryUsedPercent":87.5,"networkRxBytes":1,"networkTxBytes":2,"networkRxBytesPerSecond":1.0E7,"networkTxBytesPerSecond":0.0,"sampledAt":"2026-10-23T06:24:25Z"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "ticketCountsKeepInsertionOrder", ok(new TicketDtos.Page(
                List.of(new TicketDtos.Ticket("t-1", "u-1", "alice", "Title", "BUG", "OPEN", 1L, 2, T1, T0)), 1L, 1, 20,
                orderedCounts())),
                """
                {"success":true,"data":{"items":[{"id":"t-1","userId":"u-1","username":"alice","title":"Title","category":"BUG","status":"OPEN","version":1,"messageCount":2,"createdAt":"2026-10-23T06:24:25Z","updatedAt":"2026-10-23T06:24:25.123456Z"}],"total":1,"page":1,"pageSize":20,"counts":{"OPEN":3,"CLOSED":1,"ANSWERED":2}},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "appearanceKeepsChromeWhenNull", ok(new AppearanceDtos.AppearanceResponse(
                "Starry API", null, null, null, "default", null, null, null, null, null, null, null, null, null, null,
                0.62, 0.5, 18, null, "editorial")),
                """
                {"success":true,"data":{"siteName":"Starry API","preset":"default","sidebarOpacity":0.62,"surfaceOpacity":0.5,"blurPx":18,"chrome":"editorial"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "pluginSettingsRenameDefault", ok(new PluginDtos.ModuleSettings("p-1", "Plugin", null,
                List.of(new PluginDtos.SettingField("size", "number", "Size", 1, 10, 0.5, null, 5),
                    new PluginDtos.SettingField("name", "text", "Name", null, null, null, null, "x")),
                Map.of("size", 5), List.of(new PluginDtos.ModelChoice("m", "M", "OPENAI")))),
                """
                {"success":true,"data":{"pluginId":"p-1","title":"Plugin","fields":[{"key":"size","type":"number","label":"Size","min":1,"max":10,"step":0.5,"default":5},{"key":"name","type":"text","label":"Name","default":"x"}],"config":{"size":5},"models":[{"id":"m","name":"M","provider":"OPENAI"}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "enumAndBooleans", new DataManagementSecurityIncident(
                "e-1", DataManagementSecurityIncident.Type.SECURITY_LOCKED, DataManagementSecurityIncident.Severity.CRITICAL,
                T0, "u-1", "root", null, null, null, null, null, null, null, null, "10.0.0.1", null, null, null, null, null,
                3, true, "locked"),
                """
                {"eventId":"e-1","type":"SECURITY_LOCKED","severity":"CRITICAL","occurredAt":"2026-10-23T06:24:25.123456Z","userId":"u-1","username":"root","remoteAddress":"10.0.0.1","consecutiveFailures":3,"locked":true,"detail":"locked"}""");
        });
    }

    @Test
    void openAiCompatibleModelListKeepsSnakeCaseNames() {
        ProductionJson.run(json -> {
            var item = new ModelDtos.ModelListItem("claude-sonnet-4.5", "model", 1_761_200_665L, "anthropic", "ANTHROPIC",
                "claude", "claude-sonnet-4-5", "Claude Sonnet 4.5", "claude-sonnet-4.5", null, List.of("text", "image"),
                200_000, 64_000, 200_000, 64_000, "TOKEN", new BigDecimal("1000"), new BigDecimal("333.333333"),
                new BigDecimal("66.666667"), null, null, new BigDecimal("3"), new BigDecimal("3.00"), new BigDecimal("15"),
                null, new BigDecimal("0.30"), "https://example.com/icon.svg", "ANTHROPIC_MESSAGES", "/v1/messages");
            check(json, "modelList", new ModelDtos.ModelListResponse("list", List.of(item), null),
                """
                {"object":"list","data":[{"id":"claude-sonnet-4.5","object":"model","created":1761200665,"owned_by":"anthropic","provider":"ANTHROPIC","family":"claude","targetModelId":"claude-sonnet-4-5","name":"Claude Sonnet 4.5","modelName":"claude-sonnet-4.5","supportedInputTypes":["text","image"],"maxInputTokens":200000,"maxOutputTokens":64000,"context_length":200000,"max_output_tokens":64000,"billingMode":"TOKEN","tokensPer100Balance":1000,"inputTokensPer100Balance":333.333333,"outputTokensPer100Balance":66.666667,"totalPricePerMillionTokens":3,"inputPricePerMillionTokens":3.00,"outputPricePerMillionTokens":15,"cacheReadPricePerMillionTokens":0.30,"icon":"https://example.com/icon.svg","requestFormat":"ANTHROPIC_MESSAGES","endpoint":"/v1/messages"}],"models":[{"id":"claude-sonnet-4.5","object":"model","created":1761200665,"owned_by":"anthropic","provider":"ANTHROPIC","family":"claude","targetModelId":"claude-sonnet-4-5","name":"Claude Sonnet 4.5","modelName":"claude-sonnet-4.5","supportedInputTypes":["text","image"],"maxInputTokens":200000,"maxOutputTokens":64000,"context_length":200000,"max_output_tokens":64000,"billingMode":"TOKEN","tokensPer100Balance":1000,"inputTokensPer100Balance":333.333333,"outputTokensPer100Balance":66.666667,"totalPricePerMillionTokens":3,"inputPricePerMillionTokens":3.00,"outputPricePerMillionTokens":15,"cacheReadPricePerMillionTokens":0.30,"icon":"https://example.com/icon.svg","requestFormat":"ANTHROPIC_MESSAGES","endpoint":"/v1/messages"}]}""");
            check(json, "mapping", ok(new ModelDtos.MappingResponse("map-1", "alias", true, "FALLBACK",
                "gpt-5", List.of("gpt-5-mini", "gpt-4.1"), List.of(70, 30), 100, List.of(), T0, T1)),
                """
                {"success":true,"data":{"mappingId":"map-1","name":"alias","enabled":true,"mappingType":"FALLBACK","sourceModel":"gpt-5","targetModels":["gpt-5-mini","gpt-4.1"],"weights":[70,30],"priority":100,"apiKeyIds":[],"createdAt":"2026-10-23T06:24:25.123456Z","updatedAt":"2026-10-23T06:24:25Z"},"timestamp":"2026-10-23T06:24:25Z"}""");
        });
    }

    /**
     * The source columns of a request log (design 5.7) come after the columns a row always had and are left out of a row
     * that has none, so the list of a vendor without tiers is the JSON it was. A user sees the name, level and factor of
     * the tier that served a request; the administrator sees the rest.
     */
    @Test
    void requestLogsAddSourceColumnsOnlyToRowsOfRequestsServedFromTiers() {
        ProductionJson.run(json -> {
            StatsDtos.RequestLogResponse untiered = new StatsDtos.RequestLogResponse("req-1", "key-1", "acc-1", "/v1/messages",
                "claude-sonnet-4.5", 200, true, 8_000L, 1_000L, new BigDecimal("0.039000"), 120L, null, T1);
            check(json, "requestLogWithoutTiers", ok(List.of(untiered)),
                """
                {"success":true,"data":[{"requestId":"req-1","apiKeyId":"key-1","accountId":"acc-1","path":"/v1/messages","model":"claude-sonnet-4.5","statusCode":200,"success":true,"inputTokens":8000,"outputTokens":1000,"credits":0.039000,"latencyMs":120,"createdAt":"2026-10-23T06:24:25Z"}],"timestamp":"2026-10-23T06:24:25Z"}""");
            StatsDtos.RequestLogResponse served = new StatsDtos.RequestLogResponse("req-2", "key-1", "acc-2", "/v1/messages",
                "claude-sonnet-4.5", 200, true, 8_000L, 1_000L, new BigDecimal("0.023400"), 130L, null, T1,
                "anthropic:kiro", 2, "kiro", new BigDecimal("0.600000"), "AUTO", "OPENAI_CHAT", "wire-model", "ESTIMATED",
                "MAX_TOKENS", "images", null, null);
            check(json, "requestLogOfAdministrator", ok(List.of(served)),
                """
                {"success":true,"data":[{"requestId":"req-2","apiKeyId":"key-1","accountId":"acc-2","path":"/v1/messages","model":"claude-sonnet-4.5","statusCode":200,"success":true,"inputTokens":8000,"outputTokens":1000,"credits":0.023400,"latencyMs":130,"createdAt":"2026-10-23T06:24:25Z","sourceTierRef":"anthropic:kiro","sourceTierLevel":2,"sourceTierName":"kiro","priceFactor":0.600000,"tierSelection":"AUTO","sourceDispatch":"OPENAI_CHAT","wireModel":"wire-model","usageSource":"ESTIMATED","stopReason":"MAX_TOKENS","degradedFeatures":"images"}],"timestamp":"2026-10-23T06:24:25Z"}""");
            // An answer the gateway held back: the user's tokens and credits are 0, and what the relay reported is the operator's.
            StatsDtos.RequestLogResponse withheld = new StatsDtos.RequestLogResponse("req-3", "key-1", "acc-2", "/v1/responses",
                "claude-sonnet-4.5", 502, false, 0L, 0L, new BigDecimal("0.000000"), 130L, "The source stopped its answer", T1,
                "anthropic:relay", 1, "relay", new BigDecimal("1.000000"), "AUTO", "OPENAI_CHAT", "wire-model", null,
                "WITHHELD", "builtin_tool", 60_000L, 4_096L);
            check(json, "requestLogOfAdministratorWithAWithheldAnswer", ok(List.of(withheld)),
                """
                {"success":true,"data":[{"requestId":"req-3","apiKeyId":"key-1","accountId":"acc-2","path":"/v1/responses","model":"claude-sonnet-4.5","statusCode":502,"success":false,"inputTokens":0,"outputTokens":0,"credits":0.000000,"latencyMs":130,"errorMessage":"The source stopped its answer","createdAt":"2026-10-23T06:24:25Z","sourceTierRef":"anthropic:relay","sourceTierLevel":1,"sourceTierName":"relay","priceFactor":1.000000,"tierSelection":"AUTO","sourceDispatch":"OPENAI_CHAT","wireModel":"wire-model","stopReason":"WITHHELD","degradedFeatures":"builtin_tool","withheldInputTokens":60000,"withheldOutputTokens":4096}],"timestamp":"2026-10-23T06:24:25Z"}""");
            StatsDtos.RequestLogResponse seenByUser = new StatsDtos.RequestLogResponse("req-2", "key-1", "acc-2", "/v1/messages",
                "claude-sonnet-4.5", 200, true, 8_000L, 1_000L, new BigDecimal("0.023400"), 130L, null, T1,
                null, 2, "kiro", new BigDecimal("0.600000"), null, null, null, null, null, null, null, null);
            check(json, "requestLogOfUser", ok(new StatsDtos.RequestLogPageResponse(List.of(seenByUser), false, T1)),
                """
                {"success":true,"data":{"items":[{"requestId":"req-2","apiKeyId":"key-1","accountId":"acc-2","path":"/v1/messages","model":"claude-sonnet-4.5","statusCode":200,"success":true,"inputTokens":8000,"outputTokens":1000,"credits":0.023400,"latencyMs":130,"createdAt":"2026-10-23T06:24:25Z","sourceTierLevel":2,"sourceTierName":"kiro","priceFactor":0.600000}],"hasMore":false,"nextBefore":"2026-10-23T06:24:25Z"},"timestamp":"2026-10-23T06:24:25Z"}""");
        });
    }

    /**
     * A model listed under source tiers (design 5.7) gains one field, and only then: the list of a vendor without tiers is
     * the JSON it was. The tiers carry the scaled price and nothing of the accounts or the protocol behind them.
     */
    @Test
    void theModelListNamesSourceTiersOnlyForAModelThatIsListedUnderThem() {
        ProductionJson.run(json -> {
            ModelDtos.ModelListItem plain = new ModelDtos.ModelListItem("m", "model", 1L, "openai", null, null, null, null, null,
                null, List.of("text"), 0, 0, 8192, 1024, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
            ModelDtos.ModelListItem tiered = plain.withSourceTiers(List.of(new ModelDtos.ModelSourceTier("kiro", 2, "kiro反向代理",
                new BigDecimal("0.600000"), true, new ModelDtos.ModelPublicPricing("input_output", null,
                    new BigDecimal("555.5"), new BigDecimal("111.1"), null, null, null, new BigDecimal("1.8"),
                    new BigDecimal("9"), null, null))));
            check(json, "modelListWithoutTiers", new ModelDtos.ModelListResponse("list", List.of(plain), null),
                """
                {"object":"list","data":[{"id":"m","object":"model","created":1,"owned_by":"openai","supportedInputTypes":["text"],"maxInputTokens":0,"maxOutputTokens":0,"context_length":8192,"max_output_tokens":1024}],"models":[{"id":"m","object":"model","created":1,"owned_by":"openai","supportedInputTypes":["text"],"maxInputTokens":0,"maxOutputTokens":0,"context_length":8192,"max_output_tokens":1024}]}""");
            check(json, "modelListWithTiers", new ModelDtos.ModelListResponse("list", List.of(tiered), null),
                """
                {"object":"list","data":[{"id":"m","object":"model","created":1,"owned_by":"openai","supportedInputTypes":["text"],"maxInputTokens":0,"maxOutputTokens":0,"context_length":8192,"max_output_tokens":1024,"sourceTiers":[{"id":"kiro","level":2,"name":"kiro反向代理","priceFactor":0.600000,"selectable":true,"pricing":{"billingMode":"input_output","inputTokensPer100Balance":555.5,"outputTokensPer100Balance":111.1,"inputPricePerMillionTokens":1.8,"outputPricePerMillionTokens":9}}]}],"models":[{"id":"m","object":"model","created":1,"owned_by":"openai","supportedInputTypes":["text"],"maxInputTokens":0,"maxOutputTokens":0,"context_length":8192,"max_output_tokens":1024,"sourceTiers":[{"id":"kiro","level":2,"name":"kiro反向代理","priceFactor":0.600000,"selectable":true,"pricing":{"billingMode":"input_output","inputTokensPer100Balance":555.5,"outputTokensPer100Balance":111.1,"inputPricePerMillionTokens":1.8,"outputPricePerMillionTokens":9}}]}]}""");
            // A listing keeps its tiers when the price is applied afterwards, and gains none from an empty list.
            assertThat(tiered.withPricing(new ModelDtos.ModelPublicPricing("total", new BigDecimal("1"), null, null)).sourceTiers())
                .hasSize(1);
            assertThat(plain.withSourceTiers(List.of()).sourceTiers()).isNull();
        });
    }

    /** The pricing preview takes optional tier factors and answers them in the same order; without them it is unchanged. */
    @Test
    void thePricingPreviewAnswersTheFactorsItWasAskedFor() {
        ProductionJson.run(json -> {
            ModelDtos.ModelPublicPricing base = new ModelDtos.ModelPublicPricing("total", new BigDecimal("1000"), null, null);
            check(json, "previewWithoutFactors", ok(new ModelDtos.ModelPricingPreview("m", "m", "official", "m",
                    BigDecimal.ONE, List.of(), base, base)),
                """
                {"success":true,"data":{"model":"m","pricingModel":"m","source":"official","matchedPriceModel":"m","multiplier":1,"matchedRuleIds":[],"basePricing":{"billingMode":"total","tokensPer100Balance":1000},"pricing":{"billingMode":"total","tokensPer100Balance":1000}},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "previewWithFactors", ok(new ModelDtos.ModelPricingPreview("m", "m", "official", "m",
                    BigDecimal.ONE, List.of(), base, base, List.of(new ModelDtos.FactorPricing(new BigDecimal("0.6"), base)))),
                """
                {"success":true,"data":{"model":"m","pricingModel":"m","source":"official","matchedPriceModel":"m","multiplier":1,"matchedRuleIds":[],"basePricing":{"billingMode":"total","tokensPer100Balance":1000},"pricing":{"billingMode":"total","tokensPer100Balance":1000},"factorPricing":[{"factor":0.6,"pricing":{"billingMode":"total","tokensPer100Balance":1000}}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            read(json, "previewRequestWithFactors",
                """
                {"model":"m","factors":[0.6,"1.25"]}""",
                ModelDtos.ModelPricingPreviewRequest.class, "ModelPricingPreviewRequest[model=m, rules=[], factors=[0.6, 1.25]]");
            read(json, "previewRequestWithoutFactors",
                """
                {"model":"m","rules":[]}""",
                ModelDtos.ModelPricingPreviewRequest.class, "ModelPricingPreviewRequest[model=m, rules=[], factors=[]]");
        });
    }

    /** The status page and the account list read these; the JSON follows the records, with nothing renamed. */
    @Test
    void theTierStatusAndPlacementsCarryTheirFigures() {
        ProductionJson.run(json -> {
            check(json, "tierStatus", ok(new SourceTierDtos.Status(T1, 7L, 15, List.of(new SourceTierDtos.VendorStatus(
                "anthropic", "Anthropic", true, true, 100, 30, List.of(new SourceTierDtos.TierStatus("kiro", 2, "kiro", true, true,
                    new SourceTierDtos.TierAccounts(1, 2, 3, 4, 5), 30, 5, 2)))))),
                """
                {"success":true,"data":{"at":"2026-10-23T06:24:25Z","configVersion":7,"windowMinutes":15,"vendors":[{"vendor":"anthropic","label":"Anthropic","enabled":true,"serving":true,"autoServed":100,"degraded":30,"tiers":[{"tierId":"kiro","level":2,"name":"kiro","enabled":true,"serves":true,"accounts":{"disabled":1,"unavailable":2,"resting":3,"busy":4,"ready":5},"served":30,"servedPinned":5,"failed":2}]}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "tierPlacements", ok(new SourceTierDtos.Placements(7L, List.of(new SourceTierDtos.AccountPlacement("acc-1",
                List.of(new SourceTierDtos.Placed("anthropic", "Anthropic", "kiro", 2, "kiro", "CATEGORY", false, true)))))),
                """
                {"success":true,"data":{"configVersion":7,"accounts":[{"accountId":"acc-1","tiers":[{"vendor":"anthropic","vendorLabel":"Anthropic","tierId":"kiro","level":2,"name":"kiro","rule":"CATEGORY","relay":false,"active":true}]}]},"timestamp":"2026-10-23T06:24:25Z"}""");
        });
    }

    /**
     * The relays' part of the status and the evidence of the upgrade of a pair are fields at the end of the records they
     * join: a tier without a relay shows the status it always did ({@code unopened} and {@code xp} are left out), and a
     * figure that was not measured is absent and not zero.
     */
    @Test
    void theRelaysOfTheStatusAndTheEvidenceAreAddedAtTheEndAndAreAbsentWhereTheyDoNotApply() {
        ProductionJson.run(json -> {
            SourceTierDtos.XpFigures figures = new SourceTierDtos.XpFigures(40, 36, 4, 2, 1, 0, 1, 0, 0, 0, 0, 3, 0, 5, 1800L);
            SourceTierDtos.XpFigures unmeasured = new SourceTierDtos.XpFigures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
            check(json, "tierStatusWithRelays", ok(new SourceTierDtos.Status(T1, 7L, 15, List.of(new SourceTierDtos.VendorStatus(
                "anthropic", "Anthropic", true, true, 100, 30,
                List.of(new SourceTierDtos.TierStatus("relay", 1, "relay", true, true, new SourceTierDtos.TierAccounts(0, 0, 0, 0, 1, 0), 36, 0, 4)),
                new SourceTierDtos.XpStatus(T2, List.of(new SourceTierDtos.XpRelay("relay", "relay-1", "ready", 2, 1, figures)),
                    List.of(new SourceTierDtos.XpPair("OPENAI_CHAT", "MESSAGES", "EXPERIMENTAL", "KEYS", 2, unmeasured,
                        List.of(new SourceTierDtos.FeatureCount("image", 5)))),
                    List.of(new SourceTierDtos.XpSkipCount("relay", "GATE", 12))))))),
                """
                {"success":true,"data":{"at":"2026-10-23T06:24:25Z","configVersion":7,"windowMinutes":15,"vendors":[{"vendor":"anthropic","label":"Anthropic","enabled":true,"serving":true,"autoServed":100,"degraded":30,"tiers":[{"tierId":"relay","level":1,"name":"relay","enabled":true,"serves":true,"accounts":{"disabled":0,"unavailable":0,"resting":0,"busy":0,"ready":1,"unopened":0},"served":36,"servedPinned":0,"failed":4}],"xp":{"skipsSince":"2026-01-02T03:04:05.100Z","relays":[{"tierId":"relay","accountId":"relay-1","layer":"ready","mappings":2,"verified":1,"figures":{"attempts":40,"served":36,"failed":4,"interrupted":2,"conversionRejected":1,"contextExceeded":0,"modelNotServed":1,"keyRefused":0,"withheld":0,"withheldInputTokens":0,"withheldOutputTokens":0,"estimated":3,"clamped":0,"degraded":5,"p95Ms":1800}}],"pairs":[{"sourceProtocol":"OPENAI_CHAT","entryEndpoint":"MESSAGES","status":"EXPERIMENTAL","gate":"KEYS","allowedKeys":2,"figures":{"attempts":0,"served":0,"failed":0,"interrupted":0,"conversionRejected":0,"contextExceeded":0,"modelNotServed":0,"keyRefused":0,"withheld":0,"withheldInputTokens":0,"withheldOutputTokens":0,"estimated":0,"clamped":0,"degraded":0},"degradedFeatures":[{"feature":"image","count":5}]}],"skips":[{"tierId":"relay","reason":"GATE","count":12}]}}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "tierEvidence", ok(new SourceTierDtos.Evidence(T1, T2, 30, 7L, T2, List.of(new SourceTierDtos.PairEvidence(
                "anthropic", "Anthropic", "OPENAI_CHAT", "MESSAGES", "EXPERIMENTAL", "KEYS", 1, unmeasured, 0,
                List.of(new SourceTierDtos.Criterion("requests", ">= 200", 0.0, false), new SourceTierDtos.Criterion("estimated", "< 5%", null, false)),
                false, List.of(), List.of(new SourceTierDtos.MemberEvidence("relay", "relay-1", unmeasured)))))),
                """
                {"success":true,"data":{"at":"2026-10-23T06:24:25Z","since":"2026-01-02T03:04:05.100Z","days":30,"configVersion":7,"skipsSince":"2026-01-02T03:04:05.100Z","pairs":[{"vendor":"anthropic","label":"Anthropic","sourceProtocol":"OPENAI_CHAT","entryEndpoint":"MESSAGES","status":"EXPERIMENTAL","gate":"KEYS","allowedKeys":1,"figures":{"attempts":0,"served":0,"failed":0,"interrupted":0,"conversionRejected":0,"contextExceeded":0,"modelNotServed":0,"keyRefused":0,"withheld":0,"withheldInputTokens":0,"withheldOutputTokens":0,"estimated":0,"clamped":0,"degraded":0},"lintSkips":0,"criteria":[{"name":"requests","rule":">= 200","value":0.0,"met":false},{"name":"estimated","rule":"< 5%","met":false}],"meetsStandard":false,"degradedFeatures":[],"members":[{"tierId":"relay","accountId":"relay-1","figures":{"attempts":0,"served":0,"failed":0,"interrupted":0,"conversionRejected":0,"contextExceeded":0,"modelNotServed":0,"keyRefused":0,"withheld":0,"withheldInputTokens":0,"withheldOutputTokens":0,"estimated":0,"clamped":0,"degraded":0}}]}]},"timestamp":"2026-10-23T06:24:25Z"}""");
        });
    }

    /**
     * The source pool preference of a key (design 6.1) is one more field at the end of the key records, and only for a key
     * that has one: the key list of a deployment that never used it is the JSON it was. A request may carry it or leave
     * it out; left out, it means "no change" when a key is updated.
     */
    @Test
    void aKeyShowsItsPoolPreferenceOnlyWhenItHasOneAndTheRequestsMayCarryIt() {
        ProductionJson.run(json -> {
            check(json, "apiKeyWithPreference", ok(new ApiKeyDtos.ApiKeyResponse("key-1", "user-1", "CI key", "sk-starry-abcdef",
                "sk-star", true, new BigDecimal("25.50"), "Anthropic", "BALANCE", 12L, new BigDecimal("1234.567890"), 4_000_000_000L,
                0L, T0, T1, null, "kiro+")),
                """
                {"success":true,"data":{"keyId":"key-1","userId":"user-1","name":"CI key","key":"sk-starry-abcdef","keyPrefix":"sk-star","enabled":true,"creditsLimit":25.50,"modelProvider":"Anthropic","billingSource":"BALANCE","totalRequests":12,"totalCredits":1234.567890,"totalInputTokens":4000000000,"totalOutputTokens":0,"lastUsedAt":"2026-10-23T06:24:25.123456Z","createdAt":"2026-10-23T06:24:25Z","tierPreference":"kiro+"},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "createdApiKeyWithPreference", ok(new ApiKeyDtos.CreatedApiKeyResponse("key-3", "user-1", "new",
                "sk-starry-secret", "sk-star", true, new BigDecimal("100"), "ANTHROPIC", "BALANCE", T1, "kiro+")),
                """
                {"success":true,"data":{"keyId":"key-3","userId":"user-1","name":"new","key":"sk-starry-secret","keyPrefix":"sk-star","enabled":true,"creditsLimit":100,"modelProvider":"ANTHROPIC","billingSource":"BALANCE","createdAt":"2026-10-23T06:24:25Z","tierPreference":"kiro+"},"timestamp":"2026-10-23T06:24:25Z"}""");
            read(json, "createWithPreference",
                """
                {"name":"k","modelProvider":"Anthropic","tierPreference":"L2"}""",
                ApiKeyDtos.CreateApiKeyRequest.class,
                "CreateApiKeyRequest[name=k, creditsLimit=null, modelProvider=Anthropic, billingSource=null, tierPreference=L2]");
            read(json, "updateWithPreference",
                """
                {"tierPreference":"kiro+","clearCreditsLimit":false}""",
                ApiKeyDtos.UpdateApiKeyRequest.class,
                "UpdateApiKeyRequest[name=null, enabled=null, creditsLimit=null, modelProvider=null, billingSource=null, clearCreditsLimit=false, tierPreference=kiro+]");
            read(json, "updateWithoutPreference",
                """
                {"name":"renamed","enabled":false}""",
                ApiKeyDtos.UpdateApiKeyRequest.class,
                "UpdateApiKeyRequest[name=renamed, enabled=false, creditsLimit=null, modelProvider=null, billingSource=null, clearCreditsLimit=null, tierPreference=null]");
        });
    }

    /** What the key form offers: name, level, factor and price of the pools, and nothing of the accounts behind them. */
    @Test
    void thePoolsOfAProviderCarryOnlyWhatTheKeyFormShows() {
        ProductionJson.run(json -> {
            ModelDtos.ModelPublicPricing price = new ModelDtos.ModelPublicPricing("input_output", null, new BigDecimal("555.5"),
                new BigDecimal("111.1"), null, null, null, new BigDecimal("1.8"), new BigDecimal("9"), null, null);
            check(json, "poolsOfAProvider", ok(new SourceTierDtos.MyTiers("Anthropic", "claude-sonnet-4.5", List.of(
                new SourceTierDtos.MyTier("official", 1, "官方", new BigDecimal("1.000000"), null),
                new SourceTierDtos.MyTier("kiro", 2, "kiro反向代理", new BigDecimal("0.600000"), price)))),
                """
                {"success":true,"data":{"provider":"Anthropic","referenceModel":"claude-sonnet-4.5","tiers":[{"id":"official","level":1,"name":"官方","priceFactor":1.000000},{"id":"kiro","level":2,"name":"kiro反向代理","priceFactor":0.600000,"pricing":{"billingMode":"input_output","inputTokensPer100Balance":555.5,"outputTokensPer100Balance":111.1,"inputPricePerMillionTokens":1.8,"outputPricePerMillionTokens":9}}]},"timestamp":"2026-10-23T06:24:25Z"}""");
            check(json, "noPoolsOfAProvider", ok(new SourceTierDtos.MyTiers("OpenAI", null, List.of())),
                """
                {"success":true,"data":{"provider":"OpenAI","tiers":[]},"timestamp":"2026-10-23T06:24:25Z"}""");
        });
    }

    @Test
    void requestBodiesAreReadLeniently() {
        ProductionJson.run(json -> {
            // fields clients leave out: wrappers become null, primitives take their default
            read(json, "omittedPrimitives",
                """
                {"content":{"title":"t","category":"c","summary":"s","body":"b"},"version":3}""",
                DocumentationDtos.Save.class, "Save[content=Content[title=t, category=c, summary=s, body=b, sortOrder=0, english=null], version=3, publish=false]");
            // explicit null where a primitive is expected: Jackson 2 stored the default instead of rejecting the request
            read(json, "nullPrimitives",
                """
                {"content":{"title":"t","category":"c","summary":"s","body":"b","sortOrder":null},"version":null,"publish":null}""",
                DocumentationDtos.Save.class, "Save[content=Content[title=t, category=c, summary=s, body=b, sortOrder=0, english=null], version=0, publish=false]");
            read(json, "emptyObjectForPrimitivesOnly", "{}", AdminDtos.AdminPermissions.class, "AdminPermissions[root=false]");
            read(json, "unknownPropertiesAreIgnored",
                """
                {"name":"k","creditsLimit":10.50,"modelProvider":"OPENAI","surprise":{"a":[1,2]},"another":1}""",
                ApiKeyDtos.CreateApiKeyRequest.class, "CreateApiKeyRequest[name=k, creditsLimit=10.50, modelProvider=OPENAI, billingSource=null, tierPreference=null]");
            read(json, "decimalsKeepTheirScale",
                """
                {"name":"k","creditsLimit":10.500,"modelProvider":"OPENAI","billingSource":"BALANCE"}""",
                ApiKeyDtos.CreateApiKeyRequest.class, "CreateApiKeyRequest[name=k, creditsLimit=10.500, modelProvider=OPENAI, billingSource=BALANCE, tierPreference=null]");
            read(json, "decimalsFromStringsAndExponents",
                """
                {"amount":"10.10","paymentType":"alipay","mobile":false}""",
                PaymentDtos.CreatePaymentOrderRequest.class, "CreatePaymentOrderRequest[amount=10.10, paymentType=alipay, returnUrl=null, mobile=false]");
            read(json, "exponentDecimal",
                """
                {"amount":1e2,"paymentType":"alipay"}""",
                PaymentDtos.CreatePaymentOrderRequest.class, "CreatePaymentOrderRequest[amount=1E+2, paymentType=alipay, returnUrl=null, mobile=null]");
            read(json, "instantsFromIsoStrings",
                """
                {"enabled":true,"inviteRebateStartAt":"2026-10-23T06:24:25.123456Z","inviteRebateEndAt":"2026-10-23T08:24:25+02:00"}""",
                PaymentDtos.UpdatePaymentSettingsRequest.class, "UpdatePaymentSettingsRequest[enabled=true, minAmount=null, maxAmount=null, dailyLimit=null, orderTimeoutMinutes=null, maxPendingOrders=null, enabledTypes=null, balanceRechargeMultiplier=null, apiBillingMultiplier=null, rechargeFeeRate=null, productNamePrefix=null, productNameSuffix=null, helpImageUrl=null, helpText=null, easypayApiBase=null, easypayPid=null, easypayPkey=null, easypayPaymentMode=null, inviteRebateEnabled=null, inviteRebateRate=null, inviteRebateMaxOrders=null, inviteRebateStartAt=2026-10-23T06:24:25.123456Z, inviteRebateEndAt=2026-10-23T06:24:25Z]");
            read(json, "instantsFromEpochSeconds",
                """
                {"balance":1.5,"expiresAt":1761200665.5}""",
                PaymentDtos.CreateRedemptionCodeRequest.class, "CreateRedemptionCodeRequest[balance=1.5, planId=null, count=null, expiresAt=2025-10-23T06:24:25.500Z]");
            read(json, "multipleConstructorsUseTheCanonicalOne",
                """
                {"id":"m","name":"M","requestFormat":"OPENAI_CHAT_COMPLETIONS","endpoint":"/v1/chat/completions","contextWindow":"128000"}""",
                ModelDtos.UpsertManagedModelRequest.class, "UpsertManagedModelRequest[id=m, name=M, icon=null, provider=null, family=null, targetModelId=null, requestFormat=OPENAI_CHAT_COMPLETIONS, endpoint=/v1/chat/completions, contextWindow=128000, description=null, enabled=null, badgeColor=null]");
            read(json, "renamedProperties",
                """
                {"id":"m","object":"model","created":1,"owned_by":"openai","context_length":8192,"max_output_tokens":1024,"supportedInputTypes":["text"]}""",
                ModelDtos.ModelListItem.class, "ModelListItem[id=m, object=model, created=1, ownedBy=openai, provider=null, family=null, targetModelId=null, name=null, modelName=null, description=null, supportedInputTypes=[text], maxInputTokens=0, maxOutputTokens=0, contextLength=8192, maxOutputTokensCompatible=1024, billingMode=null, tokensPer100Balance=null, inputTokensPer100Balance=null, outputTokensPer100Balance=null, cacheWriteTokensPer100Balance=null, cacheReadTokensPer100Balance=null, totalPricePerMillionTokens=null, inputPricePerMillionTokens=null, outputPricePerMillionTokens=null, cacheWritePricePerMillionTokens=null, cacheReadPricePerMillionTokens=null, icon=null, requestFormat=null, endpoint=null, sourceTiers=null]");
            read(json, "chromeSurvivesPartialPatch",
                """
                {"siteName":"Starry API","chrome":"editorial","sidebarOpacity":0.62,"blurPx":18}""",
                AppearanceDtos.UpdateAppearanceRequest.class, "UpdateAppearanceRequest[siteName=Starry API, preset=null, primary=null, primaryMid=null, accent=null, success=null, warning=null, danger=null, header=null, text=null, textSecondary=null, textMuted=null, sidebarOpacity=0.62, surfaceOpacity=null, blurPx=18, backgroundBlurPx=null, clearLogo=null, clearFavicon=null, clearBackground=null, restoreDefault=null, chrome=editorial]");
            read(json, "listsAndWrappers",
                """
                {"name":"alias","mappingType":"FALLBACK","sourceModel":"gpt-5","targetModels":["a","b"],"weights":[70,"30"],"priority":null}""",
                ModelDtos.CreateMappingRequest.class, "CreateMappingRequest[name=alias, mappingType=FALLBACK, sourceModel=gpt-5, targetModels=[a, b], weights=[70, 30], priority=null, apiKeyIds=null]");
            read(json, "wrongShapeIsRejected", "[]", ApiKeyDtos.CreateApiKeyRequest.class, "HTTP 400");
            read(json, "scalarCoercion",
                """
                {"status":"RESOLVED","version":"7"}""",
                TicketDtos.StatusUpdate.class, "StatusUpdate[status=RESOLVED, version=7]");
            read(json, "trailingTokens",
                """
                {"status":"RESOLVED","version":7} trailing""",
                TicketDtos.StatusUpdate.class, "StatusUpdate[status=RESOLVED, version=7]");
        });
    }

    /** {@link ApiResponse#ok} stamps the current time; the tests need a fixed one. */
    private static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, T1);
    }

    /** Enum constants travel by name even when toString() is overridden; Jackson 3 would switch to toString(). */
    enum Level {
        LOW, HIGH;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    record Flagged(Level level, int count) {
    }

    @Test
    void enumsTravelByConstantName() {
        ProductionJson.run(json -> {
            check(json, "enumWrite", new Flagged(Level.HIGH, 2), """
                {"level":"HIGH","count":2}""");
            read(json, "enumRead", """
                {"level":"HIGH","count":2}""", Flagged.class, "Flagged[level=high, count=2]");
            read(json, "enumReadByToStringIsRejected", """
                {"level":"high","count":2}""", Flagged.class, "HTTP 400");
        });
    }

    /** A plain bean keeps its declaration order on the wire; Jackson 3 would sort its properties alphabetically. */
    public static class Bean {
        public String zeta = "z";
        public int alpha = 1;
        private final String mid = "m";

        public String getMid() {
            return mid;
        }
    }

    @Test
    void beansKeepDeclarationOrder() {
        ProductionJson.run(json -> check(json, "bean", new Bean(), """
            {"zeta":"z","alpha":1,"mid":"m"}"""));
    }

    @Test
    void nullsAreOmittedFromMapsButNotFromLists() {
        ProductionJson.run(json -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("b", null);
            map.put("a", 1);
            check(json, "mapWithNull", map, """
                {"a":1}""");
            check(json, "listWithNull", java.util.Arrays.asList("x", null), """
                ["x",null]""");
        });
    }

    private static Map<String, Long> orderedCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("OPEN", 3L);
        counts.put("CLOSED", 1L);
        counts.put("ANSWERED", 2L);
        return counts;
    }

    private static void check(ProductionJson.Session json, String name, Object value, String expected) throws Exception {
        String viaCodec = json.write(value);
        String viaMapper = json.writeWithMapper(value);
        assertThat(viaCodec).as(name + " via WebFlux codec").isEqualTo(expected);
        assertThat(viaMapper).as(name + " via injected mapper").isEqualTo(expected);
    }

    private static void read(ProductionJson.Session json, String name, String body, Class<?> type, String expected) throws Exception {
        String viaCodec = json.read(body, type);
        String viaMapper = json.readWithMapper(body, type);
        assertThat(viaCodec).as(name + " via WebFlux codec").isEqualTo(expected);
        if (viaCodec.startsWith("HTTP ")) {
            assertThat(viaMapper).as(name + " via injected mapper").startsWith("FAIL ");
        } else {
            assertThat(viaMapper).as(name + " via injected mapper").isEqualTo(expected);
        }
    }
}

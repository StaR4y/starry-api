package xyz.star4y.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;

@Component
public class AccountImportParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public AccountDtos.ImportAccountsRequest parse(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            throw invalid("Account import body is required");
        }

        return new AccountDtos.ImportAccountsRequest(parseDocuments(body), booleanValue(body.path("upsert")));
    }

    private List<AccountDtos.CreateAccountRequest> parseDocuments(JsonNode body) {
        if (body.isObject()) {
            JsonNode documents = firstExisting(body, "documents", "imports", "files");
            if (documents.isArray() && !documents.isEmpty()) {
                List<AccountDtos.CreateAccountRequest> accounts = new ArrayList<>();
                for (JsonNode document : documents) {
                    accounts.addAll(parseDocument(document));
                }
                if (accounts.isEmpty()) {
                    throw invalid("Account import documents did not contain any accounts");
                }
                return accounts;
            }
        }
        return parseDocument(body);
    }

    private List<AccountDtos.CreateAccountRequest> parseDocument(JsonNode body) {
        List<AccountDtos.CreateAccountRequest> accounts = new ArrayList<>();
        accounts.addAll(parseOpenAiCompatibility(body));
        if (body.isObject() && looksLikeOpenAiCompatibilityGroup(body)) {
            accounts.addAll(openAiCompatibilityGroup(body));
        }

        if (body.isArray()) {
            for (JsonNode item : body) {
                accounts.addAll(parseDocument(item));
            }
        } else if (body.isObject()) {
            JsonNode accountsNode = body.path("accounts");
            if (accountsNode.isArray()) {
                for (JsonNode item : accountsNode) {
                    accounts.addAll(parseDocument(item));
                }
            } else if (accounts.isEmpty() && looksLikeAccount(body)) {
                accounts.add(parseAccount(body));
            }
        }
        if (accounts.isEmpty()) {
            throw invalid("Account import body must be an account object, an array, or an object with accounts array");
        }
        return accounts;
    }

    private AccountDtos.CreateAccountRequest parseAccount(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }

        List<JsonNode> sources = accountSources(item);
        JsonNode usage = item.path("usage");
        String type = firstText(sources, "type");
        String provider = firstNonBlank(firstText(sources, "provider", "idp"), type);
        String authMethod = AccountPlatforms.normalizeAuthMethod(
            firstText(sources, "authMethod", "auth_method", "auth-method"),
            provider
        );
        String normalizedProvider = AccountPlatforms.normalizeProvider(provider, authMethod);
        String idToken = firstText(sources, "idToken", "id_token");
        Instant tokenExpiresAt = firstInstant(sources, "tokenExpiresAt", "token_expires_at", "expiresAt", "expires_at");
        JwtIdentity jwtIdentity = jwtIdentity(idToken);
        String email = firstNonBlank(firstText(sources, "email"), jwtIdentity.email());
        String accessToken = firstText(
            sources,
            "accessToken",
            "access_token",
            "token",
            "apiKey",
            "api_key",
            "api-key",
            "OPENAI_API_KEY"
        );
        String baseUrl = firstText(sources, "baseUrl", "base_url", "base-url");
        String upstreamAccountId = firstNonBlank(
            firstText(sources, "upstreamAccountId", "upstream_account_id", "accountId", "account_id"),
            jwtIdentity.accountId()
        );
        String accountId = firstText(item, null, "accountId", "account_id", "id");
        if (AccountPlatforms.isOpenAi(normalizedProvider, authMethod)) {
            accountId = firstNonBlank(accountId, openAiAccountId(firstNonBlank(upstreamAccountId, email, accessToken)));
        }

        return new AccountDtos.CreateAccountRequest(
            accountId,
            email,
            accessToken,
            firstText(sources, "refreshToken", "refresh_token"),
            firstText(sources, "clientId", "client_id", "client-id"),
            firstText(sources, "clientSecret", "client_secret", "client-secret"),
            firstText(sources, "region"),
            authMethod,
            normalizedProvider,
            AccountPlatforms.defaultBaseUrl(normalizedProvider, authMethod, baseUrl),
            upstreamAccountId,
            idToken,
            tokenExpiresAt,
            firstText(item, null, "profileArn", "profile_arn"),
            firstText(item, null, "machineId", "machine_id"),
            firstText(sources, "proxyUrl", "proxy_url", "proxy-url"),
            firstLong(item.path("quotaLimit"), item.path("quota_limit"), usage.path("limit")),
            firstLong(item.path("quota5hLimit"), item.path("quota_5h_limit"), usage.path("limit5h"), usage.path("limit_5h")),
            firstLong(item.path("quota7dLimit"), item.path("quota_7d_limit"), usage.path("limit7d"), usage.path("limit_7d"))
        );
    }

    private List<AccountDtos.CreateAccountRequest> parseOpenAiCompatibility(JsonNode body) {
        JsonNode groups = firstExisting(body, "openai-compatibility", "openaiCompatibility", "openai_compatibility");
        if (groups.isMissingNode() || groups.isNull() || (groups.isArray() && groups.isEmpty())) {
            return List.of();
        }

        List<AccountDtos.CreateAccountRequest> accounts = new ArrayList<>();
        Iterable<JsonNode> groupItems = groups.isArray() ? groups : List.of(groups);
        for (JsonNode group : groupItems) {
            if (!group.isObject()) {
                continue;
            }
            accounts.addAll(openAiCompatibilityGroup(group));
        }
        return accounts;
    }

    private List<AccountDtos.CreateAccountRequest> openAiCompatibilityGroup(JsonNode group) {
        String providerName = firstText(group, null, "name", "provider", "id");
        String baseUrl = firstText(group, null, "baseUrl", "base_url", "base-url");
        JsonNode entries = firstExisting(group, "apiKeyEntries", "api-key-entries", "api_key_entries", "keys");
        if (!entries.isArray() || entries.isEmpty()) {
            return List.of(openAiApiKeyAccount(providerName, baseUrl, group));
        }
        List<AccountDtos.CreateAccountRequest> accounts = new ArrayList<>();
        for (JsonNode entry : entries) {
            accounts.add(openAiApiKeyAccount(providerName, baseUrl, entry));
        }
        return accounts;
    }

    private AccountDtos.CreateAccountRequest openAiApiKeyAccount(String providerName, String baseUrl, JsonNode entry) {
        String accessToken = firstText(
            entry,
            null,
            "apiKey",
            "api_key",
            "api-key",
            "accessToken",
            "access_token",
            "token"
        );
        String email = firstText(entry, null, "email", "name");
        String upstreamAccountId = firstText(entry, null, "upstreamAccountId", "upstream_account_id", "accountId", "account_id", "id");
        String proxyUrl = firstText(entry, null, "proxyUrl", "proxy_url", "proxy-url");
        String seed = openAiCompatibilitySeed(providerName, baseUrl, proxyUrl, accessToken, upstreamAccountId, email);
        return new AccountDtos.CreateAccountRequest(
            firstNonBlank(firstText(entry, null, "accountId", "account_id"), openAiAccountId(seed)),
            email,
            accessToken,
            null,
            null,
            null,
            null,
            AccountPlatforms.OPENAI_API_KEY_AUTH,
            AccountPlatforms.OPENAI_PROVIDER,
            AccountPlatforms.defaultBaseUrl(AccountPlatforms.OPENAI_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH, baseUrl),
            upstreamAccountId,
            null,
            null,
            null,
            null,
            proxyUrl,
            firstLong(entry.path("quotaLimit"), entry.path("quota_limit")),
            firstLong(entry.path("quota5hLimit"), entry.path("quota_5h_limit")),
            firstLong(entry.path("quota7dLimit"), entry.path("quota_7d_limit"))
        );
    }

    private boolean looksLikeAccount(JsonNode body) {
        if (body == null || !body.isObject()) {
            return false;
        }
        return body.has("credentials")
            || body.has("accessToken")
            || body.has("access_token")
            || body.has("OPENAI_API_KEY")
            || body.has("apiKey")
            || body.has("api_key")
            || body.has("api-key")
            || body.has("refreshToken")
            || body.has("refresh_token")
            || looksLikeCredentialObject(body.path("token"))
            || looksLikeCredentialObject(body.path("tokens"))
            || looksLikeCredentialObject(body.path("auth"))
            || looksLikeCredentialObject(body.path("metadata"))
            || body.has("type")
            || body.has("provider");
    }

    private boolean looksLikeOpenAiCompatibilityGroup(JsonNode body) {
        if (body == null || !body.isObject()) {
            return false;
        }
        return body.has("apiKeyEntries")
            || body.has("api-key-entries")
            || body.has("api_key_entries")
            || body.has("keys");
    }

    private List<JsonNode> accountSources(JsonNode item) {
        List<JsonNode> sources = new ArrayList<>();
        addSource(sources, item);
        addSource(sources, item.path("credentials"));
        addSource(sources, item.path("token"));
        addSource(sources, item.path("tokens"));
        addSource(sources, item.path("auth"));
        addSource(sources, item.path("metadata"));
        return sources;
    }

    private void addSource(List<JsonNode> sources, JsonNode node) {
        if (node != null && node.isObject()) {
            sources.add(node);
        }
    }

    private boolean looksLikeCredentialObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.has("accessToken")
            || node.has("access_token")
            || node.has("OPENAI_API_KEY")
            || node.has("apiKey")
            || node.has("api_key")
            || node.has("api-key")
            || node.has("refreshToken")
            || node.has("refresh_token")
            || node.has("idToken")
            || node.has("id_token");
    }

    private JsonNode firstExisting(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return OBJECT_MAPPER.missingNode();
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return OBJECT_MAPPER.missingNode();
    }

    private String firstText(JsonNode primary, JsonNode secondary, String... names) {
        List<JsonNode> sources = new ArrayList<>();
        addSource(sources, primary);
        addSource(sources, secondary);
        return firstText(sources, names);
    }

    private String firstText(List<JsonNode> sources, String... names) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        for (JsonNode source : sources) {
            for (String name : names) {
                String value = text(source, name);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
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

    private String text(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isTextual() ? clean(value.asText()) : clean(value.asText(null));
    }

    private Long firstLong(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.canConvertToLong()) {
                return node.asLong();
            }
            if (node.isTextual()) {
                try {
                    return Long.parseLong(node.asText().trim());
                } catch (NumberFormatException ignored) {
                    // Ignore malformed optional quota fields.
                }
            }
        }
        return null;
    }

    private Instant firstInstant(List<JsonNode> sources, String... names) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        for (JsonNode source : sources) {
            for (String name : names) {
                Instant value = instant(source.path(name));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private Instant instant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            if (node.canConvertToLong()) {
                return epochInstant(node.asLong());
            }
            String value = node.isTextual() ? clean(node.asText()) : clean(node.asText(null));
            if (value == null) {
                return null;
            }
            try {
                return epochInstant(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                return Instant.parse(value);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Instant epochInstant(long value) {
        return value > 9_999_999_999L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    private Boolean booleanValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return Boolean.parseBoolean(node.asText());
        }
        return null;
    }

    private JwtIdentity jwtIdentity(String idToken) {
        String token = clean(idToken);
        if (token == null) {
            return JwtIdentity.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return JwtIdentity.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            JsonNode claims = OBJECT_MAPPER.readTree(payload);
            JsonNode auth = claims.path("https://api.openai.com/auth");
            String accountId = firstNonBlank(
                text(auth, "chatgpt_account_id"),
                firstNonBlank(text(claims, "account_id"), text(claims, "https://api.openai.com/account_id"))
            );
            String email = firstNonBlank(text(claims, "email"), text(claims, "https://api.openai.com/email"));
            return new JwtIdentity(accountId, email);
        } catch (Exception ignored) {
            return JwtIdentity.empty();
        }
    }

    private String padBase64(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }

    private String openAiAccountId(String seed) {
        String cleaned = clean(seed);
        if (cleaned == null) {
            return null;
        }
        return "openai-" + Hashing.sha256(cleaned).substring(0, 12);
    }

    private String openAiCompatibilitySeed(
        String providerName,
        String baseUrl,
        String proxyUrl,
        String accessToken,
        String upstreamAccountId,
        String email
    ) {
        return String.join(
            "\u0000",
            "openai-compatibility",
            emptyIfNull(providerName),
            emptyIfNull(baseUrl),
            emptyIfNull(proxyUrl),
            emptyIfNull(firstNonBlank(accessToken, upstreamAccountId, email, providerName, baseUrl))
        );
    }

    private String emptyIfNull(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "" : cleaned;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT_IMPORT", message);
    }

    private record JwtIdentity(String accountId, String email) {
        static JwtIdentity empty() {
            return new JwtIdentity(null, null);
        }
    }
}

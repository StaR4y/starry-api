package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import xyz.star4y.api.common.ApiException;

class AccountImportParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccountImportParser parser = new AccountImportParser();

    @Test
    void parsesKiroAccountManagerExportFormat() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "version": "0.12.155",
              "exportedAt": 1760000000000,
              "accounts": [
                {
                  "id": "acc-1",
                  "email": "user@example.com",
                  "idp": "BuilderId",
                  "profileArn": "arn:test",
                  "machineId": "machine-1",
                  "credentials": {
                    "accessToken": "access-1",
                    "refreshToken": "refresh-1",
                    "clientId": "client-1",
                    "clientSecret": "secret-1",
                    "region": "us-west-2",
                    "authMethod": "IdC",
                    "provider": "BuilderId"
                  },
                  "usage": {
                    "limit": 250000
                  }
                }
              ],
              "groups": [],
              "tags": []
            }
            """));

        AccountDtos.CreateAccountRequest account = request.accounts().get(0);
        assertThat(request.upsert()).isNull();
        assertThat(account.accountId()).isEqualTo("acc-1");
        assertThat(account.email()).isEqualTo("user@example.com");
        assertThat(account.accessToken()).isEqualTo("access-1");
        assertThat(account.refreshToken()).isEqualTo("refresh-1");
        assertThat(account.clientId()).isEqualTo("client-1");
        assertThat(account.clientSecret()).isEqualTo("secret-1");
        assertThat(account.region()).isEqualTo("us-west-2");
        assertThat(account.authMethod()).isEqualTo("idc");
        assertThat(account.provider()).isEqualTo("BuilderId");
        assertThat(account.profileArn()).isEqualTo("arn:test");
        assertThat(account.machineId()).isEqualTo("machine-1");
        assertThat(account.quotaLimit()).isEqualTo(250000L);
    }

    @Test
    void keepsLegacyImportWrapperCompatible() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "upsert": true,
              "accounts": [
                {
                  "accountId": "legacy-1",
                  "email": "legacy@example.com",
                  "accessToken": "access-legacy",
                  "region": "us-east-1",
                  "authMethod": "idc",
                  "provider": "BuilderID"
                }
              ]
            }
            """));

        AccountDtos.CreateAccountRequest account = request.accounts().get(0);
        assertThat(request.upsert()).isTrue();
        assertThat(account.accountId()).isEqualTo("legacy-1");
        assertThat(account.accessToken()).isEqualTo("access-legacy");
        assertThat(account.provider()).isEqualTo("BuilderId");
    }

    @Test
    void acceptsSingleCodexTokenObject() throws Exception {
        String idToken = codexIdToken("codex@example.com", "acct_codex_123");
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "type": "codex",
              "id_token": "%s",
              "access_token": "codex-access",
              "refresh_token": "codex-refresh"
            }
            """.formatted(idToken)));

        AccountDtos.CreateAccountRequest account = request.accounts().get(0);
        assertThat(account.provider()).isEqualTo(AccountPlatforms.OPENAI_PROVIDER);
        assertThat(account.authMethod()).isEqualTo(AccountPlatforms.OPENAI_CODEX_AUTH);
        assertThat(account.email()).isEqualTo("codex@example.com");
        assertThat(account.accessToken()).isEqualTo("codex-access");
        assertThat(account.refreshToken()).isEqualTo("codex-refresh");
        assertThat(account.baseUrl()).isEqualTo(AccountPlatforms.DEFAULT_CODEX_BASE_URL);
        assertThat(account.upstreamAccountId()).isEqualTo("acct_codex_123");
        assertThat(account.accountId()).startsWith("openai-");
    }

    @Test
    void acceptsNestedCodexAuthJsonTokenObject() throws Exception {
        String idToken = codexIdToken("nested@example.com", "acct_nested_123");
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "type": "codex",
              "token": {
                "id_token": "%s",
                "access_token": "nested-access",
                "refresh_token": "nested-refresh"
              }
            }
            """.formatted(idToken)));

        AccountDtos.CreateAccountRequest account = request.accounts().get(0);
        assertThat(account.provider()).isEqualTo(AccountPlatforms.OPENAI_PROVIDER);
        assertThat(account.authMethod()).isEqualTo(AccountPlatforms.OPENAI_CODEX_AUTH);
        assertThat(account.email()).isEqualTo("nested@example.com");
        assertThat(account.accessToken()).isEqualTo("nested-access");
        assertThat(account.refreshToken()).isEqualTo("nested-refresh");
        assertThat(account.upstreamAccountId()).isEqualTo("acct_nested_123");
        assertThat(account.accountId()).startsWith("openai-");
    }

    @Test
    void acceptsCoreAuthMetadataTokenObject() throws Exception {
        String idToken = codexIdToken("metadata@example.com", "acct_metadata_123");
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "id": "codex-auth-file",
              "provider": "codex",
              "metadata": {
                "id_token": "%s",
                "access_token": "metadata-access",
                "refresh_token": "metadata-refresh"
              }
            }
            """.formatted(idToken)));

        AccountDtos.CreateAccountRequest account = request.accounts().get(0);
        assertThat(account.accountId()).isEqualTo("codex-auth-file");
        assertThat(account.provider()).isEqualTo(AccountPlatforms.OPENAI_PROVIDER);
        assertThat(account.authMethod()).isEqualTo(AccountPlatforms.OPENAI_CODEX_AUTH);
        assertThat(account.accessToken()).isEqualTo("metadata-access");
        assertThat(account.refreshToken()).isEqualTo("metadata-refresh");
        assertThat(account.upstreamAccountId()).isEqualTo("acct_metadata_123");
    }

    @Test
    void acceptsOpenAiCompatibilityConfig() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "upsert": true,
              "openai-compatibility": [
                {
                  "name": "openrouter",
                  "base-url": "https://openrouter.ai/api/v1",
                  "api-key-entries": [
                    {
                      "api-key": "sk-or-test",
                      "proxy-url": "direct"
                    }
                  ]
                }
              ]
            }
            """));

        AccountDtos.CreateAccountRequest account = request.accounts().get(0);
        assertThat(request.upsert()).isTrue();
        assertThat(account.provider()).isEqualTo(AccountPlatforms.OPENAI_PROVIDER);
        assertThat(account.authMethod()).isEqualTo(AccountPlatforms.OPENAI_API_KEY_AUTH);
        assertThat(account.accessToken()).isEqualTo("sk-or-test");
        assertThat(account.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(account.proxyUrl()).isEqualTo("direct");
        assertThat(account.accountId()).startsWith("openai-");
    }

    @Test
    void createsDistinctOpenAiCompatibilityAccountsForEachApiKeyEntry() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "openai-compatibility": [
                {
                  "name": "openrouter",
                  "base-url": "https://openrouter.ai/api/v1",
                  "api-key-entries": [
                    {
                      "id": "shared-source-id",
                      "api-key": "sk-or-a"
                    },
                    {
                      "id": "shared-source-id",
                      "api-key": "sk-or-b"
                    }
                  ]
                }
              ]
            }
            """));

        assertThat(request.accounts()).hasSize(2);
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::accessToken)
            .containsExactly("sk-or-a", "sk-or-b");
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::accountId)
            .doesNotHaveDuplicates()
            .allSatisfy(accountId -> assertThat(accountId).startsWith("openai-"));
    }

    @Test
    void preservesGoogleAndAnthropicProviderNamesForApiKeyAccounts() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "accounts": [
                {
                  "provider": "Gemini",
                  "authMethod": "api-key",
                  "accessToken": "google-key"
                },
                {
                  "provider": "Anthropic",
                  "authMethod": "api-key",
                  "accessToken": "anthropic-key"
                }
              ]
            }
            """));

        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::provider)
            .containsExactly(AccountPlatforms.GOOGLE_PROVIDER, AccountPlatforms.ANTHROPIC_PROVIDER);
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::authMethod)
            .containsExactly(AccountPlatforms.OPENAI_API_KEY_AUTH, AccountPlatforms.OPENAI_API_KEY_AUTH);
    }

    @Test
    void acceptsCombinedAccountsAndOpenAiCompatibilityBlocks() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "upsert": true,
              "accounts": [
                {
                  "accountId": "kiro-1",
                  "accessToken": "kiro-token",
                  "provider": "BuilderId"
                }
              ],
              "openai-compatibility": [
                {
                  "name": "openrouter",
                  "base-url": "https://openrouter.ai/api/v1",
                  "api-key-entries": [
                    {
                      "api-key": "sk-or-test"
                    }
                  ]
                }
              ]
            }
            """));

        assertThat(request.upsert()).isTrue();
        assertThat(request.accounts()).hasSize(2);
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::accessToken)
            .containsExactly("sk-or-test", "kiro-token");
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::provider)
            .containsExactly(AccountPlatforms.OPENAI_PROVIDER, "BuilderId");
    }

    @Test
    void acceptsMultipleImportDocuments() throws Exception {
        String idToken = codexIdToken("codex@example.com", "acct_codex_123");
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "upsert": true,
              "documents": [
                {
                  "accounts": [
                    {
                      "accountId": "kiro-1",
                      "accessToken": "kiro-token",
                      "provider": "BuilderId"
                    }
                  ]
                },
                {
                  "type": "codex",
                  "id_token": "%s",
                  "access_token": "codex-access"
                },
                [
                  {
                    "accountId": "raw-1",
                    "accessToken": "raw-token"
                  }
                ]
              ]
            }
            """.formatted(idToken)));

        assertThat(request.upsert()).isTrue();
        assertThat(request.accounts()).hasSize(3);
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::accessToken)
            .containsExactly("kiro-token", "codex-access", "raw-token");
    }

    @Test
    void acceptsRawOpenAiCompatibilityGroupDocument() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            {
              "name": "openrouter",
              "base-url": "https://openrouter.ai/api/v1",
              "api-key-entries": [
                {
                  "api-key": "sk-or-a"
                },
                {
                  "api-key": "sk-or-b"
                }
              ]
            }
            """));

        assertThat(request.accounts()).hasSize(2);
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::accessToken)
            .containsExactly("sk-or-a", "sk-or-b");
        assertThat(request.accounts()).extracting(AccountDtos.CreateAccountRequest::baseUrl)
            .containsExactly("https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1");
    }

    @Test
    void acceptsRawAccountArray() throws Exception {
        AccountDtos.ImportAccountsRequest request = parser.parse(objectMapper.readTree("""
            [
              {
                "email": "array@example.com",
                "accessToken": "access-array"
              }
            ]
            """));

        assertThat(request.accounts()).hasSize(1);
        assertThat(request.accounts().get(0).email()).isEqualTo("array@example.com");
        assertThat(request.accounts().get(0).accessToken()).isEqualTo("access-array");
    }

    @Test
    void rejectsMissingAccountsArray() throws Exception {
        assertThatThrownBy(() -> parser.parse(objectMapper.readTree("{}")))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("accounts array");
    }

    private String codexIdToken(String email, String accountId) {
        String payload = """
            {
              "email": "%s",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "%s"
              }
            }
            """.formatted(email, accountId);
        return "header."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + ".signature";
    }
}

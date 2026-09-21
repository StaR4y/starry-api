# API Reference

Default base URL:

```text
http://127.0.0.1:8080
```

Interactive OpenAPI UI:

```http
GET /docs
GET /v3/api-docs
```

## Authentication

Proxy APIs require a client API key unless anonymous proxy mode is enabled.

Use either header:

```http
Authorization: Bearer sk-...
X-Api-Key: sk-...
```

Admin APIs use a local admin session:

```http
Authorization: Bearer adm-...
```

The legacy static admin token remains supported when `KIRO_ADMIN_TOKEN` is configured:

```http
X-Admin-Token: change-me
Authorization: Bearer change-me
```

## Support Tickets

Console sessions are required for `/tickets`. Ordinary users only access their own tickets; administrators access all tickets. Unauthorized ownership is reported as 404. Client proxy API keys cannot access these endpoints.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/tickets` | Paginated list; `page=1`, `pageSize=20` (max 100), optional `status` and `search`; returns `items`, `total`, `page`, `pageSize`, scoped status `counts`. |
| POST | `/tickets` | Multipart creation: JSON part `request` with `title`, `category`, `body`; optional repeated `files` parts. |
| GET | `/tickets/{id}` | Latest 30 messages and ticket metadata; `before` loads older messages; returns `ticket`, chronological `messages`, `hasMore`, `nextBefore`. |
| POST | `/tickets/{id}/replies` | Multipart JSON `request` with `body`, required `version`, optional admin reply `status`; optional repeated `files`. |
| PATCH | `/tickets/{id}/status` | JSON `{ "status": "CLOSED", "version": 1 }`; stale versions return 409. |
| GET | `/tickets/{id}/attachments/{attachmentId}` | Authenticated binary download, no-store, attachment disposition. |
| GET / PUT | `/admin/ticket-settings` | Admin notification settings and SMTP/recipient/failure state. |
| POST | `/admin/ticket-settings/{id}/retry` | Admin retry of failed, eligible notifications; enabled notifications required. |

Categories: `GENERAL`, `API`, `BILLING`, `ACCOUNT`, `BUG`. Statuses: `OPEN`, `IN_PROGRESS`, `WAITING_USER`, `RESOLVED`, `CLOSED`. Replying as a user sets `OPEN`; administrator replies default to `WAITING_USER`. Ended tickets must be reopened before replying.

Notification PUT payload: `{ "enabled": false, "mode": "SUMMARY", "recipients": [], "reminderMinutes": 240, "consoleUrl": "https://example.com" }`. Mode can be `SUMMARY` or `CONTENT`; recipients must be active administrator email addresses, and empty means all such addresses. See [limits, delivery behavior and verification](SUPPORT_TICKETS.md).

## Admin Login

On first startup, the service creates:

```text
username: admin
password: <random password printed in startup logs once>
```

This default/initial administrator is the application-level root of trust. Compromise of its login password or session means the entire Starry API service must be treated as compromised. The data-management security key described below is a second confirmation for high-risk database operations; it is not a security boundary after the application root has been compromised. The generated bootstrap password appears once in startup output so the first login is possible; protect that output and rotate the password immediately. Never place login passwords or data-management keys in later logs, tickets, audit notes, or alert messages.

Login:

```http
POST /auth/login
Content-Type: application/json
```

```json
{
  "username": "admin",
  "password": "startup-random-password"
}
```

Fetch the current logged-in account, including the latest `balance`:

```http
GET /auth/me
Authorization: Bearer adm-...
```

`GET /auth/permissions` returns `data: {"root": true|false}` for the current session, with `Cache-Control: no-store`. Root is resolved from the immutable `application_root_user_id` and the account's current enabled/admin/password-change state, never from the username or browser storage. Regular administrators, users and the legacy static admin token receive `root: false`. The console uses this response to hide and guard Data Management and Plugin Development; the server independently enforces their permissions on every request.

If `user.firstLogin=true`, only password change is allowed:

```http
POST /auth/password
Authorization: Bearer adm-...
Content-Type: application/json
```

A successful password change revokes every console session for that account, including the calling session. New registration, manually supplied initial, and replacement passwords must contain 12 to 128 characters; login still accepts shorter legacy passwords so they can be rotated. Log in again with the new password.

```json
{
  "oldPassword": "startup-random-password",
  "newPassword": "new-strong-password"
}
```

Public admin registration is disabled by default. Clients can inspect the public registration policy without an admin token:

```http
GET /auth/registration-settings
```

When `registrationEnabled=true`, register with:

```http
POST /auth/register
Content-Type: application/json
```

```json
{
  "username": "user",
  "email": "user@example.com",
  "displayName": "User",
  "password": "strong-password",
  "verifyCode": "123456",
  "inviteCode": "INV-XXXX-XXXX-XXXX"
}
```

When `emailVerifyEnabled=true`, request a registration email code first:

```http
POST /auth/send-verify-code
Content-Type: application/json
```

```json
{
  "email": "user@example.com"
}
```

Administrators configure registration and SMTP settings through `GET/PATCH /admin/users/registration-settings`. SMTP fields follow the sub2api-style setting keys: `email_verify_enabled`, `smtp_host`, `smtp_port`, `smtp_username`, `smtp_password`, `smtp_from_email`, `smtp_from_name`, `smtp_use_tls`, and `smtp_security`. API JSON uses camelCase field names, and responses only expose `smtpPasswordConfigured`, never the stored password. `smtpSecurity` accepts `auto`, `ssl`, `starttls`, or `none`; Tencent Exmail should use `smtp.exmail.qq.com:465` with `ssl`, or port `587` with `starttls`.

When `inviteCodeEnabled=true`, administrators can generate one-time invite codes from `/admin/users/invite-codes`. A valid `inviteCode` lets the user register directly even when public `registrationEnabled=false`; invite-code registration skips email verification and consumes the code atomically.

## Response Envelope

Admin APIs return:

```json
{
  "success": true,
  "data": {},
  "timestamp": "2026-06-06T12:00:00Z"
}
```

Errors return:

```json
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Missing API key"
  },
  "timestamp": "2026-06-06T12:00:00Z"
}
```

Proxy APIs return the client platform's native response shape where possible.

## Public Proxy APIs

### Health

```http
GET /health
GET /v1/health
```

```json
{
  "status": "ok",
  "service": "starryapi-service",
  "uptime_ms": 12345
}
```

### List Models

```http
GET /v1/models
GET /models
```

The model list is OpenAI-compatible and includes platform metadata. It keeps the small set of required routing aliases and derives default OpenAI, Google/Gemini, Anthropic, and DeepSeek IDs from the bundled `model-price-repo` snapshot. Repository IDs use provider prefixes where the proxy needs them for deterministic routing.

```json
{
  "object": "list",
  "data": [
    {
      "id": "gemini-2.5-pro",
      "object": "model",
      "created": 1770000000,
      "owned_by": "starryapi",
      "provider": "Google",
      "family": "Gemini 2.5",
      "targetModelId": "claude-sonnet-4.5",
      "name": "Gemini 2.5 Pro compatible alias",
      "modelName": "Gemini 2.5 Pro compatible alias",
      "description": "Google model alias for the unified Starry API routing layer",
      "supportedInputTypes": ["TEXT", "IMAGE"],
      "maxInputTokens": 200000,
      "maxOutputTokens": 64000,
      "context_length": 200000,
      "max_output_tokens": 64000,
      "billingMode": "input_output",
      "inputTokensPer100Balance": 80000000,
      "outputTokensPer100Balance": 10000000,
      "cacheWriteTokensPer100Balance": 80000000,
      "cacheReadTokensPer100Balance": 800000000,
      "totalPricePerMillionTokens": 5.625,
      "inputPricePerMillionTokens": 1.25,
      "outputPricePerMillionTokens": 10.0,
      "cacheWritePricePerMillionTokens": 1.25,
      "cacheReadPricePerMillionTokens": 0.125,
      "icon": null,
      "requestFormat": "OPENAI_CHAT_COMPLETIONS",
      "endpoint": "/v1/chat/completions"
    }
  ],
  "models": [
    {
      "id": "gemini-2.5-pro",
      "object": "model",
      "created": 1770000000,
      "owned_by": "starryapi",
      "provider": "Google",
      "family": "Gemini 2.5",
      "targetModelId": "claude-sonnet-4.5",
      "name": "Gemini 2.5 Pro compatible alias",
      "modelName": "Gemini 2.5 Pro compatible alias",
      "description": "Google model alias for the unified Starry API routing layer",
      "supportedInputTypes": ["TEXT", "IMAGE"],
      "maxInputTokens": 200000,
      "maxOutputTokens": 64000,
      "context_length": 200000,
      "max_output_tokens": 64000,
      "billingMode": "input_output",
      "inputTokensPer100Balance": 80000000,
      "outputTokensPer100Balance": 10000000,
      "cacheWriteTokensPer100Balance": 80000000,
      "cacheReadTokensPer100Balance": 800000000,
      "totalPricePerMillionTokens": 5.625,
      "inputPricePerMillionTokens": 1.25,
      "outputPricePerMillionTokens": 10.0,
      "cacheWritePricePerMillionTokens": 1.25,
      "cacheReadPricePerMillionTokens": 0.125,
      "icon": null,
      "requestFormat": "OPENAI_CHAT_COMPLETIONS",
      "endpoint": "/v1/chat/completions"
    }
  ]
}
```

`models` is a compatibility alias of `data`. Disabled administrator-managed entries are omitted from both arrays.

Public model entries expose both token capacity per 100 balance and the exact effective balance price per 1M tokens. By default the service loads input, output, cache-write, and cache-read USD token costs from [`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) and multiplies each cost by the administrator's model billing multiplier. The default multiplier is `1.0`, so the numeric balance charge equals official USD cost. Itemized models populate the four detailed `*PricePerMillionTokens` fields and expose `totalPricePerMillionTokens` as a user-facing 50/50 input-output reference price. Runtime billing still uses actual token classes. Legacy fixed-total rules expose their actual flat total price, including wildcard rules.

Pricing resolution is deterministic: enabled rules sort by ascending priority, then exact model, catalog family, catalog provider, and wildcard at equal priority. Wildcards sort by descending literal length. Requested-alias exact matches precede target exact matches; remaining ties use pattern and rule ID. Only the winning rule applies. Its multiplier directly replaces the global ratio, so `0.1` always means 10% of official price. Effective prices are exposed on `/v1/models` and shown on the `模型 ID` page. Known prefixes (`starry/`, `openai-compatible/`, `openai/`, `anthropic/`, `google/`, `gemini/`, `deepseek/`) are normalized for exact rules as well as pricing lookup. The model's own exact/normalized official price is preferred; otherwise configured catalog targets are followed. No family/provider median is used. Without a verified price or explicit legacy fixed price, entries expose `billingMode: "unavailable"` and no numeric prices; billable requests return HTTP 503 / `MODEL_PRICE_UNAVAILABLE` before reservation and upstream dispatch.

Current provider groups:

| Provider | Families |
| --- | --- |
| Anthropic | Routing Preset, Claude 4, Claude 3, CodeWhisperer Internal |
| OpenAI | GPT-5, GPT-4, GPT-3.5, OpenAI Reasoning, OpenAI OSS, Audio, Image |
| Google | Gemini 3.7/3.6/3.5/3.1/3, Gemini 2.5/2.0/1.5, Gemma, Lyria, Google |
| DeepSeek | DeepSeek, DeepSeek V4 |

Administrators can add providers and families through `/admin/models`, so clients should use the `provider` and `family` values returned by the API instead of treating this table as a closed enum.

### OpenAI Chat Completions

```http
POST /v1/chat/completions
POST /chat/completions
```

```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are concise."},
    {"role": "user", "content": "Say hello."}
  ],
  "temperature": 0.7,
  "top_p": 1,
  "max_tokens": 1024,
  "stream": false,
  "conversation_id": "optional-session-id"
}
```

Supported fields:

| Field | Notes |
| --- | --- |
| `model` | Required. Model mapping is applied before upstream routing. Max 256 characters. |
| `messages` | Required. Supports `system`, `user`, `assistant`, and `tool` roles. |
| `temperature`, `top_p`, `max_tokens` | Forwarded to upstream inference config. |
| `stream` | Emits SSE after upstream completion. True upstream passthrough is not implemented yet. |
| `conversation_id`, `conversationId`, `thread_id`, `session_id` | Session affinity hints. |
| `tools` | Basic OpenAI function tool schema conversion. |
| `thinking` | Forwarded as additional upstream model request fields. |

The cataloged `deepseek-chat`, `deepseek-reasoner`, `deepseek-v4-flash`, and `deepseek-v4-pro` IDs support Chat Completions through the isolated DeepSeek account pool. Use the bare IDs for official routing; `starry/` is also accepted and removed before lookup. Provider-scoped API keys reject route-only aliases that are absent from the enabled catalog. `openai-compatible/` selects the generic third-party OpenAI-compatible pool only after that prefixed model ID has been registered in the key's provider catalog.

### OpenAI Responses

```http
POST /v1/responses
POST /responses
```

```json
{
  "model": "gpt-4o",
  "input": "Say hello.",
  "instructions": "You are concise.",
  "temperature": 0.7,
  "top_p": 1,
  "max_output_tokens": 1024,
  "stream": false
}
```

`platform/openai/OpenAiRequestAdapter` converts Responses input, function calls, and function outputs into the internal chat shape before routing. For DeepSeek, Responses currently supports only `deepseek-v4-flash`; requesting `deepseek-v4-pro` through this endpoint returns `400`, so Pro callers must use Chat Completions.

### Anthropic Messages

```http
POST /v1/messages
POST /messages
```

```json
{
  "model": "claude-sonnet-4.5",
  "system": "You are concise.",
  "messages": [
    {
      "role": "user",
      "content": [{"type": "text", "text": "Say hello."}]
    }
  ],
  "max_tokens": 1024,
  "stream": false
}
```

`platform/anthropic/AnthropicRequestAdapter` converts text blocks, tool use, tool result, and tool declarations into the internal chat shape.

Anthropic Messages requests and `anthropic/` model IDs first select native `provider=Anthropic`, `authMethod=api-key` accounts and use `AnthropicUpstreamClient`. When that subtype pool is unavailable, routing falls back to compatible Kiro/AWS accounts. Bare Claude model IDs used through OpenAI-compatible endpoints continue to follow the Kiro route unless a model mapping selects an `anthropic/` target.

### Gemini GenerateContent

```http
POST /v1beta/models/{model}:generateContent
POST /v1/models/{model}:generateContent
POST /models/{model}:generateContent
```

```json
{
  "systemInstruction": {
    "parts": [{"text": "You are concise."}]
  },
  "contents": [
    {
      "role": "user",
      "parts": [{"text": "Say hello."}]
    }
  ],
  "generationConfig": {
    "temperature": 0.7,
    "topP": 1,
    "maxOutputTokens": 1024
  }
}
```

Response shape:

```json
{
  "candidates": [
    {
      "content": {
        "role": "model",
        "parts": [{"text": "Hello."}]
      },
      "finishReason": "STOP",
      "index": 0
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 10,
    "candidatesTokenCount": 20,
    "totalTokenCount": 30
  },
  "modelVersion": "gemini-2.5-pro",
  "responseId": "gen-..."
}
```

`platform/gemini/GeminiRequestAdapter` supports text parts, `systemInstruction`, `generationConfig`, `functionDeclarations`, `functionCall`, and `functionResponse`.

Gemini is currently an inbound compatibility layer. Built-in Gemini catalog aliases resolve to Kiro-compatible target models during Kiro payload construction. Direct Google/Gemini API-key upstream dispatch, direct account testing, and `streamGenerateContent` are not implemented.

## Console and Admin APIs

### Accounts

Upstream account pool management requires an `ADMIN` account session. `USER` accounts receive `403 ADMIN_ROLE_REQUIRED`.

```http
GET    /admin/accounts
POST   /admin/accounts
POST   /admin/accounts/chatgpt-oauth/start
POST   /admin/accounts/chatgpt-oauth/{loginId}/poll
DELETE /admin/accounts/chatgpt-oauth/{loginId}
GET    /admin/accounts/settings
PATCH  /admin/accounts/settings
GET    /admin/accounts/pool-status
PATCH  /admin/accounts/{accountId}
GET    /admin/accounts/{accountId}/openai-quota
DELETE /admin/accounts/{accountId}
DELETE /admin/accounts
POST   /admin/accounts/import
POST   /admin/accounts/test
GET    /admin/accounts/test-models
POST   /admin/accounts/disabled-scheduled-test/run
POST   /admin/accounts/{accountId}/suspend
POST   /admin/accounts/{accountId}/reset
```

Create account:

```json
{
  "accountId": "optional-stable-id",
  "email": "user@example.com",
  "accessToken": "kiro-access-token",
  "refreshToken": "optional-refresh-token",
  "clientId": "optional-client-id",
  "clientSecret": "optional-client-secret",
  "region": "us-east-1",
  "authMethod": "idc",
  "provider": "BuilderID",
  "baseUrl": null,
  "upstreamAccountId": null,
  "profileArn": "arn:aws:codewhisperer:us-east-1:...",
  "machineId": "optional-machine-id",
  "proxyUrl": "http://user:pass@host:port",
  "quotaLimit": 1000000,
  "quota5hLimit": 200000,
  "quota7dLimit": 800000
}
```

Only `accessToken` is required for a basic Kiro upstream account. For official OpenAI API key accounts, use:

```json
{
  "accountId": "openai-main",
  "email": "team@example.com",
  "accessToken": "sk-...",
  "authMethod": "api-key",
  "provider": "OpenAI",
  "baseUrl": "https://api.openai.com/v1"
}
```

For an official DeepSeek API key account, use `provider=DeepSeek`. The provider normalizes `authMethod` to `api-key`, and omitting `baseUrl` selects `https://api.deepseek.com/v1`:

```json
{
  "accountId": "deepseek-main",
  "email": "deepseek@example.com",
  "accessToken": "sk-...",
  "authMethod": "api-key",
  "provider": "DeepSeek"
}
```

Accounts whose `baseUrl` host is `api.deepseek.com` are also recognized as DeepSeek for compatibility with records previously labeled `OpenAI-compatible`. Official DeepSeek model requests select only this DeepSeek pool; ordinary OpenAI/Codex selection excludes it, and DeepSeek never falls back across providers when its own pool is unavailable.

For a native Anthropic API-key account, use `provider=Anthropic` and `authMethod=api-key`. Omitting `baseUrl` selects `https://api.anthropic.com/v1`:

```json
{
  "accountId": "anthropic-main",
  "email": "claude@example.com",
  "accessToken": "sk-ant-...",
  "authMethod": "api-key",
  "provider": "Anthropic"
}
```

For other third-party OpenAI-compatible API key accounts, use `provider=OpenAI-compatible`, `authMethod=api-key`, the upstream site API key as `accessToken`, and the upstream API root as `baseUrl`:

```json
{
  "accountId": "openrouter-main",
  "email": "openrouter@example.com",
  "accessToken": "sk-or-...",
  "authMethod": "api-key",
  "provider": "OpenAI-compatible",
  "baseUrl": "https://openrouter.ai/api/v1"
}
```

For a generic OpenAI-compatible account, `baseUrl` may be either the site origin, such as `https://joystar.cc`, or a versioned API base, such as `https://openrouter.ai/api/v1`. When the path does not already end in an API version segment, Starry API calls `/v1/chat/completions` and `/v1/responses` by default.

Route third-party model IDs through model mappings by targeting `openai-compatible/<actual-model-id>`. Before creating the mapping, register both the public source ID and prefixed target ID as enabled `/admin/models` entries under the same provider, then create an API key for that provider. For example, map `llama-3.3-70b` to `openai-compatible/meta-llama/llama-3.3-70b-instruct`; Starry API uses the prefix to select generic OpenAI-compatible accounts and sends the unprefixed model ID to that upstream site. Provider-scoped keys reject unknown models and cross-provider mappings.

For Codex OAuth token accounts, use `authMethod=codex`, `provider=OpenAI`, `accessToken`, optional `refreshToken`, optional `idToken`, and optional `upstreamAccountId`. If `baseUrl` is omitted, Codex defaults to `https://chatgpt.com/backend-api/codex`.

The management console can obtain those Codex credentials through OpenAI's browser-based device
authorization flow, so administrators do not need to extract or paste tokens. Start a new login
with an empty object, or pass an existing Codex account ID to reauthorize it:

Device code login must first be enabled in the ChatGPT security settings for a personal account,
or in the ChatGPT workspace permissions by a workspace administrator. If OpenAI reports that
device authorization is disabled, enable it and start a new login to obtain a new code.

```http
POST /admin/accounts/chatgpt-oauth/start
Content-Type: application/json

{"accountId": null}
```

The response contains a server-generated `loginId`, the fixed OpenAI verification URL, a one-time
`userCode`, `expiresAt`, and the suggested polling interval. Open the verification URL in the
administrator's browser, enter the code, then call
`POST /admin/accounts/chatgpt-oauth/{loginId}/poll` until the status is `SUCCEEDED`, `FAILED`,
`EXPIRED`, or `CANCELLED`. A `PENDING` response includes `retryAfterSeconds`; clients must respect
it. Delete the login resource to cancel a pending flow.

Login sessions are held only in a bounded in-memory cache, expire after 15 minutes, are tied to the
administrator who created them, and are lost on application restart. Each administrator may have
at most three pending or completing logins. Access, refresh, and ID tokens are never returned by
these endpoints; successful credentials are written directly to the existing account record.
Reauthorizing a selected account also verifies the ChatGPT user/workspace identity before replacing
credentials. Older imported accounts without enough identity data must be added as a new browser
authorization instead of being overwritten.

This flow authorizes only the existing Codex upstream at
`https://chatgpt.com/backend-api/codex`; it does not proxy the ChatGPT web application. OpenAI may
disable device authorization for particular accounts or regions. Operators are responsible for
using authorized accounts and complying with the applicable OpenAI terms and policies. Manual
Codex token import remains available as a recovery path. Keep browser-authorized accounts on
trusted private deployments. Public, multi-tenant, CI, or otherwise untrusted programmatic
workloads should use an OpenAI Platform API-key account instead; see the
[official OpenAI authentication guidance](https://learn.chatgpt.com/docs/auth#mainContent).

The import endpoint accepts:

- Kiro Account Manager JSON exports.
- A single account object, an account array, or `{ "accounts": [...] }`.
- Multiple import documents as `{ "documents": [...] }`; each document may use any supported import shape.
- Codex token JSON with `type: "codex"`, `access_token`, `refresh_token`, and `id_token`.
- OpenAI-compatible config blocks shaped as `openai-compatibility[].api-key-entries[]` with `api-key`, optional `base-url`, and optional `proxy-url`.

Account identity is matched by normalized email during create/import. `upsert=false` skips an imported item when the email already exists; `upsert=true` updates that existing email record. Imported `accountId` values are treated as external/source identifiers only: if the requested `accountId` collides with an existing row but the email is different, the service creates a new account with a generated internal `accountId`.

The management console can upload multiple `.json` files, parse them in the browser, and submit one import request per file. Results are aggregated in the console after all files finish.

Account quota tracking supports a total quota plus 5-hour and 7-day rolling windows. `quotaUsed`, `quota5hUsed`, and `quota7dUsed` are incremented from successful upstream token usage; `quota5hResetAt` and `quota7dResetAt` advance when each rolling window starts over. The management console renders all three as remaining-quota progress bars.

Read an account's allocation policy and live admission snapshot with `GET /admin/accounts/{accountId}/allocation-policy`. Update one or more limits with `PATCH /admin/accounts/{accountId}/allocation-policy`; omitted fields retain their current values and each successful update increments `policyVersion`:

```json
{
  "allocationPriority": 50,
  "safeConcurrency": 6,
  "safeRpm": 120,
  "burstCapacity": 8,
  "maxSessions": 12,
  "sessionIdleSeconds": 900,
  "maxInflightTokens": 2000000
}
```

Validation ranges are `1..10000` for priority and concurrency, `1..1000000` for RPM, `1..100000` for burst, `0..100000` for sessions, `60..86400` seconds for session idle, and `1..1000000000` for in-flight tokens. Lower `allocationPriority` values are preferred. OpenAI, Codex, DeepSeek, and OpenAI-compatible accounts are stateless for this scheduler, so the server always stores `maxSessions=0` for them. Anthropic and Kiro-compatible accounts may use session limits.

The response includes the editable fields plus `lifecycleState`, `stateVersion`, `warmupStage`, `cooldownUntil`, `recentErrorRate` (a `0..1` EWMA), `recentLatencyMs` (total attempt latency, not TTFT), `activeConcurrency`, input/output token reservations, and `updatedAt`. New Anthropic accounts default to concurrency/RPM/burst/session limits `2/20/2/4`; OpenAI-family accounts default to `4/60/4/0`; Kiro/other accounts default to `2/30/2/4`. All types default to priority `100`, 600-second session idle, and 1,000,000 in-flight tokens.

Account routing policy is stored as `routingPolicy` on the same settings document. Defaults keep Codex OAuth accounts in a separate pool from official OpenAI API keys, keep ordinary GPT Chat/Responses on official OpenAI API-key accounts, bind only explicit conversations after a successful upstream result, and do not bind by API key when no conversation identifier is present. Codex retries at most one account after dispatch (`codexMaxRetryAccounts=1`). Per-account concurrency and RPM are unchanged.

```json
{
  "routingPolicy": {
    "isolateCodexPool": true,
    "gptUsesCodexAccounts": false,
    "bindSessionOnAdmission": false,
    "bindSessionByApiKey": false,
    "codexMaxRetryAccounts": 1,
    "openAiMaxRetryAccounts": 3,
    "deepSeekMaxRetryAccounts": 3,
    "anthropicMaxRetryAccounts": 2,
    "kiroMaxRetryAccounts": 2
  }
}
```

`codexMaxRetryAccounts` and the other `*MaxRetryAccounts` fields are 1-5. They cap how many different upstream accounts may be tried **after** a call has already been dispatched. Admission capacity rejects still try another account without counting as a retry. Keep `gptUsesCodexAccounts` at its default value of `false` for official API-key routing; set it to `true` only when ordinary GPT traffic should consume Codex accounts.

The **tiered account-pool circuit-breaker policy** is an experimental feature and is disabled by default. Enable or disable it through `PATCH /admin/accounts/settings`:

```json
{
  "poolPolicy": {
    "enabled": true
  }
}
```

When disabled, account selection ignores `poolTier` for circuit routing and the management console remains in ordinary account-management mode. The console hides circuit metrics, tier controls, weights, and the circuit-policy banner; the page header exposes only an **Enable experimental feature** entry point. Enabling it requires an instability confirmation, then switches the account page to a read-only circuit-monitoring layout and hides account mutation actions. The enabled layout keeps the experimental label, instability warning, and disable switch visible because false trips, routing oscillation, and monitoring-schema changes are still possible during the experimental period.

Each circuit has a bounded `provider:model-family` key derived from the resolved upstream model catalog, for example `openai:gpt-5` or `deepseek:deepseek-v4`. Unknown model IDs are grouped into the provider's controlled `provider:other` pool instead of creating an unbounded key. Account `poolTier` remains either `primary` or `secondary`; configure it through the account create/update APIs before enabling the policy. Because an `OPEN` pool routes only to its secondary tier, production deployments should provision at least one usable secondary account for every enabled provider.

`GET /admin/accounts/pool-status` returns the authoritative per-model-family snapshots in `modelPools`. Legacy aggregate fields remain in the response for compatibility; new clients should use `modelPools` for circuit state and tier metrics:

```json
{
  "success": true,
  "data": {
    "enabled": true,
    "accountCount": 6,
    "availableAccountCount": 5,
    "activeTier": "mixed",
    "primaryDisabled": true,
    "primaryFailureRatePercent": 25.0,
    "primaryRequests": 40,
    "primaryFailures": 10,
    "windowStartedAt": "2026-08-10T03:00:00Z",
    "lastEvaluatedAt": "2026-08-10T03:05:00Z",
    "lastRetestAt": "2026-08-10T03:04:00Z",
    "lastRetestFailureRatePercent": 25.0,
    "lastRetestRecoveryRatePercent": 75.0,
    "retestRunning": false,
    "settings": {
      "enabled": true,
      "windowMinutes": 5,
      "minSampleSize": 10,
      "retestThresholdPercent": 50.0,
      "failoverThresholdPercent": 70.0,
      "recoveryThresholdPercent": 70.0,
      "retestIntervalMinutes": 5,
      "retestMaxConcurrency": 4,
      "autoWeightEnabled": true,
      "circuitEpoch": "11111111-1111-1111-1111-111111111111",
      "activationStartedAt": "2026-08-10T03:00:00Z"
    },
    "failureRanking": [],
    "modelPools": [
      {
        "key": "openai:gpt-5",
        "provider": "OpenAI",
        "modelFamily": "GPT-5",
        "representativeModel": "gpt-5",
        "state": "OPEN",
        "activeTier": "secondary",
        "primaryRequests": 20,
        "primaryFailures": 15,
        "primaryFailureRatePercent": 75.0,
        "secondaryRequests": 8,
        "secondaryFailures": 0,
        "secondaryFailureRatePercent": 0.0,
        "primaryAccountCount": 2,
        "secondaryAccountCount": 1,
        "windowStartedAt": "2026-08-10T03:00:00Z",
        "lastTransitionAt": "2026-08-10T03:04:00Z",
        "openedAt": "2026-08-10T03:04:00Z",
        "nextProbeAt": "2026-08-10T03:09:00Z",
        "lastOutcomeAt": "2026-08-10T03:04:00Z",
        "halfOpenInFlight": 0,
        "halfOpenCompleted": 0,
        "halfOpenSucceeded": 0
      }
    ]
  },
  "timestamp": "2026-08-10T03:05:00Z"
}
```

Circuit states are `CLOSED` (primary tier preferred), `OPEN` (secondary tier only during strict cooldown), and `HALF_OPEN` (bounded real-traffic probes use the primary tier while other traffic stays on the secondary tier). `windowMinutes`, `minSampleSize`, and `failoverThresholdPercent` control opening; `retestIntervalMinutes` is the cooldown before half-open probing; `retestMaxConcurrency` is the half-open probe budget; and `recoveryThresholdPercent` controls recovery. The legacy `retestThresholdPercent` field remains accepted for settings compatibility but does not independently trigger a state transition. Probe leases are bounded to 2-10 minutes so abandoned requests cannot retain capacity indefinitely. Each `false -> true` activation creates a fresh `circuitEpoch`; requests and reconciliation from an earlier activation are ignored. A background reconciliation reads the matching PostgreSQL epoch every 30 seconds. Its persistence-confirmation generation keeps query-time commits in the local delta until the next baseline and retains failed log writes in local-only minute buckets, so reconciliation cannot drop a circuit outcome. Policy-setting notifications trigger an immediate reconciliation; neither path issues synthetic tests or fans requests out to all accounts.

`POST /admin/accounts/test` tests Kiro accounts through the Kiro upstream client, OpenAI and DeepSeek API-key accounts through `/chat/completions`, Codex accounts through `/responses`, and Anthropic API-key accounts through `/messages`. Google/Gemini API-key records can be stored but direct testing currently returns a per-account `501` result because no Google upstream client is wired.
`updateStatus` defaults to `true`; set it to `false` only when a test should not change account state. Successful tests re-enable the account and clear suspended/quota state. Transient failures increment `errorCount` but do not disable the account. A `402` may mark quota exhausted, while a normal `429` remains a temporary rate-limit signal and does not set `quotaExhaustedAt`, disable, or suspend the account. A `401` or `403` permanently disables the credential only when the error contains explicit token-revocation or organization/account-disable evidence; ambiguous responses remain enabled for lifecycle quarantine and later review.

During normal proxy traffic with the experimental policy disabled, recoverable upstream failures continue to increment the account's consecutive `errorCount`; reaching two consecutive failures schedules one background account test. That test no longer disables a credential for a transient result. With the policy enabled, retryable transport failures and provider-declared retryable `408`, `409`, and `5xx` outcomes are scoped to the affected model-family circuit. Primary-tier circuit failures do not increment the account-global `errorCount`, while secondary-tier failures still increment it. Every dispatched attempt also updates the shared PostgreSQL lifecycle. A `502` created for an invalid or unparsable upstream response is explicitly non-retryable and does not enter the circuit window.

`GET /admin/accounts/test-models` returns provider-specific test model groups for Kiro, OpenAI API key, Google/Gemini, Anthropic, DeepSeek, and Codex accounts. OpenAI, Google/Gemini, Anthropic, and DeepSeek options are derived from the bundled [`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) snapshot. Only entries with verified input/output token prices, chat mode, and a request endpoint compatible with the current proxy become default supported IDs; Realtime-only entries are excluded. OpenAI, Google, and Anthropic catalog aliases keep their routing prefix, for example `openai/gpt-5`, `google/gemini-2.5-pro`, and `anthropic/claude-sonnet-4-5`; account-test requests strip prefixes where the upstream API requires bare IDs. Codex OAuth tests retain a smaller ChatGPT-account-compatible GPT-5 subset. The management console uses this endpoint to show the model list for each account row, but the presence of Google options does not imply that direct Google account testing is implemented.

### API Keys

```http
GET    /admin/api-keys
POST   /admin/api-keys
PATCH  /admin/api-keys/{keyId}
DELETE /admin/api-keys/{keyId}
```

Create key:

```json
{
  "name": "default",
  "creditsLimit": 1000,
  "modelProvider": "OpenAI"
}
```

`modelProvider` is required for newly created keys and must match the case-insensitive `provider` value of at least one enabled `/v1/models` entry. `*` is reserved only for migrated legacy keys and is rejected on create or explicit update. Before upstream selection, the requested model and any mapped target must both exist in the enabled catalog and belong to this provider. Missing models return `400 MODEL_NOT_AVAILABLE` or `400 MAPPED_MODEL_NOT_AVAILABLE`; cross-provider access returns `403 API_KEY_MODEL_PROVIDER_FORBIDDEN`.

API keys are scoped to the logged-in local user. Administrators can list and manage all keys and see each key's `userId`; normal users only list, update, delete, and copy their own keys.

New keys persist the full `sk-...` value in the owning user's key record, so the console can copy the API key and generated client configuration directly. Keys created before this behavior only have `keyHash` and `keyPrefix`; their full secret cannot be recovered and should be recreated when direct copy is needed.

### Managed Model Catalog

These endpoints require an administrator session:

```http
GET    /admin/models
PUT    /admin/models
POST   /admin/models/icon
DELETE /admin/models?modelId={modelId}
DELETE /admin/models/vendors?provider={provider}
PUT    /admin/models/order
GET    /admin/models/display
PATCH  /admin/models/display
GET    /system/model-catalog-display
```

Create or override a catalog entry:

```json
{
  "id": "openai-compatible/meta-llama/llama-3.3-70b-instruct",
  "name": "Llama 3.3 70B",
  "provider": "OpenRouter",
  "family": "Llama 3",
  "targetModelId": "openai-compatible/meta-llama/llama-3.3-70b-instruct",
  "requestFormat": "OPENAI_CHAT_COMPLETIONS",
  "endpoint": "/v1/chat/completions",
  "contextWindow": 131072,
  "description": "OpenRouter-hosted Llama model",
  "enabled": true
}
```

Only `OPENAI_CHAT_COMPLETIONS` is currently accepted as `requestFormat`. Updating a built-in ID stores an override; deleting a built-in ID disables it, while deleting a custom ID removes its customization. `DELETE /admin/models/vendors` removes a custom vendor and every custom model under it; built-in vendors and vendors that still contain built-in models are rejected. Icon upload accepts multipart field `file`, detects PNG/JPEG/WebP from file bytes, enforces `STARRY_MODEL_MAX_ICON_BYTES`, and returns a URL under `/assets/model-icons/`. Catalog customizations are stored in PostgreSQL, but uploaded files live in `STARRY_MODEL_ICON_DIR` and are not included in database exports.

`PUT /admin/models/order` saves the global model-ID display order for every user. Send the complete `modelIds` list, or `{ "restoreDefault": true }` to return to catalog order. Unspecified current IDs are appended in their previous relative order. `GET/PATCH /admin/models/display` stores gallery layout (`cards` / `list` / `compact`), density, column count (`0` = auto), field visibility, grouping (`provider` / `none`), and card style (`default` / `outline` / `flat`). `GET /system/model-catalog-display` is public and returns those display settings plus the custom `modelIds` order so the console gallery stays consistent for all signed-in users. `/v1/models` also follows the saved order.

### Model Pricing

These endpoints require an administrator session:

```http
GET  /admin/model-pricing
POST /admin/model-pricing
PUT  /admin/model-pricing
POST /admin/model-pricing/preview
PATCH /admin/model-pricing/source
POST /admin/model-pricing/refresh
```

POST and PUT replace the complete override-rule list. An empty list restores the configured global multiplier (default 1.0). Rules support model patterns and catalog provider/family scopes, each with a direct multiplier of official price. Multipliers support six decimal places, range 0.000001-1000; priority ranges from 0 to 10000, default 100. At most 200 rules are accepted. Duplicate normalized exact patterns, provider/family scopes, or IDs return `MODEL_PRICE_RULE_DUPLICATE`. The first enabled rule under the ordering above replaces the global multiplier:

```json
{
  "rules": [
    {
      "modelPattern": "openai/gpt-5*",
      "priceMultiplier": 0.1,
      "priority": 10,
      "enabled": true
    }
  ]
}
```

`POST /admin/model-pricing/preview` accepts `{ "model": "starry/gpt-5.5", "rules": [...] }`. Supply stable `ruleId` values to identify draft matches. It validates the draft using save-time constraints, uses the current global multiplier, and never persists changes. The response contains:

- `model`: requested ID; `pricingModel`: resolved catalog pricing ID.
- `source`: `official`, `normalized`, `legacy_total`, `legacy_itemized`, or `unavailable`; `matchedPriceModel`: verified source key when available.
- `multiplier`: effective ratio, omitted for legacy absolute prices.
- `matchedRuleIds`: enabled matching rules in application order; the first wins.
- `basePricing`: official price at 1.0; `pricing`: effective price under the submitted draft. Both use the public pricing shape.

An empty draft previews defaults even if saved rules exist. Preview uses standard, non-fast pricing; request fast mode still applies its existing 2x surcharge. Missing prices are represented as `unavailable` in preview rather than failing the preview request. Legacy raw `*Multiplier` fields remain accepted for compatibility and are preserved by the UI; explicitly setting `priceMultiplier` clears the legacy prices on save.

Rule scope fields:

| Scope | Required fields | Coverage |
| --- | --- | --- |
| `model` (default) | `modelPattern` | Exact ID, or explicit `*` wildcard. Bare `gpt` / `gpt5` are not prefix matches. |
| `provider` | `provider`, `priceMultiplier` | All requested catalog models owned by that provider, including future additions. |
| `family` | `provider`, `family`, `priceMultiplier` | That provider's catalog family, including future additions. |

Example: `{"scope":"provider","provider":"OpenAI","priceMultiplier":0.2,"priority":100}` sets an OpenAI default.
`{"scope":"family","provider":"OpenAI","family":"GPT-5","priceMultiplier":0.1,"priority":100}` overrides it for GPT-5.
All four token-price classes use the selected multiplier; ratios never stack. Membership follows the requested catalog entry,
not the upstream target's provider. Provider/family matching is case-insensitive; `Gemini` normalizes to `Google`.
Scoped rules ignore `modelPattern` (returned as `*` for compatibility), require a multiplier, and cannot create legacy fixed prices.
Omitted `scope` retains the old model-pattern behavior. Invalid scopes or missing provider/family fields return `MODEL_PRICE_SCOPE_INVALID`.

### Model Mappings

These endpoints require an administrator session. Ordinary user sessions cannot list, create, update, or delete mapping rules.

```http
GET    /admin/model-mappings
POST   /admin/model-mappings
PATCH  /admin/model-mappings/{mappingId}
DELETE /admin/model-mappings/{mappingId}
```

```json
{
  "name": "openai-fast-to-haiku",
  "mappingType": "alias",
  "sourceModel": "gpt-4o-mini",
  "targetModels": ["claude-haiku-4.5"],
  "priority": 10,
  "apiKeyIds": null
}
```

`sourceModel` supports exact match and `*` wildcards. Matching is bounded to 256 characters.

### Payment and Balance

The following routes are available to any authenticated `ADMIN` or `USER` account. Orders are always scoped to the logged-in user:

```http
GET  /payment/settings
GET  /payment/orders
POST /payment/orders
GET  /payment/orders/{orderId}
POST /payment/orders/{orderId}/cancel
POST /payment/redeem-code
```

Create a recharge order with an amount, one of the payment types enabled in `/payment/settings`, and optional return-page metadata:

```json
{
  "amount": 100,
  "paymentType": "alipay",
  "returnUrl": "https://console.example/#/payment",
  "mobile": false
}
```

Payment configuration, all-user order inspection, redemption-code administration, and invite-rebate history require an `ADMIN` session:

```http
GET   /admin/payment/settings
PATCH /admin/payment/settings
GET   /admin/payment/orders
GET   /admin/payment/orders/{orderId}
GET   /admin/payment/redemption-codes
POST  /admin/payment/redemption-codes
POST  /admin/payment/redemption-codes/{codeId}/revoke
GET   /admin/payment/invite-rebates
```

EasyPay sends signed asynchronous notifications to either form of the public callback below. These routes do not use a console session; the payment service verifies the callback signature and order state before crediting a balance.

```http
GET  /payment/webhook/easypay
POST /payment/webhook/easypay
```

### Coding Plan Subscriptions

API keys choose `billingSource: "BALANCE" | "CODING_PLAN"` at creation and may change it when no request is pending. Existing keys default to `BALANCE`. A balance key never spends subscription quota; a Coding Plan key never falls back to the wallet. Admin-owned keys retain their existing no-wallet-charge behavior. Normal-user requests reserve funds before upstream dispatch, then settle actual usage and release the difference.

User-scoped routes require a console session:

```http
GET  /payment/coding-plans
POST /payment/coding-plans/{planId}/purchase
GET  /payment/coding-plan-subscriptions
GET  /payment/coding-plan-subscriptions/page?page=1&pageSize=20&status=ACTIVE
GET  /payment/coding-plan-subscriptions/summary?provider=OpenAI
GET  /payment/coding-plan-subscriptions/{subscriptionId}/ledger?before=0
```

Purchase requires the `USER` role, a JSON body `{"version": 1}`, and a stable `Idempotency-Key` header (1-100 characters). The path identifies the plan. The server validates the displayed version and atomically debits the wallet, appends a purchase ledger entry, and issues the subscription. Retry the same operation with the same header. Reusing a key for different terms returns `409 IDEMPOTENCY_CONFLICT`; a changed product version returns `409 CODING_PLAN_VERSION_CHANGED`.

Each subscription and issued plan redemption code pins an immutable product version. Editing a product publishes a new version without changing previously issued quota, provider scope, or duration. Disabling or reordering a product does not publish new financial terms. Same-product renewals queue; they become usable when the preceding term expires or is consumed, not while its quota is merely reserved.

Administrator routes:

```http
GET   /admin/coding-plans
POST  /admin/coding-plans
PATCH /admin/coding-plans/{planId}
GET   /admin/coding-plan-subscriptions?userId={userId}
GET   /admin/coding-plan-subscriptions/page?page=1&pageSize=20&search=&status=
POST  /admin/coding-plan-subscriptions
PATCH /admin/coding-plan-subscriptions/{subscriptionId}
GET   /admin/coding-plan-subscriptions/{subscriptionId}/ledger?before=0
GET   /admin/billing/reviews
POST  /admin/billing/reviews/{requestHash}/resolve
```

Example product:

```json
{
  "name": "Coding Pro",
  "description": "Monthly developer subscription",
  "priceCredits": "20",
  "durationDays": 30,
  "quotaCredits": "100",
  "allowedProviders": ["OpenAI", "Anthropic"],
  "sortOrder": 0
}
```

Money supports at most six decimal places. Product prices and quotas are serialized as decimal strings. An omitted provider list on creation, or an explicit empty list, covers all providers; an omitted list on PATCH preserves the current scope. A manual grant uses `{"userId":"user-1","planId":"plan-1"}` and an `Idempotency-Key` header. Status changes require a reason, for example `{"status":"REVOKED","reason":"Entitlement correction"}`. Restoring an expired subscription or a term overlapping an already-advanced renewal returns 409.

Subscription responses include `planVersion`, `reservedCredits`, `availableCredits`, and effective status (`ACTIVE`, `SCHEDULED`, `EXHAUSTED`, `EXPIRED`, `REVOKED`, `CANCELLED`). `remainingCredits` is quota minus consumed usage; `availableCredits` additionally excludes holds. Check status before treating a row as spendable. The summary endpoint aggregates all matching subscriptions server-side, including outstanding holds on revoked/expired terms. Page responses contain `items,total,page,pageSize` (page size at most 200); ledger pages contain up to 100 entries and use the last entry ID as `before`. Legacy list endpoints return at most 200 rows.

An insufficient balance or matching plan quota returns 402 before dispatch. Unknown costs after a disconnected/timed-out dispatched request retain the hold and require review; further calls by that user return `409 BILLING_REVIEW_REQUIRED`. Administrators resolve with `{"action":"SETTLE","credits":"0.15","reason":"Verified upstream usage"}` or `{"action":"WAIVE","reason":"Confirmed no charge"}`. Every action is audited. Changing a key's provider/billing source or deleting it while billing is unfinished returns `409 API_KEY_BILLING_BUSY`. On key PATCH, `clearCreditsLimit:true` explicitly removes a previously configured limit; omitted fields preserve values.

Plan redemption codes use `planId` instead of `balance` (prefix `PLN-`); redemption honors the pinned version even after that product is updated or delisted. Checkout currently uses wallet purchase, administrative grants, or redemption codes. Direct gateway payment for a plan is not implemented. See [Coding Plan rebuild and verification](CODING_PLAN_REBUILD.md) for migration and recovery details.

### Public System Status

```http
GET /system/status
```

This endpoint is public so the WebUI and external monitors can distinguish normal operation from the fail-closed maintenance state. It exposes only `status`, `maintenance`, `code`, `message`, and `checkedAt`; it does not expose key-verification attempts, administrator identity, or security-file details.

### PostgreSQL Data Transfer

All of these endpoints require the initial application Root's database-backed `ADMIN` session and operate on the PostgreSQL 17 database/schema `starry_api`. Ordinary administrators receive `403 DATA_MANAGEMENT_ROOT_REQUIRED`, including for status and backup-list reads, even if they possess a security key or a Root-issued grant. The legacy static admin token remains disallowed:

```http
GET    /admin/data-transfer/status
GET    /admin/data-transfer/security/status
POST   /admin/data-transfer/security/init
POST   /admin/data-transfer/security/unlock
GET    /admin/data-transfer/database.dump
GET    /admin/data-transfer/backup.zip
GET    /admin/data-transfer/backups
POST   /admin/data-transfer/backups
GET    /admin/data-transfer/backups/{fileName}
DELETE /admin/data-transfer/backups/{fileName}
POST   /admin/data-transfer/import?confirm=RESTORE_DATABASE
```

Data-management security can be initialized only by the application root using a database-backed ADMIN session. Initialization returns the `dmsk-...` security key once. Copy it directly to a controlled password manager; the WebUI does not create a plaintext download. The service stores only a salted PBKDF2 verifier in its owner-only security state file. Before a protected operation, exchange the key at `/security/unlock` for a short-lived `dmt-...` token bound to the current administrator session, requested action, and optional backup filename target. Send that token as `X-Data-Management-Token` to the protected endpoint. Supported actions are `EXPORT_DATABASE`, `EXPORT_BACKUP`, `CREATE_BACKUP`, `DOWNLOAD_BACKUP`, `DELETE_BACKUP`, and `RESTORE_DATABASE`; all grants can be issued to and used by the application root only, while download/delete actions require the exact backup filename as `target`. Delete and restore consume their token once. This feature supports one active service instance; persist the entire security directory and its initialization marker, then enable `STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED` so total volume loss fails closed.

Five consecutive incorrect security-key attempts permanently lock data management and place the whole site in fail-closed maintenance mode. Only static WebUI resources, `GET /system/status`, and health endpoints remain available; authentication, admin, proxy, and payment APIs return `503 SYSTEM_MAINTENANCE`. Recovery is an operator workflow during service startup, not another public endpoint. Bundled Docker deployments fix the writable owner-only input at `/app/security/recovery.key`; create it through the image's interactive `/app/bin/recover-data-management --lang zh|en` utility and then restart `service`.

At startup, the service atomically stages a readable recovery file before comparing its key. On the normal path, a matching key commits the recovered state, records `RECOVERY_SUCCEEDED`, and consumes the staged one-time file; a readable, valid-format but nonmatching key records `RECOVERY_FAILED`, consumes the file, and keeps the site locked in maintenance. If truncation, deletion, or directory synchronization cannot be confirmed, the file is not considered consumed and the current process remains fail-closed even when the matching state commit succeeded. A later startup retries cleanup of the staged sibling; for bundled Docker deployments it is `/app/security/.recovery.key.consuming`. Operational recovery is complete only when `GET /system/status` reports `maintenance=false` and neither `/app/security/recovery.key` nor the staged sibling exists. A crash before a valid recovery state commit restores the staged file for the next startup. A missing or malformed file, symbolic link, or unsafe permission set is rejected and leaves maintenance active. Direct host/JAR deployments may instead set `STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE` to an absolute writable owner-only regular file; its staged sibling is `.<configured-file-name>.consuming`. Read-only secret mounts are unsupported because the service must erase and synchronize the input.

Every failed attempt is atomically spooled with the lock state, then appended idempotently to `starry_security.data_management_security_audit_events` with the event-time administrator/root-email snapshot and sanitized request metadata. It enters the durable outbox even when no Root email is configured. Forwarded headers retain only valid IP literals; User-Agent and client request IDs are stored as irreversible SHA-256 fingerprints. SMTP delivery prefers the event snapshot and otherwise re-resolves the current email through the immutable Root marker on every retry, so a later email update can release the pending alert. Delivery is at-least-once and carries a stable `X-Starry-Security-Event-Id`. The fifth subject explicitly states that the backend is likely compromised. Neither local incidents, audit/outbox rows, emails, nor structured logs contain passwords, password hashes/salts, the security key, submitted wrong values, session tokens, or data-management tokens. The `starry_security` schema is excluded from application backup/restore so later security evidence cannot be rolled back by an older archive.

`database.dump` is a PostgreSQL custom-format archive. `backup.zip` and stored backup files are ZIP packages containing `database.dump`, metadata, and restore notes. The import endpoint accepts only a `.dump` archive or a Starry API `.zip` package as multipart field `file`; it validates the archive, serializes maintenance operations, and creates a rollback backup before restore.

### Usage, Logs, and Login Accounts

```http
GET  /admin/usage-summary/me
GET  /admin/usage-summary
GET  /admin/logs
GET  /admin/performance
GET  /admin/users
POST /admin/users
PATCH /admin/users/{userId}
POST /admin/users/{userId}/reset-password
GET  /admin/users/registration-settings
PATCH /admin/users/registration-settings
GET  /admin/users/invite-codes
POST /admin/users/invite-codes
POST /admin/users/invite-codes/batch
POST /admin/users/invite-codes/{codeId}/revoke
```

`GET /admin/usage-summary/me` is available to any authenticated account and returns only that user's totals and per-model usage. The global `/admin/usage-summary`, `/admin/logs`, `/admin/performance`, and `/admin/users/*` routes require an `ADMIN` session; `USER` accounts receive `403 ADMIN_ROLE_REQUIRED`. The global usage response also includes active-user summaries.

For circuit reconciliation, each underlying `request_logs` row also stores `circuit_epoch`, `circuit_key`, `circuit_tier`, `upstream_model`, and nullable `upstream_success`. `success` remains the end-to-end proxy result, while `upstream_success` distinguishes a successful upstream call (`true`) from a circuit-countable failure (`false`); it is still `true` when the upstream succeeded but local usage settlement failed, and is `null` for account-scoped or non-retryable failures. The indexed circuit window is isolated to the current activation epoch and therefore includes only that activation's successful upstream calls, retryable transport failures, and provider-declared retryable `408`, `409`, and `5xx` outcomes without admitting account-scoped `401`, `402`, `403`, or `429` rows. These fields are internal persistence metadata and are not currently included in the `GET /admin/logs` response projection.

`/admin/performance` returns the latest 120 performance samples. The backend records one sample every 30 seconds:

```json
{
  "systemCpuLoadPercent": 12.5,
  "processCpuLoadPercent": 3.2,
  "heapUsedBytes": 134217728,
  "heapCommittedBytes": 268435456,
  "heapMaxBytes": 1073741824,
  "nonHeapUsedBytes": 98304000,
  "systemMemoryTotalBytes": 8589934592,
  "systemMemoryFreeBytes": 4294967296,
  "systemMemoryUsedPercent": 50.0,
  "networkRxBytes": 123456789,
  "networkTxBytes": 987654321,
  "networkRxBytesPerSecond": 2048.0,
  "networkTxBytesPerSecond": 1024.0,
  "sampledAt": "2026-06-07T12:00:00Z"
}
```

Network counters are read from Linux `/proc/net/dev`. On non-Linux hosts those network fields can be `null`.

Create or update login accounts with `role=ADMIN` or `role=USER`. `balance` is a non-negative account balance for ordinary users:

```json
{
  "username": "normal-user",
  "email": "user@example.com",
  "displayName": "Normal User",
  "role": "USER",
  "balance": 100,
  "password": "optional-initial-password"
}
```

Public self-registration and invite-code registration create `USER` accounts with zero balance.

## Status Codes

| Status | Meaning |
| --- | --- |
| `400` | Invalid request body or missing model/messages. |
| `401` | Missing or invalid API/admin key. |
| `403` | First-login password change required, forbidden admin request, or API key model-provider restriction. |
| `413` | Request body exceeds configured max request size. |
| `429` | API key rate limit, credits limit, or upstream capacity/rate limit. Upstream `429` is transient and does not by itself mark an account quota exhausted. |
| `502` | Upstream returned an unrecoverable error. |
| `503` | No available upstream account, or whole-site `SYSTEM_MAINTENANCE` after the data-management security lock. |

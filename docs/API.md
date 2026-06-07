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

## Admin Login

On first startup, the service creates:

```text
username: admin
password: <random password printed in startup logs once>
```

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

If `user.firstLogin=true`, only password change is allowed:

```http
POST /auth/password
Authorization: Bearer adm-...
Content-Type: application/json
```

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

Administrators configure registration and SMTP settings through `GET/PATCH /admin/users/registration-settings`. SMTP fields follow the sub2api-style setting keys: `email_verify_enabled`, `smtp_host`, `smtp_port`, `smtp_username`, `smtp_password`, `smtp_from_email`, `smtp_from_name`, and `smtp_use_tls`. API JSON uses camelCase field names, and responses only expose `smtpPasswordConfigured`, never the stored password.

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

The model list is OpenAI-compatible and includes platform metadata. It keeps legacy short aliases and also exposes OpenRouter-sourced complete IDs for OpenAI, Google/Gemini, and Anthropic models, such as `openai/gpt-5`, `google/gemini-2.5-pro`, and `anthropic/claude-sonnet-4.5`.

```json
{
  "object": "list",
  "data": [
    {
      "id": "gemini-2.5-pro",
      "object": "model",
      "created": 1770000000,
      "owned_by": "starryapi",
      "provider": "Gemini",
      "family": "Gemini 2.5",
      "targetModelId": "claude-sonnet-4.5",
      "name": "Gemini 2.5 Pro compatible alias",
      "modelName": "Gemini 2.5 Pro compatible alias",
      "description": "Gemini-compatible alias for the unified Starry API routing layer",
      "supportedInputTypes": ["TEXT", "IMAGE"],
      "maxInputTokens": 200000,
      "maxOutputTokens": 64000,
      "context_length": 200000,
      "max_output_tokens": 64000
    }
  ]
}
```

Current provider groups:

| Provider | Families |
| --- | --- |
| Anthropic | Routing Preset, Claude 4, Claude 3, CodeWhisperer Internal |
| OpenAI | GPT-5, GPT-4, GPT-3.5, OpenAI Reasoning |
| Gemini | Gemini 2.5, Gemini 2.0, Gemini 1.5 |

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

`platform/openai/OpenAiRequestAdapter` converts Responses input, function calls, and function outputs into the internal chat shape before routing.

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

## Admin APIs

### Accounts

Upstream account pool management requires an `ADMIN` account session. `USER` accounts receive `403 ADMIN_ROLE_REQUIRED`.

```http
GET    /admin/accounts
POST   /admin/accounts
PATCH  /admin/accounts/{accountId}
DELETE /admin/accounts/{accountId}
POST   /admin/accounts/import
POST   /admin/accounts/test
GET    /admin/accounts/test-models
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
  "quotaLimit": 1000000
}
```

Only `accessToken` is required for a basic Kiro upstream account. For OpenAI-compatible API key accounts, use:

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

For Codex OAuth token accounts, use `authMethod=codex`, `provider=OpenAI`, `accessToken`, optional `refreshToken`, optional `idToken`, and optional `upstreamAccountId`. If `baseUrl` is omitted, Codex defaults to `https://chatgpt.com/backend-api/codex`.

The import endpoint accepts:

- Kiro Account Manager JSON exports.
- A single account object, an account array, or `{ "accounts": [...] }`.
- Multiple import documents as `{ "documents": [...] }`; each document may use any supported import shape.
- Codex token JSON with `type: "codex"`, `access_token`, `refresh_token`, and `id_token`.
- OpenAI-compatible config blocks shaped as `openai-compatibility[].api-key-entries[]` with `api-key`, optional `base-url`, and optional `proxy-url`.

The management console can upload multiple `.json` files, parse them in the browser, and submit one import request per file. Results are aggregated in the console after all files finish.

`POST /admin/accounts/test` tests Kiro accounts through the Kiro upstream client, OpenAI API key accounts through `/chat/completions`, and Codex accounts through `/responses`.
`updateStatus` defaults to `true`; set it to `false` only when a test should not change account state. Successful tests re-enable the account and clear suspended/quota state. Failed tests disable and suspend the account so it becomes unavailable. `402` and `429` also mark the account quota exhausted, while `401` and `403` use the auth failure suspend reason.

`GET /admin/accounts/test-models` returns provider-specific test model groups for Kiro, OpenAI API key, Google/Gemini, Anthropic, and Codex accounts. OpenAI, Google, and Anthropic test model IDs are synchronized from `https://openrouter.ai/api/v1/models` and keep the OpenRouter provider prefix, for example `openai/gpt-5`, `google/gemini-2.5-pro`, and `anthropic/claude-sonnet-4.5`. Codex OAuth account tests use ChatGPT-account-compatible GPT-5 series model IDs by default; legacy Codex-only slugs such as `gpt-5-codex` are normalized before testing to avoid unsupported-model failures. The management console uses this endpoint to show the correct model list for each account row.

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
  "creditsLimit": 1000
}
```

The full `sk-...` secret is returned only once.

### Model Mappings

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

### Logs and Login Accounts

```http
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
POST /admin/users/invite-codes/{codeId}/revoke
```

`/admin/logs` and `/admin/performance` back the global dashboard, and `/admin/users/*` is the account administration panel. These endpoints require an `ADMIN` account session. `USER` accounts receive `403 ADMIN_ROLE_REQUIRED`.

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
| `403` | First-login password change required or forbidden admin request. |
| `413` | Request body exceeds configured max request size. |
| `429` | API key rate limit, credits limit, or upstream quota/rate limit. |
| `502` | Upstream returned an unrecoverable error. |
| `503` | No available upstream account. |

# Account Import and Proxy Startup

This service becomes a reverse proxy as soon as the `service` module starts. There is no separate proxy worker.

## Start the Reverse Proxy

Create the database once:

```bash
mysql -uroot -e "CREATE DATABASE IF NOT EXISTS kiro_proxy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

Start with Gradle:

```bash
./scripts/start-proxy.sh
```

Start on another port:

```bash
SERVER_PORT=8081 ./scripts/start-proxy.sh
```

Start from the boot jar:

```bash
KIRO_RUN_MODE=jar ./scripts/start-proxy.sh
```

On first startup, read the generated `admin` password from the startup logs. The service prints it only once when the `admin_users` table is empty.

## Login as Admin

```bash
curl -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<startup-password>"}'
```

Export the returned `data.accessToken`:

```bash
export ADMIN_TOKEN="adm-..."
```

On first login, change the generated password before using other admin APIs:

```bash
curl -X POST http://127.0.0.1:8080/auth/password \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"oldPassword":"<startup-password>","newPassword":"<new-password>"}'
```

## Import One Account

Minimum payload:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"email":"user@example.com","accessToken":"kiro-access-token","region":"us-east-1"}'
```

Only `accessToken` is required. `region` defaults to `us-east-1`.

## Batch Import Accounts

Edit the sample file:

```text
examples/accounts.import.json
```

Import it:

```bash
ADMIN_TOKEN="$ADMIN_TOKEN" ./scripts/import-accounts.sh
```

Import another file:

```bash
ADMIN_TOKEN="$ADMIN_TOKEN" ./scripts/import-accounts.sh /path/to/accounts.import.json
```

Request format. You can paste Kiro Account Manager JSON export directly:

```json
{
  "version": "kiro-account-manager",
  "exportedAt": 1760000000000,
  "upsert": true,
  "accounts": [
    {
      "id": "kiro-main",
      "email": "user@example.com",
      "idp": "BuilderId",
      "profileArn": "arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX",
      "machineId": "optional-machine-id",
      "proxyUrl": "http://user:pass@host:port",
      "credentials": {
        "accessToken": "kiro-access-token",
        "refreshToken": "optional-refresh-token",
        "clientId": "optional-client-id",
        "clientSecret": "optional-client-secret",
        "region": "us-east-1",
        "authMethod": "IdC",
        "provider": "BuilderId"
      },
      "usage": {
        "limit": 1000000
      }
    }
  ],
  "groups": [],
  "tags": []
}
```

Import behavior:

- `upsert=false` or omitted: duplicate `accountId` entries are skipped.
- `upsert=true`: duplicate `accountId` entries update existing accounts.
- Kiro Account Manager `id` is treated as `accountId`.
- Missing `id/accountId`: the service generates one, so the item is always created as a new account.
- `credentials.accessToken` or top-level `accessToken` is required for import. Accounts with refresh credentials are refreshed in the background every hour by default; if Kiro still reports an invalid bearer token, the service refreshes once immediately and retries. IdC/BuilderId accounts also need `clientId` and `clientSecret`.
- Each item returns `CREATED`, `UPDATED`, `SKIPPED`, or `FAILED`.

## Test Upstream Accounts

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts/test \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "onlyEnabled": true,
    "model": "simple-task",
    "prompt": "Reply OK in one short sentence.",
    "maxConcurrency": 3
  }'
```

Add `accountIds` to test specific accounts only:

```json
{
  "accountIds": ["kiro-main"],
  "onlyEnabled": true
}
```

## Create a Proxy API Key

```bash
curl -X POST http://127.0.0.1:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"default","creditsLimit":1000}'
```

Save the returned `data.key`, then call the proxy with it:

```bash
export STARRY_API_KEY="sk-..."

curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer $STARRY_API_KEY"
```

OpenAI-compatible clients should use:

```text
Base URL: http://127.0.0.1:8080/v1
API Key:  sk-...
```

OpenCode should use a project-level custom provider backed by the OpenAI-compatible endpoint:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "enabled_providers": ["starry"],
  "model": "{env:OPENCODE_MODEL}",
  "small_model": "{env:OPENCODE_SMALL_MODEL}",
  "provider": {
    "starry": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Starry API",
      "options": {
        "baseURL": "http://127.0.0.1:8080/v1",
        "apiKey": "{env:OPENCODE_STARRY_API_KEY}"
      },
      "models": {
        "gpt-5.5": { "name": "GPT-5.5" },
        "gpt-5.4-mini": { "name": "GPT-5.4 Mini" }
      }
    }
  }
}
```

The `enabled_providers` entry prevents OpenCode from falling back to a local/global OpenAI credential when the Starry API key is selected in the WebUI.
For existing API keys, only the key prefix is retained by the server. Paste the full one-time key into the WebUI setup dialog before copying an OpenCode script; a `REPLACE_REST_OF_KEY` placeholder will not authenticate.
OpenCode may send a separate `small_model` request for lightweight tasks such as title generation. This is a second client request using the same API key/account affinity, not a fan-out to every upstream account.

The WebUI API Key page can generate copy-ready scripts for generic shell variables, Claude Code, OpenCode, or all client settings at once.
It now includes OpenAI, Anthropic, Gemini, OpenCode, and generic Starry API variables.

## Configure Online Recharge

Log in as an administrator and open `支付设置`.

Required EasyPay fields:

- `接口地址`: gateway base URL, for example `https://pay.example.com`. Values ending in `/submit.php`, `/mapi.php`, or `/api.php` are normalized automatically.
- `商户 PID`: EasyPay merchant ID.
- `商户密钥`: EasyPay signing key. Leave it blank when editing if the key is already configured.
- `支付模式`: `跳转收银台` uses `submit.php`; `二维码接口` uses `mapi.php`.

After enabling online recharge, ordinary users can open `余额充值`, create one order per JSON/API request, and pay through the returned payment URL or QR code. EasyPay must be configured to call:

```text
https://<your-domain>/payment/webhook/easypay
```

When the webhook signature is valid and the upstream trade status is successful, the service marks the order as completed and increments the user's balance. Duplicate successful callbacks are ignored after the first completion.

Persist client API configuration in a console profile:

macOS:

```bash
PROFILE="${ZDOTDIR:-$HOME}/.zshrc"
cat >> "$PROFILE" <<'EOF'
export OPENAI_BASE_URL='http://127.0.0.1:8080/v1'
export OPENAI_API_KEY='sk-REPLACE_ME'
export GEMINI_BASE_URL='http://127.0.0.1:8080/v1beta'
export GEMINI_API_KEY='sk-REPLACE_ME'
export STARRY_API_BASE_URL='http://127.0.0.1:8080/v1'
export STARRY_API_KEY='sk-REPLACE_ME'
export STARRY_API_PORT='8080'
EOF
export OPENAI_BASE_URL='http://127.0.0.1:8080/v1'
export OPENAI_API_KEY='sk-REPLACE_ME'
export GEMINI_BASE_URL='http://127.0.0.1:8080/v1beta'
export GEMINI_API_KEY='sk-REPLACE_ME'
export STARRY_API_BASE_URL='http://127.0.0.1:8080/v1'
export STARRY_API_KEY='sk-REPLACE_ME'
export STARRY_API_PORT='8080'
```

Linux:

```bash
PROFILE="$HOME/.bashrc"; [ -n "$ZSH_VERSION" ] && PROFILE="${ZDOTDIR:-$HOME}/.zshrc"
cat >> "$PROFILE" <<'EOF'
export OPENAI_BASE_URL='http://127.0.0.1:8080/v1'
export OPENAI_API_KEY='sk-REPLACE_ME'
export GEMINI_BASE_URL='http://127.0.0.1:8080/v1beta'
export GEMINI_API_KEY='sk-REPLACE_ME'
export STARRY_API_BASE_URL='http://127.0.0.1:8080/v1'
export STARRY_API_KEY='sk-REPLACE_ME'
export STARRY_API_PORT='8080'
EOF
export OPENAI_BASE_URL='http://127.0.0.1:8080/v1'
export OPENAI_API_KEY='sk-REPLACE_ME'
export GEMINI_BASE_URL='http://127.0.0.1:8080/v1beta'
export GEMINI_API_KEY='sk-REPLACE_ME'
export STARRY_API_BASE_URL='http://127.0.0.1:8080/v1'
export STARRY_API_KEY='sk-REPLACE_ME'
export STARRY_API_PORT='8080'
```

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force (Split-Path $PROFILE) | Out-Null
@'
$env:OPENAI_BASE_URL = 'http://127.0.0.1:8080/v1'
$env:OPENAI_API_KEY = 'sk-REPLACE_ME'
$env:GEMINI_BASE_URL = 'http://127.0.0.1:8080/v1beta'
$env:GEMINI_API_KEY = 'sk-REPLACE_ME'
$env:STARRY_API_BASE_URL = 'http://127.0.0.1:8080/v1'
$env:STARRY_API_KEY = 'sk-REPLACE_ME'
$env:STARRY_API_PORT = '8080'
'@ | Add-Content -Path $PROFILE -Encoding UTF8
$env:OPENAI_BASE_URL = 'http://127.0.0.1:8080/v1'
$env:OPENAI_API_KEY = 'sk-REPLACE_ME'
$env:GEMINI_BASE_URL = 'http://127.0.0.1:8080/v1beta'
$env:GEMINI_API_KEY = 'sk-REPLACE_ME'
$env:STARRY_API_BASE_URL = 'http://127.0.0.1:8080/v1'
$env:STARRY_API_KEY = 'sk-REPLACE_ME'
$env:STARRY_API_PORT = '8080'
```

Chat completion test:

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $STARRY_API_KEY" \
  -d '{
    "model": "claude-sonnet-4.5",
    "messages": [
      {"role": "user", "content": "Say hello in one sentence."}
    ],
    "max_tokens": 256
  }'
```

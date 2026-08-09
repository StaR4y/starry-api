# Account Import and Proxy Startup

This service becomes a reverse proxy as soon as the `service` module starts. There is no separate proxy worker.

## Start the Reverse Proxy

On a fresh PostgreSQL 17 cluster, create the database and least-privilege roles once:

```bash
export POSTGRES_USER=postgres
export POSTGRES_DB=starry_api
export STARRY_POSTGRES_MIGRATION_PASSWORD='<strong-local-migration-password>'
export STARRY_POSTGRES_APP_PASSWORD='<strong-local-application-password>'
createdb --username "$POSTGRES_USER" --encoding=UTF8 "$POSTGRES_DB"
./docker/postgresql/init/001-starry-api-roles.sh
```

Before starting outside Compose, set `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DATABASE=starry_api`, `POSTGRES_USERNAME=starry_api`, `POSTGRES_PASSWORD`, `POSTGRES_MIGRATION_USERNAME=starry_api_migration`, and `POSTGRES_MIGRATION_PASSWORD`.

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

To add an official DeepSeek account, set `provider=DeepSeek`. The service normalizes the authentication method to `api-key`, and a missing `baseUrl` defaults to `https://api.deepseek.com/v1`:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "email":"deepseek@example.com",
    "provider":"DeepSeek",
    "authMethod":"api-key",
    "accessToken":"sk-..."
  }'
```

Use the bare `deepseek-v4-flash` or `deepseek-v4-pro` ID for official routing. `starry/` and `deepseek/` forms are also accepted; `openai-compatible/` is reserved for the generic third-party pool. Both DeepSeek models support Chat Completions. Responses currently supports only `deepseek-v4-flash`, and requests for Pro on that endpoint return `400`.

DeepSeek requests are isolated to DeepSeek accounts and do not fall back to OpenAI or Codex. For compatibility, an existing account whose `baseUrl` host is `api.deepseek.com` is recognized as DeepSeek even if its stored provider is `OpenAI-compatible`; ordinary OpenAI/Codex selection excludes it.

To add an API key from another OpenAI-compatible website, use `provider=OpenAI-compatible`, the site API key as `accessToken`, and the site API root as `baseUrl`:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "email":"openrouter@example.com",
    "provider":"OpenAI-compatible",
    "authMethod":"api-key",
    "baseUrl":"https://openrouter.ai/api/v1",
    "accessToken":"sk-or-..."
  }'
```

Map third-party public model IDs to `openai-compatible/<actual-model-id>`, for example `llama-3.3-70b -> openai-compatible/meta-llama/llama-3.3-70b-instruct`. Starry API strips the routing prefix before sending the request upstream. For generic OpenAI-compatible accounts, `baseUrl` can be the site origin (`https://joystar.cc`) or a versioned API base (`https://openrouter.ai/api/v1`). If the path does not already end in a version segment, the runtime and account tester automatically use `/v1/chat/completions` for chat-completion checks.

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

- `upsert=false` or omitted: imported entries with an existing email are skipped.
- `upsert=true`: imported entries with an existing email update that account.
- Kiro Account Manager `id` is accepted as an imported `accountId`, but duplicate detection is based on normalized email.
- Missing or colliding `id/accountId`: the service generates a new internal `accountId`, so a different email is created as a separate account.
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

DeepSeek account tests default to `deepseek-v4-flash`; the account row can also be tested with `deepseek-v4-pro`. A DeepSeek `429` means transient concurrency or rate limiting, not exhausted quota. The service increments `errorCount` and applies same-provider retry/cooldown without setting `quotaExhaustedAt`, disabling, or suspending the account. This exception also applies to manual and scheduled account tests; other providers retain the existing `429` quota semantics.

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

## Database Backup and Data Transfer

Log in as an administrator and open `数据管理`.

Available operations:

- `导出 DUMP`: downloads a PostgreSQL custom-format archive containing schema and data.
- `下载 ZIP`: downloads a portable package containing `database.dump`, `metadata.json`, and a README.
- `创建备份`: writes a `.zip` package containing `database.dump` and metadata to the server backup directory.
- `导入并恢复`: uploads a PostgreSQL `.dump` or Starry API `.zip` package and restores it into the current database.

Importing data is intentionally guarded. The WebUI asks you to type:

```text
RESTORE_DATABASE
```

Before every import, the service validates the custom archive with `pg_restore` and then checks the archive TOC. Only PostgreSQL 17 archives for database/schema `starry_api` are accepted; they must contain Flyway history and the core application tables and must not contain another schema. The runtime image includes PostgreSQL 17 client tools; hosts running the jar directly must provide PostgreSQL 17 `pg_dump` and `pg_restore` commands in `PATH`.

Backups use a PostgreSQL session shared advisory lock and restores use the matching exclusive lock. These locks coordinate all service instances, not only threads in one JVM. After acquiring the exclusive lock, restore performs this sequence:

1. Revoke database `CONNECT` from `starry_api_runtime` and terminate existing `starry_api` backends.
2. Create a server-side rollback backup from the quiesced database.
3. Run `pg_restore --clean --if-exists --single-transaction --exit-on-error` for schema `starry_api`.
4. Verify that schema `starry_api`, all required core tables, and the successful Flyway V1 record exist, then run `ANALYZE` while runtime access is still disabled. If validation or analysis fails after the incoming restore committed, automatically clean-restore the pre-restore dump and validate and analyze the rollback result before reopening access.
5. Restore `CONNECT` in a `finally` path, then clear local sessions and database-derived caches even when restore failed after entering isolation.
6. Let other instances reconnect their PostgreSQL notification listeners; every successful listener reconnect performs the same full local-state invalidation. Generation checks prevent pre-restore asynchronous cache loads from publishing stale state afterward.

The restore window is intentionally disruptive. Route no new traffic to the deployment, expect in-flight API/database requests to fail, and expect administrators to sign in again afterward. Restore is destructive even though it is transactional, so retain an offline backup in addition to the automatically created rollback package.

After either a successful or failed restore, verify runtime access and connection recovery with a migration/superuser session:

```sql
SELECT pg_catalog.has_database_privilege(
    'starry_api_runtime',
    'starry_api',
    'CONNECT'
) AS runtime_connect_restored;

SELECT pid, usename, application_name, state
FROM pg_catalog.pg_stat_activity
WHERE datname = 'starry_api'
ORDER BY pid;
```

`runtime_connect_restored` must be `true`. If both the primary and fallback `finally` connection failed during a database/network outage, restore access as the database owner or cluster administrator before restarting application instances:

```sql
GRANT CONNECT ON DATABASE starry_api TO starry_api_runtime;
```

Deployment notes:

- Docker and prebuilt artifact deployments mount backups at `./data/backups:/app/backups`.
- Override the backup directory with `STARRY_BACKUP_DIR`.
- Override the upload/import size limit with `STARRY_MAX_IMPORT_BYTES` in bytes. The default is `268435456` bytes.

Admin API examples:

```bash
curl -OJ http://127.0.0.1:8080/admin/data-transfer/database.dump \
  -H "Authorization: Bearer $ADMIN_TOKEN"

curl -OJ http://127.0.0.1:8080/admin/data-transfer/backup.zip \
  -H "Authorization: Bearer $ADMIN_TOKEN"

curl -X POST http://127.0.0.1:8080/admin/data-transfer/backups \
  -H "Authorization: Bearer $ADMIN_TOKEN"

curl -X POST 'http://127.0.0.1:8080/admin/data-transfer/import?confirm=RESTORE_DATABASE' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@/path/to/starry-api-backup.zip"

curl -X POST 'http://127.0.0.1:8080/admin/data-transfer/import?confirm=RESTORE_DATABASE' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@/path/to/starry-api-database.dump"
```

## Request Log Aggregation and Retention

The service aggregates only completed UTC hours into `request_usage_hourly`, then applies the affected deltas to `request_usage_daily`. `aggregation_watermarks` records progress, a configurable lookback window is rebuilt for late commits, and a PostgreSQL advisory lock prevents concurrent workers from duplicating maintenance. Every batch commits separately so catch-up work does not hold one long transaction.

By default:

- incremental aggregation runs every minute after a 30-second startup delay, advances one new hour per transaction, and performs at most six transactions per run
- maintenance runs daily at 03:25 UTC
- 12 future monthly `request_logs` partitions are pre-created
- four calendar months of raw partitions are retained, guaranteeing at least 90 days; exceptional old rows in the default partition are pruned in bounded transactions
- hourly aggregates are retained for 90 days and pruned in bounded transactions; daily aggregates serve longer-range queries
- API billing idempotency events are retained for 400 days and pruned in bounded batches; the immutable balance ledger is retained indefinitely

Raw `request_logs` partitions are dropped only after the aggregation watermark is fully caught up. After a long outage, monitor the watermark and disk usage while the bounded batches recover; retention resumes automatically once the backlog reaches the latest completed hour.

Configure these policies with `STARRY_STATS_AGGREGATION_ENABLED`, `STARRY_STATS_AGGREGATION_INTERVAL`, `STARRY_STATS_AGGREGATION_INITIAL_DELAY`, the `STARRY_STATS_AGGREGATION_*_HOURS` and `STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN` controls, `STARRY_STATS_MAINTENANCE_CRON`, `STARRY_STATS_PARTITION_MONTHS_AHEAD`, and the `STARRY_STATS_RAW_*`, `STARRY_STATS_HOURLY_*`, and `STARRY_STATS_USAGE_EVENT_*` variables. Keep aggregation enabled in production because the same worker owns partition creation and retention cleanup.

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

Proxy API calls deduct balance by billable token count. The default admin setting `API计费倍率` is `0.0000166666666667`, which means 100 balance covers about 6,000,000 tokens; administrators can adjust it from `支付设置`. Model-specific rules in `模型价格` support a total equivalent multiplier (`x`) mode for direct whole-model pricing, and a sub2api/LiteLLM itemized mode where input, output, cache write, and cache read are configured as balance per 1M tokens, priced separately, summed, and then charged against the user's balance. The admin one-click preset action saves a compact wildcard catalog instead of generating one rule per discovered model.

Without a matching enabled rule, DeepSeek uses these built-in official defaults in balance per 1M tokens:

| Model | Input/cache miss | Cache hit | Output |
| --- | ---: | ---: | ---: |
| `deepseek-v4-flash` | 0.14 | 0.0028 | 0.28 |
| `deepseek-v4-pro` | 0.435 | 0.003625 | 0.87 |

Cache writes use the input/cache-miss price. The first enabled matching explicit rule wins, so exact model rules and wildcards such as `*` override these built-in values. Only an unmatched DeepSeek model uses the table; other unmatched models continue to use the global fallback multiplier. Update an explicit pricing rule when official pricing changes and an immediate override is required.

Use the WebUI API Key page to generate per-key client configuration. The current setup is API-key based and no longer writes shell profiles automatically.

Codex CLI (`~/.codex/config.toml`):

```toml
model = "gpt-5.5"
review_model = "gpt-5.5"
model_provider = "starry"
model_reasoning_effort = "xhigh"
model_verbosity = "medium"
disable_response_storage = true
windows_wsl_setup_acknowledged = true

[model_providers.starry]
name = "Starry API"
base_url = "http://127.0.0.1:8080/v1"
wire_api = "responses"
experimental_bearer_token = "sk-REPLACE_ME"
supports_websockets = false
```

Codex CLI (`~/.codex/auth.json`):

```json
{
  "OPENAI_API_KEY": "sk-REPLACE_ME"
}
```

If Codex prints `Your access token could not be refreshed because your refresh token was already used`, the local `auth.json` still contains stale ChatGPT OAuth fields. Back up the file and replace it with the minimal API-key JSON above, or run `codex logout` before applying the Starry API-key configuration.

Claude Code:

```bash
export ANTHROPIC_BASE_URL='http://127.0.0.1:8080'
export ANTHROPIC_API_KEY='sk-REPLACE_ME'
export ANTHROPIC_AUTH_TOKEN='sk-REPLACE_ME'
export ANTHROPIC_MODEL='claude-sonnet-4.5'
export ANTHROPIC_SMALL_FAST_MODEL='claude-haiku-4.5'
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1'
export CLAUDE_CODE_ATTRIBUTION_HEADER='0'
```

Windows CMD:

```cmd
set "ANTHROPIC_BASE_URL=http://127.0.0.1:8080"
set "ANTHROPIC_API_KEY=sk-REPLACE_ME"
set "ANTHROPIC_AUTH_TOKEN=sk-REPLACE_ME"
set "ANTHROPIC_MODEL=claude-sonnet-4.5"
set "ANTHROPIC_SMALL_FAST_MODEL=claude-haiku-4.5"
set "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"
set "CLAUDE_CODE_ATTRIBUTION_HEADER=0"
```

PowerShell:

```powershell
$env:ANTHROPIC_BASE_URL = 'http://127.0.0.1:8080'
$env:ANTHROPIC_API_KEY = 'sk-REPLACE_ME'
$env:ANTHROPIC_AUTH_TOKEN = 'sk-REPLACE_ME'
$env:ANTHROPIC_MODEL = 'claude-sonnet-4.5'
$env:ANTHROPIC_SMALL_FAST_MODEL = 'claude-haiku-4.5'
$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = '1'
$env:CLAUDE_CODE_ATTRIBUTION_HEADER = '0'
```

OpenCode (`opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "model": "starry/gpt-5.5",
  "small_model": "starry/gpt-5.4-mini",
  "provider": {
    "starry": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Starry API",
      "options": {
        "baseURL": "http://127.0.0.1:8080/v1",
        "apiKey": "sk-REPLACE_ME"
      }
    }
  }
}
```

CC Switch one-click imports use deep links like:

```text
ccswitch://v1/import?resource=provider&app=claude&name=Starry+API+Claude&homepage=http%3A%2F%2F127.0.0.1%3A8080&endpoint=http%3A%2F%2F127.0.0.1%3A8080&apiKey=sk-REPLACE_ME&model=claude-sonnet-4.5&icon=claude&enabled=true&haikuModel=claude-haiku-4.5&sonnetModel=claude-sonnet-4.5&opusModel=claude-opus-4.5
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

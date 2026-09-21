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

On first startup, read the generated `admin` password from the startup logs. The service prints it only once when the application Root marker is unassigned and the `admin_users` table is empty.

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

Password changes revoke every existing console session for that account. Log in again with the new password and replace `ADMIN_TOKEN` before continuing.

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

Use a cataloged `deepseek-chat`, `deepseek-reasoner`, `deepseek-v4-flash`, or `deepseek-v4-pro` ID for official routing; the `starry/` form is also accepted. All four support Chat Completions. Responses currently supports only `deepseek-v4-flash`; other DeepSeek IDs return `400` on that endpoint. A provider-scoped API key rejects model IDs that are absent from the enabled catalog, even when the lower-level route parser recognizes an older alias.

DeepSeek requests are isolated to DeepSeek accounts and do not fall back to OpenAI or Codex. For compatibility, an existing account whose `baseUrl` host is `api.deepseek.com` is recognized as DeepSeek even if its stored provider is `OpenAI-compatible`; ordinary OpenAI/Codex selection excludes it.

To add a native Anthropic API-key account, set `provider=Anthropic` and `authMethod=api-key`. A missing `baseUrl` defaults to `https://api.anthropic.com/v1`:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "email":"claude@example.com",
    "provider":"Anthropic",
    "authMethod":"api-key",
    "accessToken":"sk-ant-..."
  }'
```

Anthropic Messages and `anthropic/` model routes prefer native Anthropic accounts, then fall back to compatible Kiro/AWS accounts. Bare Claude aliases on OpenAI-compatible endpoints continue to use the Kiro route unless a model mapping selects an `anthropic/` target.

### Account allocation controls

Edit an account in the management console and open **分配策略** to inspect its lifecycle and change priority, concurrency, RPM, burst, session, and in-flight token limits. Lower priority values receive traffic first. Start with the provider defaults and increase capacity gradually; a newly created or restored account uses 10%, 25%, 50%, then 100% capacity while warming.

The scheduler admits each attempt atomically in PostgreSQL. A request can be rejected before dispatch when concurrency, RPM, burst, session, in-flight token capacity, or a matching model/endpoint cooldown is active; the proxy then tries another eligible account without counting the rejected account as an upstream failure. Dispatched leases reserve capacity for at most ten minutes and are reconciled on success/failure. Cancellation and post-dispatch local failures abandon the lease immediately. If a process dies, expired reservations are reclaimed the next time that account is considered.

Use `GET /admin/accounts/{accountId}/allocation-policy` for diagnosis. Check `lifecycleState`, `cooldownUntil`, `activeConcurrency`, and reserved tokens before raising limits. `DEGRADED` remains selectable, while `COOLDOWN`, `QUARANTINED`, and `DISABLED` do not admit traffic. Cooldown/quarantine expiry returns the account to stage-1 warming. A normal `429` does not permanently disable an account or set quota exhausted; only explicit credential-revocation evidence does so for `401/403`. For model/endpoint-local `429` diagnosis, query `account_scoped_cooldowns` and compare its `scope_key` with the upstream model ID or request endpoint.

OpenAI/Codex/DeepSeek/OpenAI-compatible scheduling is stateless and forces `maxSessions=0`. Native Anthropic accounts enforce session capacity only when clients provide a reliable conversation/session identifier. Current latency feedback measures the full upstream attempt, not streaming TTFT. Account-specific connection pools, enforced proxy-exit/TLS identity baselines, and Anthropic OAuth/Setup Token credentials are not yet available.

Codex routing is operator-tunable under 系统设置 -> 账号池设置 -> 账号路由策略. By default Codex accounts are isolated from official OpenAI API keys, ordinary GPT requests use official `sk-` accounts, admission does not create affinity, a bare API key is never treated as a session, and a dispatched Codex request is attempted on only one account. Explicit conversation affinity is committed after a successful upstream result. Enable `gptUsesCodexAccounts` only when ordinary GPT traffic should consume Codex accounts; disabling `isolateCodexPool` additionally permits mixed fallback. Default distinct-account limits are Codex 1, OpenAI/DeepSeek 3, and Anthropic/Kiro 2. Concurrency and RPM remain per-account allocation limits (OpenAI-family default 4/60).

Account-allocation retention runs at `03:35 UTC` by default. Each transaction removes at most `STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE` rows from each supported table, and one run performs at most `STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES` transactions. Terminal leases are retained for seven days and allocation events for 90 days by default; expired session bindings, scoped cooldowns, and response-owner rows are eligible immediately. Monitor table growth and the `Failed account-allocation retention maintenance` warning before increasing either retention period or batch count.

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

For a third-party model, first register the public model ID and the `openai-compatible/<actual-model-id>` target in the managed model catalog under the same provider, then create an API key for that provider and add the mapping. For example, map `llama-3.3-70b` to `openai-compatible/meta-llama/llama-3.3-70b-instruct` after both IDs are enabled. Starry API strips the routing prefix before sending the request upstream. Provider-scoped keys validate both sides of a mapping and reject unknown or cross-provider targets. For generic OpenAI-compatible accounts, `baseUrl` can be the site origin (`https://joystar.cc`) or a versioned API base (`https://openrouter.ai/api/v1`). If the path does not already end in a version segment, the runtime and account tester automatically use `/v1/chat/completions` for chat-completion checks.

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

DeepSeek account tests default to `deepseek-v4-flash`; the account row can also be tested with any other cataloged DeepSeek ID. For every provider, a normal `429` means transient capacity or rate limiting rather than a permanently exhausted quota. Manual and scheduled tests increment `errorCount` without setting `quotaExhaustedAt`, disabling, or suspending the account.

## Operate the Experimental Tiered Circuit Breaker

The **tiered account-pool circuit-breaker policy** is experimental and disabled by default. The ordinary account page exposes an **Enable experimental feature** entry point; enabling it requires an instability confirmation. Keep it disabled for critical traffic until primary/secondary capacity, alerting, and rollback have been tested: false trips, routing oscillation, and monitoring-schema changes are still possible.

With the policy disabled, the account page remains in ordinary management mode and hides the circuit banner, circuit metrics, tier controls, and weights. Enabling it changes the entire page to read-only model-pool monitoring; account create, edit, import, test, reset, and delete actions are hidden until the policy is disabled again. The enabled layout keeps the experimental label, instability warning, and disable switch visible.

Prepare tier assignments through the admin API before enabling the policy. Tier membership is stored per account and applies to all supported model families for that account's provider. Assign at least one healthy secondary account to every provider that must remain available during an open circuit:

```bash
curl -X PATCH http://127.0.0.1:8080/admin/accounts/openai-secondary \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"poolTier":"secondary","poolWeight":100}'
```

Enable the policy only after assignments are complete. Each disabled-to-enabled transition creates a new circuit epoch and activation boundary, so the first 30-second reconciliation starts with a clean observation window and includes only requests captured after this activation:

```bash
curl -X PATCH http://127.0.0.1:8080/admin/accounts/settings \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"poolPolicy":{"enabled":true}}'
```

Every resolved upstream model is assigned to a bounded `provider:model-family` circuit, such as `openai:gpt-5` or `deepseek:deepseek-v4`. Unknown models are grouped under `provider:other`, not allocated arbitrary keys. The states have these operational meanings:

- `CLOSED`: primary accounts handle traffic; the circuit opens after the configured window reaches `minSampleSize` and `failoverThresholdPercent`.
- `OPEN`: only secondary accounts handle traffic during the full `retestIntervalMinutes` cooldown.
- `HALF_OPEN`: up to `retestMaxConcurrency` real client requests probe primary accounts; other requests continue on secondary. The pool closes at or above `recoveryThresholdPercent`, otherwise it reopens.

The field names `retestIntervalMinutes` and `retestMaxConcurrency` are retained for API compatibility but now mean half-open cooldown and probe budget. `retestThresholdPercent` is also retained for compatibility and does not independently transition a circuit. The state machine never fans synthetic tests out to all accounts. Manual account tests and the separately configured disabled-account test scheduler remain independent administrative features.

Fetch the native per-model-family snapshot with:

```bash
curl http://127.0.0.1:8080/admin/accounts/pool-status \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Use `data.modelPools` as the source of truth. Alert on `state != CLOSED`, a missing `secondaryAccountCount` for a required provider, sustained secondary failures, and probes that repeatedly return to `OPEN`. `nextProbeAt`, `halfOpenInFlight`, `halfOpenCompleted`, and `halfOpenSucceeded` distinguish cooldown from active recovery. Aggregate fields such as `activeTier` and `primaryDisabled` are retained for older clients and may represent several model pools at once.

Each request log persists `circuit_epoch`, `circuit_key`, `circuit_tier`, `upstream_model`, and nullable `upstream_success`. `success` is the final client-visible outcome; `upstream_success=true` also covers an upstream success followed by local usage-settlement failure, preventing the circuit from blaming the provider. Each activation has a fresh UUID epoch, so inspect only the current epoch and activation boundary when diagnosing a transition:

```sql
WITH policy AS (
    SELECT
        NULLIF(max(setting_value) FILTER (
            WHERE setting_key = 'account_pool_policy_epoch'
        ), '')::uuid AS circuit_epoch,
        NULLIF(max(setting_value) FILTER (
            WHERE setting_key = 'account_pool_policy_activation_started_at'
        ), '')::timestamptz AS activation_started_at
    FROM starry_api.system_settings
)
SELECT
    circuit_key,
    circuit_tier,
    count(*) AS requests,
    count(*) FILTER (WHERE upstream_success IS FALSE) AS failures,
    round(
        100.0 * count(*) FILTER (WHERE upstream_success IS FALSE)
        / NULLIF(count(*), 0),
        2
    ) AS failure_rate_percent
FROM starry_api.request_logs
CROSS JOIN policy
WHERE request_logs.circuit_epoch = policy.circuit_epoch
  AND created_at >= GREATEST(
      CURRENT_TIMESTAMP - INTERVAL '5 minutes',
      policy.activation_started_at
  )
  AND created_at < CURRENT_TIMESTAMP
  AND circuit_key IS NOT NULL
  AND upstream_success IS NOT NULL
GROUP BY circuit_key, circuit_tier
ORDER BY circuit_key, circuit_tier;
```

Local outcomes update a circuit immediately. A non-overlapping reconciliation runs every 30 seconds and reads the indexed PostgreSQL window; policy-setting notifications also trigger an immediate reconciliation. The query filters by the current UUID epoch, explicit lower and upper time bounds, and the nullable `upstream_success` marker. Before the query, the instance captures a log-persistence confirmation generation. Only outcomes confirmed before that generation are replaced by the shared baseline; commits confirmed during the query remain as a local delta until the next pass, and failed log writes remain in local-only minute buckets until they age out. This can briefly double-count a just-committed outcome but cannot create a missing-sample gap. It counts successful calls, retryable transport failures, and provider-declared retryable `408`, `409`, and `5xx` outcomes; account-scoped `401`, `402`, `403`, and `429` outcomes and non-retryable parser failures remain `NULL` and are excluded. It does not call an upstream API. In multi-instance deployments, circuit state is instance-local and normally converges on the shared log window within one reconciliation interval. Route-level behavior may briefly differ across instances after a failure burst.

Policy settings synchronize separately and immediately through PostgreSQL `LISTEN/NOTIFY` on `starry_api_system_settings`. Each service instance synchronously loads the policy and indexed window before application readiness, then uses a dedicated listener connection and a single-flight guard for matching `account_pool_policy_*` updates. Full invalidation after listener reconnect cancels stale refresh generations but retains the last-known-good policy and circuit state until the replacement query succeeds; a transient refresh failure therefore cannot silently restore all-tier routing. Monitor for `PostgreSQL setting notification listener disconnected; reconnecting` and confirm the listener recovers; its retry backoff is capped at 30 seconds.

To roll back circuit routing, disable the policy. This resets local circuit state and restores ordinary account management; all provider-eligible accounts are considered without circuit-tier filtering. A later re-enable creates a new epoch and does not restore the previous activation's window:

```bash
curl -X PATCH http://127.0.0.1:8080/admin/accounts/settings \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"poolPolicy":{"enabled":false}}'
```

## Create a Proxy API Key

```bash
curl -X POST http://127.0.0.1:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"default","creditsLimit":1000,"modelProvider":"OpenAI"}'
```

`modelProvider` must match an enabled provider returned by `/v1/models`. Both the requested model and any mapped target must exist in that provider's enabled catalog. Save the returned `data.key`, then call the proxy with it:

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
New API keys retain the full value for their owner and can be copied directly from the WebUI. Legacy hash-only keys retain only their prefix; paste the original full key into the setup dialog or recreate the key before copying an OpenCode script. A `REPLACE_REST_OF_KEY` placeholder will not authenticate.
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

The default/initial administrator is the application-level root. Protect its password and sessions as credentials for the entire service: if either leaks, assume full service compromise and rotate all affected credentials. The generated bootstrap password is printed once at first startup and must be changed immediately; outside that bootstrap handoff, the incident pipeline never records or emails login passwords, password hashes/salts, the data-management key, an incorrect submitted value, or session/data-management tokens. The data-management security key is an additional confirmation for high-risk database actions, not protection against an attacker who already controls the root account.

Only the application root can access Data Management, including status, backup listing, exports, initialization and recovery imports. The console hides this module from ordinary administrators and direct API calls are rejected, even with a valid security key. Flyway V6 anchors its `user_id` in `application_root_user_id`; database triggers prevent replacement, rename, disablement, demotion, deletion, or marker changes. Other administrators cannot modify or reset Root, and only Root can obtain or use any data-management grant. Startup creates Root only in an empty user database, validates it synchronously before readiness, and aborts rather than recreating a missing Root in a populated database. The initialization response reveals the `dmsk-...` key once; copy it directly to a controlled password manager and keep an encrypted offline recovery copy. The WebUI intentionally does not download a plaintext key file. Before each protected action, request a five-minute action-bound grant from `/admin/data-transfer/security/unlock`, then pass its `dmt-...` value in `X-Data-Management-Token`. `DOWNLOAD_BACKUP` and `DELETE_BACKUP` grants are also bound to the exact filename. Delete and restore grants are single-use.

Each wrong key attempt is first committed with the counter/lock state to the owner-only security file, then replayed idempotently into the append-only `starry_security` audit schema and durable SMTP outbox. The event contains the initiating account snapshot, root flag, direct remote address, validated forwarded IP literals, irreversible User-Agent/request-ID/session fingerprints, endpoint, attempt number, and lock state. It is queued even when Root has no email. If the system SMTP transport is configured, delivery prefers the captured Root email and otherwise resolves the current Root email on every retry, so binding an email later releases the pending alert. Failed or unavailable delivery is retried with exponential backoff and logged with `alert_delivery=NOT_DELIVERED`; standard SMTP is at-least-once, so a post-acceptance crash may repeat a message with the same `X-Starry-Security-Event-Id`. The fifth failure permanently locks data management and puts the entire site into maintenance without waiting for audit or SMTP: only static UI assets, `/system/status`, and health checks remain available; all auth, admin, proxy, and payment calls return `503 SYSTEM_MAINTENANCE`. Kubernetes-style readiness reports `OUT_OF_SERVICE`, while liveness stays `UP` so an automatic restart cannot bypass the lock.

Maintenance mode also fails closed for business background work. Subsequent account token refreshes and account-pool evaluations, disabled-account tests, payment reconciliations, usage aggregation/retention, performance-metric writes, account-cache refreshes, and registration-token cleanup are skipped. Lock-state refresh, pending security-incident persistence, the SMTP security-alert outbox, health checks, and in-memory session/rate-limit cleanup continue running. A task that passed the gate before the fifth failure is not forcibly interrupted; isolate the deployment if immediate termination of already-running external calls is required.

Run exactly one active service instance after enabling this feature. The verifier/lock state and short-lived grants are instance-local; replicas or rolling overlap are unsupported. Persist the entire directory containing `STARRY_DATA_MANAGEMENT_SECURITY_FILE`, including its adjacent `.initialized` marker, with owner-only permissions. After initialization and backup verification, set `STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED=true` and restart. Never delete files to recover: a missing JSON with a surviving marker, or a completely missing volume while that deployment guard is enabled, fails closed.

### Recover a Docker Compose deployment

The runtime image contains the reviewed recovery utility at the fixed path `/app/bin/recover-data-management`; do not download a recovery script at incident time and never use `curl ... | sh` for this operation. The utility requires an interactive TTY, reads the original `dmsk-...` key twice with terminal echo disabled, and writes it only to the owner-only one-time file `/app/security/recovery.key`. It rejects command-line or piped secret input, so the key is not exposed through shell history, process arguments, or `docker inspect`.

Stop normal traffic and make sure exactly one `service` container is active. For a server installed under `/opt/starry-api`, choose Chinese or English prompts and run:

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# English prompts: replace "--lang zh" with "--lang en".

docker compose -f docker-compose.artifact.yml restart service
```

Do not add Compose's `-T` option: the hidden, double-entry prompt deliberately requires a TTY. The utility verifies that the site is currently in maintenance before creating the fixed recovery file. It does not restart its own container, so the explicit restart is required. For a source deployment using the repository `docker-compose.yml`, use the same commands without `-f docker-compose.artifact.yml`.

After the service finishes starting, verify both the public status and one-time-file consumption:

```bash
docker compose -f docker-compose.artifact.yml exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming && echo "recovery key files consumed"'
```

A normal matching attempt produces `"maintenance":false`, resets the failed-attempt count, records `RECOVERY_SUCCEEDED`, and consumes both recovery paths. A normal readable, valid-format but nonmatching attempt records `RECOVERY_FAILED`, consumes both paths, and leaves the service running in maintenance; rerun the interactive utility with the correct key and restart once more.

Do not declare recovery or consumption complete from an audit record alone. If truncating, deleting, or synchronizing the one-time file fails, the current process remains fail-closed even if the matching state commit and `RECOVERY_SUCCEEDED` record already exist. Inspect names and permissions without printing their contents:

```bash
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'ls -ld /app/security; ls -l /app/security/recovery.key /app/security/.recovery.key.consuming 2>/dev/null || true'
docker compose -f docker-compose.artifact.yml logs --tail=200 service
```

Do not stage another key or manually delete either file while cleanup is unresolved. Correct the security-volume ownership, permissions, free-space, or filesystem error, then restart `service`; startup retries cleanup of `/app/security/.recovery.key.consuming`. Recovery is complete only after `/system/status` reports `"maintenance":false` and both paths are absent. If the process stops before a valid recovery state commit rather than during cleanup, the next startup restores the staged file to `/app/security/recovery.key` and safely retries the transaction.

### Recover a direct host/JAR deployment

For a service running directly on the host, stop normal traffic and all service replicas, place the original key alone in a writable owner-only regular file (`chmod 600`), set `STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE` to that absolute path, and start exactly one service instance. After validation succeeds, the service atomically stages the file, commits the recovered state, then truncates, deletes, and synchronizes the staged one-time key. Confirm the recovery audit event, `"maintenance":false`, and that both the configured file and its `.<file-name>.consuming` sibling are gone before unsetting the variable. A readable, valid-format but nonmatching key is normally recorded and consumed while maintenance remains active. A cleanup failure retains fail-closed maintenance and is retried at startup as described above. Do not use a symlink or read-only secret mount, and never leave a plaintext recovery key configured during ordinary operation.

Importing data is intentionally guarded. The WebUI asks you to type:

```text
RESTORE_DATABASE
```

Before every import, the service validates the custom archive with `pg_restore` and then checks the archive TOC. Only PostgreSQL 17 archives for database/schema `starry_api` are accepted; after restore their Flyway V1-V19 scripts and checksums must exactly match the running application, the immutable Root marker must resolve to the one enabled `ADMIN` Root, and the core application tables must be present without objects from another schema. The separate `starry_security` audit/outbox schema is intentionally absent from archives and survives application restores. The runtime image includes PostgreSQL 17 client tools; hosts running the jar directly must provide PostgreSQL 17 `pg_dump` and `pg_restore` commands in `PATH`.

Backups use a PostgreSQL session shared advisory lock and restores use the matching exclusive lock. These locks coordinate all service instances, not only threads in one JVM. After acquiring the exclusive lock, restore performs this sequence:

1. Revoke database `CONNECT` from `starry_api_runtime` and terminate existing `starry_api` backends.
2. Create a server-side rollback backup from the quiesced database.
3. Run `pg_restore --clean --if-exists --single-transaction --exit-on-error` for schema `starry_api`.
4. Verify that schema `starry_api`, all required core tables, the exact Flyway V1-V19 history/checksums, the V5 circuit-epoch column/index/settings, and exactly one enabled `admin` Root matching `application_root_user_id` exist. Also verify that `starry_security` retained its append-only trigger, least-privilege runtime ACL, and secret-free schema, then run `ANALYZE` while runtime access is still disabled. If validation or analysis fails after the incoming restore committed, automatically clean-restore the pre-restore dump and validate and analyze the rollback result before reopening access.
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
curl -X POST http://127.0.0.1:8080/admin/data-transfer/security/init \
  -H "Authorization: Bearer $ADMIN_TOKEN"

curl -X POST http://127.0.0.1:8080/admin/data-transfer/security/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"securityKey":"<offline-data-management-key>","action":"EXPORT_DATABASE","target":null}'

curl -OJ http://127.0.0.1:8080/admin/data-transfer/database.dump \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Data-Management-Token: $DATA_MANAGEMENT_TOKEN"

curl -X POST http://127.0.0.1:8080/admin/data-transfer/security/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"securityKey":"<offline-data-management-key>","action":"RESTORE_DATABASE","target":null}'

curl -X POST 'http://127.0.0.1:8080/admin/data-transfer/import?confirm=RESTORE_DATABASE' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Data-Management-Token: $DATA_MANAGEMENT_TOKEN" \
  -F "file=@/path/to/starry-api-database.dump"
```

Read the `data.accessToken` from each unlock response into `DATA_MANAGEMENT_TOKEN`. Do not enable shell tracing or persist the security key or grant token in command history.

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

Log in as an administrator and open `系统设置 -> 支付设置`.

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

Proxy API calls deduct balance from itemized token usage. Without an enabled override rule, the service uses verified official USD prices from [`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) and multiplies them by `系统设置 -> 支付设置 -> 模型官方价格计费倍率`. The default is `1.0`, which charges official cost without markup. Fast mode keeps its existing additional `2x` factor.

The service starts with the bundled catalog and attempts a checksum-verified refresh at startup and every 10 minutes. A failed refresh never clears or partially replaces the active catalog. Exact and wildcard rules in `系统设置 -> 模型管理 -> 价格倍率` override the global billing multiplier with a direct official-price multiplier; `1.0` is the official price and `0.1` is 10% of it. If a requested model has no official entry, billing applies the effective multiplier to the legacy safety fallback of `16.6666666667` balance per 1M tokens.

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

# Configuration

Runtime configuration is read from environment variables through `service/src/main/resources/application.yml`.

The service is being refactored from a Kiro-only proxy into Starry API. Some runtime variable names still use the `KIRO_` prefix for deployment compatibility. Treat those as the current upstream-adapter/runtime variables, not as frontend or public API naming.

## Server

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | HTTP listen address for direct runs. Container images override this with `0.0.0.0`; host exposure remains controlled by `HOST_BIND_ADDR`. |
| `SERVER_PORT` | `8080` | HTTP port. |
| `SPRING_CODEC_MAX_IN_MEMORY_SIZE` | `16MB` | Max in-memory body size for WebFlux codecs. |
| `JAVA_OPTS` | empty | Optional JVM flags used by container/scripts. |

## PostgreSQL 17

PostgreSQL 17 is the production baseline. The service uses JDBC for Flyway and R2DBC for application queries. The database and application schema are both `starry_api`.

| Variable | Default | Description |
| --- | --- | --- |
| `POSTGRES_HOST` | `localhost` | PostgreSQL host for direct local runs. Compose overrides this with the `postgres` service name. |
| `POSTGRES_PORT` | `5432` | PostgreSQL port. |
| `POSTGRES_DATABASE` | `starry_api` | PostgreSQL database name. |
| `POSTGRES_USERNAME` | `starry_api` | Application database role. |
| `POSTGRES_PASSWORD` | empty | Application database password; required outside trusted local development. |
| `POSTGRES_MIGRATION_USERNAME` | `starry_api_migration` | Dedicated Flyway and maintenance role. |
| `POSTGRES_MIGRATION_PASSWORD` | empty | Migration-role password; required outside trusted local development. |
| `POSTGRES_POOL_INITIAL_SIZE` | `4` | R2DBC pool initial size. |
| `POSTGRES_POOL_MAX_SIZE` | `20` | R2DBC pool maximum size; size this against the server connection budget. |
| `POSTGRES_POOL_MAX_ACQUIRE_TIME` | `5s` | Maximum wait for a pooled connection. |
| `POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME` | `5s` | Maximum connection establishment time. |
| `POSTGRES_POOL_MAX_LIFE_TIME` | `2h` | Maximum pooled connection lifetime. |
| `POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES` | `256` | Per-connection prepared-statement cache bound. |
| `POSTGRES_STATEMENT_TIMEOUT` | `PT2M` | Maximum runtime for an application SQL statement. |
| `POSTGRES_LOCK_WAIT_TIMEOUT` | `PT5S` | Maximum time an application statement waits for a database lock. |
| `POSTGRES_SHM_SIZE` | `256mb` | Shared-memory allocation for the PostgreSQL container. |
| `POSTGRES_MAX_CONNECTIONS` | `100` | Server connection ceiling; reserve capacity for migrations, maintenance, and monitoring. |
| `POSTGRES_SHARED_BUFFERS` | `256MB` | PostgreSQL shared buffer cache. |
| `POSTGRES_EFFECTIVE_CACHE_SIZE` | `768MB` | Planner estimate of memory available to PostgreSQL and the OS page cache; this does not allocate memory. |
| `POSTGRES_WORK_MEM` | `4MB` | Per sort/hash operation memory limit; one query can use this more than once. |
| `POSTGRES_MAINTENANCE_WORK_MEM` | `64MB` | Memory available to maintenance operations. |
| `POSTGRES_AUTOVACUUM_WORK_MEM` | `64MB` | Per autovacuum worker memory limit. |
| `POSTGRES_AUTOVACUUM_MAX_WORKERS` | `4` | Maximum concurrent autovacuum workers. |
| `POSTGRES_AUTOVACUUM_NAPTIME` | `30s` | Delay between autovacuum launcher rounds. |
| `POSTGRES_DEFAULT_STATISTICS_TARGET` | `200` | Default planner statistics detail. |
| `POSTGRES_WAL_COMPRESSION` | `on` | Compress full-page images in WAL to reduce write volume. |
| `POSTGRES_MIN_WAL_SIZE` | `512MB` | WAL retained below normal checkpoint pressure. |
| `POSTGRES_MAX_WAL_SIZE` | `2GB` | Soft WAL growth limit between checkpoints. |
| `POSTGRES_CHECKPOINT_COMPLETION_TARGET` | `0.9` | Spread checkpoint writes across most of the interval. |
| `POSTGRES_TRACK_IO_TIMING` | `on` | Collect I/O timing for production diagnosis. |
| `POSTGRES_IDLE_IN_TRANSACTION_SESSION_TIMEOUT` | `5min` | Terminate sessions left idle while holding an open transaction. |
| `FLYWAY_ENABLED` | `true` | Run schema migration on startup. |
| `FLYWAY_CONNECT_RETRIES` | `10` | Number of Flyway connection retries during startup. |

Create the local database and least-privilege roles on a fresh PostgreSQL cluster:

```bash
export POSTGRES_USER=postgres
export POSTGRES_DB=starry_api
export STARRY_POSTGRES_MIGRATION_PASSWORD='<strong-local-migration-password>'
export STARRY_POSTGRES_APP_PASSWORD='<strong-local-application-password>'

createdb --username "$POSTGRES_USER" --encoding=UTF8 "$POSTGRES_DB"
./docker/postgresql/init/001-starry-api-roles.sh
```

The R2DBC and Flyway URLs set `starry_api` as the current/default schema. Do not place application tables in `public`.

The Compose defaults are a conservative OLTP baseline for a node with at least 2 GiB available memory and SSD-backed storage. Recalculate buffer, cache, WAL, and connection settings from measured workload and host limits before scaling replicas. Across all service replicas, keep the sum of R2DBC pool maxima below `POSTGRES_MAX_CONNECTIONS` with headroom for Flyway, backup/restore, and operator sessions.

The bootstrap grants `starry_api_owner` inherited privileges from the concrete `starry_api` login role without allowing `SET ROLE starry_api`. This narrowly permits the owner used by data-transfer maintenance to terminate only Starry API application backends during a restore; it is intentionally not granted the cluster-wide `pg_signal_backend` role.

## Database Backup and Restore

The data-transfer API uses PostgreSQL 17 `pg_dump`/`pg_restore` custom-format archives. Container runtimes already include matching client tools.

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_BACKUP_DIR` | `${user.home}/.starry-api/backups` | Server-side `.zip` backup directory. Each package contains `database.dump` and metadata. Compose maps this to `./data/backups`. |
| `STARRY_MAX_IMPORT_BYTES` | `268435456` | Maximum uploaded `.dump` or `.zip` size in bytes. |
| `STARRY_DATA_TRANSFER_PROCESS_TIMEOUT` | `30m` | Timeout for each `pg_dump` or `pg_restore` process. |

ZIP exports and imports use `database.dump` as the archive entry. Database backups take a PostgreSQL session-level shared advisory lock, while restores take the matching exclusive lock, so the contract also applies across application instances. A conflicting cross-instance maintenance operation is rejected instead of allowing backup and restore snapshots to overlap.

Imports accept only PostgreSQL 17 custom archives whose TOC identifies database and schema `starry_api`, contains `flyway_schema_history` and the required core tables, and contains no objects from another schema. `pg_restore --clean --single-transaction` then replaces the schema. This matches the pinned PostgreSQL 17 client and is deliberately stricter than accepting an arbitrary PostgreSQL custom archive.

During restore, the migration connection revokes `CONNECT` from `starry_api_runtime`, terminates existing `starry_api` backends, creates the rollback backup from the quiesced database, and keeps runtime connections disabled until restore finishes. Before reopening access, the same owner session verifies the restored schema, every required core relation, and a successful Flyway V1 baseline, then runs `ANALYZE` so newly restored tables do not serve traffic with empty planner statistics. If the incoming `pg_restore` committed but this postcondition fails, the service automatically restores the pre-restore dump and validates and analyzes it again; rollback failures are retained as suppressed diagnostics. `CONNECT` is restored in a `finally` path, including failed restores. The local instance clears all database-derived caches and sessions whenever it entered the restore isolation window; other instances do the same when their PostgreSQL notification listener reconnects. Cache generations prevent an older in-flight refresh from repopulating state after invalidation. Administrators and users should expect in-flight requests to fail and administrator sessions to require a new login after a restore.

## Runtime and Upstream Adapter

OpenAI, Codex, Anthropic, and DeepSeek credentials are managed as upstream account records in PostgreSQL, not as deployment environment variables. A DeepSeek account with `provider=DeepSeek` defaults to `authMethod=api-key` and `baseUrl=https://api.deepseek.com/v1`, so enabling the official provider does not require a new server-side secret or URL variable.

| Variable | Default | Description |
| --- | --- | --- |
| `KIRO_ADMIN_TOKEN` | empty | Optional legacy static admin token. Prefer local admin login. |
| `KIRO_WEBUI_URL` | empty | WebUI login URL printed in startup logs. |
| `KIRO_ADMIN_SESSION_TTL` | `12h` | Local admin session lifetime. |
| `KIRO_ALLOW_ANONYMOUS_PROXY` | `false` | Allow proxy calls without API key. Keep `false` publicly. |
| `KIRO_REQUEST_TIMEOUT` | `90s` | Upstream request timeout. |
| `KIRO_MAX_RETRIES` | `2` | Upstream retry count for recoverable errors. |
| `KIRO_RATE_LIMIT_PER_MINUTE` | `120` | Fixed-window per API key rate limit. `0` disables rate limit. |
| `KIRO_ACCOUNT_SELECTION_STRATEGY` | `round_robin` | `round_robin` or `sticky`. |
| `KIRO_SESSION_AFFINITY_ENABLED` | `true` | Route same session hint to same upstream account when possible. |
| `KIRO_SESSION_AFFINITY_TTL` | `10m` | Session affinity TTL. |
| `KIRO_SESSION_AFFINITY_MAXIMUM_SIZE` | `100000` | Maximum in-memory session-affinity entries per application instance. |
| `KIRO_BASE_COOLDOWN` | `60s` | Base cooldown for recoverable account errors. |
| `KIRO_QUOTA_RESET` | `1h` | Planned quota reset duration. |
| `KIRO_PROBABILISTIC_RETRY` | `0.1` | Chance to probe a cooling account early. |
| `KIRO_MAX_REQUEST_BODY_BYTES` | `10485760` | Max inbound request content length. |
| `KIRO_TOKEN_REFRESH_INTERVAL_MS` | `3600000` | Background Kiro token refresh interval. |
| `KIRO_TOKEN_REFRESH_INITIAL_DELAY_MS` | `60000` | Delay before first background token refresh. |
| `KIRO_PREFERRED_ENDPOINT` | `codewhisperer` | Preferred Kiro/AWS endpoint: `codewhisperer`, `amazonq`, or `amazonq-cli`. |

## Client-Side API Key Setup

The WebUI API Key page generates API-key based client configuration:

| Client | Generated configuration |
| --- | --- |
| Codex CLI | `~/.codex/config.toml` plus minimal `~/.codex/auth.json`, using the HTTP Responses API and a direct Starry API-key bearer token. |
| Claude Code | Shell environment variables: `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL`, `ANTHROPIC_SMALL_FAST_MODEL`, and Claude Code traffic flags. |
| OpenCode | Project-level `opencode.json` with a `starry` OpenAI-compatible provider. |
| CC Switch | `ccswitch://v1/import?resource=provider...` deep links for Claude Code, Codex CLI, and OpenCode. |

These client settings are generated per API key and do not replace server runtime variables. For Codex CLI, replace stale ChatGPT OAuth `auth.json` content with the generated API-key JSON if Codex reports that a refresh token was already used.

## Registration Settings

Registration is configured from the admin console under `账号 -> 注册设置`.

| Setting key | Purpose |
| --- | --- |
| `registration_enabled` | Enable public self-registration. |
| `email_verify_enabled` | Require email verification for public self-registration. |
| `invite_code_enabled` | Enable one-time invite-code registration. |
| `registration_email_suffix_whitelist` | Optional JSON email suffix whitelist. |
| `smtp_security` | SMTP encryption mode: `auto`, `ssl`, `starttls`, or `none`. |

When invite-code registration is enabled, administrators can generate one-time codes in the same settings dialog. Users pass `inviteCode` to `POST /auth/register`; a valid code allows direct registration even if public registration is disabled.

For Tencent Exmail, configure `smtp.exmail.qq.com:465` with `smtp_security=ssl`, or
`smtp.exmail.qq.com:587` with `smtp_security=starttls`.

## Payment Settings

Online balance recharge business rules are configured from the admin console. The reconciliation workers have separate deployment-level scheduling controls:

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_PAYMENT_RECONCILIATION_INTERVAL` | `PT30S` | Delay between local pending-order reconciliation runs. |
| `STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY` | `PT30S` | Delay before the first local pending-order reconciliation run. |
| `STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL` | `PT30S` | Delay between payment-provider reconciliation runs. |
| `STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY` | `PT45S` | Delay before the first payment-provider reconciliation run. |

Business settings:

- Admin path: `支付设置`
- User path: `余额充值`
- Provider: EasyPay-compatible gateway
- Webhook: `GET/POST /payment/webhook/easypay`

The settings follow the sub2api-style keys stored in `system_settings`:

| Key | Purpose |
| --- | --- |
| `payment_enabled` | Enable user recharge order creation. |
| `MIN_RECHARGE_AMOUNT`, `MAX_RECHARGE_AMOUNT` | Per-order recharge amount range. `MAX_RECHARGE_AMOUNT = 0` means no maximum limit. |
| `DAILY_RECHARGE_LIMIT` | Per-user daily paid amount limit. `0` means no limit. |
| `ORDER_TIMEOUT_MINUTES` | Pending order expiry window. |
| `MAX_PENDING_ORDERS` | Maximum pending orders per user. |
| `ENABLED_PAYMENT_TYPES` | Comma-separated `alipay,wxpay`. |
| `BALANCE_RECHARGE_MULTIPLIER` | Credited balance multiplier. |
| `API_BILLING_MULTIPLIER` | Balance deducted per billable token. Default `0.0000166666666667`, so 100 balance covers about 6,000,000 tokens. |
| `RECHARGE_FEE_RATE` | Extra fee rate added to the payable amount. |
| `EASYPAY_API_BASE`, `EASYPAY_PID`, `EASYPAY_PKEY` | EasyPay gateway URL and merchant credentials. |
| `EASYPAY_PAYMENT_MODE` | `popup` for submit-page redirect, `qrcode` for `mapi.php`. |

Model-specific pricing is managed from `模型价格`. The admin form has two modes: total pricing is entered as an equivalent multiplier (`x`) against the current `API_BILLING_MULTIPLIER` baseline, while split pricing follows the sub2api/LiteLLM style with input, output, cache-write, and cache-read prices entered as balance per 1M tokens (`余额/MTok`). The one-click preset action replaces the current rule list with a compact wildcard catalog for OpenAI, Anthropic, Gemini/Google, DeepSeek, and a global fallback. Runtime billing either applies the total multiplier to all billable tokens or itemizes those token classes before deducting balance.

When no enabled rule matches, the runtime has these official DeepSeek defaults:

| Model | Input/cache miss | Cache hit | Output |
| --- | ---: | ---: | ---: |
| `deepseek-v4-flash` | 0.14 | 0.0028 | 0.28 |
| `deepseek-v4-pro` | 0.435 | 0.003625 | 0.87 |

Values are balance per 1M tokens, and cache writes use the input/cache-miss value. The first enabled matching explicit rule, including `*` or another wildcard, overrides the built-in DeepSeek price. If official pricing changes, add or update an exact DeepSeek rule instead of waiting for a release. The global `*` preset is itself an explicit override; the built-in DeepSeek table applies only when no enabled rule matches.

`EASYPAY_PKEY` is only accepted from the admin update API and is never returned to the WebUI; the admin page only shows whether it has already been configured.

## Performance Metrics

The service records performance samples into `performance_metrics` every 30 seconds after startup:

- system CPU load
- process CPU load
- JVM heap and non-heap memory
- system memory
- Linux network receive/transmit counters and per-second rates

The admin dashboard reads `GET /admin/performance` and displays the latest 120 samples. Samples older than 7 days are purged daily at 03:15 UTC.

## Request Analytics and Retention

Completed request-log hours are incrementally rolled into hourly and daily tables. A PostgreSQL advisory lock and `aggregation_watermarks` prevent overlapping workers. Each batch advances a bounded number of hours in its own short transaction and rebuilds a configurable lookback window to absorb delayed commits.

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_STATS_AGGREGATION_ENABLED` | `true` | Enable request-usage aggregation and partition maintenance. |
| `STARRY_STATS_AGGREGATION_INTERVAL` | `PT1M` | Delay between incremental aggregation runs. |
| `STARRY_STATS_AGGREGATION_INITIAL_DELAY` | `PT30S` | Delay before the first aggregation run after startup. |
| `STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS` | `1` | Maximum new hours advanced per transaction; accepted range is 1-168. |
| `STARRY_STATS_AGGREGATION_LOOKBACK_HOURS` | `6` | Completed hours rebuilt on every advancing batch to absorb late commits; accepted range is 1-168. |
| `STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN` | `6` | Maximum sequential short transactions per scheduled run; accepted range is 1-100. |
| `STARRY_STATS_MAINTENANCE_CRON` | `0 25 3 * * *` | UTC cron for partition/retention maintenance. |
| `STARRY_STATS_PARTITION_MONTHS_AHEAD` | `12` | Monthly `request_logs` partitions to pre-create; accepted range is 1-60. |
| `STARRY_STATS_RAW_RETENTION_MONTHS` | `4` | Raw monthly partitions to retain; the enforced minimum of four calendar months guarantees at least 90 days. |
| `STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE` | `10000` | Expired rows removed from the raw default partition per short transaction; accepted range is 100-100000. |
| `STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES` | `20` | Maximum raw default-partition cleanup transactions per maintenance run; accepted range is 1-100. |
| `STARRY_STATS_HOURLY_RETENTION_DAYS` | `90` | Hourly aggregate retention; accepted range is 7-3650 days. Daily aggregates remain available for long-range analytics. |
| `STARRY_STATS_HOURLY_DELETE_BATCH_SIZE` | `10000` | Expired hourly aggregates removed per short transaction; accepted range is 100-100000. |
| `STARRY_STATS_HOURLY_DELETE_MAX_BATCHES` | `20` | Maximum hourly aggregate cleanup transactions per maintenance run; accepted range is 1-100. |
| `STARRY_STATS_USAGE_EVENT_RETENTION_DAYS` | `400` | Billing-idempotency event retention; accepted range is 30-3650 days. The immutable balance ledger is not pruned. |
| `STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE` | `10000` | Rows removed per short retention transaction; accepted range is 100-100000. |
| `STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES` | `20` | Maximum retention transactions per maintenance run; accepted range is 1-100. |

Keep aggregation enabled in production. Disabling it also disables automatic partition creation and retention maintenance. Raw request-log partitions are never dropped while the aggregation watermark still has backlog, so recovery after a long outage favors complete rollups over immediate space reclamation.

## Recommended Local Environment

```bash
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT=8080
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DATABASE=starry_api
export POSTGRES_USERNAME=starry_api
export POSTGRES_PASSWORD="$STARRY_POSTGRES_APP_PASSWORD"
export POSTGRES_MIGRATION_USERNAME=starry_api_migration
export POSTGRES_MIGRATION_PASSWORD="$STARRY_POSTGRES_MIGRATION_PASSWORD"
export POSTGRES_POOL_INITIAL_SIZE=4
export POSTGRES_POOL_MAX_SIZE=20
export KIRO_WEBUI_URL=http://127.0.0.1:5173/#/login
export KIRO_ADMIN_SESSION_TTL=12h
export KIRO_ALLOW_ANONYMOUS_PROXY=false
export KIRO_RATE_LIMIT_PER_MINUTE=120

./gradlew :service:bootRun
```

## Account Selection

`round_robin`:

- Successful requests advance to the next available account.
- Better for distributing load across accounts.

`sticky`:

- Keeps using the same selected account when possible.
- Better for cache locality and conversation continuity.

Session affinity is applied before normal selection when a session hint is present.

## Production Defaults

- Save and change the first-start `admin` password immediately.
- Use local admin login for normal administration.
- Set `KIRO_ADMIN_TOKEN` only for emergency or automation paths.
- Keep `KIRO_ALLOW_ANONYMOUS_PROXY=false`.
- Put the service behind TLS.
- Keep the dedicated `starry_api` runtime role and `starry_api_migration` Flyway role separate; do not grant DDL privileges to the runtime role.
- Keep the `starry_api` schema first in `search_path` and monitor connection saturation, slow queries, autovacuum, and replication/backup health.
- Monitor `/actuator/health` and `/actuator/prometheus`.
- Disable Swagger/OpenAPI in public production environments if needed:

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

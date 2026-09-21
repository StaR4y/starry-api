# Configuration

Runtime configuration is read from environment variables through `service/src/main/resources/application.yml`.

## Plugin Runtime

| Variable | Default | Purpose |
| --- | --- | --- |
| `STARRY_PLUGIN_DIR` | `${user.home}/.starry-api/plugins` | Persistent plugin packages and data. Compose fixes the container path at `/app/plugins` and mounts `./data/plugins`. |
| `STARRY_PLUGINS_DISABLED` | `false` | Emergency startup kill switch. `true` prevents all plugin loading; `false` does not enable developer mode. |

Developer mode is disabled on a fresh database and can only be enabled through the
root password/email verification workflow. See [Plugin Developer Mode](PLUGINS.md).

The platform modularization and multi-provider routing work are already in place. Some shared runtime variables still use the historical `KIRO_` prefix for deployment compatibility; treat those as active Starry API proxy settings, not as a statement that the service is Kiro-only.

## Support Ticket Notifications

Configure under **System Settings → Ticket Notifications**. Notifications default to off and reuse system SMTP. Choose active administrator recipients (empty means all), reminder-only or conversation-content emails, a reminder interval (default 240 minutes; 15–10080 allowed), and an optional console base URL for direct links.

The durable PostgreSQL dispatcher checks every 30 seconds and resumes after restart. Optional Spring environment overrides are `STARRY_TICKETS_NOTIFICATION_POLL_INTERVAL` and `STARRY_TICKETS_NOTIFICATION_INITIAL_DELAY`, both defaulting to `PT30S`. Errors and manual retries are available in the same settings page. Ticket attachments reside in PostgreSQL and require no extra filesystem mount. See [Support Tickets](SUPPORT_TICKETS.md) for request limits, storage budgeting and delivery semantics.

## Server

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | HTTP listen address for direct runs. Container images override this with `0.0.0.0`; host exposure remains controlled by `HOST_BIND_ADDR`. |
| `SERVER_PORT` | `8080` | HTTP port. |
| `SPRING_CODEC_MAX_IN_MEMORY_SIZE` | `16MB` | Max in-memory body size for WebFlux codecs. |
| `JAVA_OPTS` | empty | Optional JVM flags used by container/scripts. |

For a normal single-instance production deployment, start with a 1.5 GiB
container memory limit and an explicit JVM budget:

```bash
JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+HeapDumpOnOutOfMemoryError"
```

Use a 2 GiB container limit for higher concurrency or many long-lived streaming
responses. Avoid assigning 75% of an otherwise unbounded host or container to
the Java heap: Netty direct buffers, Metaspace, code cache, thread stacks, and
native libraries all require memory outside the heap. See
[Performance and Memory Baseline](PERFORMANCE.md) for the measured live-data
floor, load-test results, monitoring thresholds, and sizing rationale.

## PostgreSQL 17

PostgreSQL 17 is the production baseline. The service uses JDBC for Flyway and R2DBC for application queries. The database and application schema are both `starry_api`.

| Variable | Default | Description |
| --- | --- | --- |
| `POSTGRES_HOST` | `localhost` | PostgreSQL host for direct local runs. Compose overrides this with the `postgres` service name. |
| `POSTGRES_PORT` | `5432` | PostgreSQL port. |
| `POSTGRES_DATABASE` | `starry_api` | PostgreSQL database name. |
| `POSTGRES_SSL_MODE` | `disable` | PostgreSQL SSL mode passed to both R2DBC and Flyway (`disable`, `allow`, `prefer`, `require`, `verify-ca`, or `verify-full`, subject to driver support). Use certificate verification for remote production databases. |
| `POSTGRES_BOOTSTRAP_PASSWORD` | none | Compose-only password for the `starry_api_bootstrap` PostgreSQL cluster superuser. The application never uses this role. Store it as a database break-glass credential and rotate it under an audited maintenance procedure. |
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

The bootstrap grants `starry_api_owner` inherited privileges from the concrete `starry_api` login role without allowing `SET ROLE starry_api`. This narrowly permits the owner used by data-transfer maintenance to terminate only Starry API application backends during a restore; it is intentionally not granted the cluster-wide `pg_signal_backend` role. The separate `starry_api_bootstrap` login remains a PostgreSQL superuser because the container entrypoint needs a cluster owner and operators need a database-level break-glass path. It is not exposed outside the internal Compose network and is never configured in the service. Protect it independently, restrict access to `.env`, monitor its use, and rotate it after any suspected disclosure.

## Database Backup and Restore

The data-transfer API uses PostgreSQL 17 `pg_dump`/`pg_restore` custom-format archives. Container runtimes already include matching client tools.

Data Management and Plugin Development are visible and accessible only to the initial application Root. Every data-transfer endpoint, including status, backup listing, export and grant issuance, requires that Root's active database-backed session; other administrators and static admin tokens cannot use the module. Root identification comes from `application_root_user_id`, with `/auth/permissions` supplying the console's visibility decision. Existing security-key and plugin password/email verification requirements still apply.

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_BACKUP_DIR` | `${user.home}/.starry-api/backups` | Server-side `.zip` backup directory. Each package contains `database.dump` and metadata. Compose maps this to `./data/backups`. |
| `STARRY_MAX_IMPORT_BYTES` | `268435456` | Maximum uploaded `.dump` or `.zip` size in bytes. |
| `STARRY_DATA_TRANSFER_PROCESS_TIMEOUT` | `30m` | Timeout for each `pg_dump` or `pg_restore` process. |
| `STARRY_DATA_MANAGEMENT_SECURITY_FILE` | `${user.home}/.starry-api/security/data-management.json` | Owner-only file containing the salted data-management key verifier, lock state, and bounded sanitized incident spool. Persist its whole directory, including the automatically created `.initialized` marker; neither file contains a plaintext key. |
| `STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED` | `false` | Fail closed when both the security state and marker are absent. Leave `false` only for the first initialization; after persisting the generated state, set `true` and restart so total security-volume loss cannot be mistaken for a fresh deployment. |
| `STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE` | empty (direct host); `/app/security/recovery.key` (bundled Compose) | Writable owner-only one-time file containing the original data-management key for startup recovery after the permanent fifth-attempt lock. Docker users should create it only through the image's `/app/bin/recover-data-management --lang zh|en` utility. Matching and nonmatching readable, valid-format keys are consumed only after truncate/delete/directory-sync cleanup succeeds; failure keeps the process fail-closed and startup retries the `.<file-name>.consuming` sibling. Do not use a symlink or read-only secret mount. |

ZIP exports and imports use `database.dump` as the archive entry. The archive contains only the application `starry_api` schema; the append-only `starry_security` audit/outbox schema is deliberately excluded so restoring application data cannot erase later security evidence. Database backups take a PostgreSQL session-level shared advisory lock, while restores take the matching exclusive lock, so the contract also applies across application instances. A conflicting cross-instance maintenance operation is rejected instead of allowing backup and restore snapshots to overlap.

Imports accept only PostgreSQL 17 custom archives whose TOC identifies database and schema `starry_api`, contains `flyway_schema_history` and the required core tables, and contains no objects from another schema. `pg_restore --clean --single-transaction` then replaces the schema. This matches the pinned PostgreSQL 17 client and is deliberately stricter than accepting an arbitrary PostgreSQL custom archive.

During restore, the migration connection revokes `CONNECT` from `starry_api_runtime`, terminates existing `starry_api` backends, creates the rollback backup from the quiesced database, and keeps runtime connections disabled until restore finishes. Before reopening access, the same owner session requires the exact successful Flyway V1-V19 history captured before restore, including version, script name, migration type, and checksum; validates every required core relation; verifies the immutable `application_root_user_id` marker resolves to exactly one enabled `ADMIN` root; checks the V5 circuit-epoch column, settings, and valid supporting index; and verifies the external security schema's append-only trigger, least-privilege ACL, and absence of secret columns. It then runs `ANALYZE` so newly restored tables do not serve traffic with empty planner statistics. Older histories, unknown future migrations, renamed scripts, and checksum mismatches are rejected. If the incoming `pg_restore` committed but any postcondition fails, the service automatically restores the pre-restore dump and validates and analyzes it again; rollback failures are retained as suppressed diagnostics. `CONNECT` is restored in a `finally` path, including failed restores. The local instance clears all database-derived caches and sessions whenever it entered the restore isolation window; other instances do the same when their PostgreSQL notification listener reconnects. Cache generations prevent an older in-flight refresh from repopulating state after invalidation. Administrators and users should expect in-flight requests to fail and administrator sessions to require a new login after a restore.

The default/initial administrator is the application-level root. Its password and sessions must be protected as service-wide credentials; data-management second verification does not contain or limit an attacker who already controls that root identity. Flyway V6 persists its `user_id` in the immutable `application_root_user_id` setting, and database triggers prevent identity replacement, rename, disablement, demotion, or deletion. Other administrators cannot modify or reset this account. A root password change or self-reset revokes every existing root console session. Root is created only when the user table is empty; a missing or invalid Root in a populated database aborts startup instead of being silently recreated.

Data-management security currently requires exactly one active service instance because the verifier/lock state and action grants are instance-local. Do not enable it in a replicated or rolling-overlap service deployment. Persist the complete security directory on owner-only storage and monitor it independently from PostgreSQL backups. Deleting only the JSON after initialization leaves the adjacent marker and fails closed. After initialization, set `STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED=true`; then losing the whole security volume also fails closed instead of silently reopening initialization.

The source and prebuilt Docker images package the reviewed bilingual recovery utility at `/app/bin/recover-data-management`, and the bundled Compose definitions fix the recovery input path at `/app/security/recovery.key` on the existing security volume. Run it interactively with `docker compose exec service /app/bin/recover-data-management --lang zh` or `--lang en`, then restart only `service`. The utility hides and double-checks TTY input and never accepts the key as an argument or pipe. Do not replace this with a remotely downloaded recovery script or `curl ... | sh`. See [Operations](OPERATIONS.md#recover-a-docker-compose-deployment) for the complete recovery and verification sequence.

Risk email uses the shared system SMTP transport settings. Delivery prefers the Root email snapshot captured with the incident; when that snapshot is empty, every retry resolves the current email through `application_root_user_id`, allowing a later Root email update to release the pending alert. Sanitized incidents always enter an atomic local spool and then the append-only `starry_security` audit schema and durable leased outbox, even if no recipient is currently configured. SMTP failures are retried with exponential backoff and emitted as structured high-risk logs without weakening or rolling back the lock. Delivery is at-least-once: a crash after the SMTP server accepts a message can produce a duplicate carrying the same `X-Starry-Security-Event-Id`.

## Managed Model Catalog

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_MODEL_ICON_DIR` | `${user.home}/.starry-api/model-icons` | Owner-writable directory for administrator-uploaded model icons. |
| `STARRY_MODEL_MAX_ICON_BYTES` | `2097152` | Maximum PNG, JPEG, or WebP icon size in bytes. |

Model customizations are stored in `system_settings`; uploaded icon files are not stored in PostgreSQL and are not included in database exports. A direct host deployment should back up the icon directory separately. The bundled Compose files do not currently mount the default icon directory, so add an explicit persistent volume and pass `STARRY_MODEL_ICON_DIR` into the service before relying on custom icons across container replacement.

## Official Model Pricing

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_MODEL_PRICING_REMOTE_REFRESH_ENABLED` | `true` | Refresh the official USD price catalog from the configured remote source. |
| `STARRY_MODEL_PRICING_SYNC_CATALOG_IDS` | `false` | Append newly published first-class chat models from the price repo into the managed catalog. Disabled IDs are not re-enabled. |
| `STARRY_MODEL_PRICING_SOURCE_URL` | `Wei-Shaw/model-price-repo` raw JSON | LiteLLM-compatible model price catalog URL. |
| `STARRY_MODEL_PRICING_CHECKSUM_URL` | matching `.sha256` file | SHA-256 document used before replacing the active catalog. |
| `STARRY_MODEL_PRICING_REFRESH_INTERVAL` | `PT10M` | Delay between verified refresh attempts. |
| `STARRY_MODEL_PRICING_MAX_CATALOG_BYTES` | `4194304` | Maximum accepted catalog size. |
| `STARRY_MODEL_PRICING_DIR` | `~/.starry-api/model-pricing` | Last-good catalog cache directory. |

The jar contains a verified snapshot for offline startup. A network, parse, size, or checksum failure keeps the last verified catalog active. Operators can inspect sync status and trigger an immediate refresh from the collapsed data-source section in `系统设置 -> 模型管理 -> 价格倍率`. Pricing first checks the model's own exact/normalized official entry, then follows configured catalog targets with cycle detection. Existing routing compatibility targets do not replace a model's own verified price. Family/provider/global median guesses and the unknown-model 16.67 / MTok fallback are no longer used for token billing. An unpriced model is shown as `待定价` and billable requests fail with `MODEL_PRICE_UNAVAILABLE` before reserving funds or dispatching upstream. An explicit legacy absolute-price rule can still price such a model.

## Runtime and Upstream Adapter

OpenAI, Codex, Anthropic, and DeepSeek credentials are managed as upstream account records in PostgreSQL, not as deployment environment variables. A DeepSeek account with `provider=DeepSeek` defaults to `authMethod=api-key` and `baseUrl=https://api.deepseek.com/v1`, so enabling the official provider does not require a new server-side secret or URL variable.

### ChatGPT Browser Authorization

Administrators can add a Codex OAuth account from **账号管理 -> 新建账号 -> ChatGPT 授权**. Starry API uses
OpenAI's device authorization flow: the administrator opens `https://auth.openai.com/codex/device`
in a browser and enters the displayed one-time code while the server polls the matching OpenAI
endpoint. The deployment therefore needs outbound HTTPS access to `auth.openai.com` and
`chatgpt.com`; no public callback URL or additional OAuth environment variable is required.

Before Starry API requests the one-time code, the ChatGPT user must enable device code login under
the personal account's ChatGPT security settings. For a managed workspace, its administrator must
enable device code login in the workspace permissions. OpenAI rejects the browser flow when this
prerequisite is disabled; enable it first, then request a new code from Starry API.

The temporary login state is bounded, kept in memory, tied to the initiating administrator, and
expires after 15 minutes. Restarting the service invalidates unfinished logins but does not affect
accounts that were already saved. OAuth access, refresh, and ID tokens are sensitive database
credentials: protect PostgreSQL backups, restrict database access, and never place account rows or
tokens in logs and support tickets. Reauthorization refuses to replace an account when the stored
and newly authorized ChatGPT identities cannot be verified as the same identity.

Browser authorization creates only `provider=OpenAI`, `authMethod=codex` accounts for
`https://chatgpt.com/backend-api/codex`. It does not automate the ChatGPT website and does not create
an official OpenAI API-key account. Availability can vary by account or region. Use only accounts
the operator is permitted to manage and follow the applicable OpenAI terms and account policies.
This mode is intended for trusted private deployments. For public, multi-tenant, CI, or otherwise
untrusted programmatic use, configure an OpenAI Platform API-key account instead; see the
[official OpenAI authentication guidance](https://learn.chatgpt.com/docs/auth#mainContent).

| Variable | Default | Description |
| --- | --- | --- |
| `KIRO_ADMIN_TOKEN` | empty | Optional legacy static admin token. Prefer local admin login. |
| `KIRO_WEBUI_URL` | empty | WebUI login URL printed in startup logs. |
| `KIRO_ADMIN_SESSION_TTL` | `12h` | Local admin session lifetime. |
| `KIRO_ALLOW_ANONYMOUS_PROXY` | `false` | Allow proxy calls without API key. Keep `false` publicly. |
| `KIRO_REQUEST_TIMEOUT` | `90s` | Upstream request timeout. |
| `KIRO_MAX_RETRIES` | `2` | Upstream retry count for recoverable errors. |
| `KIRO_RATE_LIMIT_PER_MINUTE` | `120` | Fixed-window per API key rate limit. `0` disables rate limit. |
| `KIRO_ACCOUNT_SELECTION_STRATEGY` | `round_robin` | Legacy fallback used only when the PostgreSQL allocation ranker is absent (for example, direct-construction tests). Production selection uses lifecycle-aware ranking. |
| `KIRO_SESSION_AFFINITY_ENABLED` | `true` | Route same session hint to same upstream account when possible. |
| `KIRO_SESSION_AFFINITY_TTL` | `10m` | Session affinity TTL. |
| `KIRO_SESSION_AFFINITY_MAXIMUM_SIZE` | `100000` | Maximum in-memory session-affinity entries per application instance. |
| `KIRO_BASE_COOLDOWN` | `60s` | Base cooldown for recoverable account errors. |
| `KIRO_QUOTA_RESET` | `1h` | Default total-quota reset duration assigned after an upstream quota-exhaustion response when the account has no explicit reset time. |
| `KIRO_PROBABILISTIC_RETRY` | `0.1` | Chance to probe a cooling account early. |
| `KIRO_MAX_REQUEST_BODY_BYTES` | `10485760` | Max inbound request content length. |
| `KIRO_TOKEN_REFRESH_INTERVAL_MS` | `3600000` | Background refresh interval for eligible token-based accounts, including Kiro and expiring Codex credentials. |
| `KIRO_TOKEN_REFRESH_INITIAL_DELAY_MS` | `60000` | Delay before the first eligible-account token refresh. |
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

## System Settings Console

Administrator-managed settings are grouped under the WebUI `系统设置` menu:

| Category | WebUI path | Status |
| --- | --- | --- |
| `支付设置` | `系统设置 -> 支付设置` | Active payment business configuration. |
| `注册设置` | `系统设置 -> 注册设置` | Active registration, email policy, Turnstile, and invite-code configuration. |
| `SMTP 设置` | `系统设置 -> SMTP 设置` | Active shared mail transport configuration. |
| `模型管理` | `系统设置 -> 模型管理` | Active administrator model catalog, display order, official-price multipliers, and 模型 ID gallery style. The add-model form remains at `系统设置 -> 模型管理 -> 添加模型`. |
| `账号池设置` | `系统设置 -> 账号池设置` | Active routing-policy and automated-retest configuration. |
| `UI 自定义` | `系统设置 -> UI 自定义` | Site name, logo, colors, and console chrome. |

The old WebUI paths for payment settings and model creation remain redirects for bookmark compatibility. They are not separate settings locations.

## Registration and SMTP Settings

Registration is configured under `系统设置 -> 注册设置`. SMTP transport is configured separately under `系统设置 -> SMTP 设置`. Both pages use `GET/PATCH /admin/users/registration-settings`; PATCH fields are optional, so each page updates only the fields it owns and preserves the other category.

| Setting key | Purpose |
| --- | --- |
| `registration_enabled` | Enable public self-registration. |
| `email_verify_enabled` | Require email verification for public self-registration. |
| `invite_code_enabled` | Enable one-time invite-code registration. |
| `registration_email_suffix_whitelist` | Optional JSON email suffix whitelist. |
| `smtp_security` | Shared SMTP encryption mode: `auto`, `ssl`, `starttls`, or `none`. |

When invite-code registration is enabled, administrators can generate one-time codes on the registration settings page. Users pass `inviteCode` to `POST /auth/register`; a valid code allows direct registration even if public registration is disabled.

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

- Admin path: `系统设置 -> 支付设置`
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
| `MODEL_PRICE_BILLING_MULTIPLIER` | Multiplier applied to official USD token costs. Default `1.0`. The API compatibility field remains `apiBillingMultiplier`. |
| `RECHARGE_FEE_RATE` | Extra fee rate added to the payable amount. |
| `EASYPAY_API_BASE`, `EASYPAY_PID`, `EASYPAY_PKEY` | EasyPay gateway URL and merchant credentials. |
| `EASYPAY_PAYMENT_MODE` | `popup` for submit-page redirect, `qrcode` for `mapi.php`. |

Effective catalog prices are shown on the `模型 ID` page, with a total reference price by default (50% input and 50% output; the tooltip explains actual itemized billing). Administrators manage official-price multipliers from `系统设置 -> 模型管理 -> 价格倍率`. With no override rules, runtime billing itemizes input, output, cache-write, and cache-read token classes using the verified official catalog, then applies `MODEL_PRICE_BILLING_MULTIPLIER`. A matching rule replaces that global multiplier: `1.0` keeps the official price and `0.1` charges 10% of it, without multiplying by the global ratio again.

Rules are selected by ascending priority (0-10000). At equal priority the order is exact model, catalog family, catalog provider, then wildcard; more literal wildcard characters win over fewer. An exact requested-alias rule takes precedence over an exact target-model rule. Final ties use pattern and rule ID for deterministic selection. Known provider prefixes are normalized for exact matching; duplicate normalized exact patterns, provider/family scopes, or rule IDs are rejected. The draft price preview shows the winning rule, other matched rules, verified pricing model, and official/effective rates without saving.

The `厂商与系列` section directly edits a provider's default multiplier and each catalog family's override. Unset family multipliers inherit the provider default; unset providers inherit the global multiplier. These are persistent metadata scopes, so newly added models automatically inherit their catalog provider/family settings. Provider ownership is taken from the requested catalog entry, never from a cross-provider routing target. Exact model exceptions and explicitly higher-priority rules remain effective. Resetting a scope removes only that override.

Model patterns remain exact unless they contain `*`: `gpt` and `gpt5` do not match `gpt-5.5`. Use the family controls for `OpenAI / GPT-5`, or explicitly use `gpt-*` / `gpt-5*` for textual prefix matching. The model-rule editor warns when an entered exact ID is absent from the catalog.

The WebUI's `恢复默认` action saves an empty override list and therefore restores the configured global multiplier. Legacy fixed total and itemized prices remain effective and are displayed as charged, including catch-all rules. Editing other rules or legacy metadata preserves their raw per-token values; `改为倍率` requires explicit confirmation and the normal rule save. New rules use multipliers between 0.000001 and 1000. Failed source synchronization retains rule drafts. The old `/model-pricing` hash route redirects to `模型 ID`.

The old `API_BILLING_MULTIPLIER` database row is intentionally ignored so an existing per-token value such as `0.0000166666666667` cannot be misinterpreted as the new official-price multiplier after upgrade.

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

## Account Allocation Retention

Account-admission cleanup is independent from request analytics. It uses bounded short transactions and skips its scheduled run while data-management maintenance is locked.

| Variable | Default | Description |
| --- | --- | --- |
| `STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED` | `true` | Enable scheduled cleanup of terminal leases, allocation events, expired sessions, scoped cooldowns, and OpenAI response owners. |
| `STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON` | `0 35 3 * * *` | UTC cron for account-allocation cleanup. |
| `STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS` | `7` | Terminal lease retention; accepted range is 1-3650 days. |
| `STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS` | `90` | Allocation audit-event retention; accepted range is 30-3650 days. |
| `STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE` | `10000` | Maximum rows removed from each supported table per transaction; accepted range is 100-100000. |
| `STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES` | `20` | Maximum cleanup transactions per scheduled run; accepted range is 1-100. |

Expired session, scoped-cooldown, and response-owner rows do not use the lease/event retention windows. They become eligible as soon as their own expiry timestamp passes.

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

Production selection first isolates the provider/account subtype, applies the model-family primary/secondary circuit policy, and filters unavailable lifecycle states. Native Anthropic pools select from the lowest allocation priority and then minimize health and pressure risk. OpenAI-family pools score health, latency, concurrency, token pressure, priority, configured weight, and quota headroom, then choose among the best three with weighted randomness.

Session affinity is checked before normal ranking only when the bound account remains eligible. The safe routing defaults isolate Codex accounts, keep ordinary GPT traffic on official OpenAI API keys, bind explicit conversations only after a successful upstream result, and do not bind a bare client API key. These controls and distinct-account retry limits are managed in 系统设置 -> 账号池设置 -> 账号路由策略. `KIRO_ACCOUNT_SELECTION_STRATEGY` remains only as a compatibility fallback when the allocation ranker is not present.

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

# Architecture

Starry API is organized around three separate concerns:

- inbound platform protocols: OpenAI, Anthropic, Gemini
- provider-neutral platform core records and model catalog metadata
- upstream execution adapters: Kiro/AWS CodeWhisperer, official OpenAI/Codex, Anthropic API key, and official DeepSeek through the shared OpenAI HTTP transport

The core proxy layer should not contain platform-specific request conversion, response formatting, or model catalog data.

Detailed package ownership rules are maintained in [Package Guide](PACKAGE_GUIDE.md).

## Administrative Trust and Maintenance Boundary

The default/initial database administrator is the application-level root of trust. Flyway V6 stores its immutable `user_id` in `system_settings.application_root_user_id`; database triggers prevent that account or marker from being renamed, replaced, disabled, demoted, or deleted. Bootstrap assigns this identity only when the database has no users, while a missing Root in a populated database aborts startup. A stolen root password or active root session is equivalent to compromise of the whole service. Data-management security adds action-bound, short-lived second verification for export, backup, delete, and restore operations; it is defense against accidental or opportunistic use of an authenticated session, not a boundary that survives root compromise.

The security key is verified against a salted PBKDF2 value in an owner-only external state file. It is never persisted in PostgreSQL or copied into an incident. Each sanitized failure incident is appended to that same atomic state update as the consecutive-failure counter and lock flag; a durable dispatcher removes it only after the database audit/outbox enqueue succeeds. Five consecutive verification failures therefore set a permanent lock and invalidate data-management grants without waiting for PostgreSQL or SMTP. A highest-priority WebFlux filter then uses an in-memory fail-closed snapshot to return `503 SYSTEM_MAINTENANCE` for every service namespace while still serving static UI assets, public system status, and health probes. Readiness becomes `OUT_OF_SERVICE` while liveness remains `UP`. Locked recovery requires an explicitly configured writable owner-only key file at startup; recovery atomically stages the file, commits the unlocked state, and erases the staged key, with startup reconciliation for a crash between those steps.

Security incidents and their SMTP outbox live in the dedicated `starry_security` PostgreSQL schema, outside the `starry_api` backup/restore scope. Audit rows reject update/delete operations, and the runtime role receives only append/read access. Alerts reuse the shared system SMTP transport and prefer the application Root email captured with the event; if it was empty, each retry resolves the current email through the immutable Root marker, so adding an email later releases the pending alert. Delivery is retried with leases and exponential backoff. SMTP is necessarily at-least-once rather than exactly-once; a stable security event header lets receivers deduplicate the rare message repeated after a delivery/acknowledgement crash. Passwords, password hashes/salts, submitted security-key values, and session/access tokens are excluded from state incidents, audit rows, outbox rows, emails, and structured logs.

The verifier/lock file and short-lived grants are intentionally instance-local. Enabling data-management security therefore supports exactly one active service instance; do not deploy service replicas or rolling overlap. The whole configured security directory, including the adjacent initialization marker, must be on persistent owner-only storage. A missing/corrupt state file after initialization fails closed instead of allowing reinitialization; the post-initialization `require-initialized` deployment guard also detects total volume loss. HA requires moving the state machine and grants to an external strongly consistent security store and is not implemented by this boundary.

## Gradle Modules

```text
:service-api     Java API contracts and shared DTOs
:service         Spring Boot server, persistence, proxy orchestration, platform adapters
:webui           Vue/Vite management console
:plugin-api      Full-trust Java plugin SDK and lifecycle contract
:plugin-example  Buildable development plugin (not loaded by the server automatically)
```

## Plugin Trust Boundary

Third-party PF4J plugins are disabled by default. The immutable application Root must
verify its current password and a session-bound six-digit email challenge before
enabling developer mode. Plugin code receives full host-service access; this is an
installation consent boundary, not a security sandbox. No plugin marketplace or
signing requirement is implemented. The `plugin` package owns installation, lifecycle,
configuration/debug tools and auditing; `plugin-api` is the SDK. See [Plugins](PLUGINS.md)
for compatibility, logging coverage, recovery and resource-cleanup limitations.

`service-api` and `plugin-api` are independent library modules. The former retains
the original business contracts and Java package names; the latter exposes generic
host access, JSON/configuration, managed resources/tasks and streaming HTTP. The
`service` module implements the SDK. Plugin-local JSON type caches, bounded resource
registrations and lazy task workers are released with the context; no new database,
message broker or external scheduling service is required.

## Backend Package Layout

```text
service/src/main/java/tech/starryapi
├── admin              Local admin users, password hashing, sessions, startup guide
├── account            Upstream account persistence, import, test, pool selection
├── apikey             API key generation, hashing, auth, usage accounting
├── common             Error handling, admin auth helpers, shared utilities
├── config             Spring Boot, WebFlux, WebClient, OpenAPI, scheduling config
├── model              Managed catalog, pricing, model mappings, and icon storage
├── payment            Recharge orders, callbacks, balance ledger, and redemption codes
├── proxy              Public proxy controller, core facade, rate limit, model list
├── stats              Request log persistence and admin statistics
└── platform
    ├── core           Provider-neutral completion result, usage, and tool-use records
    ├── catalog        Unified model catalog and model metadata record
    ├── openai         OpenAI Chat/Responses request and response module
    ├── deepseek       DeepSeek model normalization and endpoint capability policy
    ├── anthropic      Anthropic Messages request, response, and native upstream module
    ├── gemini         Gemini GenerateContent request and response module
    ├── kiro           Kiro/AWS payload factory, upstream client, event-stream parser
    └── upstream       Shared upstream exception abstraction
```

Package rules:

- `proxy` may depend on `platform/*`.
- `platform/openai`, `platform/anthropic`, and `platform/gemini` own inbound/outbound protocol shapes; `platform/openai` also provides the shared OpenAI-compatible HTTP transport.
- `platform/openai`, `platform/anthropic`, and `platform/gemini` may depend on `platform/core` and `platform/catalog`, but must not import each other or `platform/kiro`.
- `platform/deepseek` owns the official model IDs, model normalization, and Chat/Responses capability policy. It reuses `platform/openai.OpenAiUpstreamClient` for HTTP transport instead of duplicating a client.
- `model/OfficialModelPricingService` owns the verified official USD price catalog. It starts from a bundled `Wei-Shaw/model-price-repo` snapshot and periodically replaces it only after the remote JSON matches the published SHA-256 checksum.
- `platform/catalog/ModelPriceRepoModelIds` derives the built-in OpenAI, Anthropic, Gemini, and DeepSeek chat model IDs from that bundled snapshot. An entry must provide input/output token prices and a request endpoint compatible with the current proxy before it is advertised by default; Realtime-only entries are excluded.
- `platform/core` contains provider-neutral records such as `PlatformCompletionResult`, `PlatformUsage`, and `PlatformToolUse`.
- `service-api/model` contains the public model catalog REST contract, model DTOs, and `ModelCatalogProvider`. `platform/catalog` is a service-side aggregation detail used only by the provider implementation.
- `platform/kiro` owns Kiro/AWS payloads, headers, upstream endpoint selection, model ID mapping, and AWS event-stream parsing.
- Admin/account/API key/stat modules must not depend on OpenAI/Anthropic/Gemini response factories.
- New client protocols should add a new `platform/<name>` package and register model metadata in `platform/catalog`.

## Frontend Layout

```text
webui/src
├── api          TypeScript API clients aligned with backend endpoints
├── components   Shared visual components
├── layouts      Authenticated shell
├── platforms    Frontend platform registry and platform-scoped setup modules
│   ├── openai
│   ├── deepseek
│   ├── anthropic
│   ├── gemini
│   ├── opencode
│   ├── registry.ts
│   ├── clientSetup.ts
│   └── types.ts
├── router       Route table and auth guard
├── stores       Pinia auth store
├── types        API DTO types
├── utils        HTTP and formatting helpers
└── views        Dashboard/admin pages
```

Frontend package rules:

- `views` render pages and hold page state.
- `platforms/<provider>/index.ts` owns provider display metadata and model ID inference.
- `platforms/<provider>/setup.ts` owns provider-specific environment variable blocks.
- `platforms/registry.ts` owns provider/family ordering and unified inference.
- `platforms/clientSetup.ts` aggregates OpenAI, Anthropic, Gemini, and OpenCode setup fragments. DeepSeek models use the same Starry API OpenAI-compatible client connection, so they do not require a separate client setup fragment.
- View components should not hard-code provider order or client environment variable blocks.

## Request Flow

```text
Client
  |
  | OpenAI (including DeepSeek models) / Anthropic / Gemini endpoint
  v
ProxyController
  |
  v
ProxyFacade
  |
  +-- platform/<client>.RequestAdapter
  |
  +-- ApiKeyService.authenticate()
  +-- RateLimiterService.check()
  +-- ModelMappingService.apply()
  +-- ModelPoolKeyResolver.resolve()
  +-- AccountPoolPolicyService.applyTierPolicy()
  +-- AccountPoolService.selectAccount()
  |
  +-- upstream route
  |   +-- platform/kiro.KiroPayloadFactory / KiroUpstreamClient / AwsEventStreamParser
  |   +-- platform/openai.OpenAiUpstreamClient (OpenAI, Codex, DeepSeek)
  |   +-- platform/anthropic.AnthropicUpstreamClient
  |       returns platform/core.PlatformCompletionResult
  |
  +-- platform/<client>.ResponseFactory
  |
  +-- ApiKeyService.recordUsage()
  +-- AccountPoolService.recordSuccess()/recordRequestFailure()
  +-- StatsService.log(circuit key, tier, upstream outcome)
  |
  v
Client-compatible response
```

## Model Catalog

`ProxyModelCatalog` aggregates provider catalogs in this order:

1. Anthropic
2. OpenAI
3. Gemini
4. DeepSeek

Each model record exposes:

- `id`: client-facing model ID
- `provider`: platform group
- `family`: platform-local family
- `owned_by`: catalog owner shown to OpenAI-compatible clients
- `targetModelId`: upstream model ID after alias mapping
- token and input capability metadata
- optional administrator icon, request format, and client endpoint metadata

OpenAI, Google/Gemini, Anthropic, and DeepSeek built-ins are derived from the checked-in `model-price-repo` snapshot, plus a small set of compatibility routing aliases. Official USD prices refresh from the same repository at startup and every 10 minutes (checksum-verified, last-good disk cache). Catalog aliases resolve to official prices by exact ID, prefix strip, hyphen/dot normalization, then family/provider median — never via routing `targetModelId`. The 16.67 / MTok safety fallback remains only for IDs that are not in the managed catalog. Administrators can optionally enable `syncCatalogIds` in 系统设置 → 模型管理 → 价格倍率 so newly published first-class chat models are appended; disabled IDs are not resurrected. Administrators can add providers and models, override built-ins, disable entries, upload PNG/JPEG/WebP icons, and persist a global model-ID display order and gallery style through `/admin/models`. Catalog customizations, order, and display settings live in `system_settings`; icon files live in `STARRY_MODEL_ICON_DIR` and must be backed up separately from PostgreSQL exports.

GPT/OpenAI IDs route to OpenAI API-key or Codex token accounts. `openai-compatible/<model>` selects a third-party OpenAI-compatible API-key account and is stripped before dispatch, but provider-scoped API keys can use it only when both the requested source ID and mapped prefixed target ID are enabled catalog entries under the key's provider. DeepSeek contributes `deepseek-chat`, `deepseek-reasoner`, `deepseek-v4-flash`, and `deepseek-v4-pro`; the built-in catalog reliably supports the bare and `starry/` forms. The lower-level route parser still recognizes `deepseek/`, but a provider-scoped key rejects that alias unless an administrator catalogs it under the same provider. Pricing canonicalization recognizes all three prefixes independently of route authorization. Claude/Kiro aliases continue to use `ModelCatalogProvider.resolveTargetModel`; `anthropic/` targets prefer native Anthropic API-key accounts. Shared default capabilities and routing targets are defined by the `service-api/model` contract, not a provider-specific catalog.

Every newly created API key is bound to one enabled catalog provider through `api_keys.model_provider`. Authorization validates both the client-requested model and the resolved mapping target before selecting an upstream account. The wildcard provider is retained only for migrated legacy keys.

## Persistent State

PostgreSQL 17 is the persistence baseline. The database and application schema are both named `starry_api`; Flyway installs a fresh baseline into that schema. Identifiers that users treat case-insensitively use functional `lower(...)` indexes, while tokens, hashes, and IDs remain case-sensitive.

Flyway creates these primary tables:

| Table | Purpose |
| --- | --- |
| `proxy_accounts` | Upstream account credentials, total/5h/7day quota windows, suspension, and metadata. |
| `api_keys` | Per-user API key owner, provider scope, full key copy value for new keys, hashes, status, limits, and aggregate usage. |
| `api_key_usage_daily` | Daily API key usage summary. |
| `api_usage_events` | Hash-partitioned idempotency/settlement events used to update key usage and user balances exactly once. |
| `model_mappings` | Model alias/replacement/load-balancing rules. |
| `request_logs` | Monthly range-partitioned request history with time, API key, account, circuit key/tier, resolved upstream model, upstream outcome, and BRIN/partial covering indexes. |
| `request_usage_hourly` / `request_usage_daily` | Long-range request/token/cost aggregates for analytics. |
| `aggregation_watermarks` | Incremental aggregation progress. |
| `performance_metrics` | Runtime CPU, memory, and network samples. |
| `admin_users` | Local admin users, password hashes, roles, first-login state. |
| `registration_email_tokens` | Hashed, expiring registration email verification tokens. |
| `system_settings` | Runtime settings, managed catalog/pricing snapshots, payment settings, and the immutable application Root marker. |
| `payment_orders` | Recharge order state and crediting status. |
| `invite_codes` / `redemption_codes` | Registration invitation and balance redemption codes. |
| `invite_rebate_records` | Idempotent invite rebate records. |
| `balance_ledger` | Auditable, idempotent balance mutations. |
| `flyway_schema_history` | Flyway migration history. |

The separate `starry_security` schema contains append-only `data_management_security_audit_events` and the leased `data_management_security_alert_outbox`. It is intentionally excluded from normal `starry_api` application exports and restores.

## Account Pool Behavior

An account is skipped when:

- `enabled=false`
- `suspendedAt` is set
- total quota, 5-hour quota, or 7-day quota is exhausted and its reset window is still active
- recoverable failures are still cooling down and probabilistic retry does not trigger

The tiered account-pool circuit breaker is experimental and disabled by default. When disabled, `AccountPoolPolicyService` returns the provider-eligible account list unchanged, preserves the original session-affinity key, and omits circuit dimensions from request logs; account selection ignores circuit tiers, and the WebUI shows ordinary account management rather than circuit content. The persistent `poolTier` and `poolWeight` fields remain available to the account API so operators can prepare primary and secondary capacity before enabling the feature.

When enabled, `ModelPoolKeyResolver` resolves every request after model mapping into a stable, bounded `provider:model-family` key. Catalog-backed models use their catalog provider and family, so model families under the same provider have independent circuits. Unknown IDs are collapsed into the relevant `provider:other` pool; arbitrary user model IDs therefore cannot create unbounded in-memory circuit cardinality. The same request-level key and token are retained across account retries.

Each model pool owns an independent, concurrency-safe sliding-window state machine:

| State | Routing behavior |
| --- | --- |
| `CLOSED` | Prefer primary accounts and open only after the primary tier reaches both the minimum sample size and failure-rate threshold. The existing provider-list fallback remains available when no primary account is configured. |
| `OPEN` | Route only through secondary accounts until the strict cooldown expires. |
| `HALF_OPEN` | Admit a bounded number of real client requests to the primary tier as probes; keep all non-probe traffic on secondary accounts. |

The cooldown and probe budget reuse the compatible settings fields `retestIntervalMinutes` and `retestMaxConcurrency`. Probe leases are bounded to 2-10 minutes and prevent abandoned requests from occupying slots forever: an expired probe reopens the circuit. Requests without a stable request token cannot become probes, and one request token can consume at most one probe slot across account retries. Once all allowed probes finish, reaching `recoveryThresholdPercent` closes the circuit and clears the primary window; an impossible or insufficient recovery rate reopens it. A post-recovery grace window prevents the same stale samples from immediately reopening the circuit. No synthetic probe request or all-account retest is generated by this state machine.

Real request outcomes update the local window immediately. Every 30 seconds, `AccountPoolRetestService` reconciles each instance's circuit metrics from the shared `request_logs` PostgreSQL window. Each `false -> true` activation creates a new UUID circuit epoch and activation timestamp. Requests capture that epoch before routing, and reconciliation accepts only rows from the current epoch at or after the activation boundary, so requests spanning a disable/re-enable boundary and historical rows from an earlier activation cannot mutate the new circuit state. The time predicate also has an exclusive upper bound so PostgreSQL prunes past and future partitions.

Rows enter this window only when `upstream_success` explicitly marks a successful call or a circuit-countable failure: retryable transport failures and provider-declared retryable `408`, `409`, and `5xx` outcomes count, while account-scoped and non-retryable failures do not. The partial covering index `idx_request_logs_circuit_window` leads on `circuit_epoch`, then `created_at DESC` and `circuit_key`, includes the tier and upstream outcome, and uses matching non-null predicates for an index-only scan. An evaluation captures a persistence-confirmation generation before querying PostgreSQL. It replaces only local outcomes whose log commits were confirmed before that generation; commits completed during the query stay in the local delta until the next baseline, and failed log writes stay in a local-only minute bucket until window expiry. Confirmed outcomes are aggregated by minute and confirmation generation, while only actual in-flight writes retain per-request IDs. This prevents gaps and bounds retained state by in-flight writes plus window buckets instead of request volume. Pools absent from the newest result are explicitly reconciled to an empty external baseline instead of retaining stale metrics. Circuit state is local to each service instance and converges through the shared window; it is not distributed through PostgreSQL notifications.

`request_logs.success` is the final proxy/settlement outcome. The separate nullable `upstream_success` records whether a circuit-countable upstream call succeeded, so a successful upstream response followed by a local billing failure is not misclassified as an upstream failure. `circuit_epoch`, `circuit_key`, `circuit_tier`, and `upstream_model` preserve the reconciliation dimensions and are retained when rows move out of the default partition.

Account-global lifecycle and model-pool failure domains are deliberately separate. With the experimental policy enabled, circuit-countable primary-tier failures update only the affected model-family circuit and do not increment the compatibility `errorCount`. The same failure on a secondary account also increments `errorCount`. Every dispatched attempt is additionally reconciled into the PostgreSQL account lifecycle described below. A `401` or `403` disables an account only when the response contains structured permanent-revocation evidence; an ambiguous Anthropic `403` is quarantined instead. A normal upstream `429` does not set `quotaExhaustedAt` or disable the account. Model- and endpoint-scoped `429` outcomes create or extend a cooldown for only that account and matching upstream model or endpoint. They are audited without mutating the account-global lifecycle or compatibility `errorCount`, so unrelated traffic can continue using the credential.

Pool settings, the activation epoch, and its start timestamp are persisted in `system_settings`. Each instance synchronously preloads the policy and indexed window before application readiness. Its PostgreSQL trigger then publishes changed keys on `starry_api_system_settings`; every instance keeps a dedicated `LISTEN` connection, invalidates the affected caches, and single-flights the policy reload plus immediate window reconciliation. Listener reconnects invalidate stale refresh generations but retain the last-known-good settings and circuit state until a complete replacement snapshot succeeds. Disabling resets local circuit state; re-enabling starts a fresh epoch and never restores metrics from an earlier activation.

Account selection is provider-isolated in both directions. DeepSeek model requests select only accounts whose provider is `DeepSeek` or whose official host is `api.deepseek.com`; ordinary OpenAI/Codex pools explicitly exclude those accounts. A depleted DeepSeek pool therefore returns no available account rather than falling back to another provider. Retries caused by a recoverable DeepSeek error remain inside the DeepSeek pool.

Only retryable transport failures and provider-declared retryable `408`, `409`, and `5xx` outcomes enter the model-family circuit window. Account retries remain inside the selected provider pool and never reuse the same account. The original account counts toward the limit:

| Failure | Cross-account behavior | Lifecycle result |
| --- | --- | --- |
| `400`, `404`, `422` | No retry | Request-scoped; account health is unchanged. |
| Unstructured `401` | No retry | Credential is quarantined; it is not permanently disabled without revocation evidence. |
| Revoked credential `401`/`403` | At most 2 distinct accounts | Permanently disables and suspends only the rejected credential. |
| `402` | At most 3 distinct accounts | Account-capacity cooldown, default 10 minutes. |
| Proxy, regional, or ambiguous `403` | At most 2 distinct accounts | Quarantine, default 10-15 minutes. |
| Transport, `408`, `409`, recoverable `502` | At most 2 distinct accounts | Degrade first; enter cooldown when the error EWMA reaches 0.50. |
| `429` | At most 3 distinct accounts | Account scope cools down; model/endpoint scope degrades. It does not mark quota exhausted. |
| `500`, `501`, `503`, `504` | At most 3 distinct accounts | Provider-pool cooldown, default 1 minute. |
| `529` | At most 2 distinct accounts | Account-capacity cooldown, default 10 minutes. |

### PostgreSQL Account Allocation

PostgreSQL 17 is the coordination authority for account admission; Redis is not required. `account_allocation_policies` stores operator limits, `account_allocation_states` stores lifecycle and EWMAs, `account_admission_states` stores hot counters and token buckets, and `account_scoped_cooldowns` stores model/endpoint-local blocks. `try_acquire_scoped_account_lease` serializes admission for one account and atomically checks matching scoped cooldowns, current lifecycle, effective concurrency, RPM, burst, in-flight token reservations, and optional Anthropic session capacity before creating a ten-minute lease. The proxy deliberately passes a null expected lifecycle version: the database transaction is authoritative, while a two-second ranking snapshot must not cause a false stale-version rejection. Dispatch marking occurs immediately before the upstream call. Success and upstream-failure reconciliation release reservations; cancellation and local pipeline failures abandon an open lease; failure before dispatch releases it without health impact. Expired leases are reclaimed and their counters persisted before any later admission decision for that account.

Default policies are conservative and provider-specific:

| Account type | Safe concurrency | Safe RPM | Burst | Max sessions | Session idle | In-flight tokens |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Anthropic API key | 2 | 20 | 2 | 4 | 600 seconds | 1,000,000 |
| OpenAI, Codex, DeepSeek, OpenAI-compatible | 4 | 60 | 4 | 0 (stateless) | 600 seconds | 1,000,000 |
| Kiro/other | 2 | 30 | 2 | 4 | 600 seconds | 1,000,000 |

Every policy starts at allocation priority `100`; lower values are preferred. New accounts start in `WARMING` at stage 1. Stages admit 10%, 25%, 50%, and 100% of configured concurrency/RPM/burst, advancing after successful calls until `HEALTHY`. Existing accounts migrated by V8 start `HEALTHY` at stage 4. The lifecycle states are:

| State | Meaning |
| --- | --- |
| `WARMING` | Staged capacity after create, restore, or cooldown expiry. |
| `HEALTHY` | Full configured capacity. |
| `DEGRADED` | Selectable, but ranked below healthier/lower-pressure capacity. |
| `COOLDOWN` | Temporarily unavailable until `cooldownUntil`, then returns to stage-1 warming. |
| `QUARANTINED` | Temporarily isolated for uncertain credential, proxy, regional, or unknown failures. |
| `DISABLED` | Administrator-disabled or permanently revoked credential. |

Official OpenAI API keys, Codex OAuth accounts, DeepSeek accounts, and generic OpenAI-compatible accounts have separate selection paths. The safe default keeps Codex isolated and routes ordinary GPT requests only to official OpenAI API keys; administrators can explicitly opt into Codex routing or mixed fallback. Anthropic selection first restricts candidates to the lowest allocation-priority value, then favors lower error, latency, concurrency, and token pressure. OpenAI-family pools score all eligible accounts, take the best three, and perform weighted random selection among them to avoid concentrating traffic on one credential. Session capacity is enforced only for a reliable explicit Anthropic conversation identifier; a bare API key never consumes session capacity. Successful conversation affinity is written with `putIfAbsent` under the same model-pool key used for reads, preventing concurrent requests from oscillating the binding. Admission-time and API-key-wide binding remain opt-in compatibility settings.

OpenAI Responses ownership is stored in `openai_response_owners` for six hours. A known chain prefers its producing account. A chain that can be rebuilt from the local response-state cache may move when necessary; an unknown or non-rebuildable chain is attempted on only one account and never fanned out across retries.

Terminal admission leases are retained for seven days and allocation events for 90 days by default. A bounded UTC maintenance job also removes expired session rows, scoped cooldowns, and OpenAI response owners. Current feedback latency is total upstream attempt latency, not true time-to-first-token (TTFT). Account-specific HTTP connection pools, enforced proxy-exit/TLS identity baselines, and Anthropic OAuth/Setup Token accounts are not implemented yet.

## Session Affinity

The service recognizes these headers:

- `x-claude-code-session-id`
- `x-opencode-session`
- `x-session-affinity`
- `x-conversation-id`

It also recognizes these request fields:

- `conversation_id`
- `conversationId`
- `thread_id`
- `session_id`

The affinity key is prefixed by API key ID and, when routed through the account-pool service, by the resolved model-pool key. Different API keys and different model families therefore do not share routing affinity.

## Current Upstream Adapters

`platform/kiro` sends a Kiro/AWS-compatible payload to the configured upstream endpoint with:

- `Authorization: Bearer <account.accessToken>`
- Kiro/AWS user-agent headers
- AWS SDK invocation headers
- endpoint-specific origin and target settings

`AwsEventStreamParser` extracts:

- assistant text
- tool use events
- input tokens
- output tokens
- cache read/write tokens
- metering credits

`platform/openai.OpenAiUpstreamClient` sends Bearer-authenticated JSON requests for official OpenAI and generic OpenAI-compatible API-key accounts. Codex token accounts use the ChatGPT backend Responses endpoint, refresh expiring OAuth credentials through a per-account single-flight refresh path, and normalize supported GPT-5 model IDs before dispatch.

DeepSeek reuses the OpenAI transport with the account's official base URL, while routing and policy remain owned by `platform/deepseek`:

- Chat Completions accepts all four cataloged DeepSeek IDs.
- Responses accepts only `deepseek-v4-flash`; Pro is rejected with `400` before an unsupported upstream call.
- DeepSeek requests do not enable the GPT fast/priority mode.
- Both response shapes are parsed into `PlatformCompletionResult` for the inbound response factory.

`platform/anthropic.AnthropicUpstreamClient` sends native `/messages` requests with `x-api-key` and `anthropic-version` headers, translates tools and content blocks, and parses JSON or Anthropic SSE responses into `PlatformCompletionResult`. Anthropic Messages and `anthropic/` routes prefer these native accounts, then fall back to compatible Kiro/AWS capacity.

Gemini currently has no direct Google upstream adapter. GenerateContent is an inbound compatibility protocol whose built-in aliases resolve to Kiro-compatible target models; direct Google API-key account tests and dispatch remain unimplemented.

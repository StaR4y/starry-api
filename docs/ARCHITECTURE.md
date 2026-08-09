# Architecture

Starry API is organized around three separate concerns:

- inbound platform protocols: OpenAI, Anthropic, Gemini
- provider-neutral platform core records and model catalog metadata
- upstream execution adapters: Kiro/AWS CodeWhisperer, official OpenAI/Codex, Anthropic API key, and official DeepSeek through the shared OpenAI HTTP transport

The core proxy layer should not contain platform-specific request conversion, response formatting, or model catalog data.

Detailed package ownership rules are maintained in [Package Guide](PACKAGE_GUIDE.md).

## Gradle Modules

```text
:api      Java API contracts and shared DTOs
:service  Spring Boot server, persistence, proxy orchestration, platform adapters
:webui    Vue/Vite management console
```

## Backend Package Layout

```text
service/src/main/java/xyz/star4y/api
├── admin              Local admin users, password hashing, sessions, startup guide
├── account            Upstream account persistence, import, test, pool selection
├── apikey             API key generation, hashing, auth, usage accounting
├── common             Error handling, admin auth helpers, shared utilities
├── config             Spring Boot, WebFlux, WebClient, OpenAPI, scheduling config
├── model              Model mapping persistence and matching logic
├── proxy              Public proxy controller, core facade, rate limit, model list
├── stats              Request log persistence and admin statistics
└── platform
    ├── core           Provider-neutral completion result, usage, and tool-use records
    ├── catalog        Unified model catalog and model metadata record
    ├── openai         OpenAI Chat/Responses request and response module
    ├── deepseek       DeepSeek model, default pricing, and endpoint capability policy
    ├── anthropic     Anthropic Messages request and response module
    ├── gemini        Gemini GenerateContent request and response module
    ├── kiro          Kiro/AWS payload factory, upstream client, event-stream parser
    └── upstream      Shared upstream exception abstraction
```

Package rules:

- `proxy` may depend on `platform/*`.
- `platform/openai`, `platform/anthropic`, and `platform/gemini` own inbound/outbound protocol shapes; `platform/openai` also provides the shared OpenAI-compatible HTTP transport.
- `platform/openai`, `platform/anthropic`, and `platform/gemini` may depend on `platform/core` and `platform/catalog`, but must not import each other or `platform/kiro`.
- `platform/deepseek` owns the official model IDs, built-in per-token prices, model normalization, and Chat/Responses capability policy. It reuses `platform/openai.OpenAiUpstreamClient` for HTTP transport instead of duplicating a client.
- `platform/core` contains provider-neutral records such as `PlatformCompletionResult`, `PlatformUsage`, and `PlatformToolUse`.
- `api/model` contains the public model catalog REST contract, model DTOs, and `ModelCatalogProvider`. `platform/catalog` is a service-side aggregation detail used only by the provider implementation.
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
  +-- AccountPoolService.recordSuccess()/recordError()
  +-- StatsService.log()
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

OpenAI, Google/Gemini, and Anthropic catalog entries include both legacy short aliases and OpenRouter-sourced complete IDs with provider prefixes. GPT/OpenAI IDs are routed to OpenAI API key or Codex token accounts; `openai-compatible/<model>` is a routing prefix for third-party OpenAI-compatible API-key accounts and is stripped before the upstream request is sent. DeepSeek contributes the first-class `deepseek-v4-flash` and `deepseek-v4-pro` entries. Official routing also recognizes `starry/` and `deepseek/` forms, while pricing canonicalization additionally recognizes `openai-compatible/` so equivalent IDs share pricing rules and defaults without changing that prefix's third-party routing meaning. Claude/Kiro-compatible IDs continue to use `ModelCatalogProvider.resolveTargetModel` for Kiro upstream model aliases. Shared default capabilities and current default routing targets are defined by the `api/model` contract, not in a provider-specific catalog.

## Persistent State

PostgreSQL 17 is the persistence baseline. The database and application schema are both named `starry_api`; Flyway installs a fresh baseline into that schema. Identifiers that users treat case-insensitively use functional `lower(...)` indexes, while tokens, hashes, and IDs remain case-sensitive.

Flyway creates these primary tables:

| Table | Purpose |
| --- | --- |
| `proxy_accounts` | Upstream account credentials, total/5h/7day quota windows, suspension, and metadata. |
| `api_keys` | Per-user API key owner, full key copy value for new keys, hashes, status, limits, and aggregate usage. |
| `api_key_usage_daily` | Daily API key usage summary. |
| `model_mappings` | Model alias/replacement/load-balancing rules. |
| `request_logs` | Monthly range-partitioned request history with time, API key, account, and BRIN indexes. |
| `request_usage_hourly` / `request_usage_daily` | Long-range request/token/cost aggregates for analytics. |
| `aggregation_watermarks` | Incremental aggregation progress. |
| `performance_metrics` | Runtime CPU, memory, and network samples. |
| `admin_users` | Local admin users, password hashes, roles, first-login state. |
| `system_settings` | Runtime registration, payment, and account-pool settings. |
| `payment_orders` | Recharge order state and crediting status. |
| `invite_codes` / `redemption_codes` | Registration invitation and balance redemption codes. |
| `invite_rebate_records` | Idempotent invite rebate records. |
| `balance_ledger` | Auditable, idempotent balance mutations. |
| `flyway_schema_history` | Flyway migration history. |

## Account Pool Behavior

An account is skipped when:

- `enabled=false`
- `suspendedAt` is set
- total quota, 5-hour quota, or 7-day quota is exhausted and its reset window is still active
- recoverable failures are still cooling down and probabilistic retry does not trigger

After two consecutive upstream request failures, the service schedules a background account test. A failed automatic test updates status through the same path as manual account testing and disables the account.

Account selection is provider-isolated in both directions. DeepSeek model requests select only accounts whose provider is `DeepSeek` or whose official host is `api.deepseek.com`; ordinary OpenAI/Codex pools explicitly exclude those accounts. A depleted DeepSeek pool therefore returns no available account rather than falling back to another provider. Retries caused by a recoverable DeepSeek error remain inside the DeepSeek pool.

Recoverable upstream status codes:

- `401`
- `402`
- `403`
- `429`
- `5xx`

For most providers, `429` participates in the existing quota-exhaustion semantics. For DeepSeek, `429` is treated as transient concurrency/rate limiting: it increments `errorCount` and participates in same-provider retry and exponential cooldown, but does not set `quotaExhaustedAt`, disable, or suspend the account. Account tests preserve the same exception.

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

The affinity key is prefixed by API key ID, so different API keys do not share routing affinity.

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

`platform/openai.OpenAiUpstreamClient` sends Bearer-authenticated JSON requests for OpenAI-compatible API-key accounts. DeepSeek reuses this transport with the account's official base URL, while routing and policy remain owned by `platform/deepseek`:

- Chat Completions accepts both `deepseek-v4-flash` and `deepseek-v4-pro`.
- Responses accepts only `deepseek-v4-flash`; Pro is rejected with `400` before an unsupported upstream call.
- DeepSeek requests do not enable the GPT fast/priority mode.
- Both response shapes are parsed into `PlatformCompletionResult` for the inbound response factory.

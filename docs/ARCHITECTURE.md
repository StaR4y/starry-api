# Architecture

Starry API is organized around three separate concerns:

- inbound platform protocols: OpenAI, Anthropic, Gemini
- provider-neutral platform core records and model catalog metadata
- upstream execution adapters: currently Kiro/AWS CodeWhisperer

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
    ├── anthropic     Anthropic Messages request and response module
    ├── gemini        Gemini GenerateContent request and response module
    ├── kiro          Kiro/AWS payload factory, upstream client, event-stream parser
    └── upstream      Shared upstream exception abstraction
```

Package rules:

- `proxy` may depend on `platform/*`.
- `platform/openai`, `platform/anthropic`, and `platform/gemini` convert only inbound/outbound protocol shapes.
- `platform/openai`, `platform/anthropic`, and `platform/gemini` may depend on `platform/core` and `platform/catalog`, but must not import each other or `platform/kiro`.
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
- `platforms/clientSetup.ts` aggregates OpenAI, Anthropic, Gemini, and OpenCode setup fragments.
- View components should not hard-code provider order or client environment variable blocks.

## Request Flow

```text
Client
  |
  | OpenAI / Anthropic / Gemini endpoint
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
  +-- platform/kiro.KiroPayloadFactory
  +-- platform/kiro.KiroUpstreamClient
  +-- platform/kiro.AwsEventStreamParser
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

Each model record exposes:

- `id`: client-facing model ID
- `provider`: platform group
- `family`: platform-local family
- `owned_by`: catalog owner shown to OpenAI-compatible clients
- `targetModelId`: upstream model ID after alias mapping
- token and input capability metadata

OpenAI, Google/Gemini, and Anthropic catalog entries include both legacy short aliases and OpenRouter-sourced complete IDs with provider prefixes. GPT/OpenAI IDs are routed to OpenAI API key or Codex token accounts; Claude/Kiro-compatible IDs continue to use `ModelCatalogProvider.resolveTargetModel` for Kiro upstream model aliases. Shared default capabilities and current default routing targets are defined by the `api/model` contract, not in a provider-specific catalog.

## Persistent State

Flyway creates these tables:

| Table | Purpose |
| --- | --- |
| `proxy_accounts` | Upstream account credentials, quota, suspension, and metadata. |
| `api_keys` | Per-user API key owner, full key copy value for new keys, hashes, status, limits, and aggregate usage. |
| `api_key_usage_daily` | Daily API key usage summary. |
| `model_mappings` | Model alias/replacement/load-balancing rules. |
| `request_logs` | Request history, status, token use, latency, errors. |
| `admin_users` | Local admin users, password hashes, roles, first-login state. |
| `flyway_schema_history` | Flyway migration history. |

## Account Pool Behavior

An account is skipped when:

- `enabled=false`
- `suspendedAt` is set
- quota is exhausted and `quotaResetAt` is still in the future
- recoverable failures are still cooling down and probabilistic retry does not trigger

Recoverable upstream status codes:

- `402`
- `403`
- `429`

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

## Current Upstream Adapter

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

# Package Guide

Starry API has moved from a Kiro-centered proxy into a multi-platform routing service. Package names describe the platform boundary they own, not the first upstream adapter that happened to execute the call.

## Backend Rules

Backend source is rooted at:

```text
service/src/main/java/tech/starryapi
```

Use these package boundaries:

| Package | Owns | May depend on |
| --- | --- | --- |
| `proxy` | Request orchestration, auth, rate limit, model mapping, account selection, accounting, logs. | `platform/*`, account/API key/model/stat services. |
| `service-api/model` | Public model catalog API contract, model DTOs, and the backend-facing `ModelCatalogProvider` abstraction. | JDK, Jackson annotations, Spring Web annotations. |
| `platform/core` | Provider-neutral completion result, usage, and tool-use records. | Jackson and JDK only. |
| `platform/catalog` | Service implementation detail that aggregates provider catalog classes for `ModelCatalogProvider`. | Provider catalog classes. |
| `platform/openai` | OpenAI-compatible request adapters, response factories, model aliases, OpenAI/Codex/DeepSeek HTTP transport, and OpenAI account tests. | `platform/core`, `platform/catalog`, `platform/upstream`, account types and token refresh. |
| `platform/anthropic` | Anthropic-compatible request adapters, response factories, model aliases, and native Anthropic API-key transport. | `platform/core`, `platform/catalog`, `platform/upstream`, account types. |
| `platform/gemini` | Gemini-compatible request adapters, response factories, Gemini model aliases. | `platform/core`, `platform/catalog`. |
| `platform/kiro` | Kiro/AWS payload creation, endpoint dispatch, token-specific headers, model ID conversion, AWS event-stream parsing. | `platform/core`, `platform/catalog`, `platform/upstream`, account/config services. |
| `platform/upstream` | Shared upstream exception contract; a common execution-adapter interface has not been introduced yet. | JDK only where possible. |

Hard rules:

- OpenAI, Anthropic, and Gemini modules must not import each other.
- Client protocol modules must not import `platform/kiro`; they format `PlatformCompletionResult`.
- Kiro-specific names stay inside `platform/kiro`, Kiro account import/refresh code, deployment compatibility variables, or documentation that explicitly discusses migration.
- Shared model capabilities, default target IDs, and model-list DTOs belong in `service-api/model`; service code must depend on `ModelCatalogProvider` instead of reading provider catalogs directly.
- New inbound protocol support gets a new `platform/<provider>` package with request adapter, response factory, model catalog, and package-aligned tests.
- New upstream execution support gets a new `platform/<upstream>` package and should return `PlatformCompletionResult` until a richer upstream adapter interface is introduced.
- Account management may store credentials for multiple providers in `account`, but request routing must select only accounts compatible with the active upstream adapter.

## Frontend Rules

Frontend source is rooted at:

```text
webui/src
```

Use these platform boundaries:

| Package | Owns |
| --- | --- |
| `platforms/types.ts` | Shared platform and setup-script types. |
| `platforms/registry.ts` | Unified provider order, model provider/family inference, CSS class lookup. |
| `platforms/<provider>/index.ts` | Provider display metadata and model ID inference. |
| `platforms/<provider>/setup.ts` | Provider-specific client environment variables or setup fragments. |
| `platforms/clientSetup.ts` | Aggregates setup fragments into macOS/Linux/Windows scripts. |
| `platforms/models.ts` | Compatibility re-export for page imports. |

Frontend hard rules:

- `views` may import from `@/platforms/models` or `@/platforms/clientSetup`, but should not hard-code provider order, family order, or client environment variable blocks.
- Adding a provider means adding `platforms/<provider>/index.ts`, optional `platforms/<provider>/setup.ts`, and registering it in `platforms/registry.ts` or `platforms/clientSetup.ts`.
- Page components should render metadata from the registry; they should not decide whether a model is OpenAI, Anthropic, Gemini, or another provider.

## Adding A New Client Platform

1. Add `service/.../platform/<provider>/<Provider>RequestAdapter.java`.
2. Add `service/.../platform/<provider>/<Provider>ResponseFactory.java`.
3. Add `service/.../platform/<provider>/<Provider>ModelCatalog.java`.
4. Register the catalog in `platform/catalog/ProxyModelCatalog`.
5. Add facade/controller routing only for the public endpoint shape.
6. Add tests under `service/src/test/java/.../platform/<provider>`.
7. Add `webui/src/platforms/<provider>/index.ts` and register it in `registry.ts`.
8. Add setup fragments only if clients need provider-specific environment variables.

## Adding A New Upstream Adapter

1. Add `service/.../platform/<upstream>`.
2. Convert the provider-neutral chat shape into upstream payloads inside that package.
3. Return `PlatformCompletionResult` from the upstream call path.
4. Keep credentials, headers, endpoint selection, and event parsing inside the upstream package.
5. Add account metadata and configuration fields only when the adapter needs them.
6. Add tests under `service/src/test/java/.../platform/<upstream>`.

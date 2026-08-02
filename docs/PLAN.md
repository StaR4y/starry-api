# Project Plan

## Current Status

Starry API has entered the platform modularization phase.

Completed in this phase:

- Renamed the Spring Boot entrypoint to `StarryApiApplication`.
- Split backend protocol code into platform packages:
  - `platform/core`
  - `platform/openai`
  - `platform/anthropic`
  - `platform/gemini`
  - `platform/kiro`
  - `platform/catalog`
  - `platform/upstream`
- Introduced provider-neutral `PlatformCompletionResult`, `PlatformUsage`, and `PlatformToolUse`.
- Moved shared model capabilities and default routing targets into the `api/model` model catalog contract.
- Kept `proxy` focused on orchestration: auth, rate limit, model mapping, account selection, upstream invocation, accounting, logs.
- Added Gemini model aliases and basic GenerateContent compatibility.
- Split Anthropic response formatting out of the OpenAI response factory.
- Split frontend `src/platforms` into provider-scoped modules:
  - `openai/`, `anthropic/`, `gemini/` for model metadata and client setup fragments
  - `opencode/` for OpenCode setup fragments
  - `registry.ts` for provider/family inference and display order
  - `clientSetup.ts` for OS-specific setup script aggregation
- Updated visible WebUI brand from the legacy Kiro-only name to Starry API.
- Rewrote README, architecture, package guide, API, and configuration docs for the platform model.
- Established PostgreSQL 17 as the persistence baseline with database/schema `starry_api`, R2DBC pooling, a fresh Flyway baseline, partitioned request logs, rollups, and custom-format backups.

Still intentionally preserved:

- Kiro/AWS is still the only real upstream adapter.
- `KIRO_*` runtime variables remain for the active Kiro/AWS upstream adapter.
- Kiro Account Manager import format is still supported directly.

## Design Rules

- New inbound client protocols go under `service/.../platform/<provider>`.
- New upstream execution providers go under `service/.../platform/<upstream>` and expose a narrow call/result abstraction.
- Protocol response factories consume `platform/core.PlatformCompletionResult`; they must not import Kiro result records.
- Protocol model catalogs may use `api/model/ModelCatalogProvider` constants; they must not import another protocol's catalog.
- `proxy/ProxyFacade` remains the routing coordinator and should not gain large protocol conversion helpers.
- Model IDs are registered in `platform/catalog` and should include provider/family/target metadata.
- Frontend provider ordering and client setup scripts belong in `webui/src/platforms`, with provider-specific pieces under `webui/src/platforms/<provider>`, not page components.
- Tests should sit in package-aligned platform test packages when they verify package-private adapter behavior.

## Known Gaps

- Runtime env names still use `KIRO_*`; a later migration should add `STARRY_*` aliases without breaking old deployments.
- Gemini compatibility is basic GenerateContent text/function-call conversion, not full Google SDK parity.
- OpenAI and Anthropic tool/image/document handling is partial.
- `stream=true` emits SSE after upstream completion, not true upstream chunk passthrough.
- Dynamic upstream model discovery is still Kiro-specific.
- Per-account outbound proxy URL is stored but not wired into WebClient dispatch.
- Rate limiting is in-memory and not distributed across multiple nodes.

## Milestone 1: Platform Protocol Parity

Goal: make OpenAI, Anthropic, and Gemini inbound modules independently testable.

Tasks:

1. Add unit tests for OpenAI Responses request conversion.
2. Add unit tests for Anthropic tool-use/tool-result request conversion.
3. Expand Gemini support for `streamGenerateContent`.
4. Add image/document/file conversion coverage per platform.
5. Add prompt-cache and thinking/reasoning option normalization per platform.
6. Add a compatibility matrix in docs for each request/response field.

## Milestone 2: Upstream Adapter Abstraction

Goal: make Kiro/AWS one adapter instead of the implicit execution core.

Tasks:

1. Introduce a narrow upstream adapter interface over the existing `PlatformCompletionResult` return type.
2. Wrap `KiroPayloadFactory`, `KiroUpstreamClient`, and `AwsEventStreamParser` behind that interface.
3. Move Kiro-specific account credential fields behind adapter-aware account metadata where practical.
4. Add adapter selection config.
5. Add adapter-specific health/test logic.
6. Add `STARRY_*` runtime config aliases while keeping `KIRO_*` backwards compatible.

## Milestone 3: Streaming and Latency

Goal: reduce perceived latency for streaming clients.

Tasks:

1. Parse AWS event-stream chunks incrementally.
2. Emit OpenAI SSE chunks as upstream chunks arrive.
3. Emit Anthropic streaming events as upstream chunks arrive.
4. Add Gemini stream response support.
5. Track first-token latency and total latency separately.
6. Add cancellation handling for disconnected clients.

## Milestone 4: Account Reliability

Goal: stabilize multi-account operation under real traffic.

Tasks:

1. Prevent concurrent refresh for the same upstream account.
2. Add manual token validation endpoint.
3. Add account-to-API-key binding.
4. Add group-based account selection.
5. Add account-bound outbound proxy dispatch.
6. Add quota reset reconciliation.

## Milestone 5: Operations

Goal: make deployment and upgrades predictable.

Tasks:

1. Keep PostgreSQL 17 Testcontainers integration tests mandatory and validate PostgreSQL 18 compatibility in CI.
2. Add production profile defaults.
3. Add Prometheus metrics for platform, upstream, account state, latency, and errors.
4. Add structured request IDs to logs.
5. Maintain PostgreSQL backup/restore, Flyway recovery, partition retention, and capacity-planning runbooks.
6. Add a deployment upgrade guide for the Kiro-to-Starry naming transition.

## Verification Checklist

For platform/package changes:

```bash
./gradlew test
npm --prefix webui run build
```

For packaged deployment changes:

```bash
./gradlew :service:bootJar
```

For local runtime changes:

```bash
# Initialize the four PostgreSQL roles as shown in README.md, then:
./scripts/start-proxy.sh
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/v1/models
```

For proxy behavior changes:

1. Create an API key.
2. Add one valid upstream account.
3. Call OpenAI Chat Completions.
4. Call Anthropic Messages.
5. Call Gemini GenerateContent.
6. Check `/admin/logs`.
7. Check account/API key counters.

# Project Plan

Last reviewed against the worktree on 2026-10-06.

## Current Status

Platform modularization is complete. The project is now in a reliability, security, billing, and operations phase.

The current baseline includes:

- English compatibility (2026-10-06): persistent Chinese/English selection across the console, reactive UI labels and component locales, unchanged client configuration payloads, bilingual documentation editing/default tutorials, and responsive English layouts. See [internationalization](INTERNATIONALIZATION.md).

- User documentation (2026-10-06): administrator Markdown editor/templates, isolated drafts and publications, six beginner tutorials prioritizing CC Switch, searchable reader pages, signed image/video/file attachments, code highlighting/copy and configurable Apifox links. See [usage and operations](USER_DOCUMENTATION.md).

- Support tickets (2026-09-20): user submissions/private attachments, administrator conversations/status workflow, durable SMTP reminders or content emails, retries, concurrency checks and responsive console pages. See [implementation and verification](SUPPORT_TICKETS.md).

- Provider-neutral completion, usage, reasoning, and tool records under `platform/core`.
- Independent OpenAI, Anthropic, Gemini, DeepSeek, Kiro, catalog, and upstream packages.
- OpenAI API-key, Codex token, native Anthropic API-key, official DeepSeek, generic OpenAI-compatible, and Kiro/AWS execution paths.
- OpenAI Chat Completions and Responses, Anthropic Messages, and Gemini GenerateContent inbound compatibility APIs.
- Administrator-managed model providers, families, aliases, icons, pricing rules, and model-provider-scoped API keys.
- Pricing consistency repair (2026-09-20): shared display/quote/charge resolution, normalized prefix and catalog-alias matching, deterministic exact/wildcard precedence, preserved legacy fixed prices, stale-cache protection, draft preview, and responsive pricing controls. Missing verified prices now explicitly block billable requests. See [verification](MODEL_PRICING_FIXES.md).
- Provider/family pricing (2026-09-20): direct provider-default and family-override controls, automatic inheritance for future catalog models, scoped matching by requested-model ownership, and warnings for unmatched exact IDs.
- PostgreSQL 17 persistence with Flyway, partitioned request/usage data, aggregate statistics, backup/restore, payments, balances, invite codes, and redemption codes.
- Coding Plan rebuild (2026-09-12): explicit API-key billing source, immutable product versions, queued renewals, PostgreSQL billing reservations, and audited reconciliation. See [implementation and verification](CODING_PLAN_REBUILD.md).
- Plugin developer mode (2026-09-12): default-disabled full-trust PF4J plugins, root password plus email verification, JAR lifecycle/config/debug tooling and audit logs. No marketplace or sandbox. See [plugin SDK and operations](PLUGINS.md).
- Plugin SDK 1.1 (2026-09-13): renamed `api` to `service-api` without changing Java packages or HTTP routes; added typed JSON/configuration, named service discovery, managed resources/tasks and streaming HTTP with cancellation/session cleanup. SDK 1.0 contracts remain compatible on the updated host.
- Plugin hot reload (2026-09-13): normal lifecycle operations no longer require a restart; managed cleanup is verified before unloading, with root-only diagnostics and sticky per-plugin blocking on uncertainty. Queued timeouts never execute later and cancelled starts cannot publish late success. Full-trust code is still cooperative, not sandboxed or forcibly terminable.
- Per-model-family experimental circuit breakers, account cooldown/failover, session affinity, disabled-account tests, and single-flight account token refresh.
- Immutable application Root identity plus fail-closed data-management security, append-only security auditing, durable SMTP alerts, and an offline recovery workflow.
- Vue management pages for accounts, keys, models, pricing, mappings, users, payments, logs, and data transfer, plus a unified system settings center for payment, registration, SMTP, model creation, and account-pool policy.

Compatibility intentionally retained:

- Legacy `KIRO_*` runtime variables are still read when the matching `STARRY_*` variable is unset (2026-10-21 rename).
- Kiro Account Manager import documents remain accepted.
- Legacy API keys with `model_provider='*'` remain usable, but new keys must select an enabled catalog provider.

## Design Rules

- New inbound client protocols belong under `service/src/main/java/tech/starryapi/platform/<provider>`.
- New upstream execution providers belong in a platform package and return `PlatformCompletionResult` rather than provider-specific records to the proxy layer.
- Protocol response factories consume `platform/core` records and must not import Kiro result records.
- Protocol modules may depend on `platform/core` and `platform/catalog`, but OpenAI, Anthropic, and Gemini modules must not import each other.
- `proxy/ProxyFacade` remains the routing coordinator and should not accumulate protocol conversion helpers.
- Public and mapped model IDs must be enabled in the managed catalog under the API key's provider before routing.
- Frontend provider ordering and client setup generation belong in `webui/src/platforms`, not view components.
- Tests should use package-aligned test packages when they verify package-private adapter behavior.

## Current Gaps

- Coding Plan checkout uses wallet purchase, grants, and redemption codes; direct payment-gateway plan fulfillment remains pending.

- `stream=true` formats SSE only after the upstream call completes; true chunk passthrough and disconnect cancellation are not implemented.
- The Kiro, OpenAI, and Anthropic clients do not yet implement one unified upstream adapter interface.
- Gemini is an inbound compatibility layer routed through Kiro aliases; direct Google API-key dispatch, account testing, and `streamGenerateContent` are missing.
- OpenAI/Anthropic image, document, file, and prompt-cache parity remains partial.
- Account egress supports fixed subscription nodes, legacy HTTP/SOCKS5 URLs and optional TLS impersonation; pre-account ChatGPT browser authorization still uses the service's default connection. See [egress deployment and limits](ACCOUNT_EGRESS.md).
- Rate limiting and data-management unlock state are instance-local. Rate limiting is not distributed, and data-management security supports one active service instance only.
- `/actuator/prometheus` is exposed, but domain metrics for providers, upstream latency/errors, account state, and circuit transitions are incomplete.

## Workstream 1: Protocol Compatibility

Completed:

- OpenAI Responses request conversion and response/event tests, including functions, custom tools, reasoning, multimodal input, and previous-response IDs.
- Anthropic tool-use request conversion, tool-choice conversion, response formatting, native upstream requests, and streamed tool-use parsing tests.
- Basic Gemini text, system instruction, generation config, function declaration/call/response conversion.

Remaining:

1. Add explicit Anthropic `tool_result` request regression coverage.
2. Add Gemini `streamGenerateContent` support and tests.
3. Expand per-protocol image/document/file conversion coverage.
4. Document and test a field-by-field compatibility matrix for all three inbound protocols.

## Workstream 2: Upstream Abstraction

Completed:

- Kiro/AWS event-stream transport is isolated under `platform/kiro`.
- OpenAI API-key, generic OpenAI-compatible, Codex, and DeepSeek execution use the OpenAI transport with route-specific policy.
- Native Anthropic API-key execution and account testing are implemented.
- Provider failures are normalized through the shared upstream error model and failure classifier.

Remaining:

1. Define a narrow common upstream adapter interface around `PlatformCompletionResult`.
2. Move adapter selection and account test behavior behind that interface.
3. Separate provider-specific credential metadata further where it reduces branching in account services.
4. Implement a direct Google/Gemini adapter only when account testing, dispatch, and streaming can ship together.

## Workstream 3: Streaming and Latency

1. Parse upstream event streams incrementally instead of buffering the completed result.
2. Emit OpenAI and Anthropic SSE events as chunks arrive.
3. Add first-token latency separately from total request latency.
4. Propagate client disconnect cancellation to the active upstream request.
5. Verify accounting and circuit outcomes for cancelled or partially streamed calls.

## Workstream 4: Account Reliability

Completed:

- Per-account token refresh is single-flight and handles rotated Codex credentials.
- Manual, scheduled-disabled, and failure-triggered account tests are available.
- Total, 5-hour, and 7-day quota windows are tracked; `STARRY_QUOTA_RESET` supplies the fallback total-quota reset duration.
- Official DeepSeek accounts and retries are isolated from OpenAI/Codex pools.
- Experimental provider/model-family circuits reconcile from PostgreSQL request logs and support primary/secondary tiers.
- API keys are restricted to one managed model provider, and both sides of model mappings are authorized.

Remaining:

1. Extend egress selection to pre-account ChatGPT authorization if deployment requirements call for it; saved account calls, tests, and refreshes already honor their route.
2. Decide whether exact account/group-to-key binding is still needed beyond provider scoping.
3. Add distributed rate limiting before supporting horizontally scaled public traffic.
4. Graduate the model-family circuit policy only after production observation, alerting, and rollback exercises.

## Workstream 5: Security and Operations

Completed:

- PostgreSQL 17 integration tests are mandatory in the main CI job; a separate PostgreSQL 18 compatibility job runs the PostgreSQL test suite.
- CI startup/portability fixes (2026-09-22): R2DBC clients wait for Flyway even with lazy initialization, container-backed test contexts are closed per class, GNU/BSD recovery-tool behavior is aligned, and V20 corrects function EXECUTE privileges. See [failure analysis and verification](CI_FIX_REPORT_2026-09-22.md).
- PostgreSQL backup/restore, rollback validation, retention, aggregation, and capacity guidance are documented.
- Application Root identity, data-management hard lock, maintenance health behavior, append-only audit/outbox, and interactive recovery tooling are implemented and tested.
- Prometheus registry and `/actuator/prometheus` exposure are configured.

Remaining:

1. Add provider, upstream, account, billing, and circuit-specific Micrometer metrics and alerts.
2. Propagate request IDs consistently through response headers and structured application logs; IDs are currently generated and persisted for proxy statistics.
3. Add explicit production-profile defaults.
4. Design an external strongly consistent data-management state store before allowing service replicas when that feature is enabled.
5. Add a separate backup/restore procedure for uploaded model icons, which are filesystem state rather than PostgreSQL data.

## Verification Checklist

For platform, persistence, or documentation contract changes:

```bash
./gradlew test
npm --prefix webui run build
```

For packaged deployment changes:

```bash
./gradlew :service:bootJar
./scripts/test-install-server.sh
./scripts/test-recover-data-management.sh
```

For local runtime changes:

```bash
# Initialize the four PostgreSQL roles as shown in README.md, then:
./scripts/start-proxy.sh
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/v1/models
```

For proxy behavior changes, verify an enabled catalog model and matching provider-scoped API key against each affected upstream account type, then inspect `/admin/logs`, `/admin/usage-summary`, account state, API key usage, and balance settlement.

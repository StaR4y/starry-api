# Coding Plan Rebuild

Reviewed and tested on 2026-09-12. This document describes the implemented behavior,
not a claim that every future subscription feature is complete.

## Delivery Checklist

- [x] Immutable product versions, subscription snapshots, audited lifecycle, purchase idempotency
- [x] Atomic billing reservations, settlement, release, recovery, strict source isolation
- [x] API key source selection and redesigned subscription/administration interfaces
- [x] Restore-contract compatibility and real PostgreSQL concurrency tests
- [x] Desktop/mobile browser checks against the real isolated backend
- [ ] Direct payment-gateway checkout and fulfillment for subscription purchases

## Billing Contract

API keys explicitly choose `BALANCE` or `CODING_PLAN`. Existing keys migrate to
`BALANCE`. Neither source automatically falls back to the other. Users may select
Coding Plan without an active subscription, but calls fail with 402 until matching,
active quota is available. The key does not bind to one subscription: eligible
subscriptions are consumed in expiry order, with a single request allowed to split
across several subscriptions.

For example, with a wallet balance of 10 and matching subscription quota of 2:

| Key setting | A request costing 0.3 | When the selected source is insufficient |
| --- | --- | --- |
| BALANCE | Wallet becomes 9.7; plan quota remains 2 | 402; plan quota is untouched |
| CODING_PLAN | Plan quota becomes 1.7; wallet remains 10 | 402; wallet is untouched |

Plan purchase is a separate transaction: its price is deducted from the wallet.
Administrative grants and plan redemption codes issue quota without a wallet debit.
Administrator-owned API keys retain their existing accounting-only exemption.

A request captures the pricing quote, reserves an estimated amount, dispatches to
the upstream, and settles actual token usage at that quote. Holds count toward the
key's cumulative limit. Wallet holds subtract immediately from spendable balance;
unused funds are refunded. Subscription holds increment `reserved_credits`;
settlement consumes quota and releases the hold in one PostgreSQL transaction.
No external HTTP operation runs inside a database transaction.

Writers lock owner, key, request reservation, then subscriptions. A stable eligibility
instant is captured after the owner lock. Allocation totals are checked before
commit. Settlements use key-scoped request hashes; duplicate matching settlement
is a no-op, while changed amounts/token counts are rejected.

The estimate uses serialized input bytes plus a margin and the output-token bound.
Unspecified output limit defaults to 4096 on the Chat, Messages and Gemini endpoints. On the Responses endpoint it
defaults to `STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS` (16,000 unless the operator changed it; 4,096 to 1,000,000, and
the service does not start outside that range), capped at the model's maximum output. That maximum is the smaller of
the `max_output_tokens` the model list shows for the model and the one the official price catalog lists for it, or
whichever of the two exists. The model is the one the request is served as (after the key's model mapping, never the
name the client wrote), so most Claude and GPT aliases get 16,000 (a model whose price-catalog figure is below the setting gets
that figure: `gpt-4`, `claude-3-haiku-20240307` and `gpt-4o-2024-05-13` 4,096, `gemini-2.0-flash` 8,192; `gpt-4o` and `gpt-4o-mini` are held to 16,384
only when the setting is raised above that) and `deepseek-v4-*` gets 8,192. A model
the model list does not list gets 4096, and so does a model served by the operator's own backend: one the administrator
added, one written with the `openai-compatible/` prefix (even when a built-in has the same name after it), or a built-in one
the administrator pointed at their own vendor (each is listed with a placeholder and counts as listing none). The in-flight output tokens that account admission holds stay at 4096. Invalid limits are rejected. Remote
media and server-side response chains use a conservative input bound. Some upstream
transports, notably Codex, discard unsupported output limits; this is an estimate,
not a guaranteed upstream spending ceiling. When actual cost exceeds a reservation,
the server tops up only from the selected source. Insufficient top-up funds retain
the original hold and actual usage in `REVIEW_REQUIRED` instead of silently forgiving
the charge or switching sources.

## Products and Terms

- V13 snapshots product name, description, price, quota, provider scope and duration.
- Financial/scope edits publish a new version. Delisting/reordering does not.
- Existing subscriptions and issued redemption codes continue using their pinned version.
- Purchase requires the displayed version and a stable `Idempotency-Key`.
- Same-product renewals queue. Expiry or settled exhaustion advances the next term.
  A fully reserved but unconsumed term does not advance prematurely.
- Revocation prevents new admission but does not invalidate previously admitted holds.
- Restoring a revoked term is rejected if it overlaps an already-advanced renewal.
- Empty provider lists mean all providers; omitted PATCH fields preserve existing values.
- Entitlement changes and consumption are append-only in `subscription_ledger`.

## UI

The API Key page includes source badges/filtering and a source selector at creation
and editing, with wallet or matching-provider subscription availability. Optional
limits can now be explicitly cleared. Source/provider changes and deletion are
blocked while any billing request remains unfinished, including zero-cost holds.

The user page presents available quota, holds, expiry, wallet balance, subscription
states, quota ledgers, product comparison, and version-pinned wallet checkout.
Aggregates come from PostgreSQL and are not calculated from a truncated list.

The administration page separates products, subscriptions, and billing review.
It provides stock filtering, product-list pagination, version publishing, searchable
server-paginated subscriptions, user grants, reasoned revocation/restoration, and
audited review resolution. Wallet, monitor, key, document, edit and search icons use
the existing Element Plus icon library. Shared appearance components are preserved.

## Recovery

The maintenance job runs every 60 seconds (configurable with
`starry.billing.recovery-delay-ms`) and processes at most 100 expired reservations.
A reservation lease is 15 minutes.

- Unsent reservations may be released.
- Confirmed nonbillable request/credential/rate-limit rejection can release a hold.
- Dispatched requests with unknown results retain the hold for review.
- A later rejected retry does not erase uncertainty from an earlier failed attempt.
- Users with pending review cannot start additional chargeable requests.
- Administrators review the request and upstream evidence under the billing-review tab,
  then settle a verified amount or waive the charge with a recorded reason.
- A settled request cannot be charged again. Do not manually zero reserved balances.

`billing_review_actions` preserves actor, action, reason, original and resolved cost.
A successful retry is billed from its normalized successful result; failed upstream
attempts are not separately billed to the customer. This does not reconcile the
operator's entire upstream provider invoice.

## Migration and Rollout

1. Back up the database and drain/stop old proxy instances before applying the rebuild.
   Do not mix old automatic-fallback writers with new reservation writers.
2. Flyway applies V13 (versions/ledgers), V14 (explicit sources/reservations), and V15
   (expiry-boundary consistency, zero-cost pending-key protection and term overlap checks).
3. Existing V12 subscriptions receive version 1 using the terms currently stored in
   the old product table. Historical product edits before this snapshot cannot be
   reconstructed automatically. Existing usage is preserved with opening ledger entries.
4. Audit orphan subscriptions and duplicate fulfillment references before upgrading.
   The user foreign key is NOT VALID for preserved legacy orphans but enforces new writes;
   unique fulfillment indexes intentionally reject conflicting historical references.
5. Start the matching backend and WebUI together, check Flyway V15, then verify key
   creation, purchase, redemption and a low-cost call from each billing source.
6. Backups/restores must include version, subscription ledger, reservation allocation
   and review-action tables. The restore contract now requires V1-V15 history and
   matching checksums. Do not bypass validation with Flyway repair.

Useful read-only preflight queries:

```sql
SELECT s.subscription_id, s.user_id
FROM starry_api.coding_plan_subscriptions s
LEFT JOIN starry_api.admin_users u ON u.user_id=s.user_id
WHERE u.user_id IS NULL;

SELECT source, source_ref_id, count(*)
FROM starry_api.coding_plan_subscriptions
WHERE source IN ('ORDER','REDEMPTION_CODE')
  AND nullif(source_ref_id,'') IS NOT NULL
GROUP BY source, source_ref_id HAVING count(*) > 1;
```

Financial history currently has no automatic pruning. Monitor table/index growth;
define audited retention before deleting settled reservations or ledger history.

## Verification

Latest full backend run: **713 discovered, 636 executed/passed, 77 skipped, 0 failures**.
The 77 skipped cases require Docker, unavailable on this machine. They are not counted
as passed. The same 19-case Coding Plan billing contract is shared by the CI container
suite and the opt-in local PostgreSQL suite; all 19 ran against real PostgreSQL 17.

Coverage includes concurrent multi-key wallet/quota reservations, split subscription
charges, purchase retries, immutable terms/redemption after delisting, queued renewal,
unsent/dispatched cancellation, key limits/source changes/deletion, source-scoped
top-ups, expired/revoked admitted usage, term-overlap rejection, reviewed waivers,
ledger invariants, owner/provider isolation and missing-reservation rejection.
Proxy tests check pre-dispatch admission, output validation, request immutability,
routing/retry compatibility and successful accounting behavior.

```bash
# Explicitly initialized disposable PostgreSQL 17 on loopback:
env CODING_PLAN_DATABASE_TESTS=true POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55437 \
  POSTGRES_PASSWORD=starry_api_app_test \
  POSTGRES_MIGRATION_PASSWORD=starry_api_migration_test \
  ./gradlew :service:test --offline -PskipWebuiBuild=true

npm --prefix webui run build
```

Production WebUI build and typecheck passed. Playwright used installed Chrome and
real authenticated APIs at the isolated preview, not mocked billing responses.
Targeted ESLint checks passed with zero warnings. The final browser run saved 12
desktop/mobile screenshots and reported no JavaScript runtime errors.
Checks include both key sources persisting from the creation dialog, initial ledger
loading, checkout, admin publishing/subscription views, user/admin authorization,
and 1440px desktop / 390px mobile layouts without page overflow or runtime errors.
The opt-in browser script is `webui/scripts/coding-plan-browser-smoke.cjs`; it accepts
only the disposable 5174 preview and requires explicit environment opt-in/credentials.

Preview: `http://127.0.0.1:5174/#/coding-plans`, backend on 8081, PostgreSQL on 55437.
It has no upstream provider credentials. The existing 8080/5173 services are untouched.
Local preview logs and process IDs are in `/private/tmp/starry-coding-preview`;
screenshots and `report.json` are in its `screenshots` subdirectory.
The preview database and accounts are test-only, not production data.

No live paid model request, external gateway callback, or production database
migration was performed in this verification. Direct gateway plan purchase,
distributed rate limiting, true live SSE passthrough and upstream invoice
reconciliation are outside this completed slice.

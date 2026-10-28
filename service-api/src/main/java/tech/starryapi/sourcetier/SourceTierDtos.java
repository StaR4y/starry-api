package tech.starryapi.sourcetier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tech.starryapi.model.ModelDtos;

/**
 * Tiered source pools: a vendor's accounts split into ordered pools (tiers, level 1 is the best), each with a price
 * factor. The same {@link Tier}, {@link Member} and {@link Gate} shapes are read from {@code GET} and written with
 * {@code PUT}; a null optional field on input means its default. The ranges are checked by the service, which answers
 * with a {@code SOURCE_*} code that says what is wrong; only presence and gross size are checked here.
 */
public final class SourceTierDtos {
    private SourceTierDtos() {}

    /**
     * {@code configVersion} counts every change of the configuration (all vendors) and is what the nodes refresh on;
     * {@code vendors} has one entry per vendor the page can configure, configured or not.
     */
    public record Overview(long configVersion, Runtime runtime, List<Vendor> vendors) {}

    /**
     * What this release can run. A setting that needs a capability the release lacks is refused with 409
     * {@code SOURCE_TIER_RUNTIME_UNSUPPORTED} instead of being stored and taking effect after a later upgrade:
     * {@code nativeTiers} for a vendor with {@code enabled}, {@code unifyEndpoints} for the all-endpoints switch,
     * {@code xpMembers} for cross-protocol member rows and gates other than OFF. {@code pairs} lists the status of every
     * (vendor, source protocol, entry endpoint) a gate can open. {@code selectors} says that a client can name the tier it
     * wants (the {@code model@tier} form), which refuses no setting.
     */
    public record Runtime(boolean nativeTiers, boolean unifyEndpoints, boolean xpMembers, List<PairStatus> pairs, boolean selectors) {

        public Runtime(boolean nativeTiers, boolean unifyEndpoints, boolean xpMembers, List<PairStatus> pairs) {
            this(nativeTiers, unifyEndpoints, xpMembers, pairs, false);
        }
    }

    /** {@code status} is NOT_OFFERED, EXPERIMENTAL (a gate may only list API keys) or VERIFIED (a gate may be ON). */
    public record PairStatus(String vendor, String sourceProtocol, String entryEndpoint, String status) {}

    /**
     * A vendor's configuration as stored. {@code version} is what {@code expectedVersion} must equal to change it
     * (0 while {@code configured} is false). {@code warnings} are about the configuration as a whole and do not block it.
     * {@code tierAccounts} is the number of enabled accounts each tier (by {@code tierId}) holds as stored, pins, categories
     * and the fall into the lowest tier together; a tier without any is absent.
     */
    public record Vendor(
        String vendor,
        String label,
        boolean configured,
        long version,
        boolean enabled,
        boolean unifyEndpoints,
        boolean publicListing,
        boolean stickySessions,
        int refusalCap,
        boolean crossTierReservedSlot,
        List<Tier> tiers,
        List<Member> members,
        List<Gate> gates,
        List<Warning> warnings,
        Map<String, Integer> tierAccounts
    ) {}

    /**
     * {@code tierId} is lower case letters, digits and hyphens (up to 32) and is not {@code auto}, {@code L2} or a number,
     * which the tier selectors use; {@code level} is 1 to 9; {@code name} is up to 32 characters; {@code priceFactor} is
     * above 0 and at most 1000 with up to 6 decimals (default 1). {@code memberCategories} are the account categories
     * (OFFICIAL_API_KEY, COMPATIBLE_API_KEY, CODEX, CLAUDE_OAUTH, KIRO) that put an account here and can belong to one tier
     * of a vendor only. {@code userSelectable} and {@code enabled} default to true.
     */
    public record Tier(
        @NotBlank @Size(max = 128) String tierId,
        @NotNull Integer level,
        @NotBlank @Size(max = 256) String name,
        BigDecimal priceFactor,
        @Size(max = 16) List<@NotBlank String> memberCategories,
        Boolean userSelectable,
        Boolean enabled,
        @Size(max = 1024) String description
    ) {}

    /**
     * An account placed in a tier by hand. Without {@code protocol} it only pins a native account to {@code tierId} (the
     * remaining fields do not apply and read back as null). With {@code protocol} (OPENAI_CHAT) it registers an
     * OpenAI-compatible API key as a relay for another vendor's models: {@code models} maps catalog models to the relay's
     * model names, and both {@code usageCache*InPrompt} must be set (whether the relay's prompt tokens already include the
     * cache read / write tokens). Defaults: exclusive, upstreamStream true; vision, jsonSchema, priorityTier,
     * cacheControlPassthrough false; reasoningMode NONE (or EFFORT); maxDurationSeconds 540 (30 to 900).
     */
    public record Member(
        @NotBlank @Size(max = 128) String accountId,
        @NotBlank @Size(max = 128) String tierId,
        @Size(max = 64) String protocol,
        Boolean exclusive,
        Boolean vision,
        Boolean jsonSchema,
        Boolean priorityTier,
        Boolean cacheControlPassthrough,
        Boolean upstreamStream,
        @Size(max = 32) String reasoningMode,
        Integer maxDurationSeconds,
        Boolean usageCacheReadInPrompt,
        Boolean usageCacheWriteInPrompt,
        @Size(max = 256) List<@Valid MemberModel> models
    ) {}

    /**
     * Maps a catalog model ({@code modelId}) to the name the relay knows it by ({@code upstreamModel}, sent as is).
     * {@code maxContextTokens} is required; {@code imageTokenCap} defaults to 8192. {@code verifiedAt} and
     * {@code verified} are only read: a mapping counts as verified while its settings, account provider and base URL are
     * what they were when it was verified, and changing any of them clears the verification.
     */
    public record MemberModel(
        @NotBlank @Size(max = 1024) String modelId,
        @NotBlank @Size(max = 1024) String upstreamModel,
        @NotNull Integer maxContextTokens,
        Integer imageTokenCap,
        Boolean enabled,
        Instant verifiedAt,
        Boolean verified
    ) {}

    /**
     * Whether a source protocol may serve an entry endpoint (CHAT, MESSAGES, RESPONSES, GEMINI) of a vendor: state OFF,
     * KEYS (only {@code allowKeyIds}, at most 50) or ON. {@code sourceProtocol} defaults to OPENAI_CHAT.
     */
    public record Gate(
        @Size(max = 64) String sourceProtocol,
        @NotBlank @Size(max = 32) String entryEndpoint,
        @NotBlank @Size(max = 16) String state,
        @Size(max = 256) List<String> allowKeyIds
    ) {}

    /**
     * Something about the stored configuration that is allowed but probably not meant. {@code code} says what, so the
     * console can word it: LOWEST_TIER_COLLECTS_OFFICIAL_KEYS (official API keys that no tier lists fall into the lowest
     * tier, and so are priced at its factor; {@code count} accounts, {@code accountIds} the first few) and
     * MEMBER_NOT_SERVING_VENDOR (a pinned account is not of a kind the vendor's route uses, so the pin has no effect) and
     * PIN_ON_EXCLUSIVE_RELAY (a pinned account is the exclusive relay of another protocol in some vendor, so it is no native
     * member of any pool and the pin has no effect).
     */
    public record Warning(String code, String tierId, int count, List<String> accountIds, String message) {}

    /**
     * Replaces everything of one vendor: its switches, tiers, member rows (with their models) and gates. What is not in
     * the request is deleted. {@code expectedVersion} is the {@code version} the request was made from (0 for a vendor
     * that was never configured); another change in between answers 409 {@code SOURCE_TIER_VERSION_CONFLICT}.
     * {@code unifyEndpoints} defaults to false, {@code publicListing} to false, {@code stickySessions} to true,
     * {@code refusalCap} to 16 (4 to 64) and {@code crossTierReservedSlot} to true.
     */
    public record ReplaceVendor(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull Boolean enabled,
        Boolean unifyEndpoints,
        Boolean publicListing,
        Boolean stickySessions,
        Integer refusalCap,
        Boolean crossTierReservedSlot,
        @Size(max = 64) List<@Valid Tier> tiers,
        @Size(max = 2000) List<@Valid Member> members,
        @Size(max = 64) List<@Valid Gate> gates
    ) {}

    /**
     * Adds or updates member rows without sending the whole configuration (the accounts list uses it to set tiers in
     * bulk, the relay dialog to save one relay with its models), and removes the rows of {@code removeAccountIds}. A row
     * is identified by its account; a cross-protocol row's models are replaced by the ones sent. The result must be a
     * valid configuration as a whole, and {@code expectedVersion} works as for {@link ReplaceVendor}.
     */
    public record UpsertMembers(
        @NotNull @Min(0) Long expectedVersion,
        @Size(max = 200) List<@Valid Member> members,
        @Size(max = 200) List<@NotBlank String> removeAccountIds
    ) {}

    /**
     * What to verify of a relay ({@code POST /admin/source-tiers/{vendor}/members/{accountId}/verify}); the body may be
     * left out. {@code modelIds} are the catalog models whose mappings are probed (at most 8; when absent, the mappings
     * that are switched on, which must then be at most 8), {@code cacheProbe} adds the cache probe (default false): two
     * prompts of about 1,200 tokens for each mapping, which the operator pays for like every probe. The cache probe is
     * advice and never decides whether a mapping is verified.
     */
    public record VerifyMember(@Size(max = 8) List<@NotBlank @Size(max = 1024) String> modelIds, Boolean cacheProbe) {}

    /**
     * What a verification found. {@code verified} is true when every mapping probed is verified now. A mapping the
     * member changed while it was probed ({@code stale}) is left as it is: the probes were about a configuration that is
     * not the stored one any more, so nothing is written for it.
     */
    public record VerifyResult(String vendor, String accountId, Instant at, boolean verified, List<ModelVerification> models) {}

    /**
     * The probes of one mapping, in the order they ran. {@code verified} is whether the mapping counts as verified
     * after this run; a run that fails any probe takes a verification away, since the mapping no longer does what it
     * did. {@code cache} is present when the cache probe ran.
     */
    public record ModelVerification(
        String modelId,
        String upstreamModel,
        boolean verified,
        boolean stale,
        List<ProbeResult> probes,
        CacheAdvice cache
    ) {}

    /**
     * One probe: {@code name} is basic, tool_call, tool_loop, stream, vision or cache; {@code status} PASSED, FAILED or
     * SKIPPED. {@code message} is the gateway's own words (a failure of the relay is its status and the type it gave,
     * never its text). The token counts are what the relay reported, when it reported them.
     */
    public record ProbeResult(String name, String status, String message, long latencyMs, Long promptTokens, Long completionTokens) {}

    /**
     * What the cache probe makes of the relay's cache: {@code readInPrompt} and {@code writeInPrompt} say whether the
     * relay's prompt tokens already contain the cache read / write, and are null where the probe settles nothing, with
     * {@code message} then saying why. A member's "not contained" setting is accepted only where this says false for
     * the mapping. {@code calls} are the counts of the two answers the conclusion rests on.
     */
    public record CacheAdvice(
        Boolean readInPrompt,
        Boolean writeInPrompt,
        String readEvidence,
        String writeEvidence,
        String message,
        List<CacheCall> calls
    ) {}

    public record CacheCall(Long promptTokens, long cacheReadTokens, long cacheWriteTokens) {}

    /**
     * The models a relay lists ({@code GET /admin/source-tiers/{vendor}/members/{accountId}/upstream-models}), for
     * filling in the names of a mapping: only names a mapping can use, each once. {@code contextLength} is the window
     * the relay states for the model, null when it states none. {@code truncated} says that it listed more than 2000.
     */
    public record UpstreamModels(List<UpstreamModel> models, boolean truncated) {}

    public record UpstreamModel(String id, Integer contextLength) {}

    /**
     * What the node that answers would do with a request of each vendor right now (design 8): the accounts of every tier
     * by the layer that keeps them from serving, which tiers could serve, and how the last {@code windowMinutes} minutes
     * were served. The account figures are this node's (its cache is up to 30 seconds old); the figures of the window
     * are those of every node, read from the request log. Only vendors that have tiers are listed.
     */
    public record Status(Instant at, long configVersion, int windowMinutes, List<VendorStatus> vendors) {}

    /**
     * {@code serving} is whether requests of the vendor are planned over its tiers by this release at all (the vendor is
     * switched on and {@code nativeTiers} holds). {@code autoServed} counts the requests of the window that were served
     * with the tier chosen by the gateway, {@code degraded} those among them that were served below the best tier that
     * is switched on.
     */
    public record VendorStatus(
        String vendor,
        String label,
        boolean enabled,
        boolean serving,
        long autoServed,
        long degraded,
        List<TierStatus> tiers,
        XpStatus xp
    ) {

        public VendorStatus(
            String vendor,
            String label,
            boolean enabled,
            boolean serving,
            long autoServed,
            long degraded,
            List<TierStatus> tiers
        ) {
            this(vendor, label, enabled, serving, autoServed, degraded, tiers, null);
        }
    }

    /**
     * {@code serves} is whether this node would serve a request from the tier now: it is switched on, the vendor is
     * serving, and an account of it is ready. {@code served} counts the attempts of the window that were served from
     * the tier ({@code servedPinned} of them because the client named the tier), {@code failed} the attempts that went to
     * it and failed.
     */
    public record TierStatus(
        String tierId,
        int level,
        String name,
        boolean enabled,
        boolean serves,
        TierAccounts accounts,
        long served,
        long servedPinned,
        long failed
    ) {}

    /**
     * The accounts a tier holds, each counted once by the first layer of design 4.1 that keeps it from serving:
     * {@code disabled} (switched off), {@code unavailable} (suspended, or out of quota), {@code resting} (cooling down
     * or quarantined by the allocation lifecycle), {@code busy} (at its concurrency or in-flight token limit; the
     * request-rate limits and the per-model cooldowns show only when a request asks), and {@code ready}. A relay
     * (design 7) is counted like the others, and one more layer is its own: {@code unopened}, a relay no request can be
     * given for lack of a gate that lets requests through for a pair this release offers, or of a mapping that is
     * verified. It is checked before the layers of the account itself, since it does not change until the administrator
     * does something about it. Absent in a tier that holds no relay.
     */
    public record TierAccounts(int disabled, int unavailable, int resting, int busy, int ready, Integer unopened) {

        public TierAccounts(int disabled, int unavailable, int resting, int busy, int ready) {
            this(disabled, unavailable, resting, busy, ready, null);
        }
    }

    /**
     * What the status shows of the relays (cross-protocol sources) of a vendor, present for a vendor that has one.
     * {@code relays} are the relays of its tiers, {@code pairs} the (source protocol, entry) pairs that are offered or in
     * use with their gates and the attempts of the window, {@code skips} how often this node left a relay out of a
     * plan and why since {@code skipsSince} (the node's own count, and of relays, not of requests). The figures of the
     * window are those of every node, read from the request log.
     */
    public record XpStatus(Instant skipsSince, List<XpRelay> relays, List<XpPair> pairs, List<XpSkipCount> skips) {}

    /**
     * {@code layer} is the first layer that keeps the relay from serving, as in {@link TierAccounts} (disabled, unopened,
     * unavailable, resting, busy, or ready); {@code mappings} are those switched on and {@code verified} the ones among
     * them that count as verified now. {@code figures} are its attempts of the window.
     */
    public record XpRelay(String tierId, String accountId, String layer, int mappings, int verified, XpFigures figures) {}

    /**
     * One pair: {@code status} is what this release says of it (NOT_OFFERED, EXPERIMENTAL or VERIFIED), {@code gate} the
     * state of its gate (OFF, KEYS or ON) with {@code allowedKeys} the keys it lists, {@code figures} the attempts of the
     * window and {@code degradedFeatures} what the answered ones had to leave out of the request, most often first.
     */
    public record XpPair(
        String sourceProtocol,
        String entryEndpoint,
        String status,
        String gate,
        int allowedKeys,
        XpFigures figures,
        List<FeatureCount> degradedFeatures
    ) {}

    /**
     * The attempts that were sent to a relay in a window, counted from the request log. {@code attempts} is every one
     * (answered or failed), {@code served} those that were answered. The failures that the gateway knows the kind of are
     * counted apart: {@code interrupted} (the relay may have generated and nobody was charged: the operator's loss, the
     * UNBILLED of the design), {@code conversionRejected} (the relay refused the converted request), {@code
     * contextExceeded}, {@code modelNotServed} and {@code keyRefused} (the relay turned the key of its account down: the
     * request went on elsewhere and the account rests) and {@code withheld} (the relay answered and the gateway held the
     * answer back, as the Responses entry must for an answer its client would take for a failure: nobody was charged, and it
     * is nothing of the relay's reliability, so it is no part of {@code interrupted}); the rest of {@code failed} is anything
     * else. {@code withheldInputTokens} and {@code withheldOutputTokens} are what the relay reported of the withheld answers
     * (read as the usage of a served one is): the operator's cost, which the user was not charged and which is no part of the
     * usage the user's statistics count. {@code estimated} and
     * {@code clamped} are answered attempts whose usage was not what the relay reported, {@code degraded} those that
     * left a feature of the request out. {@code p95Ms} is the 95th percentile of the latency of the answered ones, absent
     * when none was.
     */
    public record XpFigures(
        long attempts,
        long served,
        long failed,
        long interrupted,
        long conversionRejected,
        long contextExceeded,
        long modelNotServed,
        long keyRefused,
        long withheld,
        long withheldInputTokens,
        long withheldOutputTokens,
        long estimated,
        long clamped,
        long degraded,
        Long p95Ms
    ) {}

    public record FeatureCount(String feature, long count) {}

    /** How many relays of a tier were left out of plans for a reason (see {@code XpSkip.Reason}) since the node started. */
    public record XpSkipCount(String tierId, String reason, long count) {}

    /**
     * The evidence an upgrade of a pair to VERIFIED is decided on (design 7.3): the figures of every offered pair over the
     * last {@code days} days of the request log, with the standard they are held to and whether each is met. The figures
     * of attempts are those of every node; {@code lintSkips} of a pair is the count of its vendor's relays that failed the
     * strict lint on the entry of the pair, which only this node keeps (since {@code skipsSince}), so on several nodes it is the answering node's.
     * The state of a gate is the one now: a pair that is only EXPERIMENTAL has been open to listed keys only for as long as
     * it has been open at all.
     */
    public record Evidence(Instant at, Instant since, int days, long configVersion, Instant skipsSince, List<PairEvidence> pairs) {}

    public record PairEvidence(
        String vendor,
        String label,
        String sourceProtocol,
        String entryEndpoint,
        String status,
        String gate,
        int allowedKeys,
        XpFigures figures,
        long lintSkips,
        List<Criterion> criteria,
        boolean meetsStandard,
        List<FeatureCount> degradedFeatures,
        List<MemberEvidence> members
    ) {}

    /**
     * One condition of the standard: {@code name} is requests, lintFailures, conversionRejected, estimated or unbilled,
     * {@code rule} how it is written (">= 200", "= 0", "< 1%", "< 5%", "< 2%"), {@code value} what was found (a count, or
     * a percentage of the attempts, of the answered attempts for estimated; absent when there is nothing to divide), and
     * {@code met} whether it holds. A condition that cannot be judged for lack of attempts is not met.
     */
    public record Criterion(String name, String rule, Double value, boolean met) {}

    public record MemberEvidence(String tierId, String accountId, XpFigures figures) {}

    /**
     * The tier each account is in, for the account list: an account that is in no tier of any vendor is absent. A
     * vendor's tiers count whether or not the vendor is switched on, so the list shows where an account will be when it is.
     */
    public record Placements(long configVersion, List<AccountPlacement> accounts) {}

    public record AccountPlacement(String accountId, List<Placed> tiers) {}

    /**
     * {@code vendorLabel} is the display name of the vendor. {@code rule} says what put the account there: MEMBER_ROW (it was set by hand), CATEGORY or LOWEST_TIER (no tier
     * lists its category, so it fell into the lowest one). {@code relay} marks an account registered as a relay for
     * another protocol. {@code active} is whether requests are served from the tier now; for a relay that also needs a
     * gate that is not off for a pair the release offers, and a mapping that is verified.
     */
    public record Placed(
        String vendor,
        String vendorLabel,
        String tierId,
        int level,
        String name,
        String rule,
        boolean relay,
        boolean active
    ) {}

    /**
     * What a user may choose from for an API key of {@code provider} (the key form's source pool choice): the pools of the
     * provider's vendor that clients may ask for, best first, and nothing else about them. {@code tiers} is empty when the
     * provider has no source tiers in use, or its administrator keeps the pool names to itself (an administrator is shown
     * them anyway). {@code referenceModel} is the model {@code pricing} is worked out for, the provider's first model in the
     * public model list that has a price; both are absent when there is no tier or no price.
     */
    public record MyTiers(String provider, String referenceModel, List<MyTier> tiers) {}

    /**
     * {@code id} is what a key preference, a request header or a model suffix names the pool by, {@code priceFactor} what the
     * standard price is multiplied by, and {@code pricing} the reference model's price with the factor applied, in the form
     * the public model list shows it.
     */
    public record MyTier(String id, int level, String name, BigDecimal priceFactor, ModelDtos.ModelPublicPricing pricing) {}
}

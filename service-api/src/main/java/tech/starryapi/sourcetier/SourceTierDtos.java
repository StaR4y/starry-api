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
     * MEMBER_NOT_SERVING_VENDOR (a pinned account is not of a kind the vendor's route uses, so the pin has no effect).
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
        List<TierStatus> tiers
    ) {}

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
     * request-rate limits and the per-model cooldowns show only when a request asks), and {@code ready}.
     */
    public record TierAccounts(int disabled, int unavailable, int resting, int busy, int ready) {}

    /**
     * The tier each account is in, for the account list: an account that is in no tier of any vendor is absent. A
     * vendor's tiers count whether or not the vendor is switched on, so the list shows where an account will be when it is.
     */
    public record Placements(long configVersion, List<AccountPlacement> accounts) {}

    public record AccountPlacement(String accountId, List<Placed> tiers) {}

    /**
     * {@code vendorLabel} is the display name of the vendor. {@code rule} says what put the account there: MEMBER_ROW (it was set by hand), CATEGORY or LOWEST_TIER (no tier
     * lists its category, so it fell into the lowest one). {@code relay} marks an account registered as a relay for
     * another protocol. {@code active} is whether requests are served from the tier now.
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

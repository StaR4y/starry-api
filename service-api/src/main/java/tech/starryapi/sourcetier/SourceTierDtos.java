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
     * (vendor, source protocol, entry endpoint) a gate can open.
     */
    public record Runtime(boolean nativeTiers, boolean unifyEndpoints, boolean xpMembers, List<PairStatus> pairs) {}

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
}

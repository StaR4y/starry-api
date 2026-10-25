package tech.starryapi.sourcetier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tech.starryapi.account.ModelPoolKey;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.sourcetier.SourceTierDtos.Warning;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * The rules a tier configuration must satisfy before it is stored. The database CHECKs and keys are the last line; this
 * class turns a request into the exact values that will be stored and says what is wrong in terms the administrator
 * can act on (400 {@code SOURCE_TIER_*}). It also refuses what this release cannot run (409
 * {@code SOURCE_TIER_RUNTIME_UNSUPPORTED}, see {@link SourceTierRuntimeInfo}), so a stored configuration never waits for
 * a later upgrade to take effect.
 *
 * <p>Everything here is pure except the model catalog it reads; the checks that need the accounts are
 * {@link #checkAccounts}, which the caller feeds with the rows it loaded.
 */
@Component
public class SourceTierValidator {

    static final String INVALID = "SOURCE_TIER_INVALID";
    static final String MEMBER_INVALID = "SOURCE_TIER_MEMBER_INVALID";
    static final String MEMBER_PROTOCOL_INVALID = "SOURCE_TIER_MEMBER_PROTOCOL_INVALID";
    static final String GATE_INVALID = "SOURCE_TIER_GATE_INVALID";
    static final String VENDOR_UNKNOWN = "SOURCE_TIER_VENDOR_UNKNOWN";
    static final String PROFILE_NOT_APPLICABLE = "SOURCE_PROFILE_NOT_APPLICABLE";
    static final String PAIR_NOT_OFFERED = "SOURCE_PAIR_NOT_OFFERED";
    static final String PAIR_GATE_FORBIDDEN = "SOURCE_PAIR_GATE_FORBIDDEN";
    static final String RUNTIME_UNSUPPORTED = "SOURCE_TIER_RUNTIME_UNSUPPORTED";

    public static final String WARNING_LOWEST_TIER_COLLECTS_OFFICIAL_KEYS = "LOWEST_TIER_COLLECTS_OFFICIAL_KEYS";
    public static final String WARNING_MEMBER_NOT_SERVING_VENDOR = "MEMBER_NOT_SERVING_VENDOR";

    private static final int DEFAULT_IMAGE_TOKEN_CAP = 8192;
    private static final int DEFAULT_MAX_DURATION_SECONDS = 540;
    private static final int MAX_TIERS = 9;
    private static final int MAX_VENDOR_LENGTH = 63;
    private static final int MAX_GATE_KEYS = 50;
    private static final int WARNING_SAMPLE = 5;
    private static final BigDecimal MAX_PRICE_FACTOR = BigDecimal.valueOf(1000);
    private static final Pattern VENDOR = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");
    private static final Pattern TIER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    // auto, L2 and 2 name a tier in the selectors (a model suffix, a header, a key preference), so they cannot be ids.
    private static final Pattern SELECTOR_WORD = Pattern.compile("(auto|l?[0-9]+)");
    private static final Pattern UPSTREAM_MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,254}");
    private static final Set<String> NON_MODEL_FAMILIES = Set.of("Routing Preset", "CodeWhisperer Internal");
    private static final List<String> VENDOR_ORDER = List.of("anthropic", "openai", "google", "deepseek");
    private static final List<String> REASONING_MODES = List.of("NONE", "EFFORT");
    private static final List<String> GATE_STATES = List.of("OFF", "KEYS", "ON");

    private final ModelCatalogProvider catalog;
    private final ModelPoolKeyResolver resolver;
    private final SourceTierRuntimeInfo runtime;

    public SourceTierValidator(ModelCatalogProvider catalog, ModelPoolKeyResolver resolver, SourceTierRuntimeInfo runtime) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.runtime = runtime;
    }

    /**
     * One vendor's complete configuration in the form that is stored and from which a {@link SourceTierSnapshot} is built.
     * {@code vendor().version()} is the version it was made from, not the one that will be written.
     */
    record Config(VendorConfig vendor, List<Tier> tiers, List<Member> members, List<MemberModel> models, List<PairGate> gates) {
    }

    /** Member rows to add or replace (with the models of the cross-protocol ones) and accounts whose rows go. */
    record MemberChanges(List<Member> members, List<MemberModel> models, Set<String> removed) {
    }

    private record Placed(Member member, List<MemberModel> models) {
    }

    // ---- vendors ----

    /** The vendors of the model catalog (the lower-case provider slug the router uses) with their display names. */
    Map<String, String> catalogVendors() {
        Map<String, String> found = new TreeMap<>();
        for (ModelPoolKey pool : resolver.knownPools()) {
            found.putIfAbsent(pool.provider(), pool.providerLabel());
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String vendor : VENDOR_ORDER) {
            if (found.containsKey(vendor)) {
                ordered.put(vendor, found.remove(vendor));
            }
        }
        ordered.putAll(found);
        return ordered;
    }

    /** The vendor id of a path, or 400 when it cannot be one. Whether the catalog (or the database) knows it is the caller's to say. */
    static String vendorId(String vendor) {
        String id = vendor == null ? "" : vendor.strip();
        if (id.length() > MAX_VENDOR_LENGTH || !VENDOR.matcher(id).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VENDOR_UNKNOWN, "Unknown vendor: " + abbreviated(vendor));
        }
        return id;
    }

    /** The version a request was made from; 0 stands for a vendor that was never configured. */
    static long expectedVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw invalid("expectedVersion is required: the version the request was made from, 0 for a vendor never configured");
        }
        return expectedVersion;
    }

    static ApiException unknownVendor(String vendor) {
        return new ApiException(HttpStatus.BAD_REQUEST, VENDOR_UNKNOWN,
            "Unknown vendor: " + vendor + " (a vendor is a provider of the model catalog)");
    }

    // ---- normalizing a request ----

    /** The configuration a replace request describes, with defaults applied; 400 for anything that is not valid. */
    Config normalize(String vendor, SourceTierDtos.ReplaceVendor request) {
        List<Tier> tiers = new ArrayList<>();
        List<SourceTierDtos.Tier> tierRows = orEmpty(request.tiers());
        for (int i = 0; i < tierRows.size(); i++) {
            tiers.add(tier(vendor, tierRows.get(i), "tiers[" + i + "]"));
        }
        List<Member> members = new ArrayList<>();
        List<MemberModel> models = new ArrayList<>();
        List<SourceTierDtos.Member> memberRows = orEmpty(request.members());
        for (int i = 0; i < memberRows.size(); i++) {
            Placed placed = member(vendor, memberRows.get(i), "members[" + i + "]");
            members.add(placed.member());
            models.addAll(placed.models());
        }
        List<PairGate> gates = new ArrayList<>();
        List<SourceTierDtos.Gate> gateRows = orEmpty(request.gates());
        for (int i = 0; i < gateRows.size(); i++) {
            gates.add(gate(vendor, gateRows.get(i), "gates[" + i + "]"));
        }
        return assemble(vendorConfig(vendor, request), tiers, members, models, gates);
    }

    /** The member rows an upsert request describes. At least one row or removal is required, and a row cannot also be removed. */
    MemberChanges normalizeChanges(String vendor, SourceTierDtos.UpsertMembers request) {
        List<Member> members = new ArrayList<>();
        List<MemberModel> models = new ArrayList<>();
        Set<String> upserted = new HashSet<>();
        List<SourceTierDtos.Member> rows = orEmpty(request.members());
        for (int i = 0; i < rows.size(); i++) {
            Placed placed = member(vendor, rows.get(i), "members[" + i + "]");
            if (!upserted.add(placed.member().accountId())) {
                throw memberInvalid("members[" + i + "]: account " + placed.member().accountId() + " appears twice");
            }
            members.add(placed.member());
            models.addAll(placed.models());
        }
        Set<String> removed = new HashSet<>();
        for (String accountId : orEmpty(request.removeAccountIds())) {
            String id = accountId == null ? "" : accountId.strip();
            if (id.isEmpty()) {
                throw memberInvalid("removeAccountIds contains an empty account id");
            }
            if (upserted.contains(id)) {
                throw memberInvalid("account " + id + " is both added and removed");
            }
            removed.add(id);
        }
        if (members.isEmpty() && removed.isEmpty()) {
            throw memberInvalid("Nothing to change: send members to add or update, or removeAccountIds");
        }
        return new MemberChanges(members, models, removed);
    }

    /** The configuration after {@code changes}, checked as a whole (a row may name a tier the vendor does not have). */
    Config apply(Config current, MemberChanges changes) {
        Set<String> touched = new HashSet<>(changes.removed());
        changes.members().forEach(member -> touched.add(member.accountId()));
        List<Member> members = new ArrayList<>(current.members().stream()
            .filter(member -> !touched.contains(member.accountId())).toList());
        members.addAll(changes.members());
        List<MemberModel> models = new ArrayList<>(current.models().stream()
            .filter(model -> !touched.contains(model.accountId())).toList());
        models.addAll(changes.models());
        return assemble(current.vendor(), current.tiers(), members, models, current.gates());
    }

    private VendorConfig vendorConfig(String vendor, SourceTierDtos.ReplaceVendor request) {
        VendorConfig defaults = VendorConfig.defaults(vendor);
        int refusalCap = request.refusalCap() == null ? defaults.refusalCap() : request.refusalCap();
        if (refusalCap < 4 || refusalCap > 64) {
            throw invalid("refusalCap must be between 4 and 64");
        }
        return new VendorConfig(
            vendor,
            Boolean.TRUE.equals(request.enabled()),
            Boolean.TRUE.equals(request.unifyEndpoints()),
            Boolean.TRUE.equals(request.publicListing()),
            request.stickySessions() == null ? defaults.stickySessions() : request.stickySessions(),
            refusalCap,
            request.crossTierReservedSlot() == null ? defaults.crossTierReservedSlot() : request.crossTierReservedSlot(),
            request.expectedVersion() == null ? 0L : request.expectedVersion()
        );
    }

    private static Tier tier(String vendor, SourceTierDtos.Tier row, String where) {
        String tierId = row.tierId() == null ? "" : row.tierId();
        if (!TIER_ID.matcher(tierId).matches()) {
            throw invalid(where + ".tierId must be lower case letters, digits and hyphens, 1 to 32 characters, starting with"
                + " a letter or digit: " + abbreviated(tierId));
        }
        if (SELECTOR_WORD.matcher(tierId).matches()) {
            throw invalid(where + ".tierId " + tierId + " is reserved: auto, L<n> and a bare number select a tier by level");
        }
        if (row.level() == null || row.level() < 1 || row.level() > MAX_TIERS) {
            throw invalid(where + ".level must be between 1 and " + MAX_TIERS);
        }
        String name = row.name() == null ? "" : row.name().strip();
        if (name.isEmpty()) {
            throw invalid(where + ".name must not be blank");
        }
        if (name.codePointCount(0, name.length()) > 32) {
            throw invalid(where + ".name must be at most 32 characters");
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(where + ".name must not contain control characters");
        }
        BigDecimal factor = row.priceFactor() == null ? BigDecimal.ONE : row.priceFactor();
        if (factor.signum() <= 0 || factor.compareTo(MAX_PRICE_FACTOR) > 0) {
            throw invalid(where + ".priceFactor must be above 0 and at most 1000");
        }
        if (factor.stripTrailingZeros().scale() > 6) {
            throw invalid(where + ".priceFactor has at most 6 decimals");
        }
        Set<AccountAllocationCategory> categories = EnumSet.noneOf(AccountAllocationCategory.class);
        for (String category : orEmpty(row.memberCategories())) {
            try {
                categories.add(AccountAllocationCategory.valueOf(category));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                throw invalid(where + ".memberCategories has an unknown category " + abbreviated(category) + "; use "
                    + String.join(", ", Arrays.stream(AccountAllocationCategory.values()).map(Enum::name).toList()));
            }
        }
        String description = row.description() == null ? null : row.description().strip();
        if (description != null && description.codePointCount(0, description.length()) > 256) {
            throw invalid(where + ".description must be at most 256 characters");
        }
        return new Tier(
            vendor,
            tierId,
            row.level(),
            name,
            factor.setScale(6),
            categories,
            row.userSelectable() == null || row.userSelectable(),
            row.enabled() == null || row.enabled(),
            description == null || description.isEmpty() ? null : description
        );
    }

    private Placed member(String vendor, SourceTierDtos.Member row, String where) {
        String accountId = row.accountId() == null ? "" : row.accountId().strip();
        if (accountId.isEmpty() || accountId.length() > 64) {
            throw memberInvalid(where + ".accountId must be 1 to 64 characters");
        }
        String tierId = row.tierId() == null ? "" : row.tierId();
        if (tierId.isEmpty()) {
            throw memberInvalid(where + ".tierId is required");
        }
        String protocol = row.protocol() == null || row.protocol().isBlank() ? null : row.protocol().strip();
        if (protocol == null) {
            if (!orEmpty(row.models()).isEmpty()) {
                throw memberInvalid(where + ".models only apply to a member with a protocol");
            }
            // A native pin: the columns that describe a relay do not apply and keep the defaults of the table.
            return new Placed(new Member(vendor, accountId, tierId, null, true, false, false, false, false, true, "NONE",
                DEFAULT_MAX_DURATION_SECONDS, null, null), List.of());
        }
        if (!SourceTierRuntimeInfo.OPENAI_CHAT.equals(protocol)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MEMBER_PROTOCOL_INVALID, where + ".protocol " + abbreviated(protocol)
                + " is not supported; only " + SourceTierRuntimeInfo.OPENAI_CHAT + " is");
        }
        if (!SourceTierRuntimeInfo.CROSS_PROTOCOL_VENDORS.contains(vendor)) {
            throw notApplicable(vendor);
        }
        String reasoningMode = row.reasoningMode() == null ? "NONE" : row.reasoningMode().strip();
        if (!REASONING_MODES.contains(reasoningMode)) {
            throw memberInvalid(where + ".reasoningMode must be NONE or EFFORT");
        }
        int maxDuration = row.maxDurationSeconds() == null ? DEFAULT_MAX_DURATION_SECONDS : row.maxDurationSeconds();
        if (maxDuration < 30 || maxDuration > 900) {
            throw memberInvalid(where + ".maxDurationSeconds must be between 30 and 900");
        }
        if (row.usageCacheReadInPrompt() == null || row.usageCacheWriteInPrompt() == null) {
            throw memberInvalid(where + ": usageCacheReadInPrompt and usageCacheWriteInPrompt must both be set; they say"
                + " whether the relay's prompt tokens already include cached tokens, and billing depends on it");
        }
        List<MemberModel> models = new ArrayList<>();
        Set<String> modelKeys = new HashSet<>();
        List<SourceTierDtos.MemberModel> modelRows = orEmpty(row.models());
        if (modelRows.isEmpty()) {
            throw memberInvalid(where + ".models needs at least one mapping");
        }
        for (int i = 0; i < modelRows.size(); i++) {
            MemberModel model = model(vendor, accountId, modelRows.get(i), where + ".models[" + i + "]");
            if (!modelKeys.add(model.modelId().toLowerCase(Locale.ROOT))) {
                throw memberInvalid(where + ".models[" + i + "]: model " + model.modelId() + " is mapped twice");
            }
            models.add(model);
        }
        Member member = new Member(
            vendor,
            accountId,
            tierId,
            protocol,
            row.exclusive() == null || row.exclusive(),
            Boolean.TRUE.equals(row.vision()),
            Boolean.TRUE.equals(row.jsonSchema()),
            Boolean.TRUE.equals(row.priorityTier()),
            Boolean.TRUE.equals(row.cacheControlPassthrough()),
            row.upstreamStream() == null || row.upstreamStream(),
            reasoningMode,
            maxDuration,
            row.usageCacheReadInPrompt(),
            row.usageCacheWriteInPrompt()
        );
        return new Placed(member, models);
    }

    private MemberModel model(String vendor, String accountId, SourceTierDtos.MemberModel row, String where) {
        String modelId = row.modelId() == null ? "" : row.modelId().strip();
        if (modelId.isEmpty() || modelId.length() > 256) {
            throw memberInvalid(where + ".modelId must be 1 to 256 characters");
        }
        if (!isCatalogModelOf(vendor, modelId)) {
            throw memberInvalid(where + ".modelId " + modelId + " is not an enabled model of the " + vendor
                + " catalog (routing presets and CodeWhisperer internal ids cannot be mapped)");
        }
        String upstreamModel = row.upstreamModel() == null ? "" : row.upstreamModel();
        if (!UPSTREAM_MODEL.matcher(upstreamModel).matches()) {
            throw memberInvalid(where + ".upstreamModel must be a plain model name (letters, digits and . _ : / @ + -, up to"
                + " 255 characters, no wildcard): " + abbreviated(upstreamModel));
        }
        if (row.maxContextTokens() == null || row.maxContextTokens() < 1) {
            throw memberInvalid(where + ".maxContextTokens is required and must be positive");
        }
        int imageTokenCap = row.imageTokenCap() == null ? DEFAULT_IMAGE_TOKEN_CAP : row.imageTokenCap();
        if (imageTokenCap < 1 || imageTokenCap > 1_000_000) {
            throw memberInvalid(where + ".imageTokenCap must be between 1 and 1000000");
        }
        // A mapping is verified by a probe against the relay, never by the request that saves it.
        return new MemberModel(vendor, accountId, modelId, upstreamModel, row.maxContextTokens(), imageTokenCap,
            row.enabled() == null || row.enabled(), null, null);
    }

    private boolean isCatalogModelOf(String vendor, String modelId) {
        for (ModelSpec spec : catalog.models()) {
            if (spec.id() != null && spec.id().equalsIgnoreCase(modelId)) {
                return !NON_MODEL_FAMILIES.contains(spec.family())
                    && vendor.equals(resolver.resolve(spec.id(), spec.id(), null).provider());
            }
        }
        return false;
    }

    private static PairGate gate(String vendor, SourceTierDtos.Gate row, String where) {
        if (!SourceTierRuntimeInfo.CROSS_PROTOCOL_VENDORS.contains(vendor)) {
            throw notApplicable(vendor);
        }
        String protocol = row.sourceProtocol() == null || row.sourceProtocol().isBlank()
            ? SourceTierRuntimeInfo.OPENAI_CHAT : row.sourceProtocol().strip();
        if (!SourceTierRuntimeInfo.OPENAI_CHAT.equals(protocol)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, GATE_INVALID, where + ".sourceProtocol " + abbreviated(protocol)
                + " is not supported; only " + SourceTierRuntimeInfo.OPENAI_CHAT + " is");
        }
        String entry = row.entryEndpoint() == null ? "" : row.entryEndpoint().strip();
        if (!SourceTierRuntimeInfo.ENTRY_ENDPOINTS.contains(entry)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, GATE_INVALID, where + ".entryEndpoint must be one of "
                + String.join(", ", SourceTierRuntimeInfo.ENTRY_ENDPOINTS));
        }
        String state = row.state() == null ? "" : row.state().strip();
        if (!GATE_STATES.contains(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, GATE_INVALID, where + ".state must be OFF, KEYS or ON");
        }
        List<String> keys = new ArrayList<>();
        for (String key : orEmpty(row.allowKeyIds())) {
            String id = key == null ? "" : key.strip();
            if (id.isEmpty() || id.length() > 128) {
                throw new ApiException(HttpStatus.BAD_REQUEST, GATE_INVALID, where + ".allowKeyIds must be 1 to 128 characters each");
            }
            if (!keys.contains(id)) {
                keys.add(id);
            }
        }
        if (keys.size() > MAX_GATE_KEYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, GATE_INVALID, where + ".allowKeyIds lists at most " + MAX_GATE_KEYS + " keys");
        }
        return new PairGate(vendor, protocol, entry, state, keys);
    }

    // ---- the whole config ----

    /** Checks what only the whole configuration shows: unique ids, levels, names, categories and accounts, and references. */
    static Config assemble(VendorConfig vendor, List<Tier> tiers, List<Member> members, List<MemberModel> models, List<PairGate> gates) {
        if (tiers.size() > MAX_TIERS) {
            throw invalid("A vendor has at most " + MAX_TIERS + " tiers");
        }
        Map<String, Tier> byId = new HashMap<>();
        Map<Integer, Tier> byLevel = new HashMap<>();
        Map<String, Tier> byName = new HashMap<>();
        Map<AccountAllocationCategory, Tier> byCategory = new HashMap<>();
        for (Tier tier : tiers) {
            if (byId.putIfAbsent(tier.tierId(), tier) != null) {
                throw invalid("Tier id " + tier.tierId() + " is used twice");
            }
            Tier sameLevel = byLevel.putIfAbsent(tier.level(), tier);
            if (sameLevel != null) {
                throw invalid("Tiers " + sameLevel.tierId() + " and " + tier.tierId() + " are both level " + tier.level());
            }
            Tier sameName = byName.putIfAbsent(tier.name(), tier);
            if (sameName != null) {
                throw invalid("Tiers " + sameName.tierId() + " and " + tier.tierId() + " are both named " + tier.name());
            }
            for (AccountAllocationCategory category : tier.memberCategories()) {
                Tier claimed = byCategory.putIfAbsent(category, tier);
                if (claimed != null) {
                    throw invalid("Category " + category + " is listed by tiers " + claimed.tierId() + " and " + tier.tierId()
                        + "; a category belongs to one tier of a vendor");
                }
            }
        }
        Set<String> accounts = new HashSet<>();
        for (Member member : members) {
            if (!accounts.add(member.accountId())) {
                throw memberInvalid("Account " + member.accountId() + " has more than one member row; a vendor places an account once");
            }
            if (!byId.containsKey(member.tierId())) {
                throw memberInvalid("Account " + member.accountId() + " is placed in tier " + member.tierId()
                    + ", which this configuration does not have");
            }
        }
        Set<String> gateKeys = new HashSet<>();
        for (PairGate gate : gates) {
            if (!gateKeys.add(gate.sourceProtocol() + "/" + gate.entryEndpoint())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, GATE_INVALID,
                    "The gate for " + gate.sourceProtocol() + " on " + gate.entryEndpoint() + " appears twice");
            }
        }
        if (vendor.unifyEndpoints() && !"anthropic".equals(vendor.vendor())) {
            throw invalid("The all-endpoints switch only exists for the anthropic vendor");
        }
        if (vendor.enabled() && tiers.stream().noneMatch(Tier::enabled)) {
            throw invalid("A vendor cannot be enabled without an enabled tier; it would not serve anything");
        }
        List<Tier> ordered = new ArrayList<>(tiers);
        ordered.sort(Comparator.comparingInt(Tier::level));
        return new Config(vendor, List.copyOf(ordered), List.copyOf(members), List.copyOf(models), List.copyOf(gates));
    }

    // ---- capabilities ----

    /**
     * Refuses a configuration this release cannot run (409), or that opens a pair the registry does not offer. Called
     * for the configuration as it would be stored, so what a previous release stored does not slip through a partial change.
     */
    void requireSupported(Config config) {
        List<String> unsupported = new ArrayList<>();
        List<String> described = new ArrayList<>();
        if (config.vendor().enabled() && !runtime.nativeTiers()) {
            unsupported.add("enabled");
            described.add("enabling a vendor");
        }
        if (config.vendor().unifyEndpoints() && !runtime.unifyEndpoints()) {
            unsupported.add("unifyEndpoints");
            described.add("the all-endpoints switch");
        }
        if (!runtime.xpMembers()) {
            if (config.members().stream().anyMatch(Member::crossProtocol)) {
                unsupported.add("crossProtocolMembers");
                described.add("cross-protocol member rows");
            }
            if (config.gates().stream().anyMatch(gate -> !"OFF".equals(gate.state()))) {
                unsupported.add("pairGates");
                described.add("gates other than OFF");
            }
        }
        if (!unsupported.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, RUNTIME_UNSUPPORTED,
                "This release cannot run " + String.join(", ", described) + ". Turn them off to save the rest.", unsupported);
        }
        for (PairGate gate : config.gates()) {
            if ("OFF".equals(gate.state())) {
                continue;
            }
            PairStatus status = runtime.pairStatus(gate.vendor(), gate.sourceProtocol(), gate.entryEndpoint());
            if (status == PairStatus.NOT_OFFERED) {
                throw new ApiException(HttpStatus.BAD_REQUEST, PAIR_NOT_OFFERED,
                    gate.sourceProtocol() + " is not offered for " + gate.entryEndpoint() + " requests of " + gate.vendor());
            }
            if (status == PairStatus.EXPERIMENTAL && "ON".equals(gate.state())) {
                throw new ApiException(HttpStatus.CONFLICT, PAIR_GATE_FORBIDDEN, gate.sourceProtocol() + " for "
                    + gate.entryEndpoint() + " requests of " + gate.vendor() + " is experimental: open it to listed API keys only");
            }
        }
    }

    /** The account rules, given the accounts of {@code config}'s member rows (by account id). */
    void checkAccounts(Config config, Map<String, ProxyAccountEntity> accounts) {
        for (Member member : config.members()) {
            ProxyAccountEntity account = accounts.get(member.accountId());
            if (account == null) {
                throw memberInvalid("Account " + member.accountId() + " does not exist");
            }
            if (member.crossProtocol() && !PoolCell.XP_OPENAI_CHAT.predicate().test(account)) {
                throw memberInvalid("Account " + member.accountId()
                    + " is not an OpenAI-compatible API key, which is all a relay can be");
            }
        }
    }

    // ---- warnings ----

    /**
     * What is allowed but probably not meant, for the stored configuration of one vendor, as {@code snapshot} holds it.
     * {@code accounts} are the accounts known to the system; only enabled ones count towards the lowest tier, since a
     * disabled key serves nobody.
     */
    static List<Warning> warnings(String vendor, SourceTierSnapshot snapshot, Collection<ProxyAccountEntity> accounts) {
        List<Warning> warnings = new ArrayList<>();
        Predicate<ProxyAccountEntity> family = SourceTierSnapshot.providerFamily(vendor);

        // Rule 3 of SourceTierSnapshot.tierOf: what no member row and no category places falls into the lowest enabled tier.
        // That is intended for relays and Kiro accounts, but an official key is the most expensive source, and it would
        // be billed at the factor of the cheapest tier.
        List<String> collected = new ArrayList<>();
        Tier lowest = null;
        for (ProxyAccountEntity account : accounts) {
            if (!Boolean.TRUE.equals(account.getEnabled()) || !family.test(account)
                || AccountAllocationCategory.classify(account.getProvider(), account.getAuthMethod(), account.getBaseUrl())
                    != AccountAllocationCategory.OFFICIAL_API_KEY) {
                continue;
            }
            SourceTierSnapshot.Placement placement = snapshot.placementOf(vendor, account).orElse(null);
            if (placement != null && placement.rule() == SourceTierSnapshot.Rule.LOWEST_TIER) {
                collected.add(account.getAccountId());
                lowest = placement.tier();
            }
        }
        if (!collected.isEmpty()) {
            collected.sort(Comparator.naturalOrder());
            warnings.add(new Warning(WARNING_LOWEST_TIER_COLLECTS_OFFICIAL_KEYS, lowest.tierId(), collected.size(),
                collected.stream().limit(WARNING_SAMPLE).toList(), collected.size() + " official API key(s) are not listed"
                    + " by any tier and fall into the lowest tier " + lowest.tierId() + ", so they are priced at its factor."
                    + " List OFFICIAL_API_KEY in the tier that should own them."));
        }

        // A pin matters for the accounts the vendor's models can reach.
        Predicate<ProxyAccountEntity> reachable = SourceTierSnapshot.routedFor(vendor);
        Map<String, ProxyAccountEntity> byId = new HashMap<>();
        accounts.forEach(account -> byId.put(account.getAccountId(), account));
        snapshot.members(vendor).stream().filter(member -> !member.crossProtocol()).sorted(Comparator.comparing(Member::accountId))
            .forEach(member -> {
                ProxyAccountEntity account = byId.get(member.accountId());
                if (account != null && !reachable.test(account)) {
                    warnings.add(new Warning(WARNING_MEMBER_NOT_SERVING_VENDOR, member.tierId(), 1, List.of(member.accountId()),
                        "Account " + member.accountId() + " is pinned to tier " + member.tierId() + " but is not a kind of account"
                            + " that any route to " + vendor + " models uses, so the pin has no effect."));
                }
            });
        return warnings;
    }

    // ---- fingerprint ----

    /**
     * Identifies what a verification of a mapping vouches for: the relay settings that shape the request and the reading
     * of the response, the catalog model and the relay's name for it, and where the account sends it (provider and base
     * URL). Anything else, such as the tier, the limits or whether the row is switched on, may change without a new probe.
     * The verifier stores this as {@code verified_hash}; a stored hash that differs from the current value means the
     * mapping is not verified any more.
     */
    public static String mappingFingerprint(Member member, MemberModel model, ProxyAccountEntity account) {
        StringBuilder canonical = new StringBuilder("tier-mapping-v1;");
        field(canonical, member.protocol());
        field(canonical, member.vision());
        field(canonical, member.jsonSchema());
        field(canonical, member.priorityTier());
        field(canonical, member.cacheControlPassthrough());
        field(canonical, member.upstreamStream());
        field(canonical, member.reasoningMode());
        field(canonical, member.usageCacheReadInPrompt());
        field(canonical, member.usageCacheWriteInPrompt());
        field(canonical, model.modelId().toLowerCase(Locale.ROOT));
        field(canonical, model.upstreamModel());
        field(canonical, account == null ? null : account.getProvider());
        field(canonical, account == null ? null : account.getBaseUrl());
        return Hashing.sha256(canonical.toString());
    }

    // The length first, so that no two lists of values can read the same.
    private static void field(StringBuilder canonical, Object value) {
        String text = value == null ? "" : value.toString();
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    // ---- errors ----

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String abbreviated(String value) {
        if (value == null) {
            return "null";
        }
        String text = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return "\"" + text + "\"";
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID, message);
    }

    private static ApiException memberInvalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, MEMBER_INVALID, message);
    }

    private static ApiException notApplicable(String vendor) {
        return new ApiException(HttpStatus.BAD_REQUEST, PROFILE_NOT_APPLICABLE, "Cross-protocol relays only serve "
            + String.join(", ", SourceTierRuntimeInfo.CROSS_PROTOCOL_VENDORS) + "; " + vendor
            + " already uses compatible API keys as its own accounts");
    }
}

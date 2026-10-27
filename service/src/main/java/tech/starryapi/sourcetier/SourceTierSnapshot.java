package tech.starryapi.sourcetier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;

/**
 * The tiered-source configuration as one immutable value: what {@link SourceTierRegistry} publishes, and what a request
 * reads from start to end so a configuration change in the middle cannot split it. {@link #EMPTY} is the configuration
 * of a deployment that has never configured tiers.
 *
 * <p>Only what the runtime reads is kept: the verification summaries of the member models stay in the database.
 */
public final class SourceTierSnapshot {

    public static final SourceTierSnapshot EMPTY = of(0L, List.of(), List.of(), List.of(), List.of(), List.of());

    public record VendorConfig(
        String vendor,
        boolean enabled,
        boolean unifyEndpoints,
        boolean publicListing,
        boolean stickySessions,
        int refusalCap,
        boolean crossTierReservedSlot,
        long version
    ) {
        /** The table's default of {@code refusal_cap}. */
        public static final int DEFAULT_REFUSAL_CAP = 16;

        /** What a vendor is before anyone configured it: switched off, with the defaults of the table for the rest. */
        public static VendorConfig defaults(String vendor) {
            return new VendorConfig(vendor, false, false, false, true, DEFAULT_REFUSAL_CAP, true, 0L);
        }
    }

    /** One tier (pool) of a vendor; level 1 is the best. Categories are the ones that put an account here (2.4 rule 2). */
    public record Tier(
        String vendor,
        String tierId,
        int level,
        String name,
        BigDecimal priceFactor,
        Set<AccountAllocationCategory> memberCategories,
        boolean userSelectable,
        boolean enabled,
        String description
    ) {
        public Tier {
            memberCategories = Set.copyOf(memberCategories);
        }
    }

    /**
     * An account placed in a tier by an explicit row. A row without a protocol only pins a native account; a row with
     * one registers an account as a relay for another protocol, and the settings after the tier are about that relay.
     */
    public record Member(
        String vendor,
        String accountId,
        String tierId,
        String protocol,
        boolean exclusive,
        boolean vision,
        boolean jsonSchema,
        boolean priorityTier,
        boolean cacheControlPassthrough,
        boolean upstreamStream,
        String reasoningMode,
        int maxDurationSeconds,
        Boolean usageCacheReadInPrompt,
        Boolean usageCacheWriteInPrompt
    ) {
        public boolean crossProtocol() {
            return protocol != null;
        }
    }

    public record MemberModel(
        String vendor,
        String accountId,
        String modelId,
        String upstreamModel,
        int maxContextTokens,
        int imageTokenCap,
        boolean enabled,
        Instant verifiedAt,
        String verifiedHash
    ) {
    }

    /** Whether a source protocol may serve an entry endpoint of a vendor; no row means off. */
    public record PairGate(
        String vendor,
        String sourceProtocol,
        String entryEndpoint,
        String state,
        List<String> allowKeyIds
    ) {
        /**
         * The key id the callers that have no key go by (an unauthenticated proxy request where the deployment allows one,
         * and the console's own assistant). It names no API key, so no gate can list it.
         */
        public static final String ANONYMOUS_KEY_ID = "anonymous";

        public PairGate {
            allowKeyIds = List.copyOf(allowKeyIds);
        }

        /** Whether the list of keys names this one; the key id of a caller without a key is never listed. */
        public boolean lists(String keyId) {
            return keyId != null && !ANONYMOUS_KEY_ID.equals(keyId) && allowKeyIds.contains(keyId);
        }
    }

    private record MemberKey(String vendor, String accountId) {
    }

    /** Everything one vendor has, indexed for the lookups a request makes. */
    private static final class VendorIndex {
        final VendorConfig config;
        final List<Tier> tiers;
        final Map<String, Tier> tiersById;
        final Map<AccountAllocationCategory, Tier> tierByCategory;
        final Tier lowestEnabled;
        final Map<String, Member> members;
        final List<PairGate> gates;
        final Predicate<ProxyAccountEntity> family;

        VendorIndex(VendorConfig config, List<Tier> tiers, Map<String, Member> members, List<PairGate> gates) {
            this.config = config;
            this.tiers = tiers;
            Map<String, Tier> byId = new HashMap<>();
            Map<AccountAllocationCategory, Tier> byCategory = new EnumMap<>(AccountAllocationCategory.class);
            Tier lowest = null;
            for (Tier tier : tiers) {
                byId.putIfAbsent(tier.tierId(), tier);
                // A category belongs to one tier; if two claim it, the better one wins.
                tier.memberCategories().forEach(category -> byCategory.putIfAbsent(category, tier));
                if (tier.enabled()) {
                    lowest = tier;
                }
            }
            this.tiersById = Map.copyOf(byId);
            this.tierByCategory = byCategory.isEmpty() ? Map.of() : Map.copyOf(byCategory);
            this.lowestEnabled = lowest;
            this.members = members;
            this.gates = gates;
            this.family = providerFamily(config.vendor());
        }
    }

    private final long configVersion;
    private final Map<String, VendorIndex> vendors;
    private final Map<MemberKey, List<MemberModel>> memberModels;
    private final Set<String> exclusiveXpAccountIds;
    private final boolean crossProtocolMembers;
    private final Map<String, Set<String>> openRelayEntries;
    private final boolean tiersInUse;

    private SourceTierSnapshot(
        long configVersion,
        Map<String, VendorIndex> vendors,
        Map<MemberKey, List<MemberModel>> memberModels,
        Set<String> exclusiveXpAccountIds,
        boolean crossProtocolMembers,
        Map<String, Set<String>> openRelayEntries
    ) {
        this.configVersion = configVersion;
        this.vendors = vendors;
        this.memberModels = memberModels;
        this.exclusiveXpAccountIds = exclusiveXpAccountIds;
        this.crossProtocolMembers = crossProtocolMembers;
        this.openRelayEntries = openRelayEntries;
        this.tiersInUse = vendors.values().stream().anyMatch(index -> index.config.enabled() && index.tiers.stream().anyMatch(Tier::enabled));
    }

    /**
     * Builds a snapshot from the rows of the five tables. The tables are consistent with each other (foreign keys, and
     * one transaction reads them), so a row that points at something missing is not expected; it is ignored rather than
     * failing the whole configuration.
     */
    public static SourceTierSnapshot of(
        long configVersion,
        Collection<VendorConfig> vendorRows,
        Collection<Tier> tierRows,
        Collection<Member> memberRows,
        Collection<MemberModel> modelRows,
        Collection<PairGate> gateRows
    ) {
        Map<String, VendorConfig> configs = new LinkedHashMap<>();
        vendorRows.forEach(config -> configs.putIfAbsent(config.vendor(), config));

        Map<String, List<Tier>> tiers = new HashMap<>();
        for (Tier tier : tierRows) {
            if (configs.containsKey(tier.vendor())) {
                tiers.computeIfAbsent(tier.vendor(), ignored -> new ArrayList<>()).add(tier);
            }
        }
        tiers.values().forEach(list -> list.sort(Comparator.comparingInt(Tier::level)));

        Map<String, Map<String, Member>> members = new HashMap<>();
        Set<String> exclusive = new HashSet<>();
        boolean relays = false;
        for (Member member : memberRows) {
            boolean placed = tiers.getOrDefault(member.vendor(), List.of()).stream()
                .anyMatch(tier -> tier.tierId().equals(member.tierId()));
            if (!placed) {
                continue;
            }
            members.computeIfAbsent(member.vendor(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(member.accountId(), member);
            relays |= member.crossProtocol();
            if (member.crossProtocol() && member.exclusive()) {
                exclusive.add(member.accountId());
            }
        }

        Map<MemberKey, List<MemberModel>> models = new HashMap<>();
        for (MemberModel model : modelRows) {
            Member member = members.getOrDefault(model.vendor(), Map.of()).get(model.accountId());
            if (member != null) {
                models.computeIfAbsent(new MemberKey(model.vendor(), model.accountId()), ignored -> new ArrayList<>())
                    .add(model);
            }
        }

        Map<String, List<PairGate>> gates = new HashMap<>();
        for (PairGate gate : gateRows) {
            if (configs.containsKey(gate.vendor())) {
                gates.computeIfAbsent(gate.vendor(), ignored -> new ArrayList<>()).add(gate);
            }
        }

        Map<String, VendorIndex> indexes = new LinkedHashMap<>();
        configs.forEach((vendor, config) -> indexes.put(vendor, new VendorIndex(
            config,
            List.copyOf(tiers.getOrDefault(vendor, List.of())),
            Map.copyOf(members.getOrDefault(vendor, Map.of())),
            List.copyOf(gates.getOrDefault(vendor, List.of()))
        )));
        models.replaceAll((key, list) -> List.copyOf(list));
        return new SourceTierSnapshot(configVersion, Map.copyOf(indexes), Map.copyOf(models), Set.copyOf(exclusive), relays,
            openRelayEntries(indexes));
    }

    /**
     * By vendor, the entry endpoints for which some relay of the vendor could be given a request: a relay of a tier that is
     * switched on, in a vendor that is switched on, and a gate of the vendor for the relay's protocol and the entry that is
     * not off. The pair's state in the registry of the code is not looked at here; a pair it does not offer only makes the
     * gate useless. A vendor with none is absent.
     */
    private static Map<String, Set<String>> openRelayEntries(Map<String, VendorIndex> indexes) {
        Map<String, Set<String>> byVendor = new HashMap<>();
        for (VendorIndex index : indexes.values()) {
            if (!index.config.enabled()) {
                continue;
            }
            Set<String> entries = new HashSet<>();
            for (Member member : index.members.values()) {
                Tier tier = index.tiersById.get(member.tierId());
                if (!member.crossProtocol() || tier == null || !tier.enabled()) {
                    continue;
                }
                for (PairGate gate : index.gates) {
                    if (gate.sourceProtocol().equals(member.protocol()) && !"OFF".equals(gate.state())) {
                        entries.add(gate.entryEndpoint());
                    }
                }
            }
            if (!entries.isEmpty()) {
                byVendor.put(index.config.vendor(), Set.copyOf(entries));
            }
        }
        return Map.copyOf(byVendor);
    }

    /** The value of {@code source_tier_config_version} this snapshot was read at; 0 before anything was configured. */
    public long configVersion() {
        return configVersion;
    }

    /**
     * True when no vendor has a configuration row. Only meaningful for a snapshot that was really read: the registry
     * refuses to hand out a snapshot before its first load, so "empty" never stands for "unknown".
     */
    public boolean isEmpty() {
        return vendors.isEmpty();
    }

    /**
     * Whether any vendor is switched on with a tier switched on, which is when a request can be planned in tiers at all.
     * Without one, a deployment runs the code it ran before tiers existed and keeps nothing for the sake of tiers.
     */
    public boolean hasTiersInUse() {
        return tiersInUse;
    }

    /** The vendors that have a configuration row, enabled or not. */
    public Set<String> vendorIds() {
        return vendors.keySet();
    }

    public Optional<VendorConfig> vendor(String vendor) {
        return Optional.ofNullable(vendors.get(vendor)).map(index -> index.config);
    }

    /** The vendor's tiers, level 1 first, enabled or not. */
    public List<Tier> tiers(String vendor) {
        VendorIndex index = vendors.get(vendor);
        return index == null ? List.of() : index.tiers;
    }

    public Optional<Tier> tier(String vendor, String tierId) {
        VendorIndex index = vendors.get(vendor);
        return index == null ? Optional.empty() : Optional.ofNullable(index.tiersById.get(tierId));
    }

    public Optional<Member> member(String vendor, String accountId) {
        VendorIndex index = vendors.get(vendor);
        return index == null ? Optional.empty() : Optional.ofNullable(index.members.get(accountId));
    }

    public Collection<Member> members(String vendor) {
        VendorIndex index = vendors.get(vendor);
        return index == null ? List.of() : index.members.values();
    }

    public List<MemberModel> models(String vendor, String accountId) {
        return memberModels.getOrDefault(new MemberKey(vendor, accountId), List.of());
    }

    public List<PairGate> gates(String vendor) {
        VendorIndex index = vendors.get(vendor);
        return index == null ? List.of() : index.gates;
    }

    /**
     * The accounts registered as the exclusive relay of another protocol. They keep out of the pools that serve
     * untiered traffic; the set is empty unless such a relay is configured.
     */
    public Set<String> exclusiveXpAccountIds() {
        return exclusiveXpAccountIds;
    }

    /**
     * Whether any vendor has an account registered as a relay for another protocol. Without one, nothing about a request
     * has to be read for the sake of relays.
     */
    public boolean hasCrossProtocolMembers() {
        return crossProtocolMembers;
    }

    /**
     * Whether any relay could be given a request that comes in through the entry endpoint ({@code CHAT}, {@code MESSAGES}...):
     * a relay of a tier and a vendor that are switched on, with a gate for that entry that is not off. A request is read for
     * the sake of relays only where this holds, so that a relay that is registered and not yet opened (or whose vendor is
     * switched off) costs the traffic of no vendor anything.
     */
    public boolean hasOpenRelayFor(String entryEndpoint) {
        return entryEndpoint != null && openRelayEntries.values().stream().anyMatch(entries -> entries.contains(entryEndpoint));
    }

    /**
     * {@link #hasOpenRelayFor(String)} for one vendor. A request of a vendor none of whose gates is open for the entry is not
     * read for the relays of the vendor, whatever another vendor has open for the same entry.
     */
    public boolean hasOpenRelayFor(String vendor, String entryEndpoint) {
        return vendor != null && entryEndpoint != null && openRelayEntries.getOrDefault(vendor, Set.of()).contains(entryEndpoint);
    }

    /** Whether the account is registered as the exclusive relay of another protocol in any vendor. */
    public boolean isExclusiveRelay(String accountId) {
        return accountId != null && exclusiveXpAccountIds.contains(accountId);
    }

    /** Whether the account is registered as a relay for another protocol (a cross-protocol member) in any vendor. */
    public boolean isRelay(String accountId) {
        return accountId != null && vendors.values().stream()
            .map(index -> index.members.get(accountId))
            .anyMatch(member -> member != null && member.crossProtocol());
    }

    /**
     * The model name an account that is registered as a relay is tested with (design 7.4, "tests and retests use the
     * mapping's wire name"): the name the relay serves a mapped model under, which is not a name the account tests of a
     * compatible key know (they would turn {@code claude-*} into the vendor's default test model). Empty for an account
     * that is no relay, and then the test is what it always was.
     *
     * <p>A model the administrator named that is one of the relay's mappings is tested under its wire name, and a wire name
     * is tested as it is. Without a model, or with {@code auto} or {@code simple-task} (the preset the scheduled tests
     * use), it is the first mapping, in the order of the vendors and then of the catalog ids, those that are switched
     * on first. Any other model is the first mapping too when the relay is exclusive, since it serves nothing else; a
     * relay that also serves other models is tested with the model asked for.
     *
     * <p>Known consequence, left as design 7.4 says it: the health check of a relay that is not exclusive and also serves
     * untiered traffic (GLM, say) is the automatic and scheduled test, which asks for {@code auto} and so tests the first
     * mapping, a Claude model, and may suspend or revive the account for the untiered pool by it. The console warns
     * about a relay that is not exclusive (design 7.5); whether a preset test of such a relay should keep the model it had
     * is the owner's decision.
     */
    public Optional<String> relayTestModel(String accountId, String requestedModel) {
        if (accountId == null) {
            return Optional.empty();
        }
        List<Member> relays = vendors.values().stream()
            .map(index -> index.members.get(accountId))
            .filter(member -> member != null && member.crossProtocol())
            .sorted(Comparator.comparing(Member::vendor))
            .toList();
        List<MemberModel> models = relays.stream()
            .flatMap(member -> models(member.vendor(), accountId).stream()
                .sorted(Comparator.comparing((MemberModel model) -> !model.enabled()).thenComparing(MemberModel::modelId)))
            .toList();
        if (models.isEmpty()) {
            return Optional.empty();
        }
        String wanted = requestedModel == null ? "" : requestedModel.strip();
        for (MemberModel model : models) {
            if (model.modelId().equalsIgnoreCase(wanted) || model.upstreamModel().equals(wanted)) {
                return Optional.of(model.upstreamModel());
            }
        }
        boolean preset = wanted.isEmpty() || wanted.equalsIgnoreCase("auto") || wanted.equalsIgnoreCase("simple-task");
        return preset || relays.stream().anyMatch(Member::exclusive) ? Optional.of(models.getFirst().upstreamModel()) : Optional.empty();
    }

    /** Which of the three rules of {@link #tierOf} put an account into its tier. */
    public enum Rule {
        MEMBER_ROW, CATEGORY, LOWEST_TIER
    }

    /** A tier together with the rule that chose it. */
    public record Placement(Tier tier, Rule rule) {
    }

    /**
     * The tier an account sits in for a vendor: (1) the tier of its member row; (2) else the tier that lists the
     * account's allocation category, provided the account belongs to the provider family of the vendor, since a
     * category such as OFFICIAL_API_KEY spans OpenAI and Anthropic; (3) else the lowest enabled tier. A tier that is
     * switched off is returned like any other, so the caller sees that the account's tier does not serve; only rule 3
     * looks at enabled tiers alone. Empty when the vendor has no tiers (or none enabled, for rule 3).
     */
    public Optional<Tier> tierOf(String vendor, ProxyAccountEntity account) {
        return placementOf(vendor, account).map(Placement::tier);
    }

    /** {@link #tierOf} with the rule that decided, for whoever has to tell an account that was placed from one that fell through. */
    public Optional<Placement> placementOf(String vendor, ProxyAccountEntity account) {
        VendorIndex index = vendors.get(vendor);
        if (index == null || account == null) {
            return Optional.empty();
        }
        String accountId = account.getAccountId();
        Member member = accountId == null ? null : index.members.get(accountId);
        if (member != null) {
            return Optional.ofNullable(index.tiersById.get(member.tierId())).map(tier -> new Placement(tier, Rule.MEMBER_ROW));
        }
        if (!index.tierByCategory.isEmpty() && index.family.test(account)) {
            Tier claimed = index.tierByCategory.get(AccountAllocationCategory.classify(
                account.getProvider(),
                account.getAuthMethod(),
                account.getBaseUrl()
            ));
            if (claimed != null) {
                return Optional.of(new Placement(claimed, Rule.CATEGORY));
            }
        }
        return Optional.ofNullable(index.lowestEnabled).map(tier -> new Placement(tier, Rule.LOWEST_TIER));
    }

    /**
     * The enabled accounts of {@code accounts} that {@link #tierOf} puts into each tier of a vendor, by tier id (a tier
     * with none is absent): what the page shows as the size of a tier. Only accounts the vendor's models can reach
     * count, see {@link #routedFor}; a switched-off tier counts its accounts too, since they are placed there and wait.
     * An exclusive relay of another protocol is no native member of any tier and is not counted.
     */
    public Map<String, Integer> placedAccounts(String vendor, Collection<ProxyAccountEntity> accounts) {
        Predicate<ProxyAccountEntity> reachable = routedFor(vendor);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProxyAccountEntity account : accounts) {
            if (Boolean.TRUE.equals(account.getEnabled()) && reachable.test(account) && !isExclusiveRelay(account.getAccountId())) {
                placementOf(vendor, account).ifPresent(placement -> counts.merge(placement.tier().tierId(), 1, Integer::sum));
            }
        }
        return counts;
    }

    /** Whether a relay (a member of another protocol) is registered in the tier, in service or not, eligible for a request or not. */
    public boolean hasRelayMembers(String vendor, String tierId) {
        VendorIndex index = vendors.get(vendor);
        return index != null && index.members.values().stream()
            .anyMatch(member -> member.crossProtocol() && member.tierId().equals(tierId));
    }

    /**
     * Whether the configuration can put an account that is no relay into the tier, by any of the rules of {@link #tierOf}: a
     * native member row names the tier, the tier lists an allocation category, or it is the lowest enabled tier, which takes
     * the accounts nothing else places. A tier for which none holds is made of its relays alone, whatever accounts the
     * cache holds now or after its next reload: this is the configuration, which a request freezes with its plan.
     */
    public boolean canHoldNativeAccounts(String vendor, String tierId) {
        VendorIndex index = vendors.get(vendor);
        Tier tier = index == null ? null : index.tiersById.get(tierId);
        if (tier == null) {
            return false;
        }
        return !tier.memberCategories().isEmpty()
            || (index.lowestEnabled != null && index.lowestEnabled.tierId().equals(tierId))
            || index.members.values().stream().anyMatch(member -> !member.crossProtocol() && member.tierId().equals(tierId));
    }

    /**
     * The accounts that serve a vendor's own models on the native route, as the {@link PoolCell}s say. A category only
     * places these: OFFICIAL_API_KEY holds official OpenAI and Anthropic keys alike, COMPATIBLE_API_KEY relays, DeepSeek
     * and third-party Anthropic keys, so a GPT model asked through /v1/messages would otherwise put an Anthropic key
     * into the OpenAI tier of the same category. A vendor without an account kind of its own is served as the router
     * serves it: Google and the other vendors that fall to the Kiro route by Kiro accounts, any custom catalog vendor
     * by a compatible API key.
     */
    static Predicate<ProxyAccountEntity> providerFamily(String vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return switch (vendor) {
            case "anthropic" -> account -> PoolCell.ANTHROPIC_KEY.predicate().test(account)
                || PoolCell.KIRO_COMPATIBLE.predicate().test(account);
            case "openai" -> PoolCell.OFFICIAL_OR_CODEX.predicate();
            case "deepseek" -> PoolCell.DEEPSEEK.predicate();
            case "google", "kiro", "unknown" -> PoolCell.KIRO_COMPATIBLE.predicate();
            default -> PoolCell.OPENAI_COMPATIBLE.predicate();
        };
    }

    /**
     * The accounts a request for one of the vendor's models can reach: its {@link #providerFamily} and, since
     * /v1/messages sends every model down the Anthropic route, Anthropic keys and Kiro accounts too. A tier is made of
     * these; any other account has no route to the vendor and serves none of its requests, whatever tier it is put in.
     */
    static Predicate<ProxyAccountEntity> routedFor(String vendor) {
        return providerFamily(vendor)
            .or(PoolCell.ANTHROPIC_KEY.predicate())
            .or(PoolCell.KIRO_COMPATIBLE.predicate());
    }
}

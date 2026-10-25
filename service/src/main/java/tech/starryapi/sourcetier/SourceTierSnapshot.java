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
        public PairGate {
            allowKeyIds = List.copyOf(allowKeyIds);
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

    private SourceTierSnapshot(
        long configVersion,
        Map<String, VendorIndex> vendors,
        Map<MemberKey, List<MemberModel>> memberModels,
        Set<String> exclusiveXpAccountIds
    ) {
        this.configVersion = configVersion;
        this.vendors = vendors;
        this.memberModels = memberModels;
        this.exclusiveXpAccountIds = exclusiveXpAccountIds;
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
        for (Member member : memberRows) {
            boolean placed = tiers.getOrDefault(member.vendor(), List.of()).stream()
                .anyMatch(tier -> tier.tierId().equals(member.tierId()));
            if (!placed) {
                continue;
            }
            members.computeIfAbsent(member.vendor(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(member.accountId(), member);
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
        return new SourceTierSnapshot(configVersion, Map.copyOf(indexes), Map.copyOf(models), Set.copyOf(exclusive));
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
     */
    public Map<String, Integer> placedAccounts(String vendor, Collection<ProxyAccountEntity> accounts) {
        Predicate<ProxyAccountEntity> reachable = routedFor(vendor);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProxyAccountEntity account : accounts) {
            if (Boolean.TRUE.equals(account.getEnabled()) && reachable.test(account)) {
                placementOf(vendor, account).ifPresent(placement -> counts.merge(placement.tier().tierId(), 1, Integer::sum));
            }
        }
        return counts;
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

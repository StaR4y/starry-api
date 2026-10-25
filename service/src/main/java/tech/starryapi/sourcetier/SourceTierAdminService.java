package tech.starryapi.sourcetier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.sourcetier.SourceTierValidator.Config;
import tech.starryapi.sourcetier.SourceTierValidator.MemberChanges;

/**
 * Reads and writes the tier configuration for the administrator API. A write replaces everything of one vendor in one
 * transaction: it takes the lock every writer takes, compares the vendor's version with the one the request was made
 * from, replaces the rows, bumps the vendor's version and {@code source_tier_config_version}, and commits. The change
 * of that setting is what makes every node, this one included, read the configuration again (NOTIFY, see
 * RuntimeStateInvalidator), so the nodes never see half of a write.
 */
@Service
public class SourceTierAdminService {

    private static final Logger log = LoggerFactory.getLogger(SourceTierAdminService.class);

    /** Taken by every write, whatever the vendor, so that writes run one after the other (SystemSettingRepository.acquireTransactionLock). */
    public static final String LOCK = "starry_api:source_tiers";

    private final DatabaseClient db;
    private final TransactionalOperator transactions;
    private final SystemSettingRepository settings;
    private final SourceTierRegistry registry;
    private final SourceTierValidator validator;
    private final SourceTierRuntimeInfo runtime;
    private final SourceTierLoader loader;

    public SourceTierAdminService(
        DatabaseClient db,
        ReactiveTransactionManager transactionManager,
        SystemSettingRepository settings,
        SourceTierRegistry registry,
        SourceTierValidator validator,
        SourceTierRuntimeInfo runtime
    ) {
        this.db = db;
        this.transactions = TransactionalOperator.create(transactionManager);
        this.settings = settings;
        this.registry = registry;
        this.validator = validator;
        this.runtime = runtime;
        this.loader = new SourceTierLoader(db, transactionManager);
    }

    // ---- reading ----

    /** The stored configuration, read from the database rather than this node's snapshot, which can be 30 seconds behind. */
    public Mono<SourceTierDtos.Overview> overview() {
        return loader.load().zipWith(accounts()).map(pair -> {
            SourceTierSnapshot snapshot = pair.getT1();
            Map<String, String> catalog = validator.catalogVendors();
            List<String> vendors = new ArrayList<>(catalog.keySet());
            // A vendor that was configured and has since left the catalog stays visible, so that it can be cleaned up.
            new TreeSet<>(snapshot.vendorIds()).stream().filter(vendor -> !catalog.containsKey(vendor)).forEach(vendors::add);
            return new SourceTierDtos.Overview(
                snapshot.configVersion(),
                runtimeView(),
                vendors.stream().map(vendor -> vendorView(vendor, catalog.getOrDefault(vendor, vendor), snapshot, pair.getT2())).toList()
            );
        });
    }

    private Mono<SourceTierDtos.Vendor> view(String vendor) {
        return loader.load().zipWith(accounts()).map(pair -> vendorView(
            vendor, validator.catalogVendors().getOrDefault(vendor, vendor), pair.getT1(), pair.getT2()));
    }

    private SourceTierDtos.Runtime runtimeView() {
        return new SourceTierDtos.Runtime(
            runtime.nativeTiers(),
            runtime.unifyEndpoints(),
            runtime.xpMembers(),
            runtime.pairs().stream()
                .map(pair -> new SourceTierDtos.PairStatus(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint(),
                    runtime.pairStatus(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint()).name()))
                .toList(),
            runtime.selectors()
        );
    }

    private SourceTierDtos.Vendor vendorView(
        String vendor,
        String label,
        SourceTierSnapshot snapshot,
        Map<String, ProxyAccountEntity> accounts
    ) {
        VendorConfig stored = snapshot.vendor(vendor).orElse(null);
        // A vendor nobody configured shows the defaults a first save would store.
        VendorConfig config = stored == null ? VendorConfig.defaults(vendor) : stored;
        List<Tier> tiers = snapshot.tiers(vendor);
        List<Member> members = snapshot.members(vendor).stream().sorted(Comparator.comparing(Member::accountId)).toList();
        List<SourceTierDtos.Member> memberViews = members.stream()
            .map(member -> memberView(member, snapshot, accounts.get(member.accountId())))
            .toList();
        List<SourceTierDtos.Gate> gates = snapshot.gates(vendor).stream()
            .sorted(Comparator.comparing(PairGate::sourceProtocol).thenComparing(PairGate::entryEndpoint))
            .map(gate -> new SourceTierDtos.Gate(gate.sourceProtocol(), gate.entryEndpoint(), gate.state(), gate.allowKeyIds()))
            .toList();
        return new SourceTierDtos.Vendor(
            vendor,
            label,
            stored != null,
            config.version(),
            config.enabled(),
            config.unifyEndpoints(),
            config.publicListing(),
            config.stickySessions(),
            config.refusalCap(),
            config.crossTierReservedSlot(),
            tiers.stream().map(SourceTierAdminService::tierView).toList(),
            memberViews,
            gates,
            stored == null ? List.of() : SourceTierValidator.warnings(vendor, snapshot, accounts.values()),
            stored == null ? Map.of() : snapshot.placedAccounts(vendor, accounts.values())
        );
    }

    private static SourceTierDtos.Tier tierView(Tier tier) {
        return new SourceTierDtos.Tier(
            tier.tierId(),
            tier.level(),
            tier.name(),
            tier.priceFactor(),
            tier.memberCategories().stream().map(AccountAllocationCategory::name).sorted().toList(),
            tier.userSelectable(),
            tier.enabled(),
            tier.description()
        );
    }

    private static SourceTierDtos.Member memberView(Member member, SourceTierSnapshot snapshot, ProxyAccountEntity account) {
        if (!member.crossProtocol()) {
            return new SourceTierDtos.Member(member.accountId(), member.tierId(), null, null, null, null, null, null, null,
                null, null, null, null, null);
        }
        List<SourceTierDtos.MemberModel> models = snapshot.models(member.vendor(), member.accountId()).stream()
            .sorted(Comparator.comparing(MemberModel::modelId))
            .map(model -> new SourceTierDtos.MemberModel(model.modelId(), model.upstreamModel(), model.maxContextTokens(),
                model.imageTokenCap(), model.enabled(), model.verifiedAt(),
                model.verifiedHash() != null && model.verifiedHash().equals(SourceTierValidator.mappingFingerprint(member, model, account))))
            .toList();
        return new SourceTierDtos.Member(member.accountId(), member.tierId(), member.protocol(), member.exclusive(),
            member.vision(), member.jsonSchema(), member.priorityTier(), member.cacheControlPassthrough(),
            member.upstreamStream(), member.reasoningMode(), member.maxDurationSeconds(), member.usageCacheReadInPrompt(),
            member.usageCacheWriteInPrompt(), models);
    }

    // ---- writing ----

    /** Replaces the configuration of one vendor with the request's. */
    public Mono<SourceTierDtos.Vendor> replace(String vendorId, SourceTierDtos.ReplaceVendor request) {
        return Mono.defer(() -> {
            String vendor = SourceTierValidator.vendorId(vendorId);
            long expectedVersion = SourceTierValidator.expectedVersion(request.expectedVersion());
            Config desired = validator.normalize(vendor, request);
            // Nothing needs the database to say no to this, so it is said before any lock is taken.
            validator.requireSupported(desired);
            return write(vendor, expectedVersion, () -> Mono.just(desired));
        });
    }

    /** Adds, updates and removes member rows of one vendor. */
    public Mono<SourceTierDtos.Vendor> upsertMembers(String vendorId, SourceTierDtos.UpsertMembers request) {
        return Mono.defer(() -> {
            String vendor = SourceTierValidator.vendorId(vendorId);
            long expectedVersion = SourceTierValidator.expectedVersion(request.expectedVersion());
            MemberChanges changes = validator.normalizeChanges(vendor, request);
            return write(vendor, expectedVersion,
                () -> loader.load().map(snapshot -> validator.apply(configOf(snapshot, vendor), changes)));
        });
    }

    /**
     * The one write path. {@code desired} runs inside the transaction, after the version is known to be the expected one,
     * so a change that depends on what is stored sees what it will replace.
     */
    private Mono<SourceTierDtos.Vendor> write(String vendor, long expectedVersion, Supplier<Mono<Config>> desired) {
        Mono<Void> stored = settings.acquireTransactionLock(LOCK)
            .then(Mono.defer(() -> storedVersion(vendor)))
            .flatMap(version -> {
                if (version != expectedVersion) {
                    return Mono.<Config>error(versionConflict(vendor, version));
                }
                if (version == 0 && !validator.catalogVendors().containsKey(vendor)) {
                    return Mono.<Config>error(SourceTierValidator.unknownVendor(vendor));
                }
                return desired.get();
            })
            .flatMap(config -> {
                validator.requireSupported(config);
                List<String> accountIds = config.members().stream().map(Member::accountId).toList();
                return accounts(accountIds).flatMap(accounts -> {
                    validator.checkAccounts(config, accounts);
                    return store(config, expectedVersion + 1, accounts);
                });
            });
        return transactions.transactional(stored)
            // The NOTIFY of the commit reaches every node, this one too, but only when its listener hears it.
            .then(Mono.defer(() -> registry.refresh().onErrorResume(error -> {
                log.warn("Failed to refresh the source tier configuration after a change", error);
                return Mono.empty();
            })))
            .then(Mono.defer(() -> view(vendor)));
    }

    private Mono<Long> storedVersion(String vendor) {
        return db.sql("SELECT version FROM source_tier_vendors WHERE vendor = :vendor")
            .bind("vendor", vendor)
            .map((row, metadata) -> row.get("version", Number.class).longValue())
            .one()
            .defaultIfEmpty(0L);
    }

    private static ApiException versionConflict(String vendor, long currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, "SOURCE_TIER_VERSION_CONFLICT",
            "The tier configuration of " + vendor + " was changed after it was loaded (it is at version " + currentVersion
                + " now). Reload it and apply the change again.", Map.of("currentVersion", currentVersion));
    }

    /** What is stored for a vendor, in the form the validator works with. */
    private static Config configOf(SourceTierSnapshot snapshot, String vendor) {
        VendorConfig config = snapshot.vendor(vendor)
            .orElseGet(() -> VendorConfig.defaults(vendor));
        List<Member> members = List.copyOf(snapshot.members(vendor));
        List<MemberModel> models = members.stream().flatMap(member -> snapshot.models(vendor, member.accountId()).stream()).toList();
        return new Config(config, snapshot.tiers(vendor), members, models, snapshot.gates(vendor));
    }

    /**
     * Replaces the rows of a vendor: the tiers are deleted (their members and models go with them by cascade), the gates
     * too, and everything is written again. The unique level and name of the tiers are deferred, as the design says, so
     * that the write never depends on the order of its inserts. A mapping keeps its verification when the fingerprint of
     * what the verification vouches for is unchanged.
     */
    private Mono<Void> store(Config config, long version, Map<String, ProxyAccountEntity> accounts) {
        VendorConfig vendor = config.vendor();
        Map<String, Member> members = new HashMap<>();
        config.members().forEach(member -> members.put(member.accountId(), member));
        return db.sql("SET CONSTRAINTS ALL DEFERRED").then()
            .then(Mono.defer(() -> storedVerifications(vendor.vendor())))
            .flatMap(verifications -> upsertVendor(vendor, version)
                .then(Mono.defer(() -> db.sql("DELETE FROM source_tiers WHERE vendor = :vendor")
                    .bind("vendor", vendor.vendor()).then()))
                .then(Mono.defer(() -> db.sql("DELETE FROM source_tier_pair_gates WHERE vendor = :vendor")
                    .bind("vendor", vendor.vendor()).then()))
                .thenMany(Flux.fromIterable(config.tiers()).concatMap(this::insertTier))
                .thenMany(Flux.fromIterable(config.members()).concatMap(this::insertMember))
                .thenMany(Flux.fromIterable(config.models()).concatMap(model -> insertModel(model, carried(
                    model, verifications.get(modelKey(model.accountId(), model.modelId())), members.get(model.accountId()),
                    accounts.get(model.accountId())))))
                .thenMany(Flux.fromIterable(config.gates()).concatMap(this::insertGate))
                .then())
            .then(Mono.defer(this::bumpConfigVersion));
    }

    private record Verification(Instant verifiedAt, String hash, String summary) {
        static final Verification NONE = new Verification(null, null, "{}");
    }

    private static String modelKey(String accountId, String modelId) {
        return accountId + '\u0000' + modelId.toLowerCase(Locale.ROOT);
    }

    private static Verification carried(MemberModel model, Verification stored, Member member, ProxyAccountEntity account) {
        if (stored == null || stored.hash() == null || member == null) {
            return Verification.NONE;
        }
        return stored.hash().equals(SourceTierValidator.mappingFingerprint(member, model, account)) ? stored : Verification.NONE;
    }

    private Mono<Map<String, Verification>> storedVerifications(String vendor) {
        return db.sql("""
                SELECT account_id, model_id, verified_at, verified_hash, CAST(verify_summary AS TEXT) AS verify_summary
                FROM source_tier_member_models
                WHERE vendor = :vendor AND verified_hash IS NOT NULL
                """)
            .bind("vendor", vendor)
            .map((row, metadata) -> Map.entry(
                modelKey(row.get("account_id", String.class), row.get("model_id", String.class)),
                new Verification(row.get("verified_at", Instant.class), row.get("verified_hash", String.class),
                    row.get("verify_summary", String.class))))
            .all()
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<Void> upsertVendor(VendorConfig vendor, long version) {
        return db.sql("""
                INSERT INTO source_tier_vendors (vendor, enabled, unify_endpoints, public_listing, sticky_sessions,
                    refusal_cap, cross_tier_reserved_slot, version)
                VALUES (:vendor, :enabled, :unify, :publicListing, :sticky, :refusalCap, :reservedSlot, :version)
                ON CONFLICT (vendor) DO UPDATE SET enabled = EXCLUDED.enabled, unify_endpoints = EXCLUDED.unify_endpoints,
                    public_listing = EXCLUDED.public_listing, sticky_sessions = EXCLUDED.sticky_sessions,
                    refusal_cap = EXCLUDED.refusal_cap, cross_tier_reserved_slot = EXCLUDED.cross_tier_reserved_slot,
                    version = EXCLUDED.version
                """)
            .bind("vendor", vendor.vendor())
            .bind("enabled", vendor.enabled())
            .bind("unify", vendor.unifyEndpoints())
            .bind("publicListing", vendor.publicListing())
            .bind("sticky", vendor.stickySessions())
            .bind("refusalCap", vendor.refusalCap())
            .bind("reservedSlot", vendor.crossTierReservedSlot())
            .bind("version", version)
            .then();
    }

    private Mono<Void> insertTier(Tier tier) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO source_tiers (vendor, tier_id, level, name, price_factor, member_categories, user_selectable,
                    enabled, description)
                VALUES (:vendor, :tierId, :level, :name, :priceFactor, CAST(:categories AS TEXT[]), :userSelectable,
                    :enabled, :description)
                """)
            .bind("vendor", tier.vendor())
            .bind("tierId", tier.tierId())
            .bind("level", tier.level())
            .bind("name", tier.name())
            .bind("priceFactor", tier.priceFactor())
            .bind("categories", tier.memberCategories().stream().map(AccountAllocationCategory::name).sorted().toArray(String[]::new))
            .bind("userSelectable", tier.userSelectable())
            .bind("enabled", tier.enabled());
        return nullable(spec, "description", tier.description(), String.class).then();
    }

    private Mono<Void> insertMember(Member member) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO source_tier_members (vendor, account_id, tier_id, protocol, exclusive, vision, json_schema,
                    priority_tier, cache_control_passthrough, upstream_stream, reasoning_mode, max_duration_seconds,
                    usage_cache_read_in_prompt, usage_cache_write_in_prompt)
                VALUES (:vendor, :accountId, :tierId, :protocol, :exclusive, :vision, :jsonSchema, :priorityTier,
                    :cacheControlPassthrough, :upstreamStream, :reasoningMode, :maxDuration, :usageRead, :usageWrite)
                """)
            .bind("vendor", member.vendor())
            .bind("accountId", member.accountId())
            .bind("tierId", member.tierId())
            .bind("exclusive", member.exclusive())
            .bind("vision", member.vision())
            .bind("jsonSchema", member.jsonSchema())
            .bind("priorityTier", member.priorityTier())
            .bind("cacheControlPassthrough", member.cacheControlPassthrough())
            .bind("upstreamStream", member.upstreamStream())
            .bind("reasoningMode", member.reasoningMode())
            .bind("maxDuration", member.maxDurationSeconds());
        spec = nullable(spec, "protocol", member.protocol(), String.class);
        spec = nullable(spec, "usageRead", member.usageCacheReadInPrompt(), Boolean.class);
        spec = nullable(spec, "usageWrite", member.usageCacheWriteInPrompt(), Boolean.class);
        return spec.then();
    }

    private Mono<Void> insertModel(MemberModel model, Verification verification) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO source_tier_member_models (vendor, account_id, model_id, upstream_model, max_context_tokens,
                    image_token_cap, enabled, verified_at, verified_hash, verify_summary)
                VALUES (:vendor, :accountId, :modelId, :upstreamModel, :maxContext, :imageCap, :enabled, :verifiedAt,
                    :verifiedHash, CAST(:summary AS JSONB))
                """)
            .bind("vendor", model.vendor())
            .bind("accountId", model.accountId())
            .bind("modelId", model.modelId())
            .bind("upstreamModel", model.upstreamModel())
            .bind("maxContext", model.maxContextTokens())
            .bind("imageCap", model.imageTokenCap())
            .bind("enabled", model.enabled())
            .bind("summary", verification.summary());
        spec = nullable(spec, "verifiedAt", verification.verifiedAt(), Instant.class);
        spec = nullable(spec, "verifiedHash", verification.hash(), String.class);
        return spec.then();
    }

    private Mono<Void> insertGate(PairGate gate) {
        return db.sql("""
                INSERT INTO source_tier_pair_gates (vendor, source_protocol, entry_endpoint, state, allow_key_ids)
                VALUES (:vendor, :sourceProtocol, :entryEndpoint, :state, CAST(:keys AS TEXT[]))
                """)
            .bind("vendor", gate.vendor())
            .bind("sourceProtocol", gate.sourceProtocol())
            .bind("entryEndpoint", gate.entryEndpoint())
            .bind("state", gate.state())
            .bind("keys", gate.allowKeyIds().toArray(String[]::new))
            .then();
    }

    /** The counter every node watches; a value that is not a number counts as 0, as the registry reads it. */
    private Mono<Void> bumpConfigVersion() {
        return db.sql("""
                INSERT INTO system_settings (setting_key, setting_value)
                VALUES (:key, '1')
                ON CONFLICT (setting_key) DO UPDATE
                SET setting_value = (CASE WHEN system_settings.setting_value ~ '^[0-9]{1,17}$'
                        THEN system_settings.setting_value::BIGINT ELSE 0 END + 1)::TEXT,
                    updated_at = CURRENT_TIMESTAMP
                """)
            .bind("key", SourceTierRegistry.CONFIG_VERSION_SETTING)
            .then();
    }

    private static <T> GenericExecuteSpec nullable(GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    // ---- accounts ----

    private Mono<Map<String, ProxyAccountEntity>> accounts() {
        return db.sql(SourceTierAccounts.SELECT).map((row, metadata) -> SourceTierAccounts.read(row)).all()
            .collectMap(ProxyAccountEntity::getAccountId);
    }

    private Mono<Map<String, ProxyAccountEntity>> accounts(List<String> ids) {
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return db.sql(SourceTierAccounts.SELECT + " WHERE account_id = ANY(CAST(:ids AS VARCHAR[]))")
            .bind("ids", ids.toArray(String[]::new))
            .map((row, metadata) -> SourceTierAccounts.read(row)).all()
            .collectMap(ProxyAccountEntity::getAccountId);
    }
}

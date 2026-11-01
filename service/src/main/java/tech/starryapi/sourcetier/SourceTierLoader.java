package tech.starryapi.sourcetier;

import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * Reads the five tier tables and the configuration version in one repeatable-read transaction, so an administrator's
 * change, which is committed as one, is either all in the snapshot or not at all.
 */
final class SourceTierLoader {

    private static final Logger log = LoggerFactory.getLogger(SourceTierLoader.class);

    private static final String VENDORS = """
        SELECT vendor, enabled, unify_endpoints, public_listing, sticky_sessions, refusal_cap,
               cross_tier_reserved_slot, version
        FROM source_tier_vendors
        """;
    private static final String TIERS = """
        SELECT vendor, tier_id, level, name, price_factor, member_categories, user_selectable, enabled, description
        FROM source_tiers
        """;
    private static final String MEMBERS = """
        SELECT vendor, account_id, tier_id, protocol, exclusive, vision, json_schema, priority_tier,
               cache_control_passthrough, upstream_stream, reasoning_mode, max_duration_seconds,
               usage_cache_read_in_prompt, usage_cache_write_in_prompt
        FROM source_tier_members
        """;
    private static final String MODELS = """
        SELECT vendor, account_id, model_id, upstream_model, max_context_tokens, image_token_cap, max_output_tokens, enabled,
               verified_at, verified_hash
        FROM source_tier_member_models
        """;
    private static final String GATES = """
        SELECT vendor, source_protocol, entry_endpoint, state, allow_key_ids
        FROM source_tier_pair_gates
        """;
    private static final String CONFIG_VERSION = """
        SELECT setting_value FROM system_settings WHERE setting_key = :settingKey
        """;

    private final DatabaseClient databaseClient;
    private final TransactionalOperator readTransaction;

    SourceTierLoader(DatabaseClient databaseClient, ReactiveTransactionManager transactionManager) {
        this.databaseClient = databaseClient;
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setReadOnly(true);
        this.readTransaction = TransactionalOperator.create(transactionManager, definition);
    }

    Mono<SourceTierSnapshot> load() {
        return readTransaction.transactional(read());
    }

    private Mono<SourceTierSnapshot> read() {
        return configVersion().flatMap(version ->
            rows(VENDORS, SourceTierLoader::vendor).flatMap(vendors ->
            rows(TIERS, SourceTierLoader::tier).flatMap(tiers ->
            rows(MEMBERS, SourceTierLoader::member).flatMap(members ->
            rows(MODELS, SourceTierLoader::memberModel).flatMap(models ->
            rows(GATES, SourceTierLoader::gate).map(gates ->
                SourceTierSnapshot.of(version, vendors, tiers, members, models, gates)))))));
    }

    /** A missing or unreadable version reads as 0, the value of a deployment that never configured tiers. */
    private Mono<Long> configVersion() {
        return databaseClient.sql(CONFIG_VERSION)
            .bind("settingKey", SourceTierRegistry.CONFIG_VERSION_SETTING)
            .map((row, metadata) -> row.get("setting_value", String.class))
            .one()
            .map(SourceTierLoader::parseVersion)
            .defaultIfEmpty(0L);
    }

    private static long parseVersion(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException unreadable) {
            log.warn("Source tier configuration version {} is not a number; reading it as 0", value);
            return 0L;
        }
    }

    private <T> Mono<List<T>> rows(String sql, Function<Readable, T> mapper) {
        return databaseClient.sql(sql)
            .map((row, metadata) -> mapper.apply(row))
            .all()
            .collectList();
    }

    private static VendorConfig vendor(Readable row) {
        return new VendorConfig(
            row.get("vendor", String.class),
            flag(row, "enabled"),
            flag(row, "unify_endpoints"),
            flag(row, "public_listing"),
            flag(row, "sticky_sessions"),
            integer(row, "refusal_cap"),
            flag(row, "cross_tier_reserved_slot"),
            row.get("version", Number.class).longValue()
        );
    }

    private static Tier tier(Readable row) {
        return new Tier(
            row.get("vendor", String.class),
            row.get("tier_id", String.class),
            integer(row, "level"),
            row.get("name", String.class),
            row.get("price_factor", BigDecimal.class),
            categories(row.get("member_categories", String[].class)),
            flag(row, "user_selectable"),
            flag(row, "enabled"),
            row.get("description", String.class)
        );
    }

    /** The CHECK admits five values; one this release does not know (written by a newer node) places no account. */
    private static Set<AccountAllocationCategory> categories(String[] names) {
        Set<AccountAllocationCategory> categories = EnumSet.noneOf(AccountAllocationCategory.class);
        if (names != null) {
            for (String name : names) {
                try {
                    categories.add(AccountAllocationCategory.valueOf(name));
                } catch (IllegalArgumentException unknown) {
                    log.warn("Ignoring unknown source tier member category {}", name);
                }
            }
        }
        return categories;
    }

    private static Member member(Readable row) {
        return new Member(
            row.get("vendor", String.class),
            row.get("account_id", String.class),
            row.get("tier_id", String.class),
            row.get("protocol", String.class),
            flag(row, "exclusive"),
            flag(row, "vision"),
            flag(row, "json_schema"),
            flag(row, "priority_tier"),
            flag(row, "cache_control_passthrough"),
            flag(row, "upstream_stream"),
            row.get("reasoning_mode", String.class),
            integer(row, "max_duration_seconds"),
            row.get("usage_cache_read_in_prompt", Boolean.class),
            row.get("usage_cache_write_in_prompt", Boolean.class)
        );
    }

    private static MemberModel memberModel(Readable row) {
        return new MemberModel(
            row.get("vendor", String.class),
            row.get("account_id", String.class),
            row.get("model_id", String.class),
            row.get("upstream_model", String.class),
            integer(row, "max_context_tokens"),
            integer(row, "image_token_cap"),
            row.get("max_output_tokens", Integer.class),
            flag(row, "enabled"),
            row.get("verified_at", Instant.class),
            row.get("verified_hash", String.class)
        );
    }

    private static PairGate gate(Readable row) {
        String[] keys = row.get("allow_key_ids", String[].class);
        return new PairGate(
            row.get("vendor", String.class),
            row.get("source_protocol", String.class),
            row.get("entry_endpoint", String.class),
            row.get("state", String.class),
            keys == null ? List.of() : Arrays.stream(keys).filter(Objects::nonNull).toList()
        );
    }

    private static boolean flag(Readable row, String column) {
        return Boolean.TRUE.equals(row.get(column, Boolean.class));
    }

    private static int integer(Readable row, String column) {
        return row.get(column, Number.class).intValue();
    }
}

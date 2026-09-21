package tech.starryapi.plugin;

import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class PluginStore {
    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public PluginStore(DatabaseClient db) {
        this.db = db;
        this.tx = TransactionalOperator.create(new R2dbcTransactionManager(db.getConnectionFactory()));
    }

    public record SecurityState(boolean enabled, String challengeId, String codeHash, String codeSalt,
                                String sessionHash, String identityHash, Instant expiresAt,
                                Instant sentAt, boolean delivered, int failures, Instant lockedUntil) {}
    public record Installed(String id, String version, String provider, String description,
                            String sha256, long size, boolean autoStart, String config, Instant installedAt) {}

    public Mono<SecurityState> security() { return state(false); }

    private Mono<SecurityState> state(boolean lock) {
        return db.sql("SELECT * FROM plugin_security_state WHERE id = 1" + (lock ? " FOR UPDATE" : ""))
            .map((r, m) -> new SecurityState(Boolean.TRUE.equals(r.get("developer_enabled", Boolean.class)),
                r.get("challenge_id", String.class), r.get("code_hash", String.class), r.get("code_salt", String.class),
                r.get("session_hash", String.class), r.get("identity_hash", String.class), instant(r, "expires_at"),
                instant(r, "sent_at"), Boolean.TRUE.equals(r.get("delivered", Boolean.class)),
                r.get("failures", Integer.class), instant(r, "locked_until")))
            .one().switchIfEmpty(Mono.error(new IllegalStateException("Plugin security state is missing")));
    }

    public <T> Mono<T> locked(Function<SecurityState, Mono<T>> action) {
        return state(true).flatMap(action).as(tx::transactional);
    }

    public Mono<Void> challenge(String id, String hash, String salt, String session, String identity, Instant expires) {
        return db.sql("""
            UPDATE plugin_security_state SET challenge_id=:id, code_hash=:hash, code_salt=:salt,
            session_hash=:session, identity_hash=:identity, expires_at=:expires, sent_at=CURRENT_TIMESTAMP,
            delivered=FALSE, updated_at=CURRENT_TIMESTAMP WHERE id=1
            """).bind("id", id).bind("hash", hash).bind("salt", salt).bind("session", session)
            .bind("identity", identity).bind("expires", expires).fetch().rowsUpdated().then();
    }

    public Mono<Void> delivered(String id, boolean delivered) {
        return db.sql("UPDATE plugin_security_state SET delivered=:delivered WHERE id=1 AND challenge_id=:id")
            .bind("id", id).bind("delivered", delivered).fetch().rowsUpdated().then();
    }

    public Mono<Void> failure(int previousFailures, Instant now) {
        int failures = previousFailures + 1;
        return db.sql("""
            UPDATE plugin_security_state SET failures=:failures, locked_until=:until,
            delivered=CASE WHEN :failures >= 5 THEN FALSE ELSE delivered END WHERE id=1
            """).bind("failures", failures).bind("until", failures >= 5 ? now.plusSeconds(900) : now)
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> mode(boolean enabled) {
        return db.sql("""
            UPDATE plugin_security_state SET developer_enabled=:enabled, challenge_id=NULL, code_hash=NULL,
            code_salt=NULL, session_hash=NULL, identity_hash=NULL, expires_at=NULL, delivered=FALSE,
            failures=CASE WHEN :enabled THEN 0 ELSE failures END,
            locked_until=CASE WHEN :enabled THEN NULL ELSE locked_until END, updated_at=CURRENT_TIMESTAMP WHERE id=1
            """).bind("enabled", enabled).fetch().rowsUpdated().then();
    }

    public Flux<Installed> installed() {
        return db.sql("SELECT * FROM installed_plugins ORDER BY plugin_id").map((r, m) -> new Installed(
            r.get("plugin_id", String.class), r.get("version", String.class), r.get("provider", String.class),
            r.get("description", String.class), r.get("sha256", String.class), r.get("size_bytes", Long.class),
            Boolean.TRUE.equals(r.get("auto_start", Boolean.class)), r.get("config_json", String.class),
            instant(r, "installed_at"))).all();
    }

    public Mono<Void> save(Installed p) {
        return db.sql("""
            INSERT INTO installed_plugins(plugin_id,version,provider,description,sha256,size_bytes)
            VALUES(:id,:version,:provider,:description,:sha,:size)
            ON CONFLICT(plugin_id) DO UPDATE SET version=:version,provider=:provider,description=:description,
            sha256=:sha,size_bytes=:size,auto_start=FALSE,installed_at=CURRENT_TIMESTAMP
            """).bind("id", p.id()).bind("version", p.version()).bind("provider", p.provider())
            .bind("description", p.description()).bind("sha", p.sha256()).bind("size", p.size()).fetch().rowsUpdated().then();
    }

    public Mono<Void> autoStart(String id, boolean enabled) {
        return db.sql("UPDATE installed_plugins SET auto_start=:enabled WHERE plugin_id=:id")
            .bind("id", id).bind("enabled", enabled).fetch().rowsUpdated().then();
    }

    public Mono<Void> stopAll() {
        return db.sql("UPDATE installed_plugins SET auto_start=FALSE").fetch().rowsUpdated().then();
    }

    public Mono<Void> config(String id, String json) {
        return db.sql("UPDATE installed_plugins SET config_json=:json WHERE plugin_id=:id")
            .bind("json", json).bind("id", id).fetch().rowsUpdated().then();
    }

    public Mono<Void> remove(String id) {
        return db.sql("DELETE FROM installed_plugins WHERE plugin_id=:id").bind("id", id).fetch().rowsUpdated().then();
    }

    public Mono<Void> audit(String plugin, String actor, String action, String outcome, String detail) {
        return db.sql("INSERT INTO plugin_audit(plugin_id,actor,action,outcome,detail) VALUES(:p,:a,:e,:o,:d)")
            .bind("p", plugin).bind("a", actor).bind("e", action).bind("o", outcome).bind("d", detail)
            .fetch().rowsUpdated().then();
    }

    public Flux<PluginDtos.Audit> audits(String plugin, long before) {
        return db.sql("""
            SELECT * FROM plugin_audit WHERE (:plugin='' OR plugin_id=:plugin)
            AND (:before=0 OR id<:before) ORDER BY id DESC LIMIT 100
            """).bind("plugin", plugin).bind("before", before).map((r, m) -> new PluginDtos.Audit(
                r.get("id", Long.class), instant(r, "occurred_at"), r.get("plugin_id", String.class),
                r.get("actor", String.class), r.get("action", String.class), r.get("outcome", String.class),
                r.get("detail", String.class))).all();
    }

    public Mono<Void> prune() {
        return db.sql("SELECT starry_api.prune_plugin_audit()").fetch().all().then();
    }

    private static Instant instant(Row row, String name) { return row.get(name, Instant.class); }
}

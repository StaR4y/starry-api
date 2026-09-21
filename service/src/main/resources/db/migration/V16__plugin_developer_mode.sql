CREATE TABLE plugin_security_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    developer_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    challenge_id VARCHAR(64),
    code_hash VARCHAR(128),
    code_salt VARCHAR(64),
    session_hash VARCHAR(64),
    identity_hash VARCHAR(64),
    expires_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    failures INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO plugin_security_state (id) VALUES (1);

CREATE TABLE installed_plugins (
    plugin_id VARCHAR(80) PRIMARY KEY,
    version VARCHAR(80) NOT NULL,
    provider VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    auto_start BOOLEAN NOT NULL DEFAULT FALSE,
    config_json TEXT NOT NULL DEFAULT '{}',
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE plugin_audit (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    plugin_id VARCHAR(80) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    detail VARCHAR(2000) NOT NULL
);
CREATE INDEX plugin_audit_time_idx ON plugin_audit (occurred_at);
CREATE INDEX plugin_audit_plugin_idx ON plugin_audit (plugin_id, id DESC);

GRANT UPDATE ON plugin_security_state, installed_plugins TO starry_api_runtime;
GRANT DELETE ON installed_plugins TO starry_api_runtime;

CREATE FUNCTION starry_api.prune_plugin_audit() RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, starry_api AS $$
DECLARE deleted BIGINT;
BEGIN
    DELETE FROM starry_api.plugin_audit WHERE id IN (
        SELECT id FROM starry_api.plugin_audit
        WHERE occurred_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
        ORDER BY id LIMIT 10000
    );
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$;
REVOKE ALL ON FUNCTION starry_api.prune_plugin_audit() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.prune_plugin_audit() TO starry_api_runtime;

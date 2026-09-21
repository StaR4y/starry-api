SET search_path TO starry_api, public;

CREATE TABLE account_scoped_cooldowns (
    account_id VARCHAR(64) NOT NULL
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    scope_type VARCHAR(24) NOT NULL,
    scope_key VARCHAR(256) NOT NULL,
    cooldown_until TIMESTAMPTZ(3) NOT NULL,
    request_id VARCHAR(64),
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, scope_type, scope_key),
    CONSTRAINT ck_account_scoped_cooldown_type CHECK (
        scope_type IN ('CAPACITY_MODEL', 'CAPACITY_ENDPOINT')
    ),
    CONSTRAINT ck_account_scoped_cooldown_key CHECK (btrim(scope_key) <> ''),
    CONSTRAINT ck_account_scoped_cooldown_evidence CHECK (
        jsonb_typeof(evidence_json) = 'object'
    )
) WITH (
    fillfactor = 85,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

CREATE INDEX idx_account_scoped_cooldowns_expiry
    ON account_scoped_cooldowns (cooldown_until, account_id);
CREATE INDEX idx_account_admission_leases_completed
    ON account_admission_leases (completed_at, lease_id)
    WHERE dispatch_state IN ('COMPLETED', 'RELEASED', 'EXPIRED', 'ABANDONED');
CREATE INDEX idx_account_active_sessions_global_expiry
    ON account_active_sessions (expires_at, account_id);

CREATE TRIGGER trg_account_scoped_cooldowns_updated_at
    BEFORE UPDATE ON account_scoped_cooldowns
    FOR EACH ROW EXECUTE FUNCTION starry_api.set_updated_at();

CREATE OR REPLACE FUNCTION starry_api.capture_account_scoped_cooldown()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    scope_key_value VARCHAR(256);
    raw_cooldown TEXT;
    cooldown_seconds INTEGER := 45;
BEGIN
    IF NEW.event_type <> 'UPSTREAM_FAILURE'
        OR NEW.error_scope NOT IN ('CAPACITY_MODEL', 'CAPACITY_ENDPOINT')
        OR NEW.account_id IS NULL THEN
        RETURN NEW;
    END IF;

    scope_key_value := CASE NEW.error_scope
        WHEN 'CAPACITY_MODEL' THEN pg_catalog.left(
            pg_catalog.btrim(COALESCE(
                NEW.evidence_json ->> 'upstreamModel',
                NEW.evidence_json ->> 'model',
                ''
            )),
            256
        )
        WHEN 'CAPACITY_ENDPOINT' THEN pg_catalog.left(
            pg_catalog.btrim(COALESCE(NEW.evidence_json ->> 'endpoint', '')),
            256
        )
    END;
    IF scope_key_value IS NULL OR scope_key_value = '' THEN
        RETURN NEW;
    END IF;

    raw_cooldown := NEW.evidence_json ->> 'cooldownSeconds';
    IF raw_cooldown ~ '^[0-9]{1,6}$' THEN
        cooldown_seconds := LEAST(GREATEST(raw_cooldown::INTEGER, 1), 86400);
    END IF;

    INSERT INTO starry_api.account_scoped_cooldowns (
        account_id,
        scope_type,
        scope_key,
        cooldown_until,
        request_id,
        evidence_json
    ) VALUES (
        NEW.account_id,
        NEW.error_scope,
        scope_key_value,
        pg_catalog.clock_timestamp() + pg_catalog.make_interval(secs => cooldown_seconds),
        NEW.request_id,
        NEW.evidence_json
    )
    ON CONFLICT (account_id, scope_type, scope_key) DO UPDATE
    SET cooldown_until = GREATEST(
            account_scoped_cooldowns.cooldown_until,
            EXCLUDED.cooldown_until
        ),
        request_id = EXCLUDED.request_id,
        evidence_json = EXCLUDED.evidence_json,
        updated_at = pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_account_allocation_event_scoped_cooldown
    AFTER INSERT ON account_allocation_events
    FOR EACH ROW EXECUTE FUNCTION starry_api.capture_account_scoped_cooldown();

CREATE OR REPLACE FUNCTION starry_api.try_acquire_scoped_account_lease(
    p_account_id VARCHAR,
    p_request_id VARCHAR,
    p_expected_state_version BIGINT,
    p_session_hash VARCHAR,
    p_enforce_session_limit BOOLEAN,
    p_reserved_input_tokens BIGINT,
    p_reserved_output_tokens BIGINT,
    p_model_scope VARCHAR,
    p_endpoint_scope VARCHAR,
    p_lease_seconds INTEGER DEFAULT 600
)
RETURNS TABLE (
    accepted BOOLEAN,
    lease_id UUID,
    rejection_reason VARCHAR,
    state_version BIGINT,
    expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '2s'
AS $$
DECLARE
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
    blocked_scope VARCHAR(24);
    blocked_until TIMESTAMPTZ;
    lifecycle_version BIGINT;
    model_scope_value VARCHAR(256) := NULLIF(pg_catalog.left(pg_catalog.btrim(p_model_scope), 256), '');
    endpoint_scope_value VARCHAR(256) := NULLIF(pg_catalog.left(pg_catalog.btrim(p_endpoint_scope), 256), '');
BEGIN
    IF p_account_id IS NOT NULL AND pg_catalog.btrim(p_account_id) <> '' THEN
        PERFORM pg_catalog.pg_advisory_xact_lock(
            starry_api.account_allocation_lock_key(p_account_id)
        );
        DELETE FROM starry_api.account_scoped_cooldowns AS cooldown
        WHERE cooldown.account_id = p_account_id
          AND cooldown.cooldown_until <= now_at;

        SELECT cooldown.scope_type, cooldown.cooldown_until
        INTO blocked_scope, blocked_until
        FROM starry_api.account_scoped_cooldowns AS cooldown
        WHERE cooldown.account_id = p_account_id
          AND cooldown.cooldown_until > now_at
          AND (
              (cooldown.scope_type = 'CAPACITY_MODEL'
                  AND model_scope_value IS NOT NULL
                  AND cooldown.scope_key = model_scope_value)
              OR
              (cooldown.scope_type = 'CAPACITY_ENDPOINT'
                  AND endpoint_scope_value IS NOT NULL
                  AND cooldown.scope_key = endpoint_scope_value)
          )
        ORDER BY cooldown.cooldown_until DESC
        LIMIT 1;

        IF FOUND THEN
            SELECT lifecycle.state_version
            INTO lifecycle_version
            FROM starry_api.account_allocation_states AS lifecycle
            WHERE lifecycle.account_id = p_account_id;
            RETURN QUERY SELECT
                FALSE,
                NULL::UUID,
                CASE blocked_scope
                    WHEN 'CAPACITY_MODEL' THEN 'MODEL_COOLDOWN'::VARCHAR
                    ELSE 'ENDPOINT_COOLDOWN'::VARCHAR
                END,
                lifecycle_version,
                blocked_until;
            RETURN;
        END IF;
    END IF;

    RETURN QUERY
    SELECT *
    FROM starry_api.try_acquire_account_lease(
        p_account_id,
        p_request_id,
        p_expected_state_version,
        p_session_hash,
        p_enforce_session_limit,
        p_reserved_input_tokens,
        p_reserved_output_tokens,
        p_lease_seconds
    );
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.prune_account_allocation_history(
    p_lease_retention_days INTEGER,
    p_event_retention_days INTEGER,
    p_batch_size INTEGER
)
RETURNS TABLE (
    leases_deleted INTEGER,
    events_deleted INTEGER,
    sessions_deleted INTEGER,
    cooldowns_deleted INTEGER,
    response_owners_deleted INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
BEGIN
    IF p_lease_retention_days IS NULL OR p_lease_retention_days < 1
        OR p_lease_retention_days > 3650 THEN
        RAISE EXCEPTION 'lease retention days must be between 1 and 3650';
    END IF;
    IF p_event_retention_days IS NULL OR p_event_retention_days < 30
        OR p_event_retention_days > 3650 THEN
        RAISE EXCEPTION 'event retention days must be between 30 and 3650';
    END IF;
    IF p_batch_size IS NULL OR p_batch_size < 100 OR p_batch_size > 100000 THEN
        RAISE EXCEPTION 'batch size must be between 100 and 100000';
    END IF;

    WITH doomed AS MATERIALIZED (
        SELECT lease.lease_id
        FROM starry_api.account_admission_leases AS lease
        WHERE lease.dispatch_state IN ('COMPLETED', 'RELEASED', 'EXPIRED', 'ABANDONED')
          AND lease.completed_at < now_at
              - pg_catalog.make_interval(days => p_lease_retention_days)
        ORDER BY lease.completed_at, lease.lease_id
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM starry_api.account_admission_leases AS lease
        USING doomed
        WHERE lease.lease_id = doomed.lease_id
        RETURNING 1
    )
    SELECT count(*)::INTEGER INTO leases_deleted FROM deleted;

    WITH doomed AS MATERIALIZED (
        SELECT event.id
        FROM starry_api.account_allocation_events AS event
        WHERE event.created_at < now_at
              - pg_catalog.make_interval(days => p_event_retention_days)
        ORDER BY event.created_at, event.id
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM starry_api.account_allocation_events AS event
        USING doomed
        WHERE event.id = doomed.id
        RETURNING 1
    )
    SELECT count(*)::INTEGER INTO events_deleted FROM deleted;

    WITH doomed AS MATERIALIZED (
        SELECT session.ctid
        FROM starry_api.account_active_sessions AS session
        WHERE session.expires_at <= now_at
        ORDER BY session.expires_at
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM starry_api.account_active_sessions AS session
        USING doomed
        WHERE session.ctid = doomed.ctid
        RETURNING 1
    )
    SELECT count(*)::INTEGER INTO sessions_deleted FROM deleted;

    WITH doomed AS MATERIALIZED (
        SELECT cooldown.ctid
        FROM starry_api.account_scoped_cooldowns AS cooldown
        WHERE cooldown.cooldown_until <= now_at
        ORDER BY cooldown.cooldown_until
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM starry_api.account_scoped_cooldowns AS cooldown
        USING doomed
        WHERE cooldown.ctid = doomed.ctid
        RETURNING 1
    )
    SELECT count(*)::INTEGER INTO cooldowns_deleted FROM deleted;

    WITH doomed AS MATERIALIZED (
        SELECT owner.ctid
        FROM starry_api.openai_response_owners AS owner
        WHERE owner.expires_at <= now_at
        ORDER BY owner.expires_at
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM starry_api.openai_response_owners AS owner
        USING doomed
        WHERE owner.ctid = doomed.ctid
        RETURNING 1
    )
    SELECT count(*)::INTEGER INTO response_owners_deleted FROM deleted;

    RETURN NEXT;
END;
$$;

REVOKE ALL ON TABLE account_scoped_cooldowns FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE account_scoped_cooldowns TO starry_api_runtime;

REVOKE ALL ON FUNCTION starry_api.capture_account_scoped_cooldown() FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.try_acquire_scoped_account_lease(
    VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, VARCHAR, VARCHAR, INTEGER
) FROM PUBLIC;
REVOKE ALL ON FUNCTION starry_api.prune_account_allocation_history(INTEGER, INTEGER, INTEGER)
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION starry_api.try_acquire_scoped_account_lease(
    VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, VARCHAR, VARCHAR, INTEGER
) TO starry_api_runtime;
GRANT EXECUTE ON FUNCTION starry_api.prune_account_allocation_history(INTEGER, INTEGER, INTEGER)
    TO starry_api_runtime;

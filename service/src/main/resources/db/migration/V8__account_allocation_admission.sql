CREATE TABLE account_allocation_policies (
    account_id VARCHAR(64) PRIMARY KEY
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    allocation_priority INTEGER NOT NULL DEFAULT 100,
    safe_concurrency INTEGER NOT NULL DEFAULT 4,
    safe_rpm INTEGER NOT NULL DEFAULT 60,
    burst_capacity INTEGER NOT NULL DEFAULT 4,
    max_sessions INTEGER NOT NULL DEFAULT 0,
    session_idle_seconds INTEGER NOT NULL DEFAULT 600,
    max_inflight_tokens BIGINT NOT NULL DEFAULT 1000000,
    policy_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_allocation_priority CHECK (allocation_priority BETWEEN 1 AND 10000),
    CONSTRAINT ck_account_allocation_concurrency CHECK (safe_concurrency BETWEEN 1 AND 10000),
    CONSTRAINT ck_account_allocation_rpm CHECK (safe_rpm BETWEEN 1 AND 1000000),
    CONSTRAINT ck_account_allocation_burst CHECK (burst_capacity BETWEEN 1 AND 100000),
    CONSTRAINT ck_account_allocation_sessions CHECK (max_sessions BETWEEN 0 AND 100000),
    CONSTRAINT ck_account_allocation_session_idle CHECK (session_idle_seconds BETWEEN 60 AND 86400),
    CONSTRAINT ck_account_allocation_inflight_tokens CHECK (max_inflight_tokens BETWEEN 1 AND 1000000000),
    CONSTRAINT ck_account_allocation_policy_version CHECK (policy_version > 0)
) WITH (
    fillfactor = 90,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

CREATE TABLE account_allocation_states (
    account_id VARCHAR(64) PRIMARY KEY
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'WARMING',
    state_version BIGINT NOT NULL DEFAULT 1,
    warmup_stage SMALLINT NOT NULL DEFAULT 1,
    cooldown_until TIMESTAMPTZ(3),
    recent_error_ewma DOUBLE PRECISION NOT NULL DEFAULT 0,
    recent_latency_ewma_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    risk_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    state_reason VARCHAR(128),
    last_success_at TIMESTAMPTZ(3),
    last_failure_at TIMESTAMPTZ(3),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_allocation_lifecycle CHECK (
        lifecycle_state IN ('WARMING', 'HEALTHY', 'DEGRADED', 'COOLDOWN', 'QUARANTINED', 'DISABLED')
    ),
    CONSTRAINT ck_account_allocation_state_version CHECK (state_version > 0),
    CONSTRAINT ck_account_allocation_warmup_stage CHECK (warmup_stage BETWEEN 1 AND 4),
    CONSTRAINT ck_account_allocation_error_ewma CHECK (recent_error_ewma BETWEEN 0 AND 1),
    CONSTRAINT ck_account_allocation_latency_ewma CHECK (recent_latency_ewma_ms >= 0),
    CONSTRAINT ck_account_allocation_risk_score CHECK (risk_score >= 0)
) WITH (
    fillfactor = 80,
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_analyze_scale_factor = 0.01
);

CREATE INDEX idx_account_allocation_states_lifecycle
    ON account_allocation_states (lifecycle_state, cooldown_until, account_id);

CREATE TABLE account_admission_states (
    account_id VARCHAR(64) PRIMARY KEY
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    state_version BIGINT NOT NULL DEFAULT 1,
    rpm_tokens DOUBLE PRECISION NOT NULL DEFAULT 0,
    rpm_refilled_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    burst_tokens DOUBLE PRECISION NOT NULL DEFAULT 0,
    burst_refilled_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_concurrency INTEGER NOT NULL DEFAULT 0,
    reserved_input_tokens BIGINT NOT NULL DEFAULT 0,
    reserved_output_tokens BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_admission_state_version CHECK (state_version > 0),
    CONSTRAINT ck_account_admission_rpm_tokens CHECK (rpm_tokens >= 0),
    CONSTRAINT ck_account_admission_burst_tokens CHECK (burst_tokens >= 0),
    CONSTRAINT ck_account_admission_concurrency CHECK (active_concurrency >= 0),
    CONSTRAINT ck_account_admission_input_tokens CHECK (reserved_input_tokens >= 0),
    CONSTRAINT ck_account_admission_output_tokens CHECK (reserved_output_tokens >= 0)
) WITH (
    fillfactor = 70,
    autovacuum_vacuum_scale_factor = 0.005,
    autovacuum_vacuum_threshold = 20,
    autovacuum_analyze_scale_factor = 0.005,
    autovacuum_analyze_threshold = 20
);

CREATE TABLE account_admission_leases (
    lease_id UUID PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    state_version BIGINT NOT NULL,
    reserved_input_tokens BIGINT NOT NULL DEFAULT 0,
    reserved_output_tokens BIGINT NOT NULL DEFAULT 0,
    dispatch_state VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
    dispatched_at TIMESTAMPTZ(3),
    completed_at TIMESTAMPTZ(3),
    expires_at TIMESTAMPTZ(3) NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_admission_lease_request CHECK (btrim(request_id) <> ''),
    CONSTRAINT ck_account_admission_lease_state CHECK (
        dispatch_state IN ('RESERVED', 'DISPATCHED', 'COMPLETED', 'RELEASED', 'EXPIRED')
    ),
    CONSTRAINT ck_account_admission_lease_input CHECK (reserved_input_tokens >= 0),
    CONSTRAINT ck_account_admission_lease_output CHECK (reserved_output_tokens >= 0),
    CONSTRAINT ck_account_admission_lease_expiry CHECK (expires_at > created_at)
) WITH (
    fillfactor = 85,
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_analyze_scale_factor = 0.01
);

CREATE INDEX idx_account_admission_leases_account_active
    ON account_admission_leases (account_id, expires_at)
    WHERE dispatch_state IN ('RESERVED', 'DISPATCHED');
CREATE INDEX idx_account_admission_leases_request
    ON account_admission_leases (request_id, created_at DESC);
CREATE INDEX idx_account_admission_leases_expiry
    ON account_admission_leases (expires_at)
    WHERE dispatch_state IN ('RESERVED', 'DISPATCHED');

CREATE TABLE account_active_sessions (
    account_id VARCHAR(64) NOT NULL
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    session_hash CHAR(64) NOT NULL,
    last_seen_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ(3) NOT NULL,
    PRIMARY KEY (account_id, session_hash),
    CONSTRAINT ck_account_active_session_hash CHECK (session_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_active_session_expiry CHECK (expires_at > last_seen_at)
) WITH (
    fillfactor = 85,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

CREATE INDEX idx_account_active_sessions_expiry
    ON account_active_sessions (account_id, expires_at);

CREATE TABLE account_allocation_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_id VARCHAR(64) REFERENCES proxy_accounts(account_id) ON DELETE SET NULL,
    request_id VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    error_scope VARCHAR(64),
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_allocation_event_type CHECK (btrim(event_type) <> ''),
    CONSTRAINT ck_account_allocation_event_evidence CHECK (jsonb_typeof(evidence_json) = 'object')
);

CREATE INDEX idx_account_allocation_events_account_created
    ON account_allocation_events (account_id, created_at DESC, id DESC);
CREATE INDEX idx_account_allocation_events_request
    ON account_allocation_events (request_id, created_at DESC)
    WHERE request_id IS NOT NULL;
CREATE INDEX idx_account_allocation_events_created_brin
    ON account_allocation_events USING BRIN (created_at);

CREATE TABLE openai_response_owners (
    response_id VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL
        REFERENCES proxy_accounts(account_id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ(3) NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_openai_response_owner_id CHECK (btrim(response_id) <> ''),
    CONSTRAINT ck_openai_response_owner_expiry CHECK (expires_at > created_at)
) WITH (
    fillfactor = 90,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

CREATE INDEX idx_openai_response_owners_expiry
    ON openai_response_owners (expires_at);

CREATE OR REPLACE FUNCTION starry_api.initialize_account_allocation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    normalized_provider TEXT := pg_catalog.lower(COALESCE(NEW.provider, ''));
    normalized_auth TEXT := pg_catalog.lower(COALESCE(NEW.auth_method, ''));
    initial_concurrency INTEGER;
    initial_rpm INTEGER;
    initial_burst INTEGER;
    initial_sessions INTEGER;
BEGIN
    IF normalized_provider IN ('anthropic', 'claude') THEN
        initial_concurrency := 2;
        initial_rpm := 20;
        initial_burst := 2;
        initial_sessions := 4;
    ELSIF normalized_provider IN ('openai', 'openai-compatible', 'deepseek', 'codex')
        OR normalized_auth IN ('api-key', 'apikey', 'openai-api-key', 'codex') THEN
        initial_concurrency := 4;
        initial_rpm := 60;
        initial_burst := 4;
        initial_sessions := 0;
    ELSE
        initial_concurrency := 2;
        initial_rpm := 30;
        initial_burst := 2;
        initial_sessions := 4;
    END IF;

    INSERT INTO starry_api.account_allocation_policies (
        account_id, safe_concurrency, safe_rpm, burst_capacity, max_sessions
    ) VALUES (
        NEW.account_id, initial_concurrency, initial_rpm, initial_burst, initial_sessions
    ) ON CONFLICT (account_id) DO NOTHING;

    INSERT INTO starry_api.account_allocation_states (
        account_id, lifecycle_state, state_reason
    ) VALUES (
        NEW.account_id,
        CASE WHEN COALESCE(NEW.enabled, FALSE) THEN 'WARMING' ELSE 'DISABLED' END,
        CASE WHEN COALESCE(NEW.enabled, FALSE) THEN 'ACCOUNT_CREATED' ELSE 'ADMIN_DISABLED' END
    ) ON CONFLICT (account_id) DO NOTHING;

    INSERT INTO starry_api.account_admission_states (
        account_id, rpm_tokens, burst_tokens
    ) VALUES (
        NEW.account_id, initial_rpm, initial_burst
    ) ON CONFLICT (account_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.sync_account_allocation_enabled_state()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.enabled IS NOT DISTINCT FROM OLD.enabled THEN
        RETURN NEW;
    END IF;

    UPDATE starry_api.account_allocation_states
    SET lifecycle_state = CASE WHEN NEW.enabled THEN 'WARMING' ELSE 'DISABLED' END,
        warmup_stage = 1,
        cooldown_until = NULL,
        state_reason = CASE WHEN NEW.enabled THEN 'ADMIN_RESTORED' ELSE 'ADMIN_DISABLED' END,
        state_version = state_version + 1,
        updated_at = pg_catalog.clock_timestamp()
    WHERE account_id = NEW.account_id;
    RETURN NEW;
END;
$$;

INSERT INTO account_allocation_policies (
    account_id,
    safe_concurrency,
    safe_rpm,
    burst_capacity,
    max_sessions
)
SELECT
    account_id,
    CASE
        WHEN lower(coalesce(provider, '')) IN ('anthropic', 'claude') THEN 2
        WHEN lower(coalesce(provider, '')) IN ('openai', 'openai-compatible', 'deepseek', 'codex')
            OR lower(coalesce(auth_method, '')) IN ('api-key', 'apikey', 'openai-api-key', 'codex') THEN 4
        ELSE 2
    END,
    CASE
        WHEN lower(coalesce(provider, '')) IN ('anthropic', 'claude') THEN 20
        WHEN lower(coalesce(provider, '')) IN ('openai', 'openai-compatible', 'deepseek', 'codex')
            OR lower(coalesce(auth_method, '')) IN ('api-key', 'apikey', 'openai-api-key', 'codex') THEN 60
        ELSE 30
    END,
    CASE
        WHEN lower(coalesce(provider, '')) IN ('anthropic', 'claude') THEN 2
        WHEN lower(coalesce(provider, '')) IN ('openai', 'openai-compatible', 'deepseek', 'codex')
            OR lower(coalesce(auth_method, '')) IN ('api-key', 'apikey', 'openai-api-key', 'codex') THEN 4
        ELSE 2
    END,
    CASE
        WHEN lower(coalesce(provider, '')) IN ('openai', 'openai-compatible', 'deepseek', 'codex')
            OR lower(coalesce(auth_method, '')) IN ('api-key', 'apikey', 'openai-api-key', 'codex') THEN 0
        ELSE 4
    END
FROM proxy_accounts
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO account_allocation_states (account_id, lifecycle_state, warmup_stage, state_reason)
SELECT
    account_id,
    CASE WHEN coalesce(enabled, FALSE) AND suspended_at IS NULL THEN 'HEALTHY' ELSE 'DISABLED' END,
    CASE WHEN coalesce(enabled, FALSE) AND suspended_at IS NULL THEN 4 ELSE 1 END,
    'V8_MIGRATION'
FROM proxy_accounts
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO account_admission_states (account_id, rpm_tokens, burst_tokens)
SELECT account_id, safe_rpm, burst_capacity
FROM account_allocation_policies
ON CONFLICT (account_id) DO NOTHING;

CREATE TRIGGER trg_proxy_accounts_allocation_initialize
    AFTER INSERT ON proxy_accounts
    FOR EACH ROW EXECUTE FUNCTION starry_api.initialize_account_allocation();

CREATE TRIGGER trg_proxy_accounts_allocation_enabled
    AFTER UPDATE OF enabled ON proxy_accounts
    FOR EACH ROW EXECUTE FUNCTION starry_api.sync_account_allocation_enabled_state();

CREATE OR REPLACE FUNCTION starry_api.try_acquire_account_lease(
    p_account_id VARCHAR,
    p_request_id VARCHAR,
    p_expected_state_version BIGINT,
    p_session_hash VARCHAR,
    p_enforce_session_limit BOOLEAN,
    p_reserved_input_tokens BIGINT,
    p_reserved_output_tokens BIGINT,
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
    policy_row starry_api.account_allocation_policies%ROWTYPE;
    lifecycle_row starry_api.account_allocation_states%ROWTYPE;
    admission_row starry_api.account_admission_states%ROWTYPE;
    account_enabled BOOLEAN;
    account_suspended_at TIMESTAMPTZ;
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
    next_lease_id UUID := pg_catalog.gen_random_uuid();
    lease_expires_at TIMESTAMPTZ;
    elapsed_rpm DOUBLE PRECISION;
    elapsed_burst DOUBLE PRECISION;
    effective_rpm INTEGER;
    effective_burst INTEGER;
    effective_concurrency INTEGER;
    refilled_rpm DOUBLE PRECISION;
    refilled_burst DOUBLE PRECISION;
    active_session_count BIGINT;
    existing_session BOOLEAN;
    expired_concurrency BIGINT := 0;
    expired_input BIGINT := 0;
    expired_output BIGINT := 0;
    requested_input BIGINT := GREATEST(COALESCE(p_reserved_input_tokens, 0), 0);
    requested_output BIGINT := GREATEST(COALESCE(p_reserved_output_tokens, 0), 0);
BEGIN
    IF p_account_id IS NULL OR pg_catalog.btrim(p_account_id) = ''
        OR p_request_id IS NULL OR pg_catalog.btrim(p_request_id) = '' THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'INVALID_REQUEST'::VARCHAR, NULL::BIGINT, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;
    IF p_lease_seconds < 30 OR p_lease_seconds > 3600 THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'INVALID_LEASE_TTL'::VARCHAR, NULL::BIGINT, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    SELECT enabled, suspended_at
    INTO account_enabled, account_suspended_at
    FROM starry_api.proxy_accounts
    WHERE account_id = p_account_id;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'ACCOUNT_NOT_FOUND'::VARCHAR, NULL::BIGINT, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    SELECT * INTO admission_row
    FROM starry_api.account_admission_states
    WHERE account_id = p_account_id
    FOR UPDATE;
    SELECT * INTO policy_row
    FROM starry_api.account_allocation_policies
    WHERE account_id = p_account_id;
    SELECT * INTO lifecycle_row
    FROM starry_api.account_allocation_states
    WHERE account_id = p_account_id
    FOR UPDATE;

    IF admission_row.account_id IS NULL OR policy_row.account_id IS NULL OR lifecycle_row.account_id IS NULL THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'STATE_NOT_INITIALIZED'::VARCHAR, NULL::BIGINT, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    WITH expired AS (
        UPDATE starry_api.account_admission_leases AS expired_lease
        SET dispatch_state = 'EXPIRED', completed_at = now_at
        WHERE expired_lease.account_id = p_account_id
          AND expired_lease.dispatch_state IN ('RESERVED', 'DISPATCHED')
          AND expired_lease.expires_at <= now_at
        RETURNING expired_lease.reserved_input_tokens, expired_lease.reserved_output_tokens
    )
    SELECT count(*), coalesce(sum(reserved_input_tokens), 0), coalesce(sum(reserved_output_tokens), 0)
    INTO expired_concurrency, expired_input, expired_output
    FROM expired;

    IF expired_concurrency > 0 THEN
        admission_row.active_concurrency := GREATEST(
            admission_row.active_concurrency - expired_concurrency::INTEGER,
            0
        );
        admission_row.reserved_input_tokens := GREATEST(
            admission_row.reserved_input_tokens - expired_input,
            0
        );
        admission_row.reserved_output_tokens := GREATEST(
            admission_row.reserved_output_tokens - expired_output,
            0
        );
    END IF;

    DELETE FROM starry_api.account_active_sessions AS expired_session
    WHERE expired_session.account_id = p_account_id AND expired_session.expires_at <= now_at;

    IF lifecycle_row.lifecycle_state IN ('COOLDOWN', 'QUARANTINED')
        AND lifecycle_row.cooldown_until IS NOT NULL
        AND lifecycle_row.cooldown_until <= now_at THEN
        UPDATE starry_api.account_allocation_states AS lifecycle
        SET lifecycle_state = 'WARMING',
            warmup_stage = 1,
            cooldown_until = NULL,
            state_reason = 'COOLDOWN_EXPIRED',
            state_version = lifecycle.state_version + 1,
            updated_at = now_at
        WHERE lifecycle.account_id = p_account_id
        RETURNING * INTO lifecycle_row;
    END IF;

    IF NOT COALESCE(account_enabled, FALSE) OR account_suspended_at IS NOT NULL THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'ACCOUNT_DISABLED'::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;
    IF lifecycle_row.lifecycle_state NOT IN ('WARMING', 'HEALTHY', 'DEGRADED') THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, ('LIFECYCLE_' || lifecycle_row.lifecycle_state)::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;
    IF p_expected_state_version IS NOT NULL AND p_expected_state_version <> lifecycle_row.state_version THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'STALE_STATE'::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF lifecycle_row.lifecycle_state = 'WARMING' THEN
        effective_concurrency := GREATEST(1, pg_catalog.ceil(
            policy_row.safe_concurrency * (ARRAY[0.10, 0.25, 0.50, 1.00])[lifecycle_row.warmup_stage]
        )::INTEGER);
        effective_rpm := GREATEST(1, pg_catalog.ceil(
            policy_row.safe_rpm * (ARRAY[0.10, 0.25, 0.50, 1.00])[lifecycle_row.warmup_stage]
        )::INTEGER);
        effective_burst := GREATEST(1, pg_catalog.ceil(
            policy_row.burst_capacity * (ARRAY[0.10, 0.25, 0.50, 1.00])[lifecycle_row.warmup_stage]
        )::INTEGER);
    ELSE
        effective_concurrency := policy_row.safe_concurrency;
        effective_rpm := policy_row.safe_rpm;
        effective_burst := policy_row.burst_capacity;
    END IF;

    elapsed_rpm := GREATEST(
        EXTRACT(EPOCH FROM (now_at - admission_row.rpm_refilled_at)),
        0
    );
    elapsed_burst := GREATEST(
        EXTRACT(EPOCH FROM (now_at - admission_row.burst_refilled_at)),
        0
    );
    refilled_rpm := LEAST(
        effective_rpm::DOUBLE PRECISION,
        admission_row.rpm_tokens + elapsed_rpm * effective_rpm / 60.0
    );
    refilled_burst := LEAST(
        effective_burst::DOUBLE PRECISION,
        admission_row.burst_tokens + elapsed_burst * effective_rpm / 60.0
    );

    IF admission_row.active_concurrency >= effective_concurrency THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'CONCURRENCY_LIMIT'::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;
    IF refilled_rpm < 1 THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'RPM_LIMIT'::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;
    IF refilled_burst < 1 THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'BURST_LIMIT'::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;
    IF admission_row.reserved_input_tokens + admission_row.reserved_output_tokens
        + requested_input + requested_output > policy_row.max_inflight_tokens THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, 'INFLIGHT_TOKEN_LIMIT'::VARCHAR,
            lifecycle_row.state_version, NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF COALESCE(p_enforce_session_limit, FALSE)
        AND policy_row.max_sessions > 0
        AND p_session_hash IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM starry_api.account_active_sessions AS active_session
            WHERE active_session.account_id = p_account_id AND active_session.session_hash = p_session_hash
        ) INTO existing_session;
        IF NOT existing_session THEN
            SELECT count(*) INTO active_session_count
            FROM starry_api.account_active_sessions AS active_session
            WHERE active_session.account_id = p_account_id AND active_session.expires_at > now_at;
            IF active_session_count >= policy_row.max_sessions THEN
                RETURN QUERY SELECT FALSE, NULL::UUID, 'SESSION_LIMIT'::VARCHAR,
                    lifecycle_row.state_version, NULL::TIMESTAMPTZ;
                RETURN;
            END IF;
        END IF;
        INSERT INTO starry_api.account_active_sessions (account_id, session_hash, last_seen_at, expires_at)
        VALUES (
            p_account_id,
            p_session_hash,
            now_at,
            now_at + pg_catalog.make_interval(secs => policy_row.session_idle_seconds)
        )
        ON CONFLICT (account_id, session_hash) DO UPDATE
        SET last_seen_at = EXCLUDED.last_seen_at,
            expires_at = EXCLUDED.expires_at;
    END IF;

    lease_expires_at := now_at + pg_catalog.make_interval(secs => p_lease_seconds);
    UPDATE starry_api.account_admission_states AS admission
    SET state_version = admission.state_version + 1,
        rpm_tokens = refilled_rpm - 1,
        rpm_refilled_at = now_at,
        burst_tokens = refilled_burst - 1,
        burst_refilled_at = now_at,
        active_concurrency = admission_row.active_concurrency + 1,
        reserved_input_tokens = admission_row.reserved_input_tokens + requested_input,
        reserved_output_tokens = admission_row.reserved_output_tokens + requested_output,
        updated_at = now_at
    WHERE admission.account_id = p_account_id;

    INSERT INTO starry_api.account_admission_leases (
        lease_id, request_id, account_id, state_version,
        reserved_input_tokens, reserved_output_tokens, expires_at
    ) VALUES (
        next_lease_id, p_request_id, p_account_id, lifecycle_row.state_version,
        requested_input, requested_output, lease_expires_at
    );
    INSERT INTO starry_api.account_allocation_events (
        account_id, request_id, event_type, evidence_json
    ) VALUES (
        p_account_id,
        p_request_id,
        'ADMISSION_ACQUIRED',
        pg_catalog.jsonb_build_object(
            'leaseId', next_lease_id,
            'lifecycle', lifecycle_row.lifecycle_state,
            'reservedInputTokens', requested_input,
            'reservedOutputTokens', requested_output
        )
    );

    RETURN QUERY SELECT TRUE, next_lease_id, NULL::VARCHAR,
        lifecycle_row.state_version, lease_expires_at;
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.mark_account_lease_dispatched(p_lease_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    UPDATE starry_api.account_admission_leases
    SET dispatch_state = 'DISPATCHED', dispatched_at = pg_catalog.clock_timestamp()
    WHERE lease_id = p_lease_id
      AND dispatch_state = 'RESERVED'
      AND expires_at > pg_catalog.clock_timestamp();
    RETURN FOUND;
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.reconcile_account_lease(
    p_lease_id UUID,
    p_success BOOLEAN,
    p_actual_input_tokens BIGINT,
    p_actual_output_tokens BIGINT,
    p_latency_ms BIGINT,
    p_status_code INTEGER,
    p_error_scope VARCHAR,
    p_cooldown_seconds INTEGER,
    p_permanent_credential_failure BOOLEAN,
    p_evidence JSONB DEFAULT '{}'::jsonb
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    lease_row starry_api.account_admission_leases%ROWTYPE;
    lifecycle_row starry_api.account_allocation_states%ROWTYPE;
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
    normalized_scope TEXT := pg_catalog.upper(COALESCE(p_error_scope, 'UNKNOWN'));
    cooldown_seconds INTEGER := GREATEST(COALESCE(p_cooldown_seconds, 45), 1);
    next_error_ewma DOUBLE PRECISION;
    next_latency_ewma DOUBLE PRECISION;
    next_lifecycle VARCHAR(32);
    next_reason VARCHAR(128);
    next_cooldown TIMESTAMPTZ;
    next_warmup SMALLINT;
BEGIN
    SELECT * INTO lease_row
    FROM starry_api.account_admission_leases
    WHERE lease_id = p_lease_id
    FOR UPDATE;
    IF NOT FOUND OR lease_row.dispatch_state IN ('COMPLETED', 'RELEASED', 'EXPIRED') THEN
        RETURN FALSE;
    END IF;

    PERFORM 1 FROM starry_api.account_admission_states
    WHERE account_id = lease_row.account_id FOR UPDATE;
    SELECT * INTO lifecycle_row
    FROM starry_api.account_allocation_states
    WHERE account_id = lease_row.account_id
    FOR UPDATE;

    UPDATE starry_api.account_admission_states
    SET state_version = state_version + 1,
        active_concurrency = GREATEST(active_concurrency - 1, 0),
        reserved_input_tokens = GREATEST(
            reserved_input_tokens - lease_row.reserved_input_tokens,
            0
        ),
        reserved_output_tokens = GREATEST(
            reserved_output_tokens - lease_row.reserved_output_tokens,
            0
        ),
        updated_at = now_at
    WHERE account_id = lease_row.account_id;

    next_error_ewma := LEAST(
        1.0,
        GREATEST(0.0, lifecycle_row.recent_error_ewma * 0.8 + CASE WHEN p_success THEN 0.0 ELSE 0.2 END)
    );
    next_latency_ewma := CASE
        WHEN p_latency_ms IS NULL OR p_latency_ms < 0 THEN lifecycle_row.recent_latency_ewma_ms
        WHEN lifecycle_row.recent_latency_ewma_ms <= 0 THEN p_latency_ms::DOUBLE PRECISION
        ELSE lifecycle_row.recent_latency_ewma_ms * 0.8 + p_latency_ms * 0.2
    END;
    next_lifecycle := lifecycle_row.lifecycle_state;
    next_reason := lifecycle_row.state_reason;
    next_cooldown := lifecycle_row.cooldown_until;
    next_warmup := lifecycle_row.warmup_stage;

    IF p_success THEN
        IF lifecycle_row.lifecycle_state = 'WARMING' AND lifecycle_row.warmup_stage < 4 THEN
            next_warmup := lifecycle_row.warmup_stage + 1;
        END IF;
        IF lifecycle_row.lifecycle_state IN ('WARMING', 'DEGRADED') AND next_warmup >= 4 THEN
            next_lifecycle := 'HEALTHY';
            next_reason := 'WARMUP_COMPLETED';
        END IF;
        next_cooldown := NULL;
    ELSIF normalized_scope = 'REQUEST' THEN
        NULL;
    ELSIF COALESCE(p_permanent_credential_failure, FALSE) THEN
        next_lifecycle := 'DISABLED';
        next_reason := 'CREDENTIAL_REVOKED';
        next_cooldown := NULL;
        UPDATE starry_api.proxy_accounts
        SET enabled = FALSE,
            suspended_at = now_at,
            suspend_reason = 'AUTH_FAILED',
            suspend_message = pg_catalog.left(COALESCE(p_evidence ->> 'message', 'Credential rejected'), 2000),
            updated_at = now_at
        WHERE account_id = lease_row.account_id;
    ELSIF normalized_scope IN ('CAPACITY_ACCOUNT', 'PROVIDER_POOL') THEN
        next_lifecycle := 'COOLDOWN';
        next_reason := normalized_scope;
        next_cooldown := now_at + pg_catalog.make_interval(secs => cooldown_seconds);
        next_warmup := 1;
    ELSIF normalized_scope IN ('CAPACITY_MODEL', 'CAPACITY_ENDPOINT') THEN
        next_lifecycle := 'DEGRADED';
        next_reason := normalized_scope;
        next_cooldown := NULL;
    ELSIF normalized_scope IN ('PROXY_EDGE', 'REGIONAL_POLICY', 'UNKNOWN', 'CREDENTIAL') THEN
        next_lifecycle := 'QUARANTINED';
        next_reason := normalized_scope;
        next_cooldown := now_at + pg_catalog.make_interval(secs => cooldown_seconds);
        next_warmup := 1;
    ELSIF normalized_scope = 'TRANSPORT' THEN
        next_lifecycle := CASE WHEN next_error_ewma >= 0.50 THEN 'COOLDOWN' ELSE 'DEGRADED' END;
        next_reason := 'TRANSPORT';
        next_cooldown := CASE
            WHEN next_error_ewma >= 0.50 THEN now_at + pg_catalog.make_interval(secs => cooldown_seconds)
            ELSE NULL
        END;
        next_warmup := CASE WHEN next_error_ewma >= 0.50 THEN 1 ELSE next_warmup END;
    ELSE
        next_lifecycle := 'DEGRADED';
        next_reason := normalized_scope;
    END IF;

    UPDATE starry_api.account_allocation_states
    SET lifecycle_state = next_lifecycle,
        state_version = state_version + 1,
        warmup_stage = next_warmup,
        cooldown_until = next_cooldown,
        recent_error_ewma = next_error_ewma,
        recent_latency_ewma_ms = next_latency_ewma,
        risk_score = GREATEST(
            0.0,
            next_error_ewma * 100.0 + LEAST(next_latency_ewma / 1000.0, 100.0)
        ),
        state_reason = next_reason,
        last_success_at = CASE WHEN p_success THEN now_at ELSE last_success_at END,
        last_failure_at = CASE WHEN p_success THEN last_failure_at ELSE now_at END,
        updated_at = now_at
    WHERE account_id = lease_row.account_id;

    UPDATE starry_api.account_admission_leases
    SET dispatch_state = 'COMPLETED', completed_at = now_at
    WHERE lease_id = p_lease_id;

    INSERT INTO starry_api.account_allocation_events (
        account_id, request_id, event_type, error_scope, evidence_json
    ) VALUES (
        lease_row.account_id,
        lease_row.request_id,
        CASE WHEN p_success THEN 'UPSTREAM_SUCCESS' ELSE 'UPSTREAM_FAILURE' END,
        CASE WHEN p_success THEN NULL ELSE normalized_scope END,
        COALESCE(p_evidence, '{}'::jsonb)
            || pg_catalog.jsonb_build_object(
                'leaseId', p_lease_id,
                'statusCode', p_status_code,
                'actualInputTokens', GREATEST(COALESCE(p_actual_input_tokens, 0), 0),
                'actualOutputTokens', GREATEST(COALESCE(p_actual_output_tokens, 0), 0),
                'latencyMs', GREATEST(COALESCE(p_latency_ms, 0), 0)
            )
    );
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.release_unsent_account_lease(p_lease_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    lease_row starry_api.account_admission_leases%ROWTYPE;
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
BEGIN
    SELECT * INTO lease_row
    FROM starry_api.account_admission_leases
    WHERE lease_id = p_lease_id
    FOR UPDATE;
    IF NOT FOUND OR lease_row.dispatch_state <> 'RESERVED' THEN
        RETURN FALSE;
    END IF;

    PERFORM 1 FROM starry_api.account_admission_states
    WHERE account_id = lease_row.account_id FOR UPDATE;
    UPDATE starry_api.account_admission_states
    SET state_version = state_version + 1,
        active_concurrency = GREATEST(active_concurrency - 1, 0),
        reserved_input_tokens = GREATEST(
            reserved_input_tokens - lease_row.reserved_input_tokens,
            0
        ),
        reserved_output_tokens = GREATEST(
            reserved_output_tokens - lease_row.reserved_output_tokens,
            0
        ),
        updated_at = now_at
    WHERE account_id = lease_row.account_id;
    UPDATE starry_api.account_admission_leases
    SET dispatch_state = 'RELEASED', completed_at = now_at
    WHERE lease_id = p_lease_id;
    RETURN TRUE;
END;
$$;

CREATE TRIGGER trg_account_allocation_policies_updated_at
    BEFORE UPDATE ON account_allocation_policies
    FOR EACH ROW EXECUTE FUNCTION starry_api.set_updated_at();

CREATE TRIGGER trg_openai_response_owners_updated_at
    BEFORE UPDATE ON openai_response_owners
    FOR EACH ROW EXECUTE FUNCTION starry_api.set_updated_at();

REVOKE ALL ON TABLE
    account_allocation_policies,
    account_allocation_states,
    account_admission_states,
    account_admission_leases,
    account_active_sessions,
    account_allocation_events,
    openai_response_owners
FROM PUBLIC;

GRANT SELECT, INSERT ON TABLE
    account_allocation_policies,
    account_allocation_states,
    account_admission_states,
    account_admission_leases,
    account_active_sessions,
    account_allocation_events,
    openai_response_owners
TO starry_api_runtime;

GRANT UPDATE ON TABLE
    account_allocation_policies,
    account_allocation_states,
    account_admission_states,
    account_admission_leases,
    account_active_sessions,
    openai_response_owners
TO starry_api_runtime;

GRANT DELETE ON TABLE
    account_admission_leases,
    account_active_sessions,
    account_allocation_events,
    openai_response_owners
TO starry_api_runtime;

GRANT USAGE, SELECT ON SEQUENCE account_allocation_events_id_seq TO starry_api_runtime;

REVOKE ALL ON FUNCTION starry_api.initialize_account_allocation() FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.sync_account_allocation_enabled_state() FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.try_acquire_account_lease(VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION starry_api.mark_account_lease_dispatched(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION starry_api.reconcile_account_lease(UUID, BOOLEAN, BIGINT, BIGINT, BIGINT, INTEGER, VARCHAR, INTEGER, BOOLEAN, JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION starry_api.release_unsent_account_lease(UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION starry_api.try_acquire_account_lease(VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, INTEGER)
    TO starry_api_runtime;
GRANT EXECUTE ON FUNCTION starry_api.mark_account_lease_dispatched(UUID) TO starry_api_runtime;
GRANT EXECUTE ON FUNCTION starry_api.reconcile_account_lease(UUID, BOOLEAN, BIGINT, BIGINT, BIGINT, INTEGER, VARCHAR, INTEGER, BOOLEAN, JSONB)
    TO starry_api_runtime;
GRANT EXECUTE ON FUNCTION starry_api.release_unsent_account_lease(UUID) TO starry_api_runtime;

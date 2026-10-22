ALTER TABLE proxy_channels
    ADD COLUMN IF NOT EXISTS source_present BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS config_issue VARCHAR(512);

CREATE UNIQUE INDEX IF NOT EXISTS idx_proxy_channels_source_fingerprint
    ON proxy_channels (subscription_id, config_fingerprint) WHERE subscription_id IS NOT NULL;

ALTER TABLE account_allocation_policies
    DROP CONSTRAINT ck_account_allocation_concurrency,
    DROP CONSTRAINT ck_account_allocation_rpm,
    DROP CONSTRAINT ck_account_allocation_burst,
    ADD CONSTRAINT ck_account_allocation_concurrency CHECK (safe_concurrency BETWEEN 0 AND 10000),
    ADD CONSTRAINT ck_account_allocation_rpm CHECK (safe_rpm BETWEEN 0 AND 1000000),
    ADD CONSTRAINT ck_account_allocation_burst CHECK (burst_capacity BETWEEN 0 AND 100000),
    ADD COLUMN cooldown_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN tls_profile VARCHAR(32) NOT NULL DEFAULT '',
    ADD CONSTRAINT ck_account_allocation_cooldown CHECK (cooldown_seconds BETWEEN 0 AND 86400),
    ADD CONSTRAINT ck_account_allocation_tls_profile CHECK (tls_profile IN ('', 'chrome136', 'safari18_4'));

-- New official API accounts start conservatively; non-official API accounts are not concurrency-capped.
CREATE OR REPLACE FUNCTION starry_api.initialize_account_allocation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    normalized_provider TEXT := pg_catalog.lower(COALESCE(NEW.provider, ''));
    normalized_auth TEXT := pg_catalog.lower(COALESCE(NEW.auth_method, ''));
    api_account BOOLEAN;
    official_account BOOLEAN;
    initial_concurrency INTEGER;
    initial_rpm INTEGER;
    initial_burst INTEGER;
    initial_sessions INTEGER;
BEGIN
    api_account := normalized_auth IN ('api-key', 'apikey', 'openai-api-key');
    official_account := api_account AND ((normalized_provider = 'openai' AND
        pg_catalog.lower(COALESCE(NULLIF(pg_catalog.btrim(NEW.base_url),''), 'https://api.openai.com/v1')) ~ '^https://api[.]openai[.]com(:443)?(/[^?#]*)?$')
        OR (normalized_provider IN ('anthropic', 'claude') AND
        pg_catalog.lower(COALESCE(NULLIF(pg_catalog.btrim(NEW.base_url),''), 'https://api.anthropic.com/v1')) ~ '^https://api[.]anthropic[.]com(:443)?(/[^?#]*)?$'));
    IF api_account AND NOT official_account THEN
        initial_concurrency := 0; initial_rpm := 0; initial_burst := 0; initial_sessions := 0;
    ELSIF official_account THEN
        initial_concurrency := 14; initial_rpm := 0; initial_burst := 0; initial_sessions := 0;
    ELSIF normalized_provider='codex' OR normalized_auth='codex' THEN
        initial_concurrency := 4; initial_rpm := 60; initial_burst := 4; initial_sessions := 0;
    ELSE
        initial_concurrency := 2; initial_rpm := 30; initial_burst := 2;
        initial_sessions := CASE WHEN normalized_provider IN ('anthropic','claude') THEN 4 ELSE 0 END;
    END IF;

    INSERT INTO starry_api.account_allocation_policies(account_id,safe_concurrency,safe_rpm,burst_capacity,max_sessions)
    VALUES(NEW.account_id,initial_concurrency,initial_rpm,initial_burst,initial_sessions)
    ON CONFLICT(account_id) DO NOTHING;
    INSERT INTO starry_api.account_allocation_states(account_id,lifecycle_state,warmup_stage,state_reason)
    VALUES(NEW.account_id,CASE WHEN NOT COALESCE(NEW.enabled,FALSE) THEN 'DISABLED' WHEN api_account THEN 'HEALTHY' ELSE 'WARMING' END,
        CASE WHEN api_account THEN 4 ELSE 1 END,
        CASE WHEN COALESCE(NEW.enabled,FALSE) THEN 'ACCOUNT_CREATED' ELSE 'ADMIN_DISABLED' END)
    ON CONFLICT(account_id) DO NOTHING;
    INSERT INTO starry_api.account_admission_states(account_id,rpm_tokens,burst_tokens)
    VALUES(NEW.account_id,initial_rpm,initial_burst) ON CONFLICT(account_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.try_acquire_account_lease_v8(
    p_account_id VARCHAR, p_request_id VARCHAR, p_expected_state_version BIGINT,
    p_session_hash VARCHAR, p_enforce_session_limit BOOLEAN,
    p_reserved_input_tokens BIGINT, p_reserved_output_tokens BIGINT,
    p_lease_seconds INTEGER DEFAULT 600
)
RETURNS TABLE(accepted BOOLEAN, lease_id UUID, rejection_reason VARCHAR, state_version BIGINT, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp SET lock_timeout = '2s'
AS $$
DECLARE
    policy_row starry_api.account_allocation_policies%ROWTYPE;
    lifecycle_row starry_api.account_allocation_states%ROWTYPE;
    admission_row starry_api.account_admission_states%ROWTYPE;
    account_enabled BOOLEAN;
    account_suspended_at TIMESTAMPTZ;
    is_api_account BOOLEAN;
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
    IF p_account_id IS NULL OR pg_catalog.btrim(p_account_id) = '' OR p_request_id IS NULL OR pg_catalog.btrim(p_request_id) = '' THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'INVALID_REQUEST'::VARCHAR,NULL::BIGINT,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF p_lease_seconds < 30 OR p_lease_seconds > 3600 THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'INVALID_LEASE_TTL'::VARCHAR,NULL::BIGINT,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    SELECT enabled,suspended_at,
        lower(COALESCE(auth_method,'')) IN ('api-key','apikey','openai-api-key')
        INTO account_enabled,account_suspended_at,is_api_account FROM starry_api.proxy_accounts WHERE account_id=p_account_id;
    IF NOT FOUND THEN RETURN QUERY SELECT FALSE,NULL::UUID,'ACCOUNT_NOT_FOUND'::VARCHAR,NULL::BIGINT,NULL::TIMESTAMPTZ; RETURN; END IF;
    SELECT * INTO admission_row FROM starry_api.account_admission_states WHERE account_id=p_account_id FOR UPDATE;
    SELECT * INTO policy_row FROM starry_api.account_allocation_policies WHERE account_id=p_account_id;
    SELECT * INTO lifecycle_row FROM starry_api.account_allocation_states WHERE account_id=p_account_id FOR UPDATE;
    IF admission_row.account_id IS NULL OR policy_row.account_id IS NULL OR lifecycle_row.account_id IS NULL THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'STATE_NOT_INITIALIZED'::VARCHAR,NULL::BIGINT,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    WITH expired AS (
        UPDATE starry_api.account_admission_leases l SET dispatch_state='EXPIRED',completed_at=now_at
        WHERE l.account_id=p_account_id AND l.dispatch_state IN ('RESERVED','DISPATCHED') AND l.expires_at<=now_at
        RETURNING l.reserved_input_tokens,l.reserved_output_tokens
    ) SELECT count(*),COALESCE(sum(reserved_input_tokens),0),COALESCE(sum(reserved_output_tokens),0)
      INTO expired_concurrency,expired_input,expired_output FROM expired;
    IF expired_concurrency>0 THEN
        admission_row.active_concurrency:=GREATEST(admission_row.active_concurrency-expired_concurrency::INTEGER,0);
        admission_row.reserved_input_tokens:=GREATEST(admission_row.reserved_input_tokens-expired_input,0);
        admission_row.reserved_output_tokens:=GREATEST(admission_row.reserved_output_tokens-expired_output,0);
    END IF;
    DELETE FROM starry_api.account_active_sessions WHERE account_id=p_account_id
        AND last_seen_at<=now_at-pg_catalog.make_interval(secs=>policy_row.session_idle_seconds);
    IF lifecycle_row.lifecycle_state IN ('COOLDOWN','QUARANTINED') AND lifecycle_row.cooldown_until IS NOT NULL AND lifecycle_row.cooldown_until<=now_at THEN
        UPDATE starry_api.account_allocation_states s SET lifecycle_state='WARMING',warmup_stage=1,cooldown_until=NULL,
            state_reason='COOLDOWN_EXPIRED',state_version=s.state_version+1,updated_at=now_at WHERE account_id=p_account_id RETURNING * INTO lifecycle_row;
    END IF;
    IF NOT COALESCE(account_enabled,FALSE) OR account_suspended_at IS NOT NULL THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'ACCOUNT_DISABLED'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF lifecycle_row.lifecycle_state NOT IN ('WARMING','HEALTHY','DEGRADED') THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,('LIFECYCLE_'||lifecycle_row.lifecycle_state)::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF p_expected_state_version IS NOT NULL AND p_expected_state_version<>lifecycle_row.state_version THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'STALE_STATE'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF lifecycle_row.lifecycle_state='WARMING' AND NOT is_api_account AND policy_row.safe_concurrency>0 THEN
        effective_concurrency:=GREATEST(1,pg_catalog.ceil(policy_row.safe_concurrency*(ARRAY[0.10,0.25,0.50,1.00])[lifecycle_row.warmup_stage])::INTEGER);
    ELSE effective_concurrency:=policy_row.safe_concurrency; END IF;
    IF lifecycle_row.lifecycle_state='WARMING' AND NOT is_api_account AND policy_row.safe_rpm>0 THEN
        effective_rpm:=GREATEST(1,pg_catalog.ceil(policy_row.safe_rpm*(ARRAY[0.10,0.25,0.50,1.00])[lifecycle_row.warmup_stage])::INTEGER);
    ELSE effective_rpm:=policy_row.safe_rpm; END IF;
    IF lifecycle_row.lifecycle_state='WARMING' AND NOT is_api_account AND policy_row.burst_capacity>0 THEN
        effective_burst:=GREATEST(1,pg_catalog.ceil(policy_row.burst_capacity*(ARRAY[0.10,0.25,0.50,1.00])[lifecycle_row.warmup_stage])::INTEGER);
    ELSE effective_burst:=policy_row.burst_capacity; END IF;
    elapsed_rpm:=GREATEST(EXTRACT(EPOCH FROM(now_at-admission_row.rpm_refilled_at)),0);
    elapsed_burst:=GREATEST(EXTRACT(EPOCH FROM(now_at-admission_row.burst_refilled_at)),0);
    refilled_rpm:=CASE WHEN effective_rpm=0 THEN 1 ELSE LEAST(effective_rpm::DOUBLE PRECISION,admission_row.rpm_tokens+elapsed_rpm*effective_rpm/60.0) END;
    refilled_burst:=CASE WHEN effective_rpm=0 OR effective_burst=0 THEN 1 ELSE LEAST(effective_burst::DOUBLE PRECISION,admission_row.burst_tokens+elapsed_burst*effective_rpm/60.0) END;
    IF effective_concurrency>0 AND admission_row.active_concurrency>=effective_concurrency THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'CONCURRENCY_LIMIT'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF effective_rpm>0 AND refilled_rpm<1 THEN RETURN QUERY SELECT FALSE,NULL::UUID,'RPM_LIMIT'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN; END IF;
    IF effective_rpm>0 AND effective_burst>0 AND refilled_burst<1 THEN RETURN QUERY SELECT FALSE,NULL::UUID,'BURST_LIMIT'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN; END IF;
    IF admission_row.reserved_input_tokens+admission_row.reserved_output_tokens+requested_input+requested_output>policy_row.max_inflight_tokens THEN
        RETURN QUERY SELECT FALSE,NULL::UUID,'INFLIGHT_TOKEN_LIMIT'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF COALESCE(p_enforce_session_limit,FALSE) AND policy_row.max_sessions>0 THEN
        IF p_session_hash IS NULL OR pg_catalog.btrim(p_session_hash)='' THEN
            RETURN QUERY SELECT FALSE,NULL::UUID,'SESSION_ID_REQUIRED'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN;
        END IF;
        SELECT EXISTS(SELECT 1 FROM starry_api.account_active_sessions s WHERE s.account_id=p_account_id AND s.session_hash=p_session_hash) INTO existing_session;
        IF NOT existing_session THEN
            SELECT count(*) INTO active_session_count FROM starry_api.account_active_sessions s WHERE s.account_id=p_account_id;
            IF active_session_count>=policy_row.max_sessions THEN RETURN QUERY SELECT FALSE,NULL::UUID,'SESSION_LIMIT'::VARCHAR,lifecycle_row.state_version,NULL::TIMESTAMPTZ; RETURN; END IF;
        END IF;
        INSERT INTO starry_api.account_active_sessions(account_id,session_hash,last_seen_at,expires_at)
        VALUES(p_account_id,p_session_hash,now_at,now_at+pg_catalog.make_interval(secs=>policy_row.session_idle_seconds))
        ON CONFLICT(account_id,session_hash) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at,expires_at=EXCLUDED.expires_at;
    END IF;
    lease_expires_at:=now_at+pg_catalog.make_interval(secs=>p_lease_seconds);
    UPDATE starry_api.account_admission_states s SET state_version=s.state_version+1,
        rpm_tokens=CASE WHEN effective_rpm=0 THEN admission_row.rpm_tokens ELSE refilled_rpm-1 END,
        rpm_refilled_at=now_at,
        burst_tokens=CASE WHEN effective_rpm=0 OR effective_burst=0 THEN admission_row.burst_tokens ELSE refilled_burst-1 END,
        burst_refilled_at=now_at,active_concurrency=admission_row.active_concurrency+1,
        reserved_input_tokens=admission_row.reserved_input_tokens+requested_input,
        reserved_output_tokens=admission_row.reserved_output_tokens+requested_output,updated_at=now_at
    WHERE account_id=p_account_id;
    INSERT INTO starry_api.account_admission_leases(lease_id,request_id,account_id,state_version,reserved_input_tokens,reserved_output_tokens,expires_at)
    VALUES(next_lease_id,p_request_id,p_account_id,lifecycle_row.state_version,requested_input,requested_output,lease_expires_at);
    INSERT INTO starry_api.account_allocation_events(account_id,request_id,event_type,evidence_json)
    VALUES(p_account_id,p_request_id,'ADMISSION_ACQUIRED',pg_catalog.jsonb_build_object('leaseId',next_lease_id,'lifecycle',lifecycle_row.lifecycle_state,
        'reservedInputTokens',requested_input,'reservedOutputTokens',requested_output));
    RETURN QUERY SELECT TRUE,next_lease_id,NULL::VARCHAR,lifecycle_row.state_version,lease_expires_at;
END;
$$;

REVOKE ALL ON FUNCTION starry_api.try_acquire_account_lease_v8(VARCHAR,VARCHAR,BIGINT,VARCHAR,BOOLEAN,BIGINT,BIGINT,INTEGER) FROM PUBLIC,starry_api_runtime;

ALTER FUNCTION starry_api.reconcile_account_lease(UUID,BOOLEAN,BIGINT,BIGINT,BIGINT,INTEGER,VARCHAR,INTEGER,BOOLEAN,JSONB)
    RENAME TO reconcile_account_lease_pre_egress;
REVOKE ALL ON FUNCTION starry_api.reconcile_account_lease_pre_egress(UUID,BOOLEAN,BIGINT,BIGINT,BIGINT,INTEGER,VARCHAR,INTEGER,BOOLEAN,JSONB)
    FROM PUBLIC,starry_api_runtime;

CREATE FUNCTION starry_api.reconcile_account_lease(
    p_lease_id UUID, p_success BOOLEAN, p_actual_input_tokens BIGINT, p_actual_output_tokens BIGINT,
    p_latency_ms BIGINT, p_status_code INTEGER, p_error_scope VARCHAR, p_cooldown_seconds INTEGER,
    p_permanent_credential_failure BOOLEAN, p_evidence JSONB DEFAULT '{}'::jsonb
)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp
AS $$
DECLARE
    target_id VARCHAR;
    previous_state starry_api.account_allocation_states%ROWTYPE;
    configured_cooldown INTEGER;
    effective_cooldown INTEGER;
    result BOOLEAN;
BEGIN
    SELECT account_id INTO target_id FROM starry_api.account_admission_leases WHERE lease_id=p_lease_id;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(starry_api.account_allocation_lock_key(target_id));
    SELECT * INTO previous_state FROM starry_api.account_allocation_states WHERE account_id=target_id;
    SELECT cooldown_seconds INTO configured_cooldown FROM starry_api.account_allocation_policies WHERE account_id=target_id;
    effective_cooldown := CASE WHEN configured_cooldown>0 AND p_error_scope IN ('CAPACITY_ACCOUNT','CAPACITY_MODEL','CAPACITY_ENDPOINT','TRANSPORT','PROVIDER_POOL')
        THEN configured_cooldown ELSE p_cooldown_seconds END;
    effective_cooldown := GREATEST(effective_cooldown, COALESCE((p_evidence->>'retryAfterSeconds')::INTEGER,0));
    result := starry_api.reconcile_account_lease_pre_egress(p_lease_id,p_success,p_actual_input_tokens,p_actual_output_tokens,
        p_latency_ms,p_status_code,p_error_scope,effective_cooldown,p_permanent_credential_failure,
        COALESCE(p_evidence,'{}'::jsonb)||pg_catalog.jsonb_build_object('cooldownSeconds',effective_cooldown));
    IF result AND previous_state.cooldown_until>pg_catalog.clock_timestamp() THEN
        UPDATE starry_api.account_allocation_states SET cooldown_until=GREATEST(cooldown_until,previous_state.cooldown_until),
            lifecycle_state=CASE WHEN lifecycle_state IN ('DISABLED','QUARANTINED') THEN lifecycle_state ELSE previous_state.lifecycle_state END
        WHERE account_id=target_id;
    END IF;
    IF result AND previous_state.lifecycle_state IN ('DISABLED','QUARANTINED') THEN
        UPDATE starry_api.account_allocation_states SET lifecycle_state=previous_state.lifecycle_state,
            cooldown_until=CASE WHEN previous_state.cooldown_until IS NULL THEN NULL ELSE cooldown_until END
        WHERE account_id=target_id;
    END IF;
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION starry_api.reconcile_account_lease(UUID,BOOLEAN,BIGINT,BIGINT,BIGINT,INTEGER,VARCHAR,INTEGER,BOOLEAN,JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.reconcile_account_lease(UUID,BOOLEAN,BIGINT,BIGINT,BIGINT,INTEGER,VARCHAR,INTEGER,BOOLEAN,JSONB) TO starry_api_runtime;

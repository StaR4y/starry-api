SET search_path TO starry_api, public;

ALTER TABLE account_admission_leases
    DROP CONSTRAINT ck_account_admission_lease_state;
ALTER TABLE account_admission_leases
    ADD CONSTRAINT ck_account_admission_lease_state CHECK (
        dispatch_state IN ('RESERVED', 'DISPATCHED', 'COMPLETED', 'RELEASED', 'EXPIRED', 'ABANDONED')
    );

-- Keep the V8 implementations available behind serialized wrappers so this migration is safe
-- for databases that have already applied V8.
ALTER FUNCTION starry_api.try_acquire_account_lease(
    VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, INTEGER
) RENAME TO try_acquire_account_lease_v8;
ALTER FUNCTION starry_api.reconcile_account_lease(
    UUID, BOOLEAN, BIGINT, BIGINT, BIGINT, INTEGER, VARCHAR, INTEGER, BOOLEAN, JSONB
) RENAME TO reconcile_account_lease_v8;

REVOKE ALL ON FUNCTION starry_api.try_acquire_account_lease_v8(
    VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, INTEGER
) FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.reconcile_account_lease_v8(
    UUID, BOOLEAN, BIGINT, BIGINT, BIGINT, INTEGER, VARCHAR, INTEGER, BOOLEAN, JSONB
) FROM PUBLIC, starry_api_runtime;

CREATE OR REPLACE FUNCTION starry_api.account_allocation_lock_key(p_account_id VARCHAR)
RETURNS BIGINT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT pg_catalog.hashtextextended(COALESCE(p_account_id, ''), 730431::BIGINT)
$$;

REVOKE ALL ON FUNCTION starry_api.account_allocation_lock_key(VARCHAR) FROM PUBLIC;

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
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(
        starry_api.account_allocation_lock_key(p_account_id)
    );

    -- Reclaim first and persist immediately. A later admission rejection must not lose capacity.
    WITH expired AS (
        UPDATE starry_api.account_admission_leases AS lease
        SET dispatch_state = 'EXPIRED', completed_at = now_at
        WHERE lease.account_id = p_account_id
          AND lease.dispatch_state IN ('RESERVED', 'DISPATCHED')
          AND lease.expires_at <= now_at
        RETURNING lease.reserved_input_tokens, lease.reserved_output_tokens
    ), totals AS (
        SELECT
            count(*)::INTEGER AS concurrency,
            COALESCE(sum(reserved_input_tokens), 0)::BIGINT AS input_tokens,
            COALESCE(sum(reserved_output_tokens), 0)::BIGINT AS output_tokens
        FROM expired
    )
    UPDATE starry_api.account_admission_states AS admission
    SET state_version = admission.state_version + 1,
        active_concurrency = GREATEST(admission.active_concurrency - totals.concurrency, 0),
        reserved_input_tokens = GREATEST(admission.reserved_input_tokens - totals.input_tokens, 0),
        reserved_output_tokens = GREATEST(admission.reserved_output_tokens - totals.output_tokens, 0),
        updated_at = now_at
    FROM totals
    WHERE admission.account_id = p_account_id
      AND totals.concurrency > 0;

    RETURN QUERY
    SELECT *
    FROM starry_api.try_acquire_account_lease_v8(
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
    account_id_value VARCHAR(64);
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
    normalized_scope TEXT := pg_catalog.upper(COALESCE(p_error_scope, 'UNKNOWN'));
BEGIN
    SELECT lease.account_id INTO account_id_value
    FROM starry_api.account_admission_leases AS lease
    WHERE lease.lease_id = p_lease_id;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        starry_api.account_allocation_lock_key(account_id_value)
    );

    -- Request, model, and endpoint failures are not evidence that the whole credential is bad.
    IF NOT COALESCE(p_success, FALSE)
        AND normalized_scope IN ('REQUEST', 'CAPACITY_MODEL', 'CAPACITY_ENDPOINT') THEN
        SELECT * INTO lease_row
        FROM starry_api.account_admission_leases AS lease
        WHERE lease.lease_id = p_lease_id
        FOR UPDATE;
        IF NOT FOUND OR lease_row.dispatch_state IN (
            'COMPLETED', 'RELEASED', 'EXPIRED', 'ABANDONED'
        ) THEN
            RETURN FALSE;
        END IF;

        PERFORM 1 FROM starry_api.account_admission_states AS admission
        WHERE admission.account_id = lease_row.account_id
        FOR UPDATE;
        UPDATE starry_api.account_admission_states AS admission
        SET state_version = admission.state_version + 1,
            active_concurrency = GREATEST(admission.active_concurrency - 1, 0),
            reserved_input_tokens = GREATEST(
                admission.reserved_input_tokens - lease_row.reserved_input_tokens,
                0
            ),
            reserved_output_tokens = GREATEST(
                admission.reserved_output_tokens - lease_row.reserved_output_tokens,
                0
            ),
            updated_at = now_at
        WHERE admission.account_id = lease_row.account_id;

        UPDATE starry_api.account_admission_leases AS lease
        SET dispatch_state = 'COMPLETED', completed_at = now_at
        WHERE lease.lease_id = p_lease_id;

        INSERT INTO starry_api.account_allocation_events (
            account_id, request_id, event_type, error_scope, evidence_json
        ) VALUES (
            lease_row.account_id,
            lease_row.request_id,
            'UPSTREAM_FAILURE',
            normalized_scope,
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
    END IF;

    RETURN starry_api.reconcile_account_lease_v8(
        p_lease_id,
        p_success,
        p_actual_input_tokens,
        p_actual_output_tokens,
        p_latency_ms,
        p_status_code,
        p_error_scope,
        p_cooldown_seconds,
        p_permanent_credential_failure,
        p_evidence
    );
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.abandon_account_lease(
    p_lease_id UUID,
    p_reason VARCHAR DEFAULT 'PIPELINE_ABANDONED'
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    lease_row starry_api.account_admission_leases%ROWTYPE;
    account_id_value VARCHAR(64);
    now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
BEGIN
    SELECT lease.account_id INTO account_id_value
    FROM starry_api.account_admission_leases AS lease
    WHERE lease.lease_id = p_lease_id;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        starry_api.account_allocation_lock_key(account_id_value)
    );
    SELECT * INTO lease_row
    FROM starry_api.account_admission_leases AS lease
    WHERE lease.lease_id = p_lease_id
    FOR UPDATE;
    IF NOT FOUND OR lease_row.dispatch_state IN (
        'COMPLETED', 'RELEASED', 'EXPIRED', 'ABANDONED'
    ) THEN
        RETURN FALSE;
    END IF;

    PERFORM 1 FROM starry_api.account_admission_states AS admission
    WHERE admission.account_id = lease_row.account_id
    FOR UPDATE;
    UPDATE starry_api.account_admission_states AS admission
    SET state_version = admission.state_version + 1,
        active_concurrency = GREATEST(admission.active_concurrency - 1, 0),
        reserved_input_tokens = GREATEST(
            admission.reserved_input_tokens - lease_row.reserved_input_tokens,
            0
        ),
        reserved_output_tokens = GREATEST(
            admission.reserved_output_tokens - lease_row.reserved_output_tokens,
            0
        ),
        updated_at = now_at
    WHERE admission.account_id = lease_row.account_id;

    UPDATE starry_api.account_admission_leases AS lease
    SET dispatch_state = 'ABANDONED', completed_at = now_at
    WHERE lease.lease_id = p_lease_id;

    INSERT INTO starry_api.account_allocation_events (
        account_id, request_id, event_type, evidence_json
    ) VALUES (
        lease_row.account_id,
        lease_row.request_id,
        'ADMISSION_ABANDONED',
        pg_catalog.jsonb_build_object(
            'leaseId', p_lease_id,
            'reason', pg_catalog.left(COALESCE(NULLIF(pg_catalog.btrim(p_reason), ''), 'PIPELINE_ABANDONED'), 128)
        )
    );
    RETURN TRUE;
END;
$$;

-- Heal counters left inconsistent by V8 before serving traffic with the corrected wrappers.
UPDATE account_admission_leases
SET dispatch_state = 'EXPIRED', completed_at = pg_catalog.clock_timestamp()
WHERE dispatch_state IN ('RESERVED', 'DISPATCHED')
  AND expires_at <= pg_catalog.clock_timestamp();

UPDATE account_admission_states AS admission
SET active_concurrency = active.active_concurrency,
    reserved_input_tokens = active.reserved_input_tokens,
    reserved_output_tokens = active.reserved_output_tokens,
    state_version = admission.state_version + 1,
    updated_at = pg_catalog.clock_timestamp()
FROM (
    SELECT
        account.account_id,
        count(lease.lease_id)::INTEGER AS active_concurrency,
        COALESCE(sum(lease.reserved_input_tokens), 0)::BIGINT AS reserved_input_tokens,
        COALESCE(sum(lease.reserved_output_tokens), 0)::BIGINT AS reserved_output_tokens
    FROM account_admission_states AS account
    LEFT JOIN account_admission_leases AS lease
      ON lease.account_id = account.account_id
     AND lease.dispatch_state IN ('RESERVED', 'DISPATCHED')
     AND lease.expires_at > pg_catalog.clock_timestamp()
    GROUP BY account.account_id
) AS active
WHERE active.account_id = admission.account_id;

REVOKE ALL ON FUNCTION starry_api.try_acquire_account_lease(
    VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, INTEGER
) FROM PUBLIC;
REVOKE ALL ON FUNCTION starry_api.reconcile_account_lease(
    UUID, BOOLEAN, BIGINT, BIGINT, BIGINT, INTEGER, VARCHAR, INTEGER, BOOLEAN, JSONB
) FROM PUBLIC;
REVOKE ALL ON FUNCTION starry_api.abandon_account_lease(UUID, VARCHAR) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION starry_api.try_acquire_account_lease(
    VARCHAR, VARCHAR, BIGINT, VARCHAR, BOOLEAN, BIGINT, BIGINT, INTEGER
) TO starry_api_runtime;
GRANT EXECUTE ON FUNCTION starry_api.reconcile_account_lease(
    UUID, BOOLEAN, BIGINT, BIGINT, BIGINT, INTEGER, VARCHAR, INTEGER, BOOLEAN, JSONB
) TO starry_api_runtime;
GRANT EXECUTE ON FUNCTION starry_api.abandon_account_lease(UUID, VARCHAR) TO starry_api_runtime;

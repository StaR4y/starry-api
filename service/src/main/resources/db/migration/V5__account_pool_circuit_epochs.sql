ALTER TABLE request_logs
    ADD COLUMN circuit_epoch UUID;

DROP INDEX idx_request_logs_circuit_window;

CREATE INDEX idx_request_logs_circuit_window
    ON request_logs (circuit_epoch, created_at DESC, circuit_key)
    INCLUDE (circuit_tier, upstream_success)
    WHERE circuit_epoch IS NOT NULL
      AND circuit_key IS NOT NULL
      AND upstream_success IS NOT NULL;

INSERT INTO system_settings (setting_key, setting_value)
VALUES
    ('account_pool_policy_epoch', ''),
    ('account_pool_policy_activation_started_at', '')
ON CONFLICT (setting_key) DO NOTHING;

CREATE OR REPLACE FUNCTION starry_api.ensure_request_log_partitions(
    months_ahead INTEGER DEFAULT 12,
    months_behind INTEGER DEFAULT 1
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
    month_offset INTEGER;
    range_start TIMESTAMP WITHOUT TIME ZONE;
    range_end TIMESTAMP WITHOUT TIME ZONE;
    partition_start TIMESTAMP WITHOUT TIME ZONE;
    partition_end TIMESTAMP WITHOUT TIME ZONE;
    partition_name TEXT;
    partition_attached BOOLEAN;
    missing_partition BOOLEAN := FALSE;
    default_attached BOOLEAN;
BEGIN
    IF months_ahead < 0 OR months_ahead > 60 OR months_behind < 0 OR months_behind > 24 THEN
        RAISE EXCEPTION 'request log partition window is out of range';
    END IF;

    range_start := pg_catalog.date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
        - pg_catalog.make_interval(months => months_behind);
    range_end := pg_catalog.date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
        + pg_catalog.make_interval(months => months_ahead + 1);

    FOR month_offset IN 0..(months_ahead + months_behind) LOOP
        partition_start := range_start + pg_catalog.make_interval(months => month_offset);
        partition_name := 'request_logs_y' || pg_catalog.to_char(partition_start, 'YYYY')
            || 'm' || pg_catalog.to_char(partition_start, 'MM');
        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_inherits inheritance
            WHERE inheritance.inhparent = 'starry_api.request_logs'::pg_catalog.regclass
              AND inheritance.inhrelid = pg_catalog.to_regclass('starry_api.' || partition_name)
        ) INTO partition_attached;
        IF NOT partition_attached THEN
            missing_partition := TRUE;
            EXIT;
        END IF;
    END LOOP;

    IF NOT missing_partition THEN
        RETURN;
    END IF;

    IF NOT pg_catalog.pg_try_advisory_xact_lock(
        pg_catalog.hashtextextended('starry_api.request_log_partition_maintenance', 0)
    ) THEN
        RETURN;
    END IF;

    missing_partition := FALSE;
    FOR month_offset IN 0..(months_ahead + months_behind) LOOP
        partition_start := range_start + pg_catalog.make_interval(months => month_offset);
        partition_name := 'request_logs_y' || pg_catalog.to_char(partition_start, 'YYYY')
            || 'm' || pg_catalog.to_char(partition_start, 'MM');
        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_inherits inheritance
            WHERE inheritance.inhparent = 'starry_api.request_logs'::pg_catalog.regclass
              AND inheritance.inhrelid = pg_catalog.to_regclass('starry_api.' || partition_name)
        ) INTO partition_attached;
        IF NOT partition_attached THEN
            missing_partition := TRUE;
            EXIT;
        END IF;
    END LOOP;

    IF NOT missing_partition THEN
        RETURN;
    END IF;

    LOCK TABLE ONLY starry_api.request_logs IN ACCESS EXCLUSIVE MODE;

    SELECT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits inheritance
        WHERE inheritance.inhparent = 'starry_api.request_logs'::pg_catalog.regclass
          AND inheritance.inhrelid = pg_catalog.to_regclass('starry_api.request_logs_default')
    ) INTO default_attached;

    IF default_attached THEN
        ALTER TABLE starry_api.request_logs
            DETACH PARTITION starry_api.request_logs_default;
    END IF;

    FOR month_offset IN 0..(months_ahead + months_behind) LOOP
        partition_start := range_start + pg_catalog.make_interval(months => month_offset);
        partition_end := partition_start + INTERVAL '1 month';
        partition_name := 'request_logs_y' || pg_catalog.to_char(partition_start, 'YYYY')
            || 'm' || pg_catalog.to_char(partition_start, 'MM');

        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_inherits inheritance
            WHERE inheritance.inhparent = 'starry_api.request_logs'::pg_catalog.regclass
              AND inheritance.inhrelid = pg_catalog.to_regclass('starry_api.' || partition_name)
        ) INTO partition_attached;
        IF NOT partition_attached AND pg_catalog.to_regclass('starry_api.' || partition_name) IS NOT NULL THEN
            RAISE EXCEPTION 'relation starry_api.% exists but is not attached to request_logs', partition_name;
        ELSIF NOT partition_attached THEN
            EXECUTE pg_catalog.format(
                'CREATE TABLE starry_api.%I PARTITION OF starry_api.request_logs FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_start AT TIME ZONE 'UTC',
                partition_end AT TIME ZONE 'UTC'
            );
        END IF;
    END LOOP;

    IF default_attached THEN
        INSERT INTO starry_api.request_logs (
            id, request_id, api_key_id, user_id, account_id, path, model, status_code, success,
            input_tokens, output_tokens, credits, latency_ms, error_message, circuit_key,
            circuit_tier, upstream_model, upstream_success, circuit_epoch, created_at
        )
        SELECT
            id, request_id, api_key_id, user_id, account_id, path, model, status_code, success,
            input_tokens, output_tokens, credits, latency_ms, error_message, circuit_key,
            circuit_tier, upstream_model, upstream_success, circuit_epoch, created_at
        FROM starry_api.request_logs_default
        WHERE created_at >= range_start AT TIME ZONE 'UTC'
          AND created_at < range_end AT TIME ZONE 'UTC';

        DELETE FROM starry_api.request_logs_default
        WHERE created_at >= range_start AT TIME ZONE 'UTC'
          AND created_at < range_end AT TIME ZONE 'UTC';

        ALTER TABLE starry_api.request_logs
            ATTACH PARTITION starry_api.request_logs_default DEFAULT;
    END IF;
END;
$$;

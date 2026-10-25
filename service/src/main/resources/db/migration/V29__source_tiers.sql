-- Tiered source pools. A vendor's accounts are split into ordered pools ("tiers", level 1 is the best) that carry a
-- price factor; some members are OpenAI-format relays registered explicitly with a per-model mapping. All stages of
-- the feature share this migration, so the CHECK constraints are written for the final value sets and the Java side
-- rejects values a given release does not implement yet. Without rows in source_tier_vendors nothing changes.

CREATE TABLE source_tier_vendors (
    vendor VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    unify_endpoints BOOLEAN NOT NULL DEFAULT FALSE,
    public_listing BOOLEAN NOT NULL DEFAULT FALSE,
    sticky_sessions BOOLEAN NOT NULL DEFAULT TRUE,
    refusal_cap SMALLINT NOT NULL DEFAULT 16,
    cross_tier_reserved_slot BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_source_tier_vendors_vendor CHECK (btrim(vendor) <> ''),
    CONSTRAINT ck_source_tier_vendors_refusal_cap CHECK (refusal_cap BETWEEN 4 AND 64),
    CONSTRAINT ck_source_tier_vendors_version CHECK (version >= 1)
);

CREATE TABLE source_tiers (
    vendor VARCHAR(64) NOT NULL REFERENCES source_tier_vendors (vendor) ON DELETE CASCADE,
    tier_id VARCHAR(32) NOT NULL,
    level SMALLINT NOT NULL,
    name VARCHAR(32) NOT NULL,
    price_factor NUMERIC(12, 6) NOT NULL DEFAULT 1,
    member_categories TEXT[] NOT NULL DEFAULT '{}',
    user_selectable BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(256),
    PRIMARY KEY (vendor, tier_id),
    -- A tier id must never read as a selector keyword: auto, L2 or a bare level number.
    CONSTRAINT ck_source_tiers_tier_id CHECK (tier_id ~ '^[a-z0-9][a-z0-9-]{0,31}$' AND tier_id !~ '^(auto|l?[0-9]+)$'),
    CONSTRAINT ck_source_tiers_level CHECK (level BETWEEN 1 AND 9),
    CONSTRAINT ck_source_tiers_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_source_tiers_price_factor CHECK (price_factor > 0 AND price_factor <= 1000),
    CONSTRAINT ck_source_tiers_member_categories CHECK (
        member_categories <@ ARRAY['OFFICIAL_API_KEY', 'COMPATIBLE_API_KEY', 'CODEX', 'CLAUDE_OAUTH', 'KIRO']::TEXT[]),
    -- Deferrable so one transaction can reorder or rename tiers (SET CONSTRAINTS DEFERRED) without a temporary value.
    CONSTRAINT uq_source_tiers_level UNIQUE (vendor, level) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT uq_source_tiers_name UNIQUE (vendor, name) DEFERRABLE INITIALLY IMMEDIATE
);

-- Rows with a NULL protocol only pin a native account to a tier; the cross-protocol columns are ignored for them.
CREATE TABLE source_tier_members (
    vendor VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL REFERENCES proxy_accounts (account_id) ON DELETE CASCADE,
    tier_id VARCHAR(32) NOT NULL,
    protocol VARCHAR(24),
    exclusive BOOLEAN NOT NULL DEFAULT TRUE,
    vision BOOLEAN NOT NULL DEFAULT FALSE,
    json_schema BOOLEAN NOT NULL DEFAULT FALSE,
    priority_tier BOOLEAN NOT NULL DEFAULT FALSE,
    cache_control_passthrough BOOLEAN NOT NULL DEFAULT FALSE,
    upstream_stream BOOLEAN NOT NULL DEFAULT TRUE,
    reasoning_mode VARCHAR(8) NOT NULL DEFAULT 'NONE',
    max_duration_seconds SMALLINT NOT NULL DEFAULT 540,
    -- Whether the relay's prompt_tokens already include the cache read / write tokens; NULL until configured.
    usage_cache_read_in_prompt BOOLEAN,
    usage_cache_write_in_prompt BOOLEAN,
    PRIMARY KEY (vendor, account_id),
    FOREIGN KEY (vendor, tier_id) REFERENCES source_tiers (vendor, tier_id) ON DELETE CASCADE,
    CONSTRAINT ck_source_tier_members_protocol CHECK (
        protocol IS NULL OR protocol IN ('OPENAI_CHAT', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES')),
    CONSTRAINT ck_source_tier_members_reasoning_mode CHECK (reasoning_mode IN ('NONE', 'EFFORT')),
    CONSTRAINT ck_source_tier_members_max_duration CHECK (max_duration_seconds BETWEEN 30 AND 900),
    CONSTRAINT ck_source_tier_members_usage_basis CHECK (
        protocol IS NULL OR (usage_cache_read_in_prompt IS NOT NULL AND usage_cache_write_in_prompt IS NOT NULL))
);

CREATE INDEX idx_source_tier_members_account ON source_tier_members (account_id);
CREATE INDEX idx_source_tier_members_tier ON source_tier_members (vendor, tier_id);

CREATE TABLE source_tier_member_models (
    vendor VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    model_id VARCHAR(256) NOT NULL,
    upstream_model VARCHAR(255) NOT NULL,
    max_context_tokens INTEGER NOT NULL,
    image_token_cap INTEGER NOT NULL DEFAULT 8192,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    verified_at TIMESTAMPTZ(3),
    verified_hash CHAR(64),
    verify_summary JSONB NOT NULL DEFAULT '{}'::JSONB,
    PRIMARY KEY (vendor, account_id, model_id),
    FOREIGN KEY (vendor, account_id) REFERENCES source_tier_members (vendor, account_id) ON DELETE CASCADE,
    CONSTRAINT ck_source_tier_member_models_model_id CHECK (btrim(model_id) <> ''),
    -- Sent to the relay as is, so it must be a plain model name.
    CONSTRAINT ck_source_tier_member_models_upstream_model CHECK (upstream_model ~ '^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,254}$'),
    CONSTRAINT ck_source_tier_member_models_context CHECK (max_context_tokens > 0),
    CONSTRAINT ck_source_tier_member_models_image_cap CHECK (image_token_cap BETWEEN 1 AND 1000000),
    CONSTRAINT ck_source_tier_member_models_verified_hash CHECK (verified_hash IS NULL OR verified_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_tier_member_models_summary CHECK (jsonb_typeof(verify_summary) = 'object')
);

CREATE UNIQUE INDEX uq_source_tier_member_models_lower
    ON source_tier_member_models (vendor, account_id, lower(model_id));

-- No row means OFF. KEYS admits only the listed API keys.
CREATE TABLE source_tier_pair_gates (
    vendor VARCHAR(64) NOT NULL REFERENCES source_tier_vendors (vendor) ON DELETE CASCADE,
    source_protocol VARCHAR(24) NOT NULL DEFAULT 'OPENAI_CHAT',
    entry_endpoint VARCHAR(16) NOT NULL,
    state VARCHAR(8) NOT NULL DEFAULT 'OFF',
    allow_key_ids TEXT[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (vendor, source_protocol, entry_endpoint),
    CONSTRAINT ck_source_tier_pair_gates_protocol CHECK (
        source_protocol IN ('OPENAI_CHAT', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES')),
    CONSTRAINT ck_source_tier_pair_gates_endpoint CHECK (entry_endpoint IN ('CHAT', 'MESSAGES', 'RESPONSES', 'GEMINI')),
    CONSTRAINT ck_source_tier_pair_gates_state CHECK (state IN ('OFF', 'KEYS', 'ON')),
    CONSTRAINT ck_source_tier_pair_gates_keys CHECK (cardinality(allow_key_ids) <= 50)
);

CREATE TRIGGER trg_source_tier_vendors_updated_at
    BEFORE UPDATE ON source_tier_vendors
    FOR EACH ROW EXECUTE FUNCTION starry_api.set_updated_at();

REVOKE ALL ON TABLE source_tier_vendors, source_tiers, source_tier_members, source_tier_member_models,
    source_tier_pair_gates FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE source_tier_vendors, source_tiers, source_tier_members,
    source_tier_member_models, source_tier_pair_gates TO starry_api_runtime;

INSERT INTO system_settings (setting_key, setting_value)
VALUES ('source_tier_config_version', '0')
ON CONFLICT (setting_key) DO NOTHING;

-- Per-key preference: auto, a tier id, L<level> or a bare level number, each optionally followed by + ("and below").
ALTER TABLE api_keys
    ADD COLUMN tier_preference VARCHAR(40) NOT NULL DEFAULT 'auto',
    ADD CONSTRAINT ck_api_keys_tier_preference
        CHECK (tier_preference ~ '^(auto|[a-z0-9][a-z0-9-]{0,31}\+?|[Ll]?[1-9]\+?)$');

-- Which tier served a request and how it was dispatched. Nullable and unconstrained so the ALTER stays cheap on the large
-- partitioned table; rows written before the feature, or for untiered vendors, keep NULL.
ALTER TABLE request_logs
    ADD COLUMN source_tier_ref VARCHAR(128),
    ADD COLUMN source_tier_level SMALLINT,
    ADD COLUMN source_tier_name VARCHAR(64),
    ADD COLUMN price_factor NUMERIC(12, 6),
    ADD COLUMN tier_selection VARCHAR(32),
    ADD COLUMN source_dispatch VARCHAR(32),
    ADD COLUMN wire_model VARCHAR(256),
    ADD COLUMN usage_source VARCHAR(32),
    ADD COLUMN stop_reason VARCHAR(32),
    ADD COLUMN degraded_features VARCHAR(512);

-- ensure_request_log_partitions as in V5, with the ten columns above added to the lists that move rows out of the default
-- partition: a column that is not named there would be lost silently.
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
            circuit_tier, upstream_model, upstream_success, circuit_epoch,
            source_tier_ref, source_tier_level, source_tier_name, price_factor, tier_selection,
            source_dispatch, wire_model, usage_source, stop_reason, degraded_features, created_at
        )
        SELECT
            id, request_id, api_key_id, user_id, account_id, path, model, status_code, success,
            input_tokens, output_tokens, credits, latency_ms, error_message, circuit_key,
            circuit_tier, upstream_model, upstream_success, circuit_epoch,
            source_tier_ref, source_tier_level, source_tier_name, price_factor, tier_selection,
            source_dispatch, wire_model, usage_source, stop_reason, degraded_features, created_at
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

-- The tier that served a reservation is written together with the settlement (settle_api_usage_served below);
-- last_dispatched_* records where the latest attempt went, so a reservation that is settled later by an administrator
-- or whose lease expired can still be attributed. The check is NOT VALID on purpose: every existing row holds NULL,
-- so validating it would only scan the table under the lock of the ALTER.
ALTER TABLE billing_reservations
    ADD COLUMN served_tier_ref VARCHAR(128),
    ADD COLUMN served_tier_level SMALLINT,
    ADD COLUMN served_price_factor NUMERIC(12, 6),
    ADD COLUMN last_dispatched_tier_ref VARCHAR(128),
    ADD COLUMN last_dispatched_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_billing_reservations_served_tier CHECK (
        (served_tier_ref IS NULL AND served_tier_level IS NULL AND served_price_factor IS NULL)
        OR (served_tier_ref IS NOT NULL AND served_tier_level BETWEEN 1 AND 9
            AND served_price_factor > 0 AND served_price_factor <= 1000)
    ) NOT VALID;

ALTER TABLE api_usage_events
    ADD COLUMN source_tier_ref VARCHAR(128),
    ADD COLUMN price_factor NUMERIC(12, 6);

-- Fills the tier of a usage event from its reservation when the settlement function did not (settle_api_usage, used by
-- administrators resolving a review and by nodes that predate this migration, knows nothing about tiers): the tier that
-- served it, else the last one dispatched to. The factor is the served one, else the tier's factor frozen in the
-- reservation's pricing snapshot. It runs inside the settlement transaction, so it must never raise: a value that
-- cannot be read as a factor is skipped.
CREATE FUNCTION starry_api.fill_usage_event_tier()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    reservation RECORD;
    resolved_ref VARCHAR(128);
    resolved_factor NUMERIC;
BEGIN
    IF NEW.source_tier_ref IS NOT NULL THEN
        RETURN NEW;
    END IF;
    -- The event table keeps the hash as CHAR(64), the reservation table as VARCHAR(64): the explicit cast keeps the
    -- primary key index usable.
    SELECT r.served_tier_ref, r.last_dispatched_tier_ref, r.served_price_factor, r.pricing
    INTO reservation
    FROM starry_api.billing_reservations AS r
    WHERE r.request_hash = NEW.request_hash::VARCHAR;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    resolved_ref := COALESCE(reservation.served_tier_ref, reservation.last_dispatched_tier_ref);
    IF resolved_ref IS NULL THEN
        RETURN NEW;
    END IF;
    resolved_factor := COALESCE(NEW.price_factor, reservation.served_price_factor);
    IF resolved_factor IS NULL AND pg_catalog.jsonb_typeof(reservation.pricing #> '{tierPlan,levels}') = 'array' THEN
        SELECT pg_catalog.round((plan_level ->> 'factor')::NUMERIC, 6)
        INTO resolved_factor
        FROM pg_catalog.jsonb_array_elements(reservation.pricing #> '{tierPlan,levels}') AS plan_level
        WHERE pg_catalog.jsonb_typeof(plan_level) = 'object' AND plan_level ->> 'tierRef' = resolved_ref
          AND pg_catalog.jsonb_typeof(plan_level -> 'factor') = 'number'
        LIMIT 1;
        IF resolved_factor <= 0 OR resolved_factor > 1000 THEN
            resolved_factor := NULL;
        END IF;
    END IF;
    NEW.source_tier_ref := resolved_ref;
    NEW.price_factor := resolved_factor;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_api_usage_events_tier
    BEFORE INSERT ON api_usage_events
    FOR EACH ROW EXECUTE FUNCTION starry_api.fill_usage_event_tier();

-- Settles like settle_api_usage and records the serving tier in the same transaction, under the same lock order
-- (owner, key, reservation). The tier is written once; repeating the call with the same tier is idempotent, a different
-- tier is a conflict, and a reservation that ends in REVIEW_REQUIRED already carries it. p_level is INTEGER on purpose:
-- R2DBC binds a Java int as int4 and function resolution has no implicit int4 -> int2 conversion.
CREATE FUNCTION starry_api.settle_api_usage_served(
    p_hash VARCHAR, p_credits NUMERIC, p_input BIGINT, p_output BIGINT,
    p_tier_ref VARCHAR, p_level INTEGER, p_factor NUMERIC
) RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api, pg_temp AS $$
DECLARE
    r billing_reservations%ROWTYPE; uid VARCHAR(64); kid VARCHAR(64);
    factor NUMERIC := round(p_factor, 6);
BEGIN
    IF p_credits IS NULL OR p_credits < 0 OR p_input IS NULL OR p_input < 0 OR p_output IS NULL OR p_output < 0 THEN
        RETURN 'BILLING_AMOUNT_INVALID';
    END IF;
    IF p_tier_ref IS NULL OR btrim(p_tier_ref) = '' OR length(p_tier_ref) > 128
        OR p_level IS NULL OR p_level NOT BETWEEN 1 AND 9 OR factor IS NULL OR factor <= 0 OR factor > 1000 THEN
        RETURN 'BILLING_TIER_INVALID';
    END IF;
    SELECT user_id, key_id INTO uid, kid FROM billing_reservations WHERE request_hash = p_hash;
    IF NOT FOUND THEN RETURN 'BILLING_RESERVATION_REQUIRED'; END IF;
    PERFORM 1 FROM admin_users WHERE user_id = uid FOR UPDATE;
    PERFORM 1 FROM api_keys WHERE key_id = kid FOR UPDATE;
    SELECT * INTO r FROM billing_reservations WHERE request_hash = p_hash FOR UPDATE;
    IF r.served_tier_ref IS NOT NULL AND ROW(r.served_tier_ref, r.served_tier_level::INTEGER, r.served_price_factor)
        IS DISTINCT FROM ROW(p_tier_ref, p_level, factor) THEN
        RETURN 'IDEMPOTENCY_CONFLICT';
    END IF;
    IF r.served_tier_ref IS NULL AND r.status NOT IN ('SETTLED', 'RELEASED') THEN
        UPDATE billing_reservations
        SET served_tier_ref = p_tier_ref, served_tier_level = p_level, served_price_factor = factor
        WHERE request_hash = p_hash;
    END IF;
    RETURN starry_api.settle_api_usage(p_hash, p_credits, p_input, p_output);
END $$;

REVOKE ALL ON FUNCTION starry_api.settle_api_usage_served(VARCHAR, NUMERIC, BIGINT, BIGINT, VARCHAR, INTEGER, NUMERIC)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.settle_api_usage_served(VARCHAR, NUMERIC, BIGINT, BIGINT, VARCHAR, INTEGER, NUMERIC)
    TO starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.fill_usage_event_tier() FROM PUBLIC, starry_api_runtime;

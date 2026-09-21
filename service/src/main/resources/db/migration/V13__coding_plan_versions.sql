ALTER TABLE coding_plans ADD COLUMN version INTEGER NOT NULL DEFAULT 1;

CREATE TABLE coding_plan_versions (
    plan_id VARCHAR(64) NOT NULL REFERENCES coding_plans(plan_id),
    version INTEGER NOT NULL CHECK (version > 0),
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    price_credits NUMERIC(20,6) NOT NULL CHECK (price_credits >= 0),
    quota_credits NUMERIC(20,6) NOT NULL CHECK (quota_credits > 0),
    duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 3650),
    allowed_providers JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (plan_id, version)
);
INSERT INTO coding_plan_versions
    (plan_id, version, name, description, price_credits, quota_credits, duration_days, allowed_providers)
SELECT plan_id, version, name, description, price_credits, quota_credits, duration_days, allowed_providers
FROM coding_plans;

CREATE FUNCTION starry_api.publish_coding_plan_version() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.name, NEW.description, NEW.price_credits, NEW.quota_credits, NEW.duration_days, NEW.allowed_providers)
            IS NOT DISTINCT FROM
           ROW(OLD.name, OLD.description, OLD.price_credits, OLD.quota_credits, OLD.duration_days, OLD.allowed_providers) THEN
            NEW.version := OLD.version;
            RETURN NEW;
        END IF;
        NEW.version := OLD.version + 1;
    ELSE
        NEW.version := 1;
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION starry_api.record_coding_plan_version() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
BEGIN
    INSERT INTO coding_plan_versions
        (plan_id, version, name, description, price_credits, quota_credits, duration_days, allowed_providers)
    VALUES (NEW.plan_id, NEW.version, NEW.name, NEW.description, NEW.price_credits, NEW.quota_credits,
            NEW.duration_days, NEW.allowed_providers)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER coding_plan_version_number BEFORE INSERT OR UPDATE ON coding_plans
    FOR EACH ROW EXECUTE FUNCTION starry_api.publish_coding_plan_version();
CREATE TRIGGER coding_plan_version_history AFTER INSERT OR UPDATE ON coding_plans
    FOR EACH ROW EXECUTE FUNCTION starry_api.record_coding_plan_version();
CREATE FUNCTION starry_api.reject_coding_plan_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Coding plan history is append-only'; END $$;
CREATE TRIGGER coding_plan_versions_immutable BEFORE UPDATE OR DELETE ON coding_plan_versions
    FOR EACH ROW EXECUTE FUNCTION starry_api.reject_coding_plan_history_mutation();

ALTER TABLE coding_plan_subscriptions
    ADD COLUMN plan_version INTEGER,
    ADD COLUMN reserved_credits NUMERIC(20,6) NOT NULL DEFAULT 0,
    ADD COLUMN purchase_key VARCHAR(128),
    ADD COLUMN purchase_balance_after NUMERIC(20,6),
    ADD CONSTRAINT ck_subscription_reserved CHECK (reserved_credits >= 0 AND used_credits + reserved_credits <= quota_credits);
UPDATE coding_plan_subscriptions SET plan_version = 1, source_ref_id = NULLIF(source_ref_id, '');
ALTER TABLE coding_plan_subscriptions ALTER COLUMN plan_version SET NOT NULL;
ALTER TABLE coding_plan_subscriptions ADD FOREIGN KEY (plan_id, plan_version)
    REFERENCES coding_plan_versions(plan_id, version);
-- Existing orphan records are retained for investigation; new orphans are rejected.
ALTER TABLE coding_plan_subscriptions ADD CONSTRAINT fk_subscription_user
    FOREIGN KEY (user_id) REFERENCES admin_users(user_id) NOT VALID;
CREATE UNIQUE INDEX uk_subscription_purchase ON coding_plan_subscriptions(user_id, purchase_key)
    WHERE purchase_key IS NOT NULL;
CREATE UNIQUE INDEX uk_subscription_fulfillment ON coding_plan_subscriptions(source, source_ref_id)
    WHERE source IN ('REDEMPTION_CODE', 'ORDER') AND source_ref_id IS NOT NULL;

ALTER TABLE redemption_codes ADD COLUMN plan_version INTEGER;
UPDATE redemption_codes SET plan_version = 1 WHERE plan_id IS NOT NULL;
ALTER TABLE redemption_codes ADD FOREIGN KEY (plan_id, plan_version) REFERENCES coding_plan_versions(plan_id, version);
CREATE FUNCTION starry_api.snapshot_redemption_plan() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
BEGIN
    IF NEW.plan_id IS NOT NULL THEN
        SELECT version INTO NEW.plan_version FROM coding_plans WHERE plan_id = NEW.plan_id AND enabled;
        IF NEW.plan_version IS NULL THEN RAISE EXCEPTION 'Coding plan is unavailable'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER redemption_plan_version BEFORE INSERT ON redemption_codes
    FOR EACH ROW EXECUTE FUNCTION starry_api.snapshot_redemption_plan();

CREATE TABLE subscription_ledger (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id VARCHAR(64) NOT NULL REFERENCES coding_plan_subscriptions(subscription_id),
    entry_type VARCHAR(32) NOT NULL,
    amount NUMERIC(20,6) NOT NULL DEFAULT 0,
    reserved_delta NUMERIC(20,6) NOT NULL DEFAULT 0,
    reference_id VARCHAR(128),
    actor_id VARCHAR(64),
    reason VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_subscription_ledger_history ON subscription_ledger(subscription_id, id DESC);
CREATE TRIGGER subscription_ledger_immutable BEFORE UPDATE OR DELETE ON subscription_ledger
    FOR EACH ROW EXECUTE FUNCTION starry_api.reject_coding_plan_history_mutation();
INSERT INTO subscription_ledger(subscription_id, entry_type, amount, reason)
SELECT subscription_id, 'MIGRATION_OPENING', quota_credits - used_credits,
       'Opening balance at V13; historical usage retained in api_usage_events'
FROM coding_plan_subscriptions;

CREATE FUNCTION starry_api.activate_coding_plan(
    p_user VARCHAR, p_plan VARCHAR, p_version INTEGER, p_source VARCHAR,
    p_reference VARCHAR, p_key VARCHAR, p_actor VARCHAR
) RETURNS JSONB LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE
    owner admin_users%ROWTYPE;
    terms coding_plan_versions%ROWTYPE;
    previous coding_plan_subscriptions%ROWTYPE;
    new_id VARCHAR(64) := replace(gen_random_uuid()::text, '-', '');
    start_time TIMESTAMPTZ;
    new_balance NUMERIC(20,6);
BEGIN
    SELECT * INTO owner FROM admin_users WHERE user_id = p_user FOR UPDATE;
    IF NOT FOUND OR NOT owner.enabled OR owner.role <> 'USER' THEN
        RETURN jsonb_build_object('error', 'CODING_PLAN_USER_INELIGIBLE');
    END IF;
    SELECT * INTO previous FROM coding_plan_subscriptions WHERE user_id = p_user AND purchase_key = p_key;
    IF FOUND THEN
        IF previous.plan_id <> p_plan OR (p_version IS NOT NULL AND previous.plan_version <> p_version) THEN
            RETURN jsonb_build_object('error', 'IDEMPOTENCY_CONFLICT');
        END IF;
        RETURN jsonb_build_object('subscriptionId', previous.subscription_id, 'balance', previous.purchase_balance_after);
    END IF;
    IF p_source IN ('BALANCE_PURCHASE', 'ADMIN_GRANT') THEN
        SELECT v.* INTO terms FROM coding_plans p JOIN coding_plan_versions v
            ON v.plan_id = p.plan_id AND v.version = p.version
        WHERE p.plan_id = p_plan AND p.enabled;
        IF NOT FOUND THEN RETURN jsonb_build_object('error', 'CODING_PLAN_DISABLED'); END IF;
        IF p_version IS NOT NULL AND p_version <> terms.version THEN
            RETURN jsonb_build_object('error', 'CODING_PLAN_VERSION_CHANGED');
        END IF;
    ELSE
        SELECT * INTO terms FROM coding_plan_versions WHERE plan_id = p_plan AND version = p_version;
        IF NOT FOUND THEN RETURN jsonb_build_object('error', 'CODING_PLAN_NOT_FOUND'); END IF;
    END IF;
    new_balance := owner.balance;
    IF p_source = 'BALANCE_PURCHASE' THEN
        IF owner.balance < terms.price_credits THEN RETURN jsonb_build_object('error', 'INSUFFICIENT_BALANCE'); END IF;
        new_balance := owner.balance - terms.price_credits;
        IF terms.price_credits > 0 THEN
            INSERT INTO balance_ledger(transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                reference_type, reference_id, idempotency_key)
            VALUES (new_id, p_user, 'CODING_PLAN_PURCHASE', -terms.price_credits, owner.balance, new_balance,
                'CODING_PLAN', new_id, 'coding-plan-purchase:' || new_id);
            UPDATE admin_users SET balance = new_balance WHERE user_id = p_user;
        END IF;
    END IF;
    SELECT greatest(clock_timestamp(), coalesce(max(ends_at), clock_timestamp())) INTO start_time
    FROM coding_plan_subscriptions WHERE user_id = p_user AND plan_id = p_plan AND status = 'ACTIVE'
        AND ends_at > clock_timestamp() AND used_credits < quota_credits;
    INSERT INTO coding_plan_subscriptions(subscription_id, user_id, plan_id, plan_version, status, starts_at, ends_at,
        quota_credits, source, source_ref_id, purchase_key, purchase_balance_after)
    VALUES (new_id, p_user, p_plan, terms.version, 'ACTIVE', start_time,
        start_time + make_interval(days => terms.duration_days), terms.quota_credits,
        p_source, p_reference, p_key, new_balance);
    INSERT INTO subscription_ledger(subscription_id, entry_type, amount, reference_id, actor_id)
    VALUES (new_id, 'GRANT', terms.quota_credits, p_reference, p_actor);
    RETURN jsonb_build_object('subscriptionId', new_id, 'balance', new_balance);
END $$;

CREATE FUNCTION starry_api.advance_coding_plan_terms(p_user VARCHAR) RETURNS VOID
LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE next_term RECORD; shift_by INTERVAL;
BEGIN
    PERFORM 1 FROM admin_users WHERE user_id = p_user FOR UPDATE;
    FOR next_term IN
        SELECT DISTINCT ON (s.plan_id) s.* FROM coding_plan_subscriptions s
        WHERE s.user_id = p_user AND s.status = 'ACTIVE' AND s.starts_at > clock_timestamp()
          AND NOT EXISTS (SELECT 1 FROM coding_plan_subscriptions a WHERE a.user_id = p_user
              AND a.plan_id = s.plan_id AND a.status = 'ACTIVE' AND a.starts_at <= clock_timestamp()
              AND a.ends_at > clock_timestamp() AND a.used_credits < a.quota_credits)
        ORDER BY s.plan_id, s.starts_at, s.subscription_id
    LOOP
        shift_by := next_term.starts_at - clock_timestamp();
        WITH changed AS (
            UPDATE coding_plan_subscriptions SET starts_at = starts_at - shift_by, ends_at = ends_at - shift_by
            WHERE user_id = p_user AND plan_id = next_term.plan_id AND status = 'ACTIVE'
                AND starts_at >= next_term.starts_at RETURNING subscription_id
        ) INSERT INTO subscription_ledger(subscription_id, entry_type, reason)
          SELECT subscription_id, 'TERM_ADVANCED', 'Previous term ended or exhausted' FROM changed;
    END LOOP;
END $$;

CREATE FUNCTION starry_api.change_coding_plan_status(p_id VARCHAR, p_status VARCHAR, p_actor VARCHAR, p_reason VARCHAR)
RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE uid VARCHAR(64); current_term coding_plan_subscriptions%ROWTYPE;
BEGIN
    SELECT user_id INTO uid FROM coding_plan_subscriptions WHERE subscription_id = p_id;
    PERFORM 1 FROM admin_users WHERE user_id = uid FOR UPDATE;
    SELECT * INTO current_term FROM coding_plan_subscriptions WHERE subscription_id = p_id FOR UPDATE;
    IF NOT FOUND THEN RETURN 'SUBSCRIPTION_NOT_FOUND'; END IF;
    IF p_status NOT IN ('ACTIVE','CANCELLED','REVOKED') THEN RETURN 'SUBSCRIPTION_STATUS_INVALID'; END IF;
    IF p_status = 'ACTIVE' AND current_term.ends_at <= clock_timestamp() THEN RETURN 'SUBSCRIPTION_EXPIRED'; END IF;
    IF p_status = current_term.status THEN RETURN 'UPDATED'; END IF;
    UPDATE coding_plan_subscriptions SET status = p_status WHERE subscription_id = p_id;
    INSERT INTO subscription_ledger(subscription_id,entry_type,actor_id,reason)
    VALUES(p_id,p_status,p_actor,p_reason);
    PERFORM advance_coding_plan_terms(uid);
    RETURN 'UPDATED';
END $$;
REVOKE ALL ON FUNCTION starry_api.change_coding_plan_status(VARCHAR,VARCHAR,VARCHAR,VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.change_coding_plan_status(VARCHAR,VARCHAR,VARCHAR,VARCHAR) TO starry_api_runtime;

REVOKE ALL ON TABLE coding_plan_versions, subscription_ledger FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE coding_plan_versions, subscription_ledger TO starry_api_runtime;
GRANT USAGE, SELECT ON SEQUENCE subscription_ledger_id_seq TO starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.activate_coding_plan(VARCHAR,VARCHAR,INTEGER,VARCHAR,VARCHAR,VARCHAR,VARCHAR),
    starry_api.advance_coding_plan_terms(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.activate_coding_plan(VARCHAR,VARCHAR,INTEGER,VARCHAR,VARCHAR,VARCHAR,VARCHAR),
    starry_api.advance_coding_plan_terms(VARCHAR) TO starry_api_runtime;

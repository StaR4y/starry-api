ALTER TABLE api_keys
    ADD COLUMN billing_source VARCHAR(32) NOT NULL DEFAULT 'BALANCE'
        CHECK (billing_source IN ('BALANCE', 'CODING_PLAN')),
    ADD COLUMN reserved_credits NUMERIC(20,6) NOT NULL DEFAULT 0 CHECK (reserved_credits >= 0);
UPDATE api_keys SET billing_minimum_credits = 0;

CREATE TABLE billing_reservations (
    request_hash VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    billing_source VARCHAR(32) NOT NULL CHECK (billing_source IN ('BALANCE', 'CODING_PLAN')),
    charge_user BOOLEAN NOT NULL,
    model_provider VARCHAR(64),
    status VARCHAR(32) NOT NULL CHECK (status IN ('RESERVED', 'DISPATCHED', 'SETTLED', 'RELEASED', 'REVIEW_REQUIRED')),
    reserved_credits NUMERIC(20,6) NOT NULL CHECK (reserved_credits >= 0),
    actual_credits NUMERIC(20,6) CHECK (actual_credits >= 0),
    input_tokens BIGINT,
    output_tokens BIGINT,
    reason VARCHAR(512),
    pricing JSONB NOT NULL DEFAULT '{}',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_billing_reservation_recovery ON billing_reservations(status, expires_at)
    WHERE status IN ('RESERVED', 'DISPATCHED', 'REVIEW_REQUIRED');
CREATE INDEX idx_billing_reservation_user ON billing_reservations(user_id, created_at DESC);
CREATE TABLE billing_reservation_allocations (
    request_hash VARCHAR(64) NOT NULL REFERENCES billing_reservations(request_hash),
    subscription_id VARCHAR(64) NOT NULL REFERENCES coding_plan_subscriptions(subscription_id),
    reserved_credits NUMERIC(20,6) NOT NULL CHECK (reserved_credits >= 0),
    settled_credits NUMERIC(20,6) NOT NULL DEFAULT 0 CHECK (settled_credits >= 0),
    PRIMARY KEY (request_hash, subscription_id)
);
CREATE FUNCTION starry_api.guard_api_key_billing_change() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
BEGIN
    IF OLD.reserved_credits > 0 AND (TG_OP = 'DELETE' OR
        ROW(NEW.billing_source, NEW.model_provider) IS DISTINCT FROM ROW(OLD.billing_source, OLD.model_provider)) THEN
        RAISE EXCEPTION 'API_KEY_BILLING_BUSY' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER api_key_billing_change BEFORE UPDATE OR DELETE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION starry_api.guard_api_key_billing_change();

-- All billing writers lock owner -> key -> reservation -> subscriptions, in that order.
CREATE FUNCTION starry_api.reserve_api_usage(
    p_key VARCHAR, p_hash VARCHAR, p_request VARCHAR, p_credits NUMERIC, p_pricing JSONB
) RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE
    owner admin_users%ROWTYPE; k api_keys%ROWTYPE; existing billing_reservations%ROWTYPE;
    sub coding_plan_subscriptions%ROWTYPE; amount NUMERIC(20,6); remaining NUMERIC(20,6);
    available NUMERIC(20,6); uid VARCHAR(64); frozen NUMERIC(20,6) := ceil(p_credits * 1000000) / 1000000;
BEGIN
    IF p_credits IS NULL OR p_credits < 0 THEN RETURN 'BILLING_AMOUNT_INVALID'; END IF;
    SELECT user_id INTO uid FROM api_keys WHERE key_id = p_key;
    SELECT * INTO owner FROM admin_users WHERE user_id = uid FOR UPDATE;
    IF NOT FOUND THEN RETURN 'OWNER_NOT_FOUND'; END IF;
    SELECT * INTO k FROM api_keys WHERE key_id = p_key FOR UPDATE;
    IF NOT FOUND THEN RETURN 'KEY_NOT_FOUND'; END IF;
    SELECT * INTO existing FROM billing_reservations WHERE request_hash = p_hash FOR UPDATE;
    IF FOUND THEN
        IF existing.key_id <> p_key OR existing.request_id <> p_request THEN RETURN 'IDEMPOTENCY_CONFLICT'; END IF;
        IF existing.status IN ('RESERVED', 'DISPATCHED') THEN RETURN 'RESERVED'; END IF;
        RETURN 'BILLING_REQUEST_FINISHED';
    END IF;
    IF NOT k.enabled THEN RETURN 'KEY_DISABLED'; END IF;
    IF NOT owner.enabled THEN RETURN 'OWNER_DISABLED'; END IF;
    IF EXISTS (SELECT 1 FROM billing_reservations WHERE user_id = uid AND status = 'REVIEW_REQUIRED') THEN
        RETURN 'BILLING_REVIEW_REQUIRED';
    END IF;
    IF k.credits_limit IS NOT NULL AND k.total_credits + k.reserved_credits + frozen > k.credits_limit THEN
        RETURN 'CREDITS_LIMIT_EXCEEDED';
    END IF;
    IF owner.role = 'USER' AND k.billing_source = 'BALANCE' AND owner.balance < frozen THEN
        RETURN 'INSUFFICIENT_BALANCE';
    END IF;
    IF owner.role = 'USER' AND k.billing_source = 'CODING_PLAN' THEN
        PERFORM advance_coding_plan_terms(uid);
        SELECT coalesce(sum(s.quota_credits - s.used_credits - s.reserved_credits), 0) INTO available
        FROM coding_plan_subscriptions s JOIN coding_plan_versions v ON v.plan_id = s.plan_id AND v.version = s.plan_version
        WHERE s.user_id = uid AND s.status = 'ACTIVE' AND s.starts_at <= clock_timestamp() AND s.ends_at > clock_timestamp()
            AND (v.allowed_providers IS NULL OR v.allowed_providers @> jsonb_build_array(k.model_provider));
        IF available < frozen OR available <= 0 THEN RETURN 'CODING_PLAN_QUOTA_EXCEEDED'; END IF;
    END IF;
    INSERT INTO billing_reservations(request_hash, request_id, key_id, user_id, billing_source, charge_user,
        model_provider, status, reserved_credits, expires_at, pricing)
    VALUES (p_hash, p_request, p_key, uid, k.billing_source, owner.role = 'USER', k.model_provider, 'RESERVED',
        frozen, clock_timestamp() + interval '15 minutes', coalesce(p_pricing, '{}'));
    IF owner.role = 'USER' AND k.billing_source = 'BALANCE' AND frozen > 0 THEN
        UPDATE admin_users SET balance = balance - frozen WHERE user_id = uid;
        INSERT INTO balance_ledger(transaction_id, user_id, entry_type, amount, balance_before, balance_after,
            reference_type, reference_id, idempotency_key)
        VALUES (replace(gen_random_uuid()::text, '-', ''), uid, 'API_USAGE_RESERVE', -frozen, owner.balance,
            owner.balance - frozen, 'API_REQUEST', p_request, 'billing-reserve:' || p_hash);
    ELSIF owner.role = 'USER' AND k.billing_source = 'CODING_PLAN' THEN
        remaining := frozen;
        FOR sub IN SELECT s.* FROM coding_plan_subscriptions s JOIN coding_plan_versions v
            ON v.plan_id = s.plan_id AND v.version = s.plan_version
            WHERE s.user_id = uid AND s.status = 'ACTIVE' AND s.starts_at <= clock_timestamp() AND s.ends_at > clock_timestamp()
                AND s.quota_credits > s.used_credits + s.reserved_credits
                AND (v.allowed_providers IS NULL OR v.allowed_providers @> jsonb_build_array(k.model_provider))
            ORDER BY s.ends_at, s.subscription_id FOR UPDATE OF s
        LOOP
            EXIT WHEN remaining <= 0;
            amount := least(remaining, sub.quota_credits - sub.used_credits - sub.reserved_credits);
            INSERT INTO billing_reservation_allocations(request_hash, subscription_id, reserved_credits)
            VALUES(p_hash, sub.subscription_id, amount);
            UPDATE coding_plan_subscriptions SET reserved_credits = reserved_credits + amount WHERE subscription_id = sub.subscription_id;
            INSERT INTO subscription_ledger(subscription_id, entry_type, reserved_delta, reference_id)
            VALUES (sub.subscription_id, 'RESERVE', amount, p_hash);
            remaining := remaining - amount;
        END LOOP;
    END IF;
    UPDATE api_keys SET reserved_credits = reserved_credits + frozen WHERE key_id = p_key;
    RETURN 'RESERVED';
END $$;

CREATE FUNCTION starry_api.settle_api_usage(p_hash VARCHAR, p_credits NUMERIC, p_input BIGINT, p_output BIGINT)
RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE
    r billing_reservations%ROWTYPE; k api_keys%ROWTYPE; owner admin_users%ROWTYPE;
    allocation RECORD; amount NUMERIC(20,6); remaining NUMERIC(20,6); refund NUMERIC(20,6);
    actual NUMERIC(20,6) := round(p_credits, 6); uid VARCHAR(64); kid VARCHAR(64);
    extra NUMERIC(20,6); available NUMERIC(20,6); sub RECORD;
BEGIN
    IF p_credits IS NULL OR p_credits < 0 OR p_input < 0 OR p_output < 0 THEN RETURN 'BILLING_AMOUNT_INVALID'; END IF;
    SELECT user_id, key_id INTO uid, kid FROM billing_reservations WHERE request_hash = p_hash;
    IF NOT FOUND THEN RETURN 'BILLING_RESERVATION_REQUIRED'; END IF;
    SELECT * INTO owner FROM admin_users WHERE user_id = uid FOR UPDATE;
    SELECT * INTO k FROM api_keys WHERE key_id = kid FOR UPDATE;
    SELECT * INTO r FROM billing_reservations WHERE request_hash = p_hash FOR UPDATE;
    IF r.status = 'SETTLED' THEN
        IF r.actual_credits <> actual OR r.input_tokens <> p_input OR r.output_tokens <> p_output THEN RETURN 'IDEMPOTENCY_CONFLICT'; END IF;
        RETURN 'ALREADY_ACCOUNTED';
    END IF;
    IF r.status = 'RELEASED' THEN RETURN 'BILLING_REQUEST_FINISHED'; END IF;
    IF r.actual_credits IS NOT NULL AND ROW(r.actual_credits,r.input_tokens,r.output_tokens)
        IS DISTINCT FROM ROW(actual,p_input,p_output) THEN RETURN 'IDEMPOTENCY_CONFLICT'; END IF;
    -- Top up only from the admitted source. If funds are unavailable, retain the hold and actual usage.
    IF actual > r.reserved_credits THEN
        extra := actual - r.reserved_credits;
        available := owner.balance;
        IF r.charge_user AND r.billing_source = 'CODING_PLAN' THEN
            SELECT coalesce(sum(s.quota_credits - s.used_credits - s.reserved_credits),0) INTO available
            FROM coding_plan_subscriptions s JOIN coding_plan_versions v ON v.plan_id = s.plan_id AND v.version = s.plan_version
            WHERE s.user_id = uid AND s.status = 'ACTIVE' AND s.starts_at <= clock_timestamp() AND s.ends_at > clock_timestamp()
                AND (v.allowed_providers IS NULL OR v.allowed_providers @> jsonb_build_array(r.model_provider));
        END IF;
        IF (r.charge_user AND available < extra) OR
            (k.credits_limit IS NOT NULL AND k.total_credits + k.reserved_credits + extra > k.credits_limit) THEN
            UPDATE billing_reservations SET actual_credits = actual, input_tokens = p_input, output_tokens = p_output,
                status = 'REVIEW_REQUIRED', reason = 'Actual usage exceeded available reservation', updated_at = clock_timestamp()
            WHERE request_hash = p_hash;
            RETURN 'BILLING_REVIEW_REQUIRED';
        END IF;
        IF r.charge_user AND r.billing_source = 'BALANCE' THEN
            UPDATE admin_users SET balance = balance - extra WHERE user_id = uid;
            INSERT INTO balance_ledger(transaction_id,user_id,entry_type,amount,balance_before,balance_after,
                reference_type,reference_id,idempotency_key)
            VALUES(replace(gen_random_uuid()::text,'-',''),uid,'API_USAGE_TOPUP',-extra,owner.balance,
                owner.balance-extra,'API_REQUEST',r.request_id,'billing-topup:' || p_hash);
            owner.balance := owner.balance - extra;
        ELSIF r.charge_user AND r.billing_source = 'CODING_PLAN' THEN
            remaining := extra;
            FOR sub IN SELECT s.* FROM coding_plan_subscriptions s JOIN coding_plan_versions v
                ON v.plan_id=s.plan_id AND v.version=s.plan_version WHERE s.user_id=uid AND s.status='ACTIVE'
                AND s.starts_at<=clock_timestamp() AND s.ends_at>clock_timestamp() AND s.quota_credits>s.used_credits+s.reserved_credits
                AND (v.allowed_providers IS NULL OR v.allowed_providers @> jsonb_build_array(r.model_provider))
                ORDER BY s.ends_at,s.subscription_id FOR UPDATE OF s
            LOOP
                EXIT WHEN remaining<=0;
                amount:=least(remaining,sub.quota_credits-sub.used_credits-sub.reserved_credits);
                INSERT INTO billing_reservation_allocations(request_hash,subscription_id,reserved_credits)
                VALUES(p_hash,sub.subscription_id,amount) ON CONFLICT(request_hash,subscription_id)
                DO UPDATE SET reserved_credits=billing_reservation_allocations.reserved_credits+excluded.reserved_credits;
                UPDATE coding_plan_subscriptions SET reserved_credits=reserved_credits+amount WHERE subscription_id=sub.subscription_id;
                INSERT INTO subscription_ledger(subscription_id,entry_type,reserved_delta,reference_id)
                VALUES(sub.subscription_id,'RESERVE',amount,p_hash);
                remaining:=remaining-amount;
            END LOOP;
        END IF;
        UPDATE api_keys SET reserved_credits=reserved_credits+extra WHERE key_id=kid;
        UPDATE billing_reservations SET reserved_credits=actual WHERE request_hash=p_hash;
        r.reserved_credits:=actual;
    END IF;
    refund := r.reserved_credits - actual;
    IF r.charge_user AND r.billing_source = 'BALANCE' AND refund > 0 THEN
        UPDATE admin_users SET balance = balance + refund WHERE user_id = uid;
        INSERT INTO balance_ledger(transaction_id, user_id, entry_type, amount, balance_before, balance_after,
            reference_type, reference_id, idempotency_key)
        VALUES (replace(gen_random_uuid()::text, '-', ''), uid, 'API_USAGE_RELEASE', refund, owner.balance,
            owner.balance + refund, 'API_REQUEST', r.request_id, 'billing-release:' || p_hash);
    ELSIF r.charge_user AND r.billing_source = 'CODING_PLAN' THEN
        remaining := actual;
        FOR allocation IN SELECT a.*, s.ends_at FROM billing_reservation_allocations a
            JOIN coding_plan_subscriptions s USING(subscription_id)
            WHERE a.request_hash = p_hash ORDER BY s.ends_at, a.subscription_id FOR UPDATE OF s
        LOOP
            amount := least(remaining, allocation.reserved_credits);
            UPDATE coding_plan_subscriptions SET used_credits = used_credits + amount,
                reserved_credits = reserved_credits - allocation.reserved_credits WHERE subscription_id = allocation.subscription_id;
            UPDATE billing_reservation_allocations SET settled_credits = amount
            WHERE request_hash = p_hash AND subscription_id = allocation.subscription_id;
            INSERT INTO subscription_ledger(subscription_id, entry_type, amount, reserved_delta, reference_id)
            VALUES (allocation.subscription_id, 'SETTLE', -amount, -allocation.reserved_credits, p_hash);
            remaining := remaining - amount;
        END LOOP;
        PERFORM advance_coding_plan_terms(uid);
    END IF;
    INSERT INTO api_usage_events(request_hash, request_id, key_id, user_id, usage_date, credits, input_tokens,
        output_tokens, charge_balance, subscription_id)
    VALUES (p_hash, r.request_id, kid, uid, (clock_timestamp() AT TIME ZONE 'UTC')::date, actual, p_input, p_output,
        r.charge_user AND r.billing_source = 'BALANCE',
        (SELECT subscription_id FROM billing_reservation_allocations WHERE request_hash = p_hash ORDER BY subscription_id LIMIT 1));
    UPDATE api_keys SET total_requests = total_requests + 1, total_credits = total_credits + actual,
        total_input_tokens = total_input_tokens + p_input, total_output_tokens = total_output_tokens + p_output,
        reserved_credits = reserved_credits - r.reserved_credits, billing_minimum_credits = 0, last_used_at = clock_timestamp()
    WHERE key_id = kid;
    INSERT INTO api_key_usage_daily(key_id, usage_date, requests, credits, input_tokens, output_tokens)
    VALUES (kid, (clock_timestamp() AT TIME ZONE 'UTC')::date, 1, actual, p_input, p_output)
    ON CONFLICT(key_id, usage_date) DO UPDATE SET requests = api_key_usage_daily.requests + 1,
        credits = api_key_usage_daily.credits + excluded.credits,
        input_tokens = api_key_usage_daily.input_tokens + excluded.input_tokens,
        output_tokens = api_key_usage_daily.output_tokens + excluded.output_tokens;
    UPDATE billing_reservations SET status = 'SETTLED', actual_credits = actual, input_tokens = p_input,
        output_tokens = p_output, updated_at = clock_timestamp() WHERE request_hash = p_hash;
    RETURN 'ACCOUNTED';
END $$;

CREATE FUNCTION starry_api.release_api_reservation(p_hash VARCHAR, p_definitive BOOLEAN, p_reason VARCHAR)
RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE r billing_reservations%ROWTYPE; owner admin_users%ROWTYPE; allocation RECORD; uid VARCHAR(64); kid VARCHAR(64);
BEGIN
    SELECT user_id, key_id INTO uid, kid FROM billing_reservations WHERE request_hash = p_hash;
    IF NOT FOUND THEN RETURN 'RELEASED'; END IF;
    SELECT * INTO owner FROM admin_users WHERE user_id = uid FOR UPDATE;
    PERFORM 1 FROM api_keys WHERE key_id = kid FOR UPDATE;
    SELECT * INTO r FROM billing_reservations WHERE request_hash = p_hash FOR UPDATE;
    IF r.status IN ('SETTLED','RELEASED') THEN RETURN r.status; END IF;
    IF r.actual_credits IS NOT NULL OR (r.status <> 'RESERVED' AND NOT p_definitive) THEN
        UPDATE billing_reservations SET status = 'REVIEW_REQUIRED', reason = left(p_reason,512), updated_at = clock_timestamp()
        WHERE request_hash = p_hash;
        RETURN 'BILLING_REVIEW_REQUIRED';
    END IF;
    IF r.charge_user AND r.billing_source = 'BALANCE' AND r.reserved_credits > 0 THEN
        UPDATE admin_users SET balance = balance + r.reserved_credits WHERE user_id = uid;
        INSERT INTO balance_ledger(transaction_id,user_id,entry_type,amount,balance_before,balance_after,
            reference_type,reference_id,idempotency_key)
        VALUES(replace(gen_random_uuid()::text,'-',''),uid,'API_USAGE_RELEASE',r.reserved_credits,owner.balance,
            owner.balance + r.reserved_credits,'API_REQUEST',r.request_id,'billing-release:' || p_hash);
    ELSE
        FOR allocation IN SELECT * FROM billing_reservation_allocations WHERE request_hash = p_hash ORDER BY subscription_id LOOP
            UPDATE coding_plan_subscriptions SET reserved_credits = reserved_credits - allocation.reserved_credits
            WHERE subscription_id = allocation.subscription_id;
            INSERT INTO subscription_ledger(subscription_id,entry_type,reserved_delta,reference_id,reason)
            VALUES(allocation.subscription_id,'RELEASE',-allocation.reserved_credits,p_hash,left(p_reason,512));
        END LOOP;
    END IF;
    UPDATE api_keys SET reserved_credits = reserved_credits - r.reserved_credits WHERE key_id = kid;
    UPDATE billing_reservations SET status = 'RELEASED', reason = left(p_reason,512), updated_at = clock_timestamp() WHERE request_hash = p_hash;
    RETURN 'RELEASED';
END $$;

CREATE TABLE billing_review_actions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL REFERENCES billing_reservations(request_hash),
    actor_id VARCHAR(64) NOT NULL, action VARCHAR(32) NOT NULL, reason VARCHAR(512) NOT NULL,
    original_credits NUMERIC(20,6), resolved_credits NUMERIC(20,6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER billing_review_actions_immutable BEFORE UPDATE OR DELETE ON billing_review_actions
    FOR EACH ROW EXECUTE FUNCTION starry_api.reject_coding_plan_history_mutation();
CREATE FUNCTION starry_api.resolve_billing_review(p_hash VARCHAR,p_actor VARCHAR,p_action VARCHAR,p_reason VARCHAR,p_credits NUMERIC)
RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog,starry_api AS $$
DECLARE r billing_reservations%ROWTYPE; result TEXT;
BEGIN
    SELECT * INTO r FROM billing_reservations WHERE request_hash=p_hash;
    IF NOT FOUND THEN RETURN 'BILLING_RESERVATION_REQUIRED'; END IF;
    PERFORM 1 FROM admin_users WHERE user_id=r.user_id FOR UPDATE;
    PERFORM 1 FROM api_keys WHERE key_id=r.key_id FOR UPDATE;
    SELECT * INTO r FROM billing_reservations WHERE request_hash=p_hash FOR UPDATE;
    IF r.status<>'REVIEW_REQUIRED' THEN RETURN 'BILLING_REQUEST_FINISHED'; END IF;
    IF p_action NOT IN ('SETTLE','WAIVE') OR p_reason IS NULL OR btrim(p_reason)='' THEN RETURN 'BILLING_REVIEW_INVALID'; END IF;
    IF p_action='WAIVE' THEN p_credits:=0; END IF;
    IF p_credits IS NULL OR p_credits<0 THEN RETURN 'BILLING_AMOUNT_INVALID'; END IF;
    UPDATE billing_reservations SET actual_credits=NULL WHERE request_hash=p_hash;
    result:=settle_api_usage(p_hash,p_credits,coalesce(r.input_tokens,0),coalesce(r.output_tokens,0));
    INSERT INTO billing_review_actions(request_hash,actor_id,action,reason,original_credits,resolved_credits)
    VALUES(p_hash,p_actor,p_action,left(p_reason,512),r.actual_credits,p_credits);
    RETURN result;
END $$;
REVOKE ALL ON FUNCTION starry_api.resolve_billing_review(VARCHAR,VARCHAR,VARCHAR,VARCHAR,NUMERIC) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.resolve_billing_review(VARCHAR,VARCHAR,VARCHAR,VARCHAR,NUMERIC) TO starry_api_runtime;
GRANT SELECT,INSERT ON billing_review_actions TO starry_api_runtime;
GRANT USAGE,SELECT ON SEQUENCE billing_review_actions_id_seq TO starry_api_runtime;
REVOKE ALL ON TABLE billing_reservations, billing_reservation_allocations, billing_review_actions FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE billing_reservations, billing_reservation_allocations TO starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.reserve_api_usage(VARCHAR,VARCHAR,VARCHAR,NUMERIC,JSONB),
    starry_api.settle_api_usage(VARCHAR,NUMERIC,BIGINT,BIGINT),
    starry_api.release_api_reservation(VARCHAR,BOOLEAN,VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starry_api.reserve_api_usage(VARCHAR,VARCHAR,VARCHAR,NUMERIC,JSONB),
    starry_api.settle_api_usage(VARCHAR,NUMERIC,BIGINT,BIGINT),
    starry_api.release_api_reservation(VARCHAR,BOOLEAN,VARCHAR) TO starry_api_runtime;

-- Use one eligibility instant after acquiring the owner lock, including at expiry boundaries.
-- Preserve V13/V14 history for databases which have already run the initial rebuild.
CREATE INDEX idx_billing_unfinished_key ON billing_reservations(key_id)
    WHERE status IN ('RESERVED','DISPATCHED','REVIEW_REQUIRED');
CREATE INDEX idx_billing_review_owner ON billing_reservations(user_id)
    WHERE status = 'REVIEW_REQUIRED';
CREATE OR REPLACE FUNCTION starry_api.reserve_api_usage(
    p_key VARCHAR, p_hash VARCHAR, p_request VARCHAR, p_credits NUMERIC, p_pricing JSONB
) RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE
    owner admin_users%ROWTYPE; k api_keys%ROWTYPE; existing billing_reservations%ROWTYPE;
    sub coding_plan_subscriptions%ROWTYPE; amount NUMERIC(20,6); remaining NUMERIC(20,6);
    available NUMERIC(20,6); eligibility_at TIMESTAMPTZ; uid VARCHAR(64); frozen NUMERIC(20,6) := ceil(p_credits * 1000000) / 1000000;
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
        eligibility_at := clock_timestamp();
        SELECT coalesce(sum(s.quota_credits - s.used_credits - s.reserved_credits), 0) INTO available
        FROM coding_plan_subscriptions s JOIN coding_plan_versions v ON v.plan_id = s.plan_id AND v.version = s.plan_version
        WHERE s.user_id = uid AND s.status = 'ACTIVE' AND s.starts_at <= eligibility_at AND s.ends_at > eligibility_at
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
            WHERE s.user_id = uid AND s.status = 'ACTIVE' AND s.starts_at <= eligibility_at AND s.ends_at > eligibility_at
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
        IF remaining <> 0 THEN RAISE EXCEPTION 'BILLING_ALLOCATION_INCOMPLETE'; END IF;
    END IF;
    UPDATE api_keys SET reserved_credits = reserved_credits + frozen WHERE key_id = p_key;
    RETURN 'RESERVED';
END $$;

CREATE OR REPLACE FUNCTION starry_api.settle_api_usage(p_hash VARCHAR, p_credits NUMERIC, p_input BIGINT, p_output BIGINT)
RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE
    r billing_reservations%ROWTYPE; k api_keys%ROWTYPE; owner admin_users%ROWTYPE;
    allocation RECORD; amount NUMERIC(20,6); remaining NUMERIC(20,6); refund NUMERIC(20,6);
    actual NUMERIC(20,6) := round(p_credits, 6); uid VARCHAR(64); kid VARCHAR(64);
    extra NUMERIC(20,6); available NUMERIC(20,6); sub RECORD; eligibility_at TIMESTAMPTZ;
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
        eligibility_at := clock_timestamp();
        available := owner.balance;
        IF r.charge_user AND r.billing_source = 'CODING_PLAN' THEN
            SELECT coalesce(sum(s.quota_credits - s.used_credits - s.reserved_credits),0) INTO available
            FROM coding_plan_subscriptions s JOIN coding_plan_versions v ON v.plan_id = s.plan_id AND v.version = s.plan_version
            WHERE s.user_id = uid AND s.status = 'ACTIVE' AND s.starts_at <= eligibility_at AND s.ends_at > eligibility_at
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
                AND s.starts_at<=eligibility_at AND s.ends_at>eligibility_at AND s.quota_credits>s.used_credits+s.reserved_credits
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
            IF remaining <> 0 THEN RAISE EXCEPTION 'BILLING_ALLOCATION_INCOMPLETE'; END IF;
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
        IF remaining <> 0 THEN RAISE EXCEPTION 'BILLING_ALLOCATION_INCOMPLETE'; END IF;
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


CREATE OR REPLACE FUNCTION starry_api.guard_api_key_billing_change() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
BEGIN
    IF (TG_OP = 'DELETE' OR
        ROW(NEW.billing_source, NEW.model_provider) IS DISTINCT FROM ROW(OLD.billing_source, OLD.model_provider))
        AND (OLD.reserved_credits > 0 OR EXISTS (
            SELECT 1 FROM billing_reservations WHERE key_id = OLD.key_id
                AND status IN ('RESERVED','DISPATCHED','REVIEW_REQUIRED'))) THEN
        RAISE EXCEPTION 'API_KEY_BILLING_BUSY' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION starry_api.change_coding_plan_status(p_id VARCHAR, p_status VARCHAR, p_actor VARCHAR, p_reason VARCHAR)
RETURNS TEXT LANGUAGE plpgsql SET search_path = pg_catalog, starry_api AS $$
DECLARE uid VARCHAR(64); current_term coding_plan_subscriptions%ROWTYPE;
BEGIN
    SELECT user_id INTO uid FROM coding_plan_subscriptions WHERE subscription_id = p_id;
    PERFORM 1 FROM admin_users WHERE user_id = uid FOR UPDATE;
    SELECT * INTO current_term FROM coding_plan_subscriptions WHERE subscription_id = p_id FOR UPDATE;
    IF NOT FOUND THEN RETURN 'SUBSCRIPTION_NOT_FOUND'; END IF;
    IF p_status NOT IN ('ACTIVE','CANCELLED','REVOKED') OR p_reason IS NULL OR btrim(p_reason)='' THEN RETURN 'SUBSCRIPTION_STATUS_INVALID'; END IF;
    IF p_status = 'ACTIVE' AND current_term.ends_at <= clock_timestamp() THEN RETURN 'SUBSCRIPTION_EXPIRED'; END IF;
    IF p_status = current_term.status THEN RETURN 'UPDATED'; END IF;
    IF p_status = 'ACTIVE' AND EXISTS (
        SELECT 1 FROM coding_plan_subscriptions s
        WHERE s.user_id=uid AND s.plan_id=current_term.plan_id AND s.subscription_id<>p_id
            AND s.status='ACTIVE' AND s.used_credits<s.quota_credits
            AND s.starts_at<current_term.ends_at AND s.ends_at>current_term.starts_at
    ) THEN RETURN 'SUBSCRIPTION_TERM_OVERLAP'; END IF;
    UPDATE coding_plan_subscriptions SET status = p_status WHERE subscription_id = p_id;
    INSERT INTO subscription_ledger(subscription_id,entry_type,actor_id,reason)
    VALUES(p_id,p_status,p_actor,p_reason);
    PERFORM advance_coding_plan_terms(uid);
    RETURN 'UPDATED';
END $$;

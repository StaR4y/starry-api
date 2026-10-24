-- The routing limits a new account starts with used to be hard-coded in the account-creation trigger (and again in
-- the console form). They now come from administrator-managed system settings, one JSON object per account
-- category under account_allocation_default_<category>; a missing or invalid setting falls back to the built-in
-- values below, which are exactly what the previous trigger used. Existing accounts are not changed.

CREATE FUNCTION starry_api.classify_account_allocation(p_provider TEXT, p_auth_method TEXT, p_base_url TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    normalized_provider TEXT := pg_catalog.lower(COALESCE(p_provider, ''));
    normalized_auth TEXT := pg_catalog.lower(COALESCE(p_auth_method, ''));
BEGIN
    IF normalized_auth IN ('api-key', 'apikey', 'openai-api-key') THEN
        -- Official means HTTPS on the default port without user info and with the exact official host; a missing
        -- Base URL means the provider's default endpoint.
        IF (normalized_provider = 'openai' AND
                pg_catalog.lower(COALESCE(NULLIF(pg_catalog.btrim(p_base_url), ''), 'https://api.openai.com/v1'))
                    ~ '^https://api[.]openai[.]com(:443)?(/[^?#]*)?$')
            OR (normalized_provider IN ('anthropic', 'claude') AND
                pg_catalog.lower(COALESCE(NULLIF(pg_catalog.btrim(p_base_url), ''), 'https://api.anthropic.com/v1'))
                    ~ '^https://api[.]anthropic[.]com(:443)?(/[^?#]*)?$') THEN
            RETURN 'OFFICIAL_API_KEY';
        END IF;
        RETURN 'COMPATIBLE_API_KEY';
    END IF;
    IF normalized_provider = 'codex' OR normalized_auth = 'codex' THEN
        RETURN 'CODEX';
    END IF;
    IF normalized_provider IN ('anthropic', 'claude') THEN
        RETURN 'CLAUDE_OAUTH';
    END IF;
    RETURN 'KIRO';
END;
$$;

CREATE FUNCTION starry_api.account_allocation_builtin_defaults(p_category TEXT)
RETURNS JSONB
LANGUAGE sql
IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT pg_catalog.jsonb_build_object(
        'allocationPriority', 100,
        'safeConcurrency', CASE p_category WHEN 'OFFICIAL_API_KEY' THEN 14 WHEN 'COMPATIBLE_API_KEY' THEN 0
            WHEN 'CODEX' THEN 4 ELSE 2 END,
        'safeRpm', CASE p_category WHEN 'OFFICIAL_API_KEY' THEN 0 WHEN 'COMPATIBLE_API_KEY' THEN 0
            WHEN 'CODEX' THEN 60 ELSE 30 END,
        'burstCapacity', CASE p_category WHEN 'OFFICIAL_API_KEY' THEN 0 WHEN 'COMPATIBLE_API_KEY' THEN 0
            WHEN 'CODEX' THEN 4 ELSE 2 END,
        'maxSessions', CASE p_category WHEN 'CLAUDE_OAUTH' THEN 4 ELSE 0 END,
        'sessionIdleSeconds', 600,
        'cooldownSeconds', 0,
        'maxInflightTokens', 1000000,
        'tlsProfile', '');
$$;

-- The effective defaults for a category: the stored setting when it is complete and valid, otherwise the built-ins.
-- The ranges are those of account_allocation_policies, plus "burst is 0 exactly when the rate limit is off".
CREATE FUNCTION starry_api.account_allocation_defaults(p_category TEXT)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    builtin JSONB := starry_api.account_allocation_builtin_defaults(p_category);
    stored TEXT;
    configured JSONB;
    field TEXT;
    bounds INTEGER[];
BEGIN
    SELECT setting_value INTO stored FROM starry_api.system_settings
    WHERE setting_key = 'account_allocation_default_' || pg_catalog.lower(p_category);
    IF stored IS NULL THEN
        RETURN builtin;
    END IF;
    BEGIN
        configured := stored::JSONB;
        IF pg_catalog.jsonb_typeof(configured) <> 'object' THEN
            RETURN builtin;
        END IF;
        FOR field, bounds IN
            SELECT * FROM (VALUES
                ('allocationPriority', ARRAY[1, 10000]),
                ('safeConcurrency', ARRAY[0, 10000]),
                ('safeRpm', ARRAY[0, 1000000]),
                ('burstCapacity', ARRAY[0, 100000]),
                ('maxSessions', ARRAY[0, 100000]),
                ('sessionIdleSeconds', ARRAY[60, 86400]),
                ('cooldownSeconds', ARRAY[0, 86400]),
                ('maxInflightTokens', ARRAY[1, 1000000000])
            ) AS ranges(name, limits)
        LOOP
            IF pg_catalog.jsonb_typeof(configured -> field) IS DISTINCT FROM 'number'
                OR (configured ->> field) !~ '^[0-9]{1,10}$'
                OR (configured ->> field)::BIGINT NOT BETWEEN bounds[1] AND bounds[2] THEN
                RETURN builtin;
            END IF;
        END LOOP;
        IF pg_catalog.jsonb_typeof(configured -> 'tlsProfile') IS DISTINCT FROM 'string'
            OR (configured ->> 'tlsProfile') NOT IN ('', 'chrome136', 'safari18_4') THEN
            RETURN builtin;
        END IF;
        IF ((configured ->> 'safeRpm')::INTEGER = 0) <> ((configured ->> 'burstCapacity')::INTEGER = 0) THEN
            RETURN builtin;
        END IF;
        RETURN pg_catalog.jsonb_build_object(
            'allocationPriority', (configured ->> 'allocationPriority')::INTEGER,
            'safeConcurrency', (configured ->> 'safeConcurrency')::INTEGER,
            'safeRpm', (configured ->> 'safeRpm')::INTEGER,
            'burstCapacity', (configured ->> 'burstCapacity')::INTEGER,
            'maxSessions', (configured ->> 'maxSessions')::INTEGER,
            'sessionIdleSeconds', (configured ->> 'sessionIdleSeconds')::INTEGER,
            'cooldownSeconds', (configured ->> 'cooldownSeconds')::INTEGER,
            'maxInflightTokens', (configured ->> 'maxInflightTokens')::BIGINT,
            'tlsProfile', configured ->> 'tlsProfile');
    EXCEPTION WHEN OTHERS THEN
        RETURN builtin;
    END;
END;
$$;

-- Every creation path (create API, import, OAuth) inserts into proxy_accounts, so the trigger is the one place that
-- applies the defaults. Session limits and TLS profiles only exist for Anthropic accounts, like the per-account policy.
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
    anthropic_account BOOLEAN;
    category_defaults JSONB;
    initial_rpm INTEGER;
    initial_burst INTEGER;
BEGIN
    api_account := normalized_auth IN ('api-key', 'apikey', 'openai-api-key');
    anthropic_account := normalized_provider IN ('anthropic', 'claude');
    category_defaults := starry_api.account_allocation_defaults(
        starry_api.classify_account_allocation(NEW.provider, NEW.auth_method, NEW.base_url));
    initial_rpm := (category_defaults ->> 'safeRpm')::INTEGER;
    initial_burst := (category_defaults ->> 'burstCapacity')::INTEGER;

    INSERT INTO starry_api.account_allocation_policies(account_id, allocation_priority, safe_concurrency, safe_rpm,
        burst_capacity, max_sessions, session_idle_seconds, max_inflight_tokens, cooldown_seconds, tls_profile)
    VALUES(NEW.account_id, (category_defaults ->> 'allocationPriority')::INTEGER, (category_defaults ->> 'safeConcurrency')::INTEGER,
        initial_rpm, initial_burst,
        CASE WHEN anthropic_account THEN (category_defaults ->> 'maxSessions')::INTEGER ELSE 0 END,
        (category_defaults ->> 'sessionIdleSeconds')::INTEGER, (category_defaults ->> 'maxInflightTokens')::BIGINT,
        (category_defaults ->> 'cooldownSeconds')::INTEGER,
        CASE WHEN anthropic_account THEN category_defaults ->> 'tlsProfile' ELSE '' END)
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

REVOKE ALL ON FUNCTION starry_api.classify_account_allocation(TEXT, TEXT, TEXT) FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.account_allocation_builtin_defaults(TEXT) FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.account_allocation_defaults(TEXT) FROM PUBLIC, starry_api_runtime;

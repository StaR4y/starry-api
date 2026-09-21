SET search_path TO starry_api;

INSERT INTO system_settings (setting_key, setting_value)
VALUES ('application_root_user_id', '')
ON CONFLICT (setting_key) DO NOTHING;

-- Normalize and anchor an existing healthy default administrator during upgrades.
-- Empty databases intentionally keep an empty marker for first-start bootstrap.
UPDATE admin_users
SET username = 'admin',
    updated_at = CURRENT_TIMESTAMP
WHERE lower(username) = 'admin'
  AND username <> 'admin'
  AND role = 'ADMIN'
  AND enabled = TRUE;

UPDATE system_settings AS settings
SET setting_value = candidate.user_id,
    updated_at = CURRENT_TIMESTAMP
FROM (
    SELECT user_id
    FROM admin_users
    WHERE username = 'admin'
      AND role = 'ADMIN'
      AND enabled = TRUE
) AS candidate
WHERE settings.setting_key = 'application_root_user_id'
  AND settings.setting_value = '';

CREATE OR REPLACE FUNCTION starry_api.enforce_application_root_setting()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    normalized_root_user_id TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.setting_key = 'application_root_user_id' THEN
            RAISE EXCEPTION 'the application Root identity marker cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.setting_key = 'application_root_user_id' THEN
        IF NEW.setting_key IS DISTINCT FROM OLD.setting_key THEN
            RAISE EXCEPTION 'the application Root identity marker cannot be renamed';
        END IF;
        IF pg_catalog.btrim(OLD.setting_value) <> ''
            AND NEW.setting_value IS DISTINCT FROM OLD.setting_value THEN
            RAISE EXCEPTION 'the application Root identity marker is immutable once assigned';
        END IF;
    END IF;

    IF NEW.setting_key <> 'application_root_user_id' THEN
        RETURN NEW;
    END IF;

    normalized_root_user_id := pg_catalog.btrim(NEW.setting_value);
    IF normalized_root_user_id = '' THEN
        IF TG_OP = 'UPDATE'
            AND OLD.setting_key = 'application_root_user_id'
            AND pg_catalog.btrim(OLD.setting_value) = '' THEN
            NEW.setting_value := '';
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'the application Root identity marker cannot be created empty';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM starry_api.admin_users AS candidate
        WHERE candidate.user_id = normalized_root_user_id
          AND candidate.username = 'admin'
          AND candidate.role = 'ADMIN'
          AND candidate.enabled = TRUE
    ) THEN
        RAISE EXCEPTION 'the application Root identity marker must reference the enabled admin Root';
    END IF;

    NEW.setting_value := normalized_root_user_id;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION starry_api.enforce_application_root_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    root_user_id TEXT;
BEGIN
    SELECT NULLIF(pg_catalog.btrim(settings.setting_value), '')
    INTO root_user_id
    FROM starry_api.system_settings AS settings
    WHERE settings.setting_key = 'application_root_user_id';

    IF root_user_id IS NULL OR OLD.user_id IS DISTINCT FROM root_user_id THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'the application Root account cannot be deleted';
    END IF;

    IF NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.username IS DISTINCT FROM OLD.username
        OR NEW.username <> 'admin'
        OR NEW.role IS DISTINCT FROM 'ADMIN'
        OR NEW.enabled IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'the application Root identity, role, and enabled state are immutable';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_application_root_setting ON system_settings;
CREATE TRIGGER trg_application_root_setting
    BEFORE INSERT OR UPDATE OR DELETE ON system_settings
    FOR EACH ROW EXECUTE FUNCTION starry_api.enforce_application_root_setting();

DROP TRIGGER IF EXISTS trg_application_root_user ON admin_users;
CREATE TRIGGER trg_application_root_user
    BEFORE UPDATE OR DELETE ON admin_users
    FOR EACH ROW EXECUTE FUNCTION starry_api.enforce_application_root_user();

REVOKE ALL ON FUNCTION starry_api.enforce_application_root_setting()
    FROM PUBLIC, starry_api_runtime;
REVOKE ALL ON FUNCTION starry_api.enforce_application_root_user()
    FROM PUBLIC, starry_api_runtime;

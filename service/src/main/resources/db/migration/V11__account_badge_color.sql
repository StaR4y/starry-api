ALTER TABLE proxy_accounts
    ADD COLUMN IF NOT EXISTS badge_color VARCHAR(16);

ALTER TABLE proxy_accounts
    DROP CONSTRAINT IF EXISTS ck_proxy_accounts_badge_color;

ALTER TABLE proxy_accounts
    ADD CONSTRAINT ck_proxy_accounts_badge_color
    CHECK (badge_color IS NULL OR badge_color ~ '^#[0-9A-Fa-f]{6}$');

ALTER TABLE proxy_accounts
    ADD COLUMN pool_tier VARCHAR(32) NOT NULL DEFAULT 'primary' AFTER proxy_url,
    ADD COLUMN pool_weight INT NOT NULL DEFAULT 100 AFTER pool_tier,
    ADD INDEX idx_proxy_accounts_pool (pool_tier, enabled, suspended_at, quota_exhausted_at);

UPDATE proxy_accounts
SET pool_tier = 'primary'
WHERE pool_tier IS NULL OR pool_tier = '';

UPDATE proxy_accounts
SET pool_weight = 100
WHERE pool_weight IS NULL OR pool_weight <= 0;

ALTER TABLE proxy_accounts
    ADD COLUMN quota_5h_used BIGINT NULL AFTER quota_reset_at,
    ADD COLUMN quota_5h_limit BIGINT NULL AFTER quota_5h_used,
    ADD COLUMN quota_5h_reset_at TIMESTAMP(3) NULL AFTER quota_5h_limit,
    ADD COLUMN quota_7d_used BIGINT NULL AFTER quota_5h_reset_at,
    ADD COLUMN quota_7d_limit BIGINT NULL AFTER quota_7d_used,
    ADD COLUMN quota_7d_reset_at TIMESTAMP(3) NULL AFTER quota_7d_limit;

CREATE INDEX idx_proxy_accounts_quota_windows
    ON proxy_accounts (quota_5h_reset_at, quota_7d_reset_at);

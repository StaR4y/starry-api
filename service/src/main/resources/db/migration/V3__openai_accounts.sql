ALTER TABLE proxy_accounts
    ADD COLUMN base_url VARCHAR(1024) NULL AFTER provider,
    ADD COLUMN upstream_account_id VARCHAR(255) NULL AFTER base_url,
    ADD COLUMN id_token TEXT NULL AFTER upstream_account_id;

CREATE INDEX idx_proxy_accounts_provider ON proxy_accounts (provider);

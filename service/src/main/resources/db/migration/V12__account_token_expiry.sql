ALTER TABLE proxy_accounts
    ADD COLUMN token_expires_at TIMESTAMP(3) NULL AFTER id_token;

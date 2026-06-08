ALTER TABLE api_keys
    ADD COLUMN user_id VARCHAR(64) NULL AFTER key_id,
    ADD COLUMN key_value TEXT NULL AFTER key_hash;

UPDATE api_keys
SET user_id = COALESCE((SELECT user_id FROM admin_users ORDER BY id LIMIT 1), 'env-admin')
WHERE user_id IS NULL OR user_id = '';

ALTER TABLE api_keys
    MODIFY user_id VARCHAR(64) NOT NULL;

CREATE INDEX idx_api_keys_user_created ON api_keys (user_id, created_at);

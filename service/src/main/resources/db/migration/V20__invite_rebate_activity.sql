ALTER TABLE admin_users
    ADD COLUMN affiliate_code VARCHAR(32) NULL AFTER user_id,
    ADD COLUMN invited_by_user_id VARCHAR(64) NULL AFTER affiliate_code,
    ADD COLUMN invite_source_code VARCHAR(64) NULL AFTER invited_by_user_id;

UPDATE admin_users
SET affiliate_code = CONCAT('aff', LOWER(SUBSTRING(REPLACE(UUID(), '-', ''), 1, 8)))
WHERE affiliate_code IS NULL OR affiliate_code = '';

ALTER TABLE admin_users
    ADD UNIQUE KEY uk_admin_users_affiliate_code (affiliate_code),
    ADD INDEX idx_admin_users_invited_by_user_id (invited_by_user_id);

CREATE TABLE IF NOT EXISTS invite_rebate_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rebate_id VARCHAR(64) NOT NULL UNIQUE,
    affiliate_code VARCHAR(64) NOT NULL,
    payment_order_id VARCHAR(64) NOT NULL UNIQUE,
    inviter_user_id VARCHAR(64) NOT NULL,
    invited_user_id VARCHAR(64) NOT NULL,
    base_amount DECIMAL(20, 6) NOT NULL,
    rebate_rate DECIMAL(10, 6) NOT NULL,
    rebate_amount DECIMAL(20, 6) NOT NULL,
    rebate_order_no INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_invite_rebate_inviter_created (inviter_user_id, created_at),
    INDEX idx_invite_rebate_invited_created (invited_user_id, created_at),
    INDEX idx_invite_rebate_order_no (invited_user_id, rebate_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_settings (setting_key, setting_value)
VALUES
    ('INVITE_REBATE_ENABLED', 'false'),
    ('INVITE_REBATE_RATE', '0.10'),
    ('INVITE_REBATE_MAX_ORDERS', '10'),
    ('INVITE_REBATE_START_AT', ''),
    ('INVITE_REBATE_END_AT', '')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

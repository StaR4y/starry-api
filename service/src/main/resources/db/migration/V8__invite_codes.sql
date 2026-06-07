CREATE TABLE IF NOT EXISTS invite_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code_id VARCHAR(64) NOT NULL UNIQUE,
    code VARCHAR(64) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_by_user_id VARCHAR(64) NULL,
    used_at TIMESTAMP(3) NULL,
    expires_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_invite_codes_code_enabled (code, enabled, used),
    INDEX idx_invite_codes_created_at (created_at),
    INDEX idx_invite_codes_used_by_user_id (used_by_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_settings (setting_key, setting_value)
VALUES ('invite_code_enabled', 'false')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

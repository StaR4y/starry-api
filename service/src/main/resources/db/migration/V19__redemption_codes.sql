CREATE TABLE IF NOT EXISTS redemption_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code_id VARCHAR(64) NOT NULL UNIQUE,
    code VARCHAR(64) NOT NULL UNIQUE,
    balance DECIMAL(20, 6) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_by_user_id VARCHAR(64) NULL,
    used_by_username VARCHAR(64) NULL,
    used_at TIMESTAMP(3) NULL,
    expires_at TIMESTAMP(3) NULL,
    created_by_user_id VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_redemption_codes_code_enabled (code, enabled, used),
    INDEX idx_redemption_codes_created_at (created_at),
    INDEX idx_redemption_codes_used_by_user_id (used_by_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

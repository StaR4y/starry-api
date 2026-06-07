ALTER TABLE admin_users
    ADD COLUMN email VARCHAR(255) NULL AFTER username,
    ADD UNIQUE INDEX idx_admin_users_email (email);

CREATE TABLE IF NOT EXISTS system_settings (
    setting_key VARCHAR(128) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_settings (setting_key, setting_value)
VALUES
    ('registration_enabled', 'false'),
    ('registration_email_suffix_whitelist', '[]')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

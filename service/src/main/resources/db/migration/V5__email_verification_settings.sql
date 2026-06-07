INSERT INTO system_settings (setting_key, setting_value)
VALUES
    ('email_verify_enabled', 'false'),
    ('smtp_host', ''),
    ('smtp_port', '587'),
    ('smtp_username', ''),
    ('smtp_password', ''),
    ('smtp_from_email', ''),
    ('smtp_from_name', ''),
    ('smtp_use_tls', 'false')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

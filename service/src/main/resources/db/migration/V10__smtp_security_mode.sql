INSERT INTO system_settings (setting_key, setting_value)
VALUES ('smtp_security', 'auto')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

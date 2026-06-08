INSERT INTO system_settings (setting_key, setting_value)
VALUES ('gpt_fast_mode_enabled', 'false')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

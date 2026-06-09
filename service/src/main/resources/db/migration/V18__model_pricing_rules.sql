INSERT INTO system_settings (setting_key, setting_value)
VALUES ('MODEL_PRICING_RULES', '[]')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

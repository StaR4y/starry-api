INSERT INTO system_settings (setting_key, setting_value)
VALUES ('API_BILLING_MULTIPLIER', '0.0000166666666667')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

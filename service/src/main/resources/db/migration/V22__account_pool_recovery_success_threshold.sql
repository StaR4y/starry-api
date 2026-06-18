UPDATE system_settings
SET setting_value = '70.0'
WHERE setting_key = 'account_pool_policy_recovery_threshold_percent'
  AND (setting_value IS NULL OR setting_value = '' OR CAST(setting_value AS DECIMAL(10, 2)) < 70.0);

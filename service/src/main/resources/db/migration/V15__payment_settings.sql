INSERT INTO system_settings (setting_key, setting_value)
VALUES
    ('payment_enabled', 'false'),
    ('MIN_RECHARGE_AMOUNT', '1'),
    ('MAX_RECHARGE_AMOUNT', '0'),
    ('DAILY_RECHARGE_LIMIT', '0'),
    ('ORDER_TIMEOUT_MINUTES', '30'),
    ('MAX_PENDING_ORDERS', '3'),
    ('ENABLED_PAYMENT_TYPES', 'alipay,wxpay'),
    ('BALANCE_RECHARGE_MULTIPLIER', '1'),
    ('RECHARGE_FEE_RATE', '0'),
    ('PRODUCT_NAME_PREFIX', ''),
    ('PRODUCT_NAME_SUFFIX', ''),
    ('PAYMENT_HELP_IMAGE_URL', ''),
    ('PAYMENT_HELP_TEXT', ''),
    ('EASYPAY_API_BASE', ''),
    ('EASYPAY_PID', ''),
    ('EASYPAY_PKEY', ''),
    ('EASYPAY_PAYMENT_MODE', 'popup')
ON DUPLICATE KEY UPDATE setting_key = setting_key;

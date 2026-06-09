ALTER TABLE payment_orders
    ADD COLUMN credited_at TIMESTAMP(3) NULL AFTER completed_at,
    ADD INDEX idx_payment_orders_credited_at (credited_at);

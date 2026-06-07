ALTER TABLE admin_users
    ADD COLUMN balance DECIMAL(20, 6) NOT NULL DEFAULT 0 AFTER role,
    ADD INDEX idx_admin_users_role_enabled (role, enabled);

UPDATE admin_users
SET role = 'ADMIN'
WHERE role IS NULL OR role = '';

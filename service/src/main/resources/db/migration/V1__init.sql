CREATE TABLE IF NOT EXISTS proxy_accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(255),
    access_token TEXT NOT NULL,
    refresh_token TEXT,
    client_id VARCHAR(255),
    client_secret VARCHAR(255),
    region VARCHAR(64),
    auth_method VARCHAR(64),
    provider VARCHAR(64),
    profile_arn VARCHAR(512),
    machine_id VARCHAR(128),
    proxy_url VARCHAR(1024),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    request_count BIGINT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    quota_used BIGINT,
    quota_limit BIGINT,
    quota_exhausted_at TIMESTAMP(3) NULL,
    quota_reset_at TIMESTAMP(3) NULL,
    suspended_at TIMESTAMP(3) NULL,
    suspend_reason VARCHAR(128),
    suspend_message TEXT,
    last_used_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_proxy_accounts_enabled (enabled),
    INDEX idx_proxy_accounts_email (email),
    INDEX idx_proxy_accounts_state (enabled, suspended_at, quota_exhausted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_keys (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    key_hash CHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(24) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    credits_limit DECIMAL(20, 6),
    total_requests BIGINT NOT NULL DEFAULT 0,
    total_credits DECIMAL(20, 6) NOT NULL DEFAULT 0,
    total_input_tokens BIGINT NOT NULL DEFAULT 0,
    total_output_tokens BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_api_keys_hash_enabled (key_hash, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_key_usage_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_id VARCHAR(64) NOT NULL,
    usage_date DATE NOT NULL,
    requests BIGINT NOT NULL DEFAULT 0,
    credits DECIMAL(20, 6) NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_api_key_usage_daily (key_id, usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS model_mappings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    mapping_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mapping_type VARCHAR(32) NOT NULL,
    source_model VARCHAR(255) NOT NULL,
    target_models JSON NOT NULL,
    weights JSON,
    priority INT NOT NULL DEFAULT 100,
    api_key_ids JSON,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_model_mappings_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS request_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    api_key_id VARCHAR(64),
    account_id VARCHAR(64),
    path VARCHAR(128) NOT NULL,
    model VARCHAR(255),
    status_code INT NOT NULL,
    success BOOLEAN NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    credits DECIMAL(20, 6) NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_request_logs_created_at (created_at),
    INDEX idx_request_logs_api_key_created (api_key_id, created_at),
    INDEX idx_request_logs_account_created (account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

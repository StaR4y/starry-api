CREATE TABLE IF NOT EXISTS performance_metrics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    system_cpu_load_percent DECIMAL(8,4) NULL,
    process_cpu_load_percent DECIMAL(8,4) NULL,
    heap_used_bytes BIGINT NULL,
    heap_committed_bytes BIGINT NULL,
    heap_max_bytes BIGINT NULL,
    non_heap_used_bytes BIGINT NULL,
    system_memory_total_bytes BIGINT NULL,
    system_memory_free_bytes BIGINT NULL,
    system_memory_used_percent DECIMAL(8,4) NULL,
    network_rx_bytes BIGINT NULL,
    network_tx_bytes BIGINT NULL,
    network_rx_bytes_per_second DECIMAL(20,4) NULL,
    network_tx_bytes_per_second DECIMAL(20,4) NULL,
    sampled_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_performance_metrics_sampled_at (sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

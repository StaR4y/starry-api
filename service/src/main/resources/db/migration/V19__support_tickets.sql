CREATE TABLE support_tickets (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    username VARCHAR(100) NOT NULL,
    title VARCHAR(160) NOT NULL,
    category VARCHAR(24) NOT NULL CHECK (category IN ('GENERAL', 'API', 'BILLING', 'ACCOUNT', 'BUG')),
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED')),
    version BIGINT NOT NULL DEFAULT 1,
    message_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    notify_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    notify_lease_until TIMESTAMPTZ,
    notify_token VARCHAR(36),
    notify_attempts INTEGER NOT NULL DEFAULT 0,
    notify_error VARCHAR(100),
    notified_at TIMESTAMPTZ
);
CREATE INDEX support_tickets_user_idx ON support_tickets (user_id, updated_at DESC, id);
CREATE INDEX support_tickets_status_idx ON support_tickets (status, updated_at DESC, id);
CREATE INDEX support_tickets_notify_idx ON support_tickets (notify_at)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

CREATE TABLE support_ticket_messages (
    id BIGSERIAL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL REFERENCES support_tickets(id),
    actor_id VARCHAR(64) NOT NULL,
    actor_name VARCHAR(100) NOT NULL,
    admin BOOLEAN NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('MESSAGE', 'STATUS')),
    body TEXT NOT NULL CHECK (length(body) <= 20000),
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX support_ticket_messages_thread_idx ON support_ticket_messages (ticket_id, id DESC);
CREATE INDEX support_ticket_messages_actor_idx ON support_ticket_messages (actor_id, created_at DESC);

-- Small, private attachments stay transactional with the message. Never select data in thread queries.
CREATE TABLE support_ticket_attachments (
    id VARCHAR(36) PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES support_ticket_messages(id),
    ticket_id VARCHAR(36) NOT NULL REFERENCES support_tickets(id),
    uploader_id VARCHAR(64) NOT NULL,
    filename VARCHAR(180) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 2097152),
    data BYTEA NOT NULL CHECK (octet_length(data) = size_bytes)
);
CREATE INDEX support_ticket_attachments_message_idx ON support_ticket_attachments (message_id);
CREATE INDEX support_ticket_attachments_uploader_idx ON support_ticket_attachments (uploader_id);
GRANT UPDATE ON support_tickets TO starry_api_runtime;

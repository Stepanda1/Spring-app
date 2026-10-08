CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    delivery_count INTEGER NOT NULL DEFAULT 0 CHECK (delivery_count BETWEEN 0 AND 1)
);
CREATE INDEX notifications_outbox_idx ON notifications (created_at) WHERE published_at IS NULL;

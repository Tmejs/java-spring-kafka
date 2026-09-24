CREATE TABLE products (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    available_quantity INTEGER NOT NULL,
    CONSTRAINT chk_products_available_quantity CHECK (available_quantity >= 0)
);

CREATE TABLE reservations (
    order_id UUID PRIMARY KEY,
    triggering_event_id UUID NOT NULL,
    items_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    rejection_reason VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reservations_order_id UNIQUE (order_id),
    CONSTRAINT chk_reservations_outcome CHECK (outcome IN ('RESERVED', 'REJECTED')),
    CONSTRAINT chk_reservations_rejection_reason CHECK (
        (outcome = 'REJECTED' AND rejection_reason IS NOT NULL)
        OR (outcome = 'RESERVED' AND rejection_reason IS NULL)
    )
);

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    order_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_outbox_pending ON outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

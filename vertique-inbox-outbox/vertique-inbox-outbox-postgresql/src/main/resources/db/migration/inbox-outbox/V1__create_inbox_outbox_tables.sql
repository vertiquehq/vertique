-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Transactional Messaging: inbox and outbox tables with LISTEN/NOTIFY support.
-- The outbox table stores pending side-effects to be delivered by the relay engine.
-- The inbox table provides deduplication for inbound messages within transactions.
-- The notify trigger enables low-latency LISTEN/NOTIFY wake-up when new outbox rows are inserted.

CREATE TABLE outbox (
    id               BIGSERIAL PRIMARY KEY,
    -- Framework-generated per-row durable carrier identity (PRD identity-002 F3b). Allocated by the
    -- producer BEFORE insert and never derived from application-writable input, so a durable identity
    -- snapshot can be signed for this exact row (see SnapshotCarrierBinding) and the receive-side relay
    -- can reproduce the same carrier from this first-class column to detect a transplanted snapshot.
    carrier_id       UUID NOT NULL UNIQUE,
    aggregate_type   VARCHAR(255),
    aggregate_id     VARCHAR(255),
    event_type       VARCHAR(255) NOT NULL,
    destination      VARCHAR(255) NOT NULL,
    destination_type VARCHAR(32) NOT NULL,      -- open value type id (^[A-Za-z0-9_-]{1,32}$); built-ins: SERVICE, DELAYED_JOB, KAFKA
    payload          JSONB NOT NULL,
    headers          JSONB,
    -- Structured metadata document: {"context": {namespaces...}, "delivery": {...}}
    -- The "context" section carries durable propagation context (correlation, localization, etc.)
    -- captured at publish time. The "delivery" section carries relay-time control values.
    metadata         JSONB NOT NULL DEFAULT '{}'::jsonb,
    scheduled_at     TIMESTAMPTZ,
    available_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    state            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt          INTEGER NOT NULL DEFAULT 0,
    max_attempts     INTEGER NOT NULL DEFAULT 20,
    claimed_at       TIMESTAMPTZ,
    claimed_by       VARCHAR(255),
    published_at     TIMESTAMPTZ,
    last_error       TEXT,
    error_type       VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_pending
    ON outbox (available_at ASC, id ASC)
    WHERE state = 'PENDING';

CREATE INDEX idx_outbox_processing
    ON outbox (claimed_at ASC)
    WHERE state = 'PROCESSING';

CREATE INDEX idx_outbox_aggregate
    ON outbox (aggregate_id, id ASC)
    WHERE state IN ('PENDING', 'PROCESSING') AND aggregate_id IS NOT NULL;

CREATE INDEX idx_outbox_published
    ON outbox (published_at ASC)
    WHERE state = 'PUBLISHED';

CREATE INDEX idx_outbox_dead_letter
    ON outbox (updated_at ASC)
    WHERE state = 'DEAD_LETTER';

CREATE TABLE inbox (
    message_id   VARCHAR(255) NOT NULL,
    source       VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, source)
);

CREATE INDEX idx_inbox_processed_at ON inbox (processed_at);

CREATE OR REPLACE FUNCTION notify_transactional_outbox() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('transactional_outbox_channel', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transactional_outbox_notify
    AFTER INSERT ON outbox
    FOR EACH ROW EXECUTE FUNCTION notify_transactional_outbox();

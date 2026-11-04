-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds what dead-letter administration needs: a per-entry revision, a receipt table for administrative commands, and an
-- index that serves the dead-letter listing.
--
-- outbox.admin_revision counts administrative requeues of an entry. Only the administration commands change it, and
-- the relay's own writes never mention it, so it survives claims, retries and dead-lettering. Existing rows start at 0.
--
-- outbox_admin_operations holds one receipt per administrative command. It has no foreign key to outbox, so a receipt
-- outlives the entry it describes (a discard deletes the entry). It stores no payload, headers, raw error text or
-- metadata. A row is inserted with its intent and completed in the same transaction, so a committed row is always
-- complete.
--
-- Lock hazard. ADD COLUMN with a constant default is a catalog-only change on PostgreSQL 11 and later, so it rewrites
-- no row, but it still needs a lock that blocks every read and write of outbox, and so waits behind any open
-- transaction that touched the table. CREATE INDEX inside the migration transaction scans the whole table under a lock
-- that blocks inserts for the duration of the scan. SET LOCAL lock_timeout bounds only the wait for a lock: while a
-- statement waits, new statements on the table queue behind it for up to the timeout, then the migration fails and can
-- be rerun. It does not bound the time the index build holds its lock once it has it. A very large installation can
-- create the partial index concurrently before it applies this script, under the same name
-- (CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_dead_letter_listing ...); the statement below then finds it and
-- does nothing.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Every statement is IF NOT EXISTS, so the script can be rerun after a failure.
SET LOCAL lock_timeout = '30s';

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS admin_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS outbox_admin_operations (
    operation_id      UUID PRIMARY KEY,
    -- Intent, written when the command reserves its operation id
    action            VARCHAR(16)   NOT NULL,
    entry_id          BIGINT        NOT NULL,
    expected_revision BIGINT        NOT NULL,
    reason            VARCHAR(1024) NOT NULL,
    actor_type        VARCHAR(64)   NOT NULL,
    actor_id          VARCHAR(255)  NOT NULL,
    recorded_at       TIMESTAMPTZ   NOT NULL,
    -- Prior failure snapshot and target identity, written when the command completes
    attempt           INTEGER,
    max_attempts      INTEGER,
    error_type        VARCHAR(500),
    destination_type  VARCHAR(32),
    destination       VARCHAR(255),
    event_type        VARCHAR(255),
    aggregate_type    VARCHAR(255),
    aggregate_id      VARCHAR(255),
    -- Result, written when the command completes
    disposition       VARCHAR(16),
    result_revision   BIGINT
);

CREATE INDEX IF NOT EXISTS idx_outbox_admin_operations_entry
    ON outbox_admin_operations (entry_id, recorded_at DESC, operation_id DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_admin_operations_recorded_at
    ON outbox_admin_operations (recorded_at);

CREATE INDEX IF NOT EXISTS idx_outbox_dead_letter_listing
    ON outbox (updated_at DESC, id DESC)
    WHERE state = 'DEAD_LETTER';

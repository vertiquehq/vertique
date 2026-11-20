-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the optional idempotency key of a delayed job execution and the unique index that enforces it. The column is
-- nullable and every existing row reads NULL, so no existing execution is affected and no existing row can violate the
-- index. An execution holds its key while it is unfinished: in any state except SUCCEEDED, CANCELLED and DEAD_LETTER,
-- which have no outgoing transition. FAILED and ABANDONED count as unfinished because a retry can still move them back
-- to ENQUEUED. The key is unique per handler and scope, so one handler's or scope's key never matches another's.
--
-- ADD COLUMN of a nullable column without a default is a catalog-only change on PostgreSQL, so it rewrites no row. The
-- unique index is a plain CREATE UNIQUE INDEX: it blocks every write to job_executions (enqueue, claim, completion)
-- while it builds, and it builds over the whole table although the partial predicate matches no existing row. Both
-- statements first need a lock that waits behind any open transaction that touched the table. Bound the wait for that
-- lock: while a statement waits, new statements on the table queue behind it for up to the timeout, then the migration
-- fails and can be rerun. The timeout does not bound the build itself, so on a very large installation build the index
-- ahead of the upgrade, outside this transaction, with CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS using the same name
-- and definition (after adding the column), and the migration then builds nothing.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Both statements are IF NOT EXISTS, so the script can be rerun after a failure. An index of this name that a cancelled
-- CREATE INDEX CONCURRENTLY left invalid would make IF NOT EXISTS build nothing and would not enforce uniqueness, so the
-- script drops an invalid one first.
SET LOCAL lock_timeout = '30s';

ALTER TABLE job_executions ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_job_executions_idempotency_key') AND NOT indisvalid) THEN
        DROP INDEX idx_job_executions_idempotency_key;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_job_executions_idempotency_key
    ON job_executions (handler, scope_key, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND state NOT IN ('SUCCEEDED', 'CANCELLED', 'DEAD_LETTER');

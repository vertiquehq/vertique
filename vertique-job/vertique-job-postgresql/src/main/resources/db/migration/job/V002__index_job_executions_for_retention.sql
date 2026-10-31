-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the index the job execution retention job scans, so that deleting finished executions older than the
-- retention age reads only those executions and never the table. The index covers the three states that have no
-- outgoing transition (SUCCEEDED, CANCELLED, DEAD_LETTER) and the two states a cron execution can end in
-- without being re-enqueued (FAILED, ABANDONED), ordered by the moment the execution finished (completed_at, or
-- updated_at for a state change that did not set it), which is the order the job deletes in. Live executions
-- (ENQUEUED, PROCESSING) are not in it. On an installation that never deletes, most rows are finished, so the
-- index is then a large part of the table.
--
-- CREATE INDEX takes a lock that blocks every write to job_executions (enqueue, claim, completion) while it
-- builds, and so waits behind any open transaction that wrote to the table. Bound the wait for that lock: while the statement waits, new statements
-- on the table queue behind it for up to the timeout, then the migration fails and can be rerun. The timeout does
-- not bound the build itself, so on a very large installation build the index ahead of the upgrade, outside this
-- transaction, with CREATE INDEX CONCURRENTLY IF NOT EXISTS using the same name and definition, and the migration
-- then builds nothing.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- The statement is IF NOT EXISTS, so the script can be rerun after a failure. An index of this name that a
-- cancelled CREATE INDEX CONCURRENTLY left invalid would make IF NOT EXISTS build nothing and the planner never
-- use it, so the script drops an invalid one first.
SET LOCAL lock_timeout = '30s';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_job_executions_retention') AND NOT indisvalid) THEN
        DROP INDEX idx_job_executions_retention;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_job_executions_retention
    ON job_executions (COALESCE(completed_at, updated_at))
    WHERE state IN ('SUCCEEDED', 'CANCELLED', 'DEAD_LETTER', 'FAILED', 'ABANDONED');

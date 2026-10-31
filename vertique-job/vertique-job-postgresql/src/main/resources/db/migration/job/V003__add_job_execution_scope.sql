-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the scope an execution belongs to. The column is a label that the application selects when it enqueues the
-- job; no claim, retry, retention or cron-dedup query reads it, and no index is built on it. Existing executions read
-- the default scope.
--
-- ADD COLUMN with a constant default is a catalog-only change on PostgreSQL 11 and later, so it rewrites no row. It
-- still needs a lock that blocks every write to job_executions, and so waits behind any open transaction that wrote
-- to the table. Bound the wait for that lock: while the statement waits, new statements on the table queue behind it
-- for up to the timeout, then the migration fails and can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- The statement is IF NOT EXISTS, so the script can be rerun after a failure.
SET LOCAL lock_timeout = '30s';

ALTER TABLE job_executions ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';

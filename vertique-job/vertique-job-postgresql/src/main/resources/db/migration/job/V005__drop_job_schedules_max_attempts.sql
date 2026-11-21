-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Drops the attempt limit of a cron schedule. Cron never retries, so the limit was always 1 and nothing reads it:
-- an execution records its own limit in job_executions.max_attempts, which this migration does not touch.
--
-- DROP COLUMN is a catalog-only change on PostgreSQL, so it rewrites no row. It needs a brief exclusive lock on
-- job_schedules, which waits behind any open transaction that touched the table; bound the wait so a statement queued
-- behind it does not stall. The timeout fails the migration, which can then be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- The statement is IF EXISTS, so the script can be rerun after a failure.
--
-- During a rolling upgrade a node still running the earlier version reads and writes the dropped column at startup:
-- it persists its schedules, and its misfire recovery looks up the schedule of each single-instance job. After this
-- migration both fail on that node; each failure is logged and ignored, so the node skips its misfire catch-up until
-- it is upgraded, and the rows the upgraded nodes wrote stay current.
SET LOCAL lock_timeout = '30s';

ALTER TABLE job_schedules DROP COLUMN IF EXISTS max_attempts;

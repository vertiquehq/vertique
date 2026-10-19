-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Both statements only change catalog metadata, but each takes an ACCESS EXCLUSIVE lock until the
-- migration commits. Bound the wait for that lock: while the statement waits, new statements on
-- that table queue behind it for up to the timeout, then the migration fails and can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
SET LOCAL lock_timeout = '30s';

-- Tasks first, matching the engine's lock order (tasks before instances).
ALTER TABLE workflow_tasks ADD COLUMN tenant_key VARCHAR(255);
ALTER TABLE workflow_instances ADD COLUMN tenant_key VARCHAR(255);

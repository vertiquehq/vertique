-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Both statements only change catalog metadata, but each takes an ACCESS EXCLUSIVE lock until the
-- migration commits. Bound the wait for that lock so a long-running transaction makes the migration
-- fail and retry instead of queueing every workflow statement behind it.
SET LOCAL lock_timeout = '30s';

ALTER TABLE workflow_instances ADD COLUMN tenant_key VARCHAR(255);
ALTER TABLE workflow_tasks ADD COLUMN tenant_key VARCHAR(255);

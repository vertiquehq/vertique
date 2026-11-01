-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the index that serves business-key lookup without a definition or a scope. Not unique: a business key is unique
-- only per scope and definition id, which idx_workflow_instances_business_key already enforces.
--
-- The build is its own migration so that it runs in its own transaction and holds no lock on workflow_commands. It
-- takes a SHARE lock on workflow_instances, which blocks that table's writers, until the build commits, so the blocked
-- time grows with the table. Bound the wait for the lock: while the statement waits, new statements on that table queue
-- behind it for up to the timeout, then the migration fails and can be rerun. The timeout does not bound the build.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- The statement is IF NOT EXISTS, so the script can be rerun after a failure and is a no-op for an index built ahead of
-- the upgrade.
-- An operator of a large installation should build the index ahead of the upgrade, without blocking writers, on an
-- autocommit connection, and check that it is valid, because IF NOT EXISTS matches by name and keeps an INVALID index:
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_business_key_scope_key_definition_id
--       ON workflow_instances (business_key, scope_key, definition_id) WHERE business_key IS NOT NULL;
-- The migration then finds the index and keeps it.
SET LOCAL lock_timeout = '30s';

CREATE INDEX IF NOT EXISTS idx_workflow_instances_business_key_scope_key_definition_id
    ON workflow_instances (business_key, scope_key, definition_id) WHERE business_key IS NOT NULL;

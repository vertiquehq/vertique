-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the signal correlation schema: the table of open wait records, one per wait entry of a computed correlation
-- key; the nullable provenance column on the command log; and an index that serves business-key lookup without a
-- definition or a scope. No existing row changes, and no existing column, constraint or index changes.
--
-- The ALTER TABLE statement takes an ACCESS EXCLUSIVE lock and the index build takes a SHARE lock on
-- workflow_instances, which blocks its writers, until the migration commits. Bound the wait for those locks: while a
-- statement waits, new statements on that table queue behind it for up to the timeout, then the migration fails and
-- can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Every statement is IF [NOT] EXISTS, so the script can be rerun after a failure and is a no-op for an object built
-- ahead of the upgrade.
-- An operator of a large installation can build the workflow_instances index ahead of the upgrade, without blocking
-- writers, on an autocommit connection:
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_business_key_scope_key_definition_id
--       ON workflow_instances (business_key, scope_key, definition_id) WHERE business_key IS NOT NULL;
-- The migration then finds the index and keeps it.
-- Apply the script before the first node of this release starts; nodes of the previous release keep running while it
-- applies and afterwards. They do not know the new table or column, and no read of workflow_commands selects every
-- column, so they need no stop. Nodes of this release may start in any order once it has applied.
SET LOCAL lock_timeout = '30s';

-- One open row per wait entry of a computed key. The engine generates wait_id (a version 7 UUID), so the column has
-- no default. fork_step_id and branch_id hold '' outside a branch, so the unique key binds the main path too.
CREATE TABLE IF NOT EXISTS workflow_signal_waits (
    wait_id         UUID         NOT NULL PRIMARY KEY,
    instance_id     UUID         NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    step_id         VARCHAR(255) NOT NULL,
    fork_step_id    VARCHAR(255) NOT NULL,
    branch_id       VARCHAR(255) NOT NULL,
    scope_key       VARCHAR(255) NOT NULL,
    contract_name   VARCHAR(255) NOT NULL,
    definition_id   VARCHAR(255) NOT NULL,
    signal_name     VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_workflow_signal_waits_instance_step_branch UNIQUE (instance_id, step_id, fork_step_id, branch_id)
);

-- Delivery of a signal by contract, signal name and key.
CREATE INDEX IF NOT EXISTS idx_workflow_signal_waits_contract_signal_key
    ON workflow_signal_waits (contract_name, signal_name, correlation_key);

-- Commands: where a signal command came from, null for every existing row and for commands that record none.
ALTER TABLE workflow_commands ADD COLUMN IF NOT EXISTS provenance_json JSONB NULL;

-- Business-key lookup without a definition or a scope. Not unique: a business key is unique only per scope and
-- definition id, which idx_workflow_instances_business_key already enforces.
CREATE INDEX IF NOT EXISTS idx_workflow_instances_business_key_scope_key_definition_id
    ON workflow_instances (business_key, scope_key, definition_id) WHERE business_key IS NOT NULL;

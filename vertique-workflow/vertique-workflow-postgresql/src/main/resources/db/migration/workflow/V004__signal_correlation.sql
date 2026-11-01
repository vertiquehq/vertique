-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the signal correlation schema: the table of open wait records, one per wait entry of a computed correlation
-- key, and the nullable provenance column on the command log. No existing row changes, and no existing column,
-- constraint or index changes. V005 adds the index for business-key lookup in a transaction of its own, so that this
-- script's lock on workflow_commands is not held for the build.
--
-- The foreign key takes a SHARE ROW EXCLUSIVE lock on workflow_instances, which blocks its writers, and the ALTER TABLE
-- statement an ACCESS EXCLUSIVE lock on workflow_commands, until the migration commits. Neither rewrites a table, so
-- the script is short once it holds them. Bound the wait for those locks: while a statement waits, new statements on
-- that table queue behind it for up to the timeout, then the migration fails and can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Every statement is IF [NOT] EXISTS, so the script can be rerun after a failure.
-- Apply V004 and V005 before the first node of this release starts; nodes of the previous release keep running while
-- they apply and afterwards. They do not know the new table or column, and no read of workflow_commands selects every
-- column, so they need no stop. Nodes of this release may start in any order once both have applied.
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

-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Creates the definition registry and converts the first release's tables to scope keys. Every instance has a
-- start scope and a definition scope, every task and start-dedup row has a scope, and business keys and start
-- dedup keys are unique per scope. Existing rows read the default scope; no row is rewritten, so stored
-- fingerprints and dedup namespaces stay as they were. The workflow_dedup column scope is renamed
-- dedup_namespace.
--
-- The ALTER TABLE statements take an ACCESS EXCLUSIVE lock until the migration commits, and so do the index
-- rebuild on workflow_instances and the primary key rebuild on workflow_dedup. Bound the wait for those locks:
-- while a statement waits, new statements on that table queue behind it for up to the timeout, then the
-- migration fails and can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Except for the dedup column rename, which has no IF EXISTS form, every statement is IF [NOT] EXISTS, so an
-- operator of a very large installation can add the columns on tasks and then instances and build the two
-- scope keyset indexes ahead of the upgrade, outside this transaction (with CREATE INDEX CONCURRENTLY IF NOT
-- EXISTS), and the migration then builds nothing for them. The business-key index is always dropped and rebuilt
-- here, and the workflow_dedup rename and primary key change always run here. Stop every node that runs the
-- previous release before the first node of this release applies the script: its dedup statements name the old
-- column and the old primary key.
SET LOCAL lock_timeout = '30s';

CREATE TABLE IF NOT EXISTS workflow_definitions (
    scope_key VARCHAR(255) NOT NULL,
    definition_id VARCHAR(255) NOT NULL,
    definition_version BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    contract_name VARCHAR(255) NOT NULL,
    state_type_name VARCHAR(255) NOT NULL,
    plan_hash VARCHAR(128) NOT NULL,
    name_binding JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL,
    document TEXT,
    document_format VARCHAR(16),
    document_digest VARCHAR(64),
    source_type VARCHAR(64) NOT NULL,
    source_uri TEXT,
    source_revision VARCHAR(255),
    source_author VARCHAR(255),
    source_upload_id VARCHAR(255),
    loaded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    publish_token UUID,
    CONSTRAINT pk_workflow_definitions PRIMARY KEY (scope_key, definition_id, definition_version),
    CONSTRAINT ck_workflow_definitions_version CHECK (definition_version > 0),
    CONSTRAINT ck_workflow_definitions_kind CHECK (kind IN ('CODE','DOCUMENT')),
    CONSTRAINT ck_workflow_definitions_status CHECK (status IN ('CANDIDATE','PUBLISHING','AVAILABLE','DEPRECATED','RETIRED')),
    CONSTRAINT ck_workflow_definitions_claim CHECK ((status = 'PUBLISHING') = (publish_token IS NOT NULL)),
    CONSTRAINT ck_workflow_definitions_document CHECK (
        (kind = 'DOCUMENT' AND document IS NOT NULL AND document_format IS NOT NULL)
        OR (kind = 'CODE' AND document IS NULL AND document_format IS NULL AND scope_key = 'default')),
    CONSTRAINT ck_workflow_definitions_digest
        CHECK ((kind = 'DOCUMENT') = (document_digest IS NOT NULL) AND (document_digest IS NULL OR document_digest ~ '^[0-9a-f]{64}$'))
);
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_id ON workflow_definitions (definition_id);

-- The current version of a definition id in a scope. A scope without a row follows the default scope. A scope names a
-- version of its own or of the default scope, and the default scope names only itself. The foreign key keeps a
-- definition row that a record names from being deleted.
CREATE TABLE IF NOT EXISTS workflow_definition_activations (
    scope_key VARCHAR(255) NOT NULL,
    definition_id VARCHAR(255) NOT NULL,
    target_scope_key VARCHAR(255) NOT NULL,
    definition_version BIGINT NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_workflow_definition_activations PRIMARY KEY (scope_key, definition_id),
    CONSTRAINT ck_workflow_definition_activations_version CHECK (definition_version > 0),
    CONSTRAINT ck_workflow_definition_activations_target CHECK (
        (target_scope_key = scope_key OR target_scope_key = 'default')
        AND (scope_key <> 'default' OR target_scope_key = 'default')),
    CONSTRAINT fk_workflow_definition_activations_target
        FOREIGN KEY (target_scope_key, definition_id, definition_version)
        REFERENCES workflow_definitions (scope_key, definition_id, definition_version) ON DELETE RESTRICT
);

-- Tasks first, matching the engine's lock order (tasks before instances).
ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_instances
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default',
    ADD COLUMN IF NOT EXISTS definition_scope_key VARCHAR(255) NOT NULL DEFAULT 'default';

-- A business key is unique per start scope and definition id.
DROP INDEX IF EXISTS idx_workflow_instances_business_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_instances_business_key
    ON workflow_instances (scope_key, definition_id, business_key) WHERE business_key IS NOT NULL;

-- Keyset indexes for the scope-filtered queries: the scope, then the table's keyset order.
CREATE INDEX IF NOT EXISTS idx_workflow_tasks_scope_updated_at
    ON workflow_tasks (scope_key, updated_at DESC, task_id ASC);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_scope_updated_at
    ON workflow_instances (scope_key, updated_at DESC, id ASC);

-- The dedup column scope is a namespace (the definition id for start rows); scope_key is the start scope.
ALTER TABLE workflow_dedup RENAME COLUMN scope TO dedup_namespace;
ALTER TABLE workflow_dedup ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_dedup
    DROP CONSTRAINT IF EXISTS workflow_dedup_pkey,
    ADD CONSTRAINT workflow_dedup_pkey PRIMARY KEY (kind, scope_key, dedup_namespace, key);

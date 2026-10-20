-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- The ALTER TABLE statements below take an ACCESS EXCLUSIVE lock until the migration commits, and so
-- does each CREATE INDEX (a sequential scan of the table, since every tenant_key is null). Bound the
-- wait for those locks: while a statement waits, new statements on that table queue behind it for up
-- to the timeout, then the migration fails and can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Every tenant column and tenant index statement is IF NOT EXISTS, so an operator of a very large installation can
-- apply the two ALTER statements and build the two indexes ahead of the upgrade, outside this
-- transaction, with CREATE INDEX CONCURRENTLY IF NOT EXISTS, and the migration then builds nothing.
SET LOCAL lock_timeout = '30s';

CREATE TABLE workflow_definitions (
    tenant_key VARCHAR(255),
    definition_id VARCHAR(255) NOT NULL,
    definition_version BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL,
    plan_hash VARCHAR(64) NOT NULL,
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
    activation_token UUID,
    CONSTRAINT ck_workflow_definitions_status CHECK (status IN ('CANDIDATE','ACTIVATING','ACTIVE','SUPERSEDED','DEPRECATED','RETIRED')),
    CONSTRAINT ck_workflow_definitions_claim CHECK ((status = 'ACTIVATING') = (activation_token IS NOT NULL)),
    CONSTRAINT uq_workflow_definitions_key UNIQUE NULLS NOT DISTINCT (tenant_key, definition_id, definition_version),
    CONSTRAINT ck_workflow_definitions_document CHECK (
        (kind = 'DOCUMENT' AND document IS NOT NULL AND document_format IS NOT NULL)
        OR (kind = 'CODE' AND document IS NULL AND document_format IS NULL AND tenant_key IS NULL)),
    CONSTRAINT ck_workflow_definitions_digest
        CHECK ((kind = 'DOCUMENT') = (document_digest IS NOT NULL) AND (document_digest IS NULL OR document_digest ~ '^[0-9a-f]{64}$'))
);
CREATE UNIQUE INDEX uq_workflow_definitions_one_active
    ON workflow_definitions (tenant_key, definition_id) NULLS NOT DISTINCT WHERE status = 'ACTIVE';
CREATE INDEX idx_workflow_definitions_id ON workflow_definitions (definition_id);

-- Tasks first, matching the engine's lock order (tasks before instances).
ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS tenant_key VARCHAR(255);
ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS tenant_key VARCHAR(255);

-- Partial tenant indexes for the tenant-filtered keyset queries: the tenant, then the table's keyset order.
CREATE INDEX IF NOT EXISTS idx_workflow_tasks_tenant_updated_at
    ON workflow_tasks (tenant_key, updated_at DESC, task_id ASC) WHERE tenant_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_tenant_updated_at
    ON workflow_instances (tenant_key, updated_at DESC, id ASC) WHERE tenant_key IS NOT NULL;

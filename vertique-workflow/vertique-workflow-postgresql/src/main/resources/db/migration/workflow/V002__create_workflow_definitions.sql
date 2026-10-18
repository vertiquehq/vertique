-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

CREATE TABLE workflow_definitions (
    tenant_key VARCHAR(255),
    definition_id VARCHAR(255) NOT NULL,
    definition_version BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL,
    plan_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    document TEXT,
    document_format VARCHAR(16),
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
        OR (kind = 'CODE' AND document IS NULL AND document_format IS NULL AND tenant_key IS NULL))
);
CREATE UNIQUE INDEX uq_workflow_definitions_one_active
    ON workflow_definitions (tenant_key, definition_id) NULLS NOT DISTINCT WHERE status = 'ACTIVE';
CREATE INDEX idx_workflow_definitions_id ON workflow_definitions (definition_id);

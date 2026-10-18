-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

ALTER TABLE workflow_definitions ADD COLUMN document_digest VARCHAR(64);
UPDATE workflow_definitions SET document_digest = encode(sha256(convert_to(document, 'UTF8')), 'hex') WHERE kind = 'DOCUMENT';
ALTER TABLE workflow_definitions ADD CONSTRAINT ck_workflow_definitions_digest
    CHECK ((kind = 'DOCUMENT') = (document_digest IS NOT NULL) AND (document_digest IS NULL OR document_digest ~ '^[0-9a-f]{64}$'));

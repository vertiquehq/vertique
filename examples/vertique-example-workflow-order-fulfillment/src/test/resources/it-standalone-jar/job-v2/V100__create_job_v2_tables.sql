-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Script of a sibling instance whose classpath prefix (db/migration/job-v2) starts with the
-- packaged job prefix (db/migration/job). The proof copies it into a test archive next to the job
-- scripts. Its version is one the job scripts do not use, so a leak into the job instance would be
-- applied and recorded in the job history instead of being rejected as a duplicate version.
CREATE TABLE job_v2_markers (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label VARCHAR(255) NOT NULL
);

INSERT INTO job_v2_markers (label) VALUES ('seeded-by-sibling-migration');

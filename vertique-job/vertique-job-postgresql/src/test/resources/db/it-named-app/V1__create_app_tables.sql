-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Application-owned table that shares no name with the job tables, so a mixed-up
-- schema mapping shows up as a missing or misplaced object rather than a collision.
CREATE TABLE app_items (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label VARCHAR(255) NOT NULL
);

CREATE INDEX idx_app_items_label ON app_items (label);

INSERT INTO app_items (label) VALUES ('seeded-by-app-migration');

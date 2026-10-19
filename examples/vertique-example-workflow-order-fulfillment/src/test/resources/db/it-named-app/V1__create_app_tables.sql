-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Application-owned tables that share no name with any packaged module table, so a mixed-up
-- schema mapping shows up as a missing or misplaced object rather than a collision.

-- Unqualified: lands in the application instance's default schema.
CREATE TABLE app_items (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label VARCHAR(255) NOT NULL
);

CREATE INDEX idx_app_items_label ON app_items (label);

INSERT INTO app_items (label) VALUES ('seeded-by-app-migration');

-- Qualified: lands in the extra schema the application instance also manages.
CREATE TABLE app_extra.app_audit (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note TEXT NOT NULL
);

-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

CREATE TABLE items (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    CONSTRAINT uk_items_name UNIQUE (name)
);

-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2
CREATE TABLE flyway_it_marker (
    id INTEGER PRIMARY KEY,
    note VARCHAR(64) NOT NULL
);
INSERT INTO flyway_it_marker (id, note) VALUES (1, 'applied by V1');

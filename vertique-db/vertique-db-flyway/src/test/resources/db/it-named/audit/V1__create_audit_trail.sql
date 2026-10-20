-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2
CREATE TABLE audit_trail (
    id INTEGER PRIMARY KEY,
    note VARCHAR(64) NOT NULL
);
INSERT INTO audit_trail (id, note) VALUES (1, 'recorded once');

-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2
CREATE TABLE ledger_entry (
    id INTEGER PRIMARY KEY,
    note VARCHAR(64) NOT NULL
);
INSERT INTO ledger_entry (id, note) VALUES (1, 'posted once');

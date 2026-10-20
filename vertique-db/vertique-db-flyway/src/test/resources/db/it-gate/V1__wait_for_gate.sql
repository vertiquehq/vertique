-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2
-- Blocks for as long as the test holds the gate, so the migration stays inside its transaction.
SELECT pg_advisory_lock(7770001);
SELECT pg_advisory_unlock(7770001);
CREATE TABLE gate_item (
    id INTEGER PRIMARY KEY,
    note VARCHAR(64) NOT NULL
);

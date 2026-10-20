-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Application-owned table for the shared-transaction proof. Unqualified, so it lands in the
-- application instance's mapped schema; the decoy default schema holds an incompatible table with
-- the same name, so an application insert that ignores the mapping fails instead of succeeding.
CREATE TABLE orders (
    id    UUID PRIMARY KEY,
    label VARCHAR(255) NOT NULL
);

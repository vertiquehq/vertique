-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the partial indexes that serve the outbox entry listing for the two states an operator works with, and the
-- purge of discarded entries.
--
-- The listing orders by updated_at or created_at, then id. Entries enter DEAD_LETTER and DISCARDED rarely, so
-- indexing only those two states costs the relay's insert, claim and publish path nothing. PENDING and PROCESSING
-- entries are written on every message and are listed from the indexes they already have, with a sort of the
-- (normally small) set; PUBLISHED entries are the large set, are removed by the maintenance job after a short
-- retention and are not indexed for the listing at all. The state predicate of each index is the one the listing
-- writes as a literal, so the planner can use the index for either state or both.
--
-- Lock hazard. CREATE INDEX inside the migration transaction scans the whole table under a lock that blocks
-- inserts for the duration of the scan. SET LOCAL lock_timeout bounds only the wait for that lock: while the
-- statement waits, new statements on the table queue behind it for up to the timeout, then the migration fails
-- and can be rerun. It does not bound the time the index build holds its lock once it has it. A very large
-- installation can create the indexes concurrently before it applies this script, under the same names
-- (CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_listing_updated ...); the statements below then find them
-- and do nothing.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Every statement is IF NOT EXISTS, so the script can be rerun after a failure.
SET LOCAL lock_timeout = '30s';

CREATE INDEX IF NOT EXISTS idx_outbox_listing_updated
    ON outbox (updated_at DESC, id DESC)
    WHERE state IN ('DEAD_LETTER', 'DISCARDED');

CREATE INDEX IF NOT EXISTS idx_outbox_listing_created
    ON outbox (created_at DESC, id DESC)
    WHERE state IN ('DEAD_LETTER', 'DISCARDED');

-- Serves the purge of discarded entries, which orders by nothing but filters on state and age.
CREATE INDEX IF NOT EXISTS idx_outbox_discarded
    ON outbox (updated_at ASC)
    WHERE state = 'DISCARDED';

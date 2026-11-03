// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.util.Optional;

/**
 * Query for the administration history of one outbox entry, newest first.
 *
 * <p>Results are ordered by recording time then operation ID, both descending; {@code after} continues
 * strictly after the given position.
 *
 * @param entryId identifier of the entry whose history is read; positive
 * @param limit   maximum number of entries to return; 1 to 200
 * @param after   position to continue after, taken from a previous page; empty for the first page
 */
public record OutboxAdminHistoryQuery(long entryId, int limit, Optional<OutboxAdminHistoryKey> after) {

    /** Page size used by {@link #of(long)}. */
    public static final int DEFAULT_LIMIT = AdminChecks.DEFAULT_LIMIT;

    /** Largest accepted page size. */
    public static final int MAX_LIMIT = AdminChecks.MAX_LIMIT;

    /**
     * Creates a query.
     *
     * @param entryId identifier of the entry whose history is read; positive
     * @param limit   maximum number of entries to return; 1 to 200
     * @param after   position to continue after; {@code null} reads as empty
     * @throws dev.vertique.core.exception.ValidationException if {@code entryId} is not positive or
     *         {@code limit} is outside 1 to 200
     */
    public OutboxAdminHistoryQuery {
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.limit(limit);
        after = AdminChecks.optional(after);
    }

    /**
     * Returns a first-page query with the default limit of 50.
     *
     * @param entryId identifier of the entry whose history is read; positive
     * @return the query
     * @throws dev.vertique.core.exception.ValidationException if {@code entryId} is not positive
     */
    public static OutboxAdminHistoryQuery of(long entryId) {
        return new OutboxAdminHistoryQuery(entryId, DEFAULT_LIMIT, Optional.empty());
    }
}

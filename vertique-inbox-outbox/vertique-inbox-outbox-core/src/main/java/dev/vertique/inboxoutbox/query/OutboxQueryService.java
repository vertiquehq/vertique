// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * Read-only view of the outbox for trusted in-process code, such as administration adapters: list entries by
 * state and other filters, read one entry, and read its stored payload.
 *
 * <p>The service performs no authorization. It reads the configured store as a whole: it neither filters on nor
 * returns an entry's scope key, so it is a platform-operator surface, not a scope-isolation boundary. Stored
 * carrier, security and context metadata are never returned. Retrying and discarding entries is
 * {@link dev.vertique.inboxoutbox.ops.OutboxOperations}.
 *
 * <p>Database failures leave the service as {@link dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException}
 * whose message carries no SQL, driver text or payload.
 */
public interface OutboxQueryService {

    /** Largest page size {@link #list} accepts. */
    int MAX_PAGE_SIZE = 200;

    /** Page size to use when the caller has no preference. */
    int DEFAULT_PAGE_SIZE = 50;

    /**
     * Lists the entries that match {@code query}, one keyset page at a time, in the query's {@link OutboxSort}
     * order.
     *
     * <p>A listing reads each page from the live table, so an entry that changes state between two pages can
     * appear on neither or on both. The cursor must come from a page produced for the same query; a cursor from
     * a different filter or sort is not detected here, and an adapter that hands cursors to untrusted callers
     * should bind them to the query.
     *
     * @param query  the filters and order
     * @param cursor the page to read; {@code PageCursor.first(DEFAULT_PAGE_SIZE)} for the first page, with a page
     *               size from 1 to {@link #MAX_PAGE_SIZE}
     * @return the page of summaries and the tokens of the neighbouring pages; fails with a
     *         {@link dev.vertique.core.exception.ValidationException} for an absent argument or a page size out of
     *         range
     */
    Future<PagedResult<OutboxEntrySummary>> list(OutboxQuery query, PageCursor cursor);

    /**
     * Reads one entry, in any state.
     *
     * @param entryId identifier of the entry; positive
     * @return the entry, or empty when there is none; fails with a
     *         {@link dev.vertique.core.exception.ValidationException} for a non-positive id
     */
    Future<Optional<OutboxEntryDetail>> getById(long entryId);

    /**
     * Reads the stored payload and application headers of one entry, in any state.
     *
     * <p>The size check and the read are one statement, so a value above {@link OutboxQueryConfig#maxPayloadBytes()}
     * is never transferred.
     *
     * @param entryId identifier of the entry; positive
     * @return the content, or empty when there is no such entry; fails with an
     *         {@link OutboxPayloadTooLargeException} when the content exceeds the limit
     */
    Future<Optional<OutboxEntryPayload>> getPayload(long entryId);
}

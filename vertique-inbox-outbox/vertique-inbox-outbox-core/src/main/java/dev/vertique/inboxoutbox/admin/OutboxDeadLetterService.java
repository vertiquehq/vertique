// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import io.vertx.core.Future;
import java.util.Optional;

/**
 * Administration facade for dead-lettered outbox entries.
 *
 * <p>The facade lists and inspects entries that the relay gave up on, and retries or discards them.
 * Callers are trusted in-process code, typically administration adapters: the facade performs no
 * authorization and takes the acting identity as an explicit {@link OutboxAdminActor}, never from
 * ambient context. It administers the configured store as a whole. It neither filters on nor returns an
 * entry's scope key, so it is a platform-operator surface and not a scope-isolation boundary.
 *
 * <p>All methods are nonblocking. Each command runs in one database transaction, and its receipt is
 * returned only after commit. Reads answer with an empty {@link Optional} when the entry is absent or
 * is not dead-lettered. Invalid query or command input fails with
 * {@link dev.vertique.core.exception.ValidationException} before any database work. Commands fail with
 * {@link OutboxDeadLetterNotFoundException} or {@link OutboxDeadLetterConflictException}, and a database
 * failure with {@link dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException}. Exception
 * messages never contain payload, reason or actor data.
 */
public interface OutboxDeadLetterService {

    /**
     * Lists dead-lettered entries, newest failure first.
     *
     * @param query filters and paging position; never {@code null}
     * @return one page of summaries and the position of the next page, if any
     */
    Future<OutboxDeadLetterPage> list(OutboxDeadLetterQuery query);

    /**
     * Reads one dead-lettered entry without its payload or headers.
     *
     * @param entryId identifier of the entry; positive
     * @return the detail, or empty when the entry is absent or not dead-lettered
     */
    Future<Optional<OutboxDeadLetterDetail>> find(long entryId);

    /**
     * Reads the stored payload and application headers of one dead-lettered entry.
     *
     * <p>Fails with {@link OutboxDeadLetterConflictException} with reason
     * {@link OutboxDeadLetterConflictException.Reason#CONTENT_LIMIT_EXCEEDED} when the content is larger
     * than the configured {@link InboxOutboxAdminConfig#maxContentBytes()}.
     *
     * @param entryId identifier of the entry; positive
     * @return the content, or empty when the entry is absent or not dead-lettered
     */
    Future<Optional<OutboxDeadLetterContent>> readContent(long entryId);

    /**
     * Returns a dead-lettered entry to the pending state so the relay delivers it again.
     *
     * <p>The entry's attempt count is reset, its revision is incremented and its error fields are
     * cleared. A repeat of the same operation ID and intent returns the original receipt.
     *
     * @param command the retry request; never {@code null}
     * @return the receipt, after commit
     */
    Future<OutboxAdminReceipt> retry(OutboxAdminCommand command);

    /**
     * Permanently deletes a dead-lettered entry.
     *
     * <p>A repeat of the same operation ID and intent returns the original receipt.
     *
     * @param command the discard request; never {@code null}
     * @return the receipt, after commit
     */
    Future<OutboxAdminReceipt> discard(OutboxAdminCommand command);

    /**
     * Reads the administration history of one entry, newest first. History stays readable after the
     * entry was discarded.
     *
     * @param query the entry and paging position; never {@code null}
     * @return one page of history entries and the position of the next page, if any
     */
    Future<OutboxAdminHistoryPage> history(OutboxAdminHistoryQuery query);
}

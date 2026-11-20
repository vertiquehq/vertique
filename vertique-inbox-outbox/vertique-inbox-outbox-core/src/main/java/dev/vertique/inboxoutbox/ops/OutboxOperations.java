// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import dev.vertique.inboxoutbox.OutboxEntryState;
import io.vertx.core.Future;

/**
 * Operator commands on outbox entries for trusted in-process code, such as administration adapters: bring an
 * entry back for delivery, or drop it. Reading entries is
 * {@link dev.vertique.inboxoutbox.query.OutboxQueryService}.
 *
 * <p>Each command runs in its own transaction and takes the state the caller last saw, so a stale decision
 * fails with an {@link OutboxEntryStateException} and never acts on a row that has moved on. The service
 * performs no authorization and takes no actor: the adapter that calls it authorizes and records who acted.
 * An entry being published is never touched: the relay owns {@link OutboxEntryState#PROCESSING}, and a
 * published entry is finished.
 *
 * <p>Database failures leave the service as {@link dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException}
 * whose message carries no SQL, driver text or payload.
 */
public interface OutboxOperations {

    /**
     * Brings an entry back for delivery: it returns to {@link OutboxEntryState#PENDING} with its attempt count
     * reset, its lease and last error cleared and its availability set to now, and the relay is woken when the
     * transaction commits. An entry that was scheduled for a later time still waits for that time.
     *
     * <p>Retrying a discarded entry undoes the discard. Retrying an entry that is already {@code PENDING} is not
     * accepted: a second retry after a first one succeeded finds the entry {@code PENDING} and fails with an
     * {@link OutboxEntryStateException}, which the caller can read as "already requeued".
     *
     * <p>A retried entry is delivered after later entries of the same aggregate that were delivered while it was
     * parked, so a consumer that needs the original order has to restore it.
     *
     * @param entryId       identifier of the entry; positive
     * @param expectedState the state the caller saw; {@link OutboxEntryState#DEAD_LETTER} or
     *                      {@link OutboxEntryState#DISCARDED}
     * @return the transition that was made; fails with a {@link dev.vertique.core.exception.ValidationException}
     *         for a non-positive id or an {@code expectedState} that cannot be retried, with an
     *         {@link OutboxEntryNotFoundException} when there is no such entry, and with an
     *         {@link OutboxEntryStateException} when the entry is not in {@code expectedState}
     */
    Future<OutboxTransition> retry(long entryId, OutboxEntryState expectedState);

    /**
     * Drops an entry so the relay never delivers it: it moves to {@link OutboxEntryState#DISCARDED}, keeping its
     * attempt count and last error, with its lease cleared. The maintenance job removes it after the discarded
     * retention period, and {@link #retry} brings it back until then.
     *
     * <p>Discarding an entry that is already {@code DISCARDED} succeeds and changes nothing, whatever state the
     * caller expected, so a caller can repeat a discard it is not sure was applied. The outbox is a promise made
     * by a committed business transaction: discarding a {@code PENDING} entry drops a side effect that was never
     * delivered, and nothing tells the code that recorded it.
     *
     * @param entryId       identifier of the entry; positive
     * @param expectedState the state the caller saw; {@link OutboxEntryState#PENDING},
     *                      {@link OutboxEntryState#DEAD_LETTER} or {@link OutboxEntryState#DISCARDED}
     * @return the transition that was made; fails with a {@link dev.vertique.core.exception.ValidationException}
     *         for a non-positive id or an {@code expectedState} that cannot be discarded, with an
     *         {@link OutboxEntryNotFoundException} when there is no such entry, and with an
     *         {@link OutboxEntryStateException} when the entry is not in {@code expectedState} and not already
     *         discarded
     */
    Future<OutboxTransition> discard(long entryId, OutboxEntryState expectedState);
}

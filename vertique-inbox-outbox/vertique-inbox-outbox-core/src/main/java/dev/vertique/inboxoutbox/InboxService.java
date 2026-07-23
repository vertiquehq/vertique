// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.function.Supplier;

/**
 * Service for processing inbound messages exactly once within a database transaction.
 *
 * <p>The inbox pattern guarantees that a message identified by a {@code (messageId, source)}
 * pair is processed at most once, even if the same message is delivered multiple times (e.g.,
 * due to at-least-once delivery semantics in Kafka or other messaging systems).
 *
 * <p>Callers pass an open transaction along with the work to be performed. If the
 * {@code (messageId, source)} pair has not been seen before, the service records it and
 * invokes the work supplier; otherwise it returns a {@link InboxResult.Duplicate} without
 * invoking the supplier.
 *
 * <p>Example usage:
 * <pre>{@code
 * Future<InboxResult<Void>> result = inboxService.processOnce(
 *     event.messageId(), "orders-service", tx,
 *     () -> orderRepository.save(order, tx));
 * }</pre>
 */
public interface InboxService {

    /**
     * Processes the given work exactly once for the specified message identifier within the
     * provided transaction.
     *
     * <p>If the {@code (messageId, source)} pair has not been processed before, the inbox
     * record is inserted and {@code work} is invoked. If it has already been processed, the
     * work is skipped and a {@link InboxResult.Duplicate} is returned. Both the inbox insert
     * and the work execute within the same {@code tx} transaction, ensuring atomicity.
     *
     * @param <T>       the type of value produced by the work function
     * @param messageId globally unique identifier for the inbound message
     * @param source    logical name of the system or topic that produced the message
     * @param tx        open database transaction to use for both deduplication and work
     * @param work      supplier of the async work to perform if the message is new
     * @return a {@link Future} that completes with {@link InboxResult.Processed} containing the
     *         work result, or {@link InboxResult.Duplicate} if the message was already processed
     */
    <T> Future<InboxResult<T>> processOnce(String messageId, String source, SqlClient tx, Supplier<Future<T>> work);
}

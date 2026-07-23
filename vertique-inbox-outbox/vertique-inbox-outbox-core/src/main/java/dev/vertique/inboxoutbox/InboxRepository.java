// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

/**
 * Data access contract for the inbox deduplication table.
 *
 * <p>The inbox table stores a compact record of every processed {@code (messageId, source)}
 * pair so that duplicate deliveries can be detected without re-executing business logic.
 * The {@link #tryInsert} method must be called within the same transaction as the business
 * operation to ensure atomicity between deduplication and processing.
 *
 * <p>Implementations are provided by the persistence-specific sub-modules
 * (e.g., {@code vertique-inbox-outbox-postgresql}).
 */
public interface InboxRepository {

    /**
     * Attempts to insert a deduplication record for the given message within the provided
     * transaction.
     *
     * <p>Returns {@code true} if the record was inserted (i.e., the message is new), or
     * {@code false} if a record already exists for the {@code (messageId, source)} pair
     * (i.e., the message is a duplicate). Uses an INSERT with conflict detection to make
     * the check-and-insert atomic within the transaction.
     *
     * @param messageId globally unique identifier for the inbound message
     * @param source    logical name of the system or topic that produced the message
     * @param tx        open database transaction to use for the insert
     * @return a {@link Future} that completes with {@code true} if the message is new,
     *         or {@code false} if it is a duplicate
     */
    Future<Boolean> tryInsert(String messageId, String source, SqlClient tx);

    /**
     * Deletes processed inbox records older than the given retention period.
     *
     * <p>This method is invoked by the cleanup job to prevent unbounded growth of the inbox
     * table. Records are deleted in batches to avoid long-running transactions.
     *
     * @param retentionDays number of days to retain processed inbox records
     * @param batchSize     maximum number of records to delete in a single operation
     * @return a {@link Future} that completes with the number of records deleted
     */
    Future<Integer> cleanup(int retentionDays, int batchSize);
}

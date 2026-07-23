// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import java.util.List;

/**
 * Repository SPI for {@link WorkflowHistoryEntry} persistence.
 *
 * <p>Generic over the transaction-handle type {@code TX} so the workflow engine can depend on this
 * interface without binding to a specific database client.
 *
 * <p>History is append-only. Each entry is assigned a monotonically increasing sequence number
 * scoped to its workflow instance; the sequence is managed by the engine via {@link #nextSequence}.
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface WorkflowHistoryRepository<TX> {

    // --- Write operations ---

    /**
     * Appends a history entry within the given transaction. Uses {@code entry.recordedAt()} when
     * non-null, otherwise falls back to the current time.
     *
     * @param e  the history entry to append
     * @param tx the active transaction to use for the insert
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> append(WorkflowHistoryEntry e, TX tx);

    // --- Sequence operations ---

    /**
     * Returns the next sequence number for a workflow instance within the given transaction.
     *
     * <p>The sequence is computed as {@code MAX(sequence) + 1} for the instance, or {@code 1} if no
     * history entries exist yet. This method must be called inside the same transaction as the
     * subsequent {@link #append} call to guarantee monotonicity.
     *
     * @param id the workflow instance id whose sequence counter to advance
     * @param tx the active transaction to use
     * @return a {@link Future} containing the next sequence number
     */
    Future<Long> nextSequence(WorkflowInstanceId id, TX tx);

    // --- Read operations ---

    /**
     * Returns the most recent {@code limit} history entries for the given workflow instance, ordered
     * by sequence descending (most recent first) within the transaction.
     *
     * @param id    the workflow instance id whose history to retrieve
     * @param limit the maximum number of entries to return; must be positive
     * @param tx    the SQL client or active transaction to use for the query
     * @return a {@link Future} containing up to {@code limit} entries, most recent first
     */
    Future<List<WorkflowHistoryEntry>> listRecentByInstance(WorkflowInstanceId id, int limit, TX tx);

    /**
     * Returns all history entries for the given workflow instance, ordered by sequence ascending.
     * Executes read-only (no locking).
     *
     * @param id the workflow instance id whose history to retrieve
     * @return a {@link Future} containing the ordered history entries
     */
    Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id);

    /**
     * Returns all history entries for the given workflow instance, ordered by sequence ascending.
     * Executes against the given transaction handle, allowing callers to run the read within the
     * same transaction that holds write locks on the instance row.
     *
     * @param id the workflow instance id whose history to retrieve
     * @param tx the transaction handle (or active transaction) to use for the query
     * @return a {@link Future} containing the ordered history entries
     */
    Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id, TX tx);
}

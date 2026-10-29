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

    /**
     * Appends an entry of the decision log within the given transaction, assigning it the next sequence of its
     * instance. The entry's decision columns are stored with it and its {@code entry_version} is {@code 2}.
     *
     * <p>The default stores the entry through {@link #append} with its patch or state as the payload only, for a
     * repository that keeps no decision columns.
     *
     * @param entry the decision-log entry to append
     * @param tx    the active transaction to use for the insert
     * @return a {@link Future} that completes when the row is inserted
     */
    default Future<Void> appendDecision(DecisionEntry entry, TX tx) {
        return nextSequence(entry.instanceId(), tx).compose(seq -> {
            io.vertx.core.json.JsonObject payload = new io.vertx.core.json.JsonObject();
            if (entry.stateJson() != null) {
                payload.put("state", new io.vertx.core.json.JsonObject(entry.stateJson()));
            }
            if (entry.patchJson() != null) {
                payload.put("patch", new io.vertx.core.json.JsonArray(entry.patchJson()));
            }
            return append(
                    new WorkflowHistoryEntry(
                            entry.instanceId(), seq, entry.entryType(), payload.encode(), entry.recordedAt()),
                    tx);
        });
    }

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
     * by sequence descending (most recent first) within the transaction. The entries of the decision log
     * ({@code DECISION} and {@code STATE_BASELINE}) are not listed and do not count toward the limit; the history
     * service serves them.
     *
     * @param id    the workflow instance id whose history to retrieve
     * @param limit the maximum number of entries to return; must be positive
     * @param tx    the SQL client or active transaction to use for the query
     * @return a {@link Future} containing up to {@code limit} entries, most recent first
     */
    Future<List<WorkflowHistoryEntry>> listRecentByInstance(WorkflowInstanceId id, int limit, TX tx);

    /**
     * Returns the history entries for the given workflow instance, ordered by sequence ascending, without the entries
     * of the decision log ({@code DECISION} and {@code STATE_BASELINE}), which the history service serves.
     * Executes read-only (no locking).
     *
     * @param id the workflow instance id whose history to retrieve
     * @return a {@link Future} containing the ordered history entries
     */
    Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id);

    /**
     * Returns the history entries for the given workflow instance, ordered by sequence ascending, without the entries
     * of the decision log ({@code DECISION} and {@code STATE_BASELINE}).
     * Executes against the given transaction handle, allowing callers to run the read within the
     * same transaction that holds write locks on the instance row.
     *
     * @param id the workflow instance id whose history to retrieve
     * @param tx the transaction handle (or active transaction) to use for the query
     * @return a {@link Future} containing the ordered history entries
     */
    Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id, TX tx);

    /**
     * INTERNAL: returns up to {@code limit} enriched history entries of an instance in the sequence range
     * {@code (after, upTo]}, ascending, without {@code STATE_BASELINE} entries, each with the kind of the command it
     * names. Read after a commit to collect what the committed transaction wrote, one page at a time: a page of
     * {@code limit} entries means there may be more, and the next page starts after the last sequence of this one.
     *
     * <p>The entries are for announcement only: the {@code payloadJson} is filled only for the entry types whose events
     * read it ({@code TASK_CREATED}, {@code TASK_COMPLETED}, {@code TASK_REASSIGNED} and {@code TASK_CANCELLED}), and
     * the state patch is never filled.
     *
     * <p>The default fails with {@link UnsupportedOperationException}: a repository that does not implement it cannot
     * serve history listeners, whose notification is then lost and logged.
     *
     * @param id    the workflow instance id
     * @param after the sequence the range starts after (exclusive)
     * @param upTo  the last sequence of the range (inclusive)
     * @param limit the most entries to return
     * @param tx    the SQL client or transaction to read with
     * @return a {@link Future} containing the entries of the page, possibly empty
     */
    default Future<List<CommittedHistoryEntry>> listRange(
            WorkflowInstanceId id, long after, long upTo, int limit, TX tx) {
        return Future.failedFuture(new UnsupportedOperationException("listRange is not supported"));
    }
}

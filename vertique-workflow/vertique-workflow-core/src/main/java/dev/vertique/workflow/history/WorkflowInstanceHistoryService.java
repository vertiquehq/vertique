// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import java.util.List;

/**
 * Reads the log of one workflow instance: its entries, enriched with who acted and what was decided, and the commands
 * it has not yet settled.
 *
 * <p>This is the only read that returns the enriched {@link WorkflowHistoryEntry}. The history that
 * {@link dev.vertique.workflow.ops.WorkflowOperations#query} returns carries only the five original components and the
 * stored entry version.
 *
 * <p>The service reads only. It takes no execution lock and runs on a pooled connection outside any caller
 * transaction. It performs no authorization: an entry names actors and carries state patches, so an application that
 * exposes these reads authorizes them itself.
 */
public interface WorkflowInstanceHistoryService {

    /**
     * Returns the entries of the instance in ascending sequence order, starting after a sequence.
     *
     * <p>Read the next page by passing the last sequence of the previous page as {@code afterSequence}. An unknown
     * instance has no entries.
     *
     * @param id            the instance
     * @param afterSequence the sequence after which to start; {@code 0} for the first page
     * @param limit         the largest number of entries to return, 1 to 500
     * @return the entries, possibly empty
     * @throws IllegalArgumentException if {@code limit} is outside 1 to 500; nothing is read
     */
    Future<List<WorkflowHistoryEntry>> entries(WorkflowInstanceId id, long afterSequence, int limit);

    /**
     * Returns the commands of the instance that are not settled, parked or dead-lettered, in sequence order: those
     * {@code PENDING}, {@code DEFERRED}, {@code CLAIMED}, {@code PARKED} or {@code DEAD}.
     *
     * <p>The {@code lastError} of a command is the reason code and sanitized message stored when it failed.
     *
     * @param id the instance
     * @return the open commands, possibly empty
     */
    Future<List<PendingCommand>> openCommands(WorkflowInstanceId id);
}

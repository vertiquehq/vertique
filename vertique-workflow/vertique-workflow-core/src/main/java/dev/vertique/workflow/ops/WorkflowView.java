// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import java.util.List;

/**
 * A read-only projection of a workflow instance including recent history.
 *
 * <p>Returned by {@link WorkflowOperations#query(WorkflowInstanceId)}. The {@code recentHistory}
 * list is ordered by sequence ascending and may be limited to the most recent N entries depending
 * on the implementation.
 *
 * @param instance current snapshot of the workflow instance
 * @param recentHistory recent history entries ordered by sequence ascending
 */
public record WorkflowView(WorkflowInstance instance, List<WorkflowHistoryEntry> recentHistory) {}

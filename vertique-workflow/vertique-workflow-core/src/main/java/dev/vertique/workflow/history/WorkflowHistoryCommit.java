// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.List;
import java.util.Objects;

/**
 * The history events one committed transaction wrote for one instance.
 *
 * @param instanceId the instance the events belong to
 * @param events     the events, ascending by sequence; never empty
 */
public record WorkflowHistoryCommit(WorkflowInstanceId instanceId, List<WorkflowHistoryEvent> events) {

    /** Validates and copies the events. */
    public WorkflowHistoryCommit {
        Objects.requireNonNull(instanceId, "instanceId");
        events = List.copyOf(events);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.List;
import java.util.Objects;

/**
 * The history events one committed transaction wrote for one instance, as one range of consecutive sequences.
 *
 * <p>The events arrive together, not one call per event, because one engine action writes several entries atomically
 * (a signal, the step it completes and the step it starts, for example) and the commit is the unit that became
 * visible. A listener that sees the group can treat it as one step of the instance, for example one notification or one
 * metric increment per change, and the entries are read together once after the commit. Each event still carries its own
 * {@code sequence}, so a consumer that wants a single event at a time can iterate {@link #events()}.
 *
 * <p>A transaction registers one settlement however many actions it runs, and the ranges of one instance are merged:
 * two adjacent actions on an instance give one change, and sequences that a rollback to a savepoint let a later action
 * reuse are announced once. A range larger than the read page (1000 entries) arrives as several consecutive changes.
 *
 * @param instanceId the instance the events belong to
 * @param events     the events, ascending by sequence; never empty
 */
public record WorkflowHistoryChange(WorkflowInstanceId instanceId, List<WorkflowHistoryEvent> events) {

    /** Validates and copies the events. */
    public WorkflowHistoryChange {
        Objects.requireNonNull(instanceId, "instanceId");
        events = List.copyOf(events);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
    }
}

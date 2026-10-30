// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.history;

import dev.vertique.workflow.history.WorkflowHistoryChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A history listener that keeps what it is told, in the order it is told, next to one that always throws. */
public final class RecordedHistory implements WorkflowHistoryListener {

    private final List<WorkflowHistoryChange> changes = new CopyOnWriteArrayList<>();
    private final List<WorkflowHistoryFailure> failures = new CopyOnWriteArrayList<>();
    private final AtomicInteger thrown = new AtomicInteger();

    @Override
    public void onHistoryChanged(WorkflowHistoryChange change) {
        changes.add(change);
    }

    @Override
    public void onHistoryFailed(WorkflowHistoryFailure failure) {
        failures.add(failure);
    }

    /** A listener that throws from both callbacks and counts how often it did. */
    public WorkflowHistoryListener thrower() {
        return new WorkflowHistoryListener() {
            @Override
            public void onHistoryChanged(WorkflowHistoryChange change) {
                thrown.incrementAndGet();
                throw new IllegalStateException("the listener broke");
            }

            @Override
            public void onHistoryFailed(WorkflowHistoryFailure failure) {
                thrown.incrementAndGet();
                throw new IllegalStateException("the listener broke");
            }
        };
    }

    /** The commits announced so far, in the order of delivery. */
    public List<WorkflowHistoryChange> changes() {
        return List.copyOf(changes);
    }

    /** The failures reported so far, in the order of delivery. */
    public List<WorkflowHistoryFailure> failures() {
        return List.copyOf(failures);
    }

    /** How many callbacks the throwing listener has been given. */
    public int thrown() {
        return thrown.get();
    }

    /** The events announced for the instance, in the order of delivery. */
    public List<WorkflowHistoryEvent> eventsOf(WorkflowInstanceId id) {
        return changes.stream()
                .filter(change -> change.instanceId().equals(id))
                .flatMap(change -> change.events().stream())
                .toList();
    }
}

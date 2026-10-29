// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: delivers executor events to the observers.
 *
 * <p>Each observer is called in its own {@code try}: an exception it throws is caught and logged at warn level with
 * the observer class and the event type, the remaining observers still receive the event, and nothing reaches the
 * caller, so command execution, outcomes and transactions are unaffected.
 */
final class ExecutorEvents {

    private static final Logger log = LoggerFactory.getLogger(ExecutorEvents.class);

    private static final ExecutorEvents NONE = new ExecutorEvents(Set.of());

    private final List<WorkflowExecutorObserver> observers;

    private ExecutorEvents(Set<WorkflowExecutorObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    /** Events that go to nobody. */
    static ExecutorEvents none() {
        return NONE;
    }

    /**
     * Events that go to the observers.
     *
     * @param observers the observers; may be empty
     * @return the delivery
     */
    static ExecutorEvents to(Set<WorkflowExecutorObserver> observers) {
        return observers.isEmpty() ? NONE : new ExecutorEvents(observers);
    }

    /** Whether any observer is registered. */
    boolean any() {
        return !observers.isEmpty();
    }

    /**
     * Delivers an event to every observer.
     *
     * @param event the event
     */
    void emit(WorkflowExecutorEvent event) {
        for (WorkflowExecutorObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
                log.warn(
                        "Executor observer {} threw while handling a {} event: {}",
                        observer.getClass().getName(),
                        event.getClass().getSimpleName(),
                        t.getClass().getName());
                log.debug("Executor observer failure", t);
            }
        }
    }
}

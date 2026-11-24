// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that executor observers are isolated from the caller and from each other. */
class ExecutorEventsTest {

    private static final WorkflowExecutorEvent EVENT = new WorkflowExecutorEvent.ActorAbsent("START");

    private static ExecutorEvents to(WorkflowExecutorObserver... observers) {
        return ExecutorEvents.to(new LinkedHashSet<>(List.of(observers)));
    }

    @Test
    @DisplayName("an observer that throws an exception does not stop the next observer and nothing propagates")
    void anExceptionIsIsolated() {
        // Given a first observer that throws an exception and a second that records
        List<WorkflowExecutorEvent> heard = new ArrayList<>();
        ExecutorEvents events = to(
                event -> {
                    throw new IllegalStateException("observer broke");
                },
                heard::add);

        // When an event is emitted
        // Then nothing propagates and the second observer got the event
        assertThatCode(() -> events.emit(EVENT)).doesNotThrowAnyException();
        assertThat(heard).containsExactly(EVENT);
    }

    @Test
    @DisplayName("an observer that throws an assertion error does not stop the next observer and nothing propagates")
    void anAssertionErrorIsIsolated() {
        // Given a first observer that fails an assertion and a second that records
        List<WorkflowExecutorEvent> heard = new ArrayList<>();
        ExecutorEvents events = to(
                event -> {
                    throw new AssertionError("observer assertion failed");
                },
                heard::add);

        // When an event is emitted
        // Then nothing propagates and the second observer got the event
        assertThatCode(() -> events.emit(EVENT)).doesNotThrowAnyException();
        assertThat(heard).containsExactly(EVENT);
    }

    @Test
    @DisplayName("a virtual machine error from an observer propagates")
    void aVirtualMachineErrorPropagates() {
        // Given an observer that throws a virtual machine error
        StackOverflowError fatal = new StackOverflowError("deliberate");
        Set<WorkflowExecutorObserver> observers = Set.of(event -> {
            throw fatal;
        });

        // When an event is emitted
        // Then the very same error reaches the caller
        assertThatThrownBy(() -> ExecutorEvents.to(observers).emit(EVENT)).isSameAs(fatal);
    }
}

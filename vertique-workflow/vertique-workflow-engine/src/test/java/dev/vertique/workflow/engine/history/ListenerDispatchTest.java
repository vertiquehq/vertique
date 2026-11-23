// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies which failures the two dispatch methods isolate and which they let propagate. */
class ListenerDispatchTest {

    /** What a failing call hands over: the listener and what it threw. */
    private record Failure(String listener, Throwable thrown) {}

    private final List<String> called = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();

    private Consumer<String> throwing(String failing, Throwable thrown) {
        return listener -> {
            called.add(listener);
            if (listener.equals(failing)) {
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) thrown;
            }
        };
    }

    @Test
    @DisplayName("forEach hands an exception to the callback and still calls the next listener")
    void forEachIsolatesAnException() {
        // Given a first listener that throws an exception
        IllegalStateException boom = new IllegalStateException("boom");

        // When the listeners are called
        ListenerDispatch.forEach(
                List.of("a", "b"),
                throwing("a", boom),
                (listener, thrown) -> failures.add(new Failure(listener, thrown)));

        // Then the callback got it and the second listener was called
        assertThat(failures).containsExactly(new Failure("a", boom));
        assertThat(called).containsExactly("a", "b");
    }

    @Test
    @DisplayName("forEach lets an error propagate and stops the listeners after it")
    void forEachLetsAnErrorPropagate() {
        // Given a first listener that throws an assertion error
        AssertionError error = new AssertionError("failed");

        // When the listeners are called
        // Then the error propagates, the callback is not called and the second listener is not reached
        assertThatThrownBy(() -> ListenerDispatch.forEach(
                        List.of("a", "b"),
                        throwing("a", error),
                        (listener, thrown) -> failures.add(new Failure(listener, thrown))))
                .isSameAs(error);
        assertThat(failures).isEmpty();
        assertThat(called).containsExactly("a");
    }

    @Test
    @DisplayName("forEachIsolatingErrors hands an exception to the callback and still calls the next listener")
    void isolatingHandsAnExceptionToTheCallback() {
        // Given a first listener that throws an exception
        IllegalStateException boom = new IllegalStateException("boom");

        // When the listeners are called
        ListenerDispatch.forEachIsolatingErrors(
                List.of("a", "b"),
                throwing("a", boom),
                (listener, thrown) -> failures.add(new Failure(listener, thrown)));

        // Then the callback got it and the second listener was called
        assertThat(failures).containsExactly(new Failure("a", boom));
        assertThat(called).containsExactly("a", "b");
    }

    @Test
    @DisplayName("forEachIsolatingErrors hands an error to the callback and still calls the next listener")
    void isolatingHandsAnErrorToTheCallback() {
        // Given a first listener that throws an assertion error
        AssertionError error = new AssertionError("failed");

        // When the listeners are called
        ListenerDispatch.forEachIsolatingErrors(
                List.of("a", "b"),
                throwing("a", error),
                (listener, thrown) -> failures.add(new Failure(listener, thrown)));

        // Then the callback got the error and the second listener was called
        assertThat(failures).containsExactly(new Failure("a", error));
        assertThat(called).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a virtual machine error propagates from both methods")
    void aVirtualMachineErrorPropagatesFromBoth() {
        // Given a listener that throws a virtual machine error
        StackOverflowError fatal = new StackOverflowError("deliberate");

        // When either method calls it
        // Then the very same error propagates, the callback is not called and the next listener is not reached
        assertThatThrownBy(() -> ListenerDispatch.forEach(
                        List.of("a", "b"),
                        throwing("a", fatal),
                        (listener, thrown) -> failures.add(new Failure(listener, thrown))))
                .isSameAs(fatal);
        assertThatThrownBy(() -> ListenerDispatch.forEachIsolatingErrors(
                        List.of("a", "b"),
                        throwing("a", fatal),
                        (listener, thrown) -> failures.add(new Failure(listener, thrown))))
                .isSameAs(fatal);
        assertThat(failures).isEmpty();
        assertThat(called).containsExactly("a", "a");
    }
}

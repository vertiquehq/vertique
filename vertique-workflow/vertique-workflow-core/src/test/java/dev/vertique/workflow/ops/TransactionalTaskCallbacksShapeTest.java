// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test that verifies {@link TransactionalTaskCallbacks} declares the expected SPI method
 * names and is an interface.
 *
 * <p>Mirrors the style of {@code TimerStoreShapeTest}.
 */
class TransactionalTaskCallbacksShapeTest {

    private static final Set<String> EXPECTED_METHODS =
            Set.of("taskCompleted", "taskDueFired", "taskReassigned", "taskReminderFired");

    @Test
    @DisplayName("TransactionalTaskCallbacks declares all 4 expected SPI method names")
    void hasExpectedMethods() {
        Set<String> declared = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(declared)
                .as("TransactionalTaskCallbacks must declare all 4 SPI methods")
                .containsAll(EXPECTED_METHODS);
    }

    @Test
    @DisplayName("TransactionalTaskCallbacks is an interface")
    void isAnInterface() {
        assertThat(TransactionalTaskCallbacks.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("taskCompleted takes TaskCompletionCommand as first parameter")
    void taskCompletedSignature() throws NoSuchMethodException {
        // The interface is generic over TX; raw class lookup finds the bridge via first declared
        // method matching the name and checking its first parameter type.
        Method method = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("taskCompleted"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("taskCompleted not found"));

        assertThat(method.getParameterTypes()[0]).isEqualTo(TaskCompletionCommand.class);
    }

    @Test
    @DisplayName("taskReassigned takes TaskReassignmentCommand as first parameter")
    void taskReassignedSignature() {
        Method method = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("taskReassigned"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("taskReassigned not found"));

        assertThat(method.getParameterTypes()[0]).isEqualTo(TaskReassignmentCommand.class);
    }
}

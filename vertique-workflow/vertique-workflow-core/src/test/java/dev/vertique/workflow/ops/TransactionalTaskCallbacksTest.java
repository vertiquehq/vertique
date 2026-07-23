// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test that verifies {@link TransactionalTaskCallbacks} declares the expected SPI method
 * names including the cycle-4 {@code taskReminderFired} method.
 */
class TransactionalTaskCallbacksTest {

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
    @DisplayName("taskReminderFired declares WorkflowInstanceId as first parameter")
    void taskReminderFiredFirstParam() {
        Method method = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("taskReminderFired"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("taskReminderFired not found"));

        assertThat(method.getParameterTypes()[0]).isEqualTo(WorkflowInstanceId.class);
    }

    @Test
    @DisplayName("taskReminderFired declares UUID as second parameter (taskId)")
    void taskReminderFiredSecondParam() {
        Method method = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("taskReminderFired"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("taskReminderFired not found"));

        assertThat(method.getParameterTypes()[1]).isEqualTo(UUID.class);
    }

    @Test
    @DisplayName("taskReminderFired returns Future (return type is Future)")
    void taskReminderFiredReturnsFuture() {
        Method method = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("taskReminderFired"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("taskReminderFired not found"));

        assertThat(Future.class.isAssignableFrom(method.getReturnType())).isTrue();
    }
}

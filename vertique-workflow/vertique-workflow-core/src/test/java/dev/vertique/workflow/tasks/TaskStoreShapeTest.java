// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test that verifies {@link TaskStore} declares the expected SPI method names.
 *
 * <p>This test exists to catch accidental renames or removals of SPI methods before a downstream
 * implementation notices at compile time. Mirrors the style of {@code TimerStoreShapeTest}.
 */
class TaskStoreShapeTest {

    private static final Set<String> EXPECTED_METHODS = Set.of(
            "insertOpen",
            "lockForCompletion",
            "markCompleted",
            "markCancelled",
            "markExpired",
            "reassign",
            "findByFilter",
            "findById");

    @Test
    @DisplayName("TaskStore declares all 8 expected SPI method names")
    void taskStoreHasExpectedMethods() {
        Set<String> declared = Arrays.stream(TaskStore.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(declared).as("TaskStore must declare all 8 SPI methods").containsAll(EXPECTED_METHODS);
    }

    @Test
    @DisplayName("TaskStore is an interface")
    void taskStoreIsAnInterface() {
        assertThat(TaskStore.class.isInterface()).isTrue();
    }
}

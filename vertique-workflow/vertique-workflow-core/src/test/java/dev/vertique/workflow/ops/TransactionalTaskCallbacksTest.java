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
 * names.
 */
class TransactionalTaskCallbacksTest {

    private static final Set<String> EXPECTED_METHODS = Set.of("taskCompleted", "taskReassigned");

    @Test
    @DisplayName("TransactionalTaskCallbacks declares both expected SPI method names")
    void hasExpectedMethods() {
        Set<String> declared = Arrays.stream(TransactionalTaskCallbacks.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(declared)
                .as("TransactionalTaskCallbacks must declare both SPI methods")
                .containsAll(EXPECTED_METHODS);
    }

    @Test
    @DisplayName("TransactionalTaskCallbacks is an interface")
    void isAnInterface() {
        assertThat(TransactionalTaskCallbacks.class.isInterface()).isTrue();
    }
}

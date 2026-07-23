// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test that verifies {@link TimerStore} declares the eight expected method names.
 *
 * <p>This test exists to catch accidental renames or removals of SPI methods before a
 * downstream implementation notices at compile time.
 */
class TimerStoreShapeTest {

    private static final Set<String> EXPECTED_METHODS = Set.of(
            "insertScheduled",
            "lockForFiring",
            "markFired",
            "markCancelled",
            "markFailed",
            "findRecoverableScheduled",
            "updateExecutionId",
            "findScheduledRemindersForTask");

    @Test
    @DisplayName("TimerStore declares the 8 expected SPI method names")
    void timerStoreHasExpectedMethods() {
        Set<String> declared = Arrays.stream(TimerStore.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(declared).as("TimerStore must declare all 8 SPI methods").containsAll(EXPECTED_METHODS);
    }

    @Test
    @DisplayName("TimerStore is an interface")
    void timerStoreIsAnInterface() {
        assertThat(TimerStore.class.isInterface()).isTrue();
    }
}

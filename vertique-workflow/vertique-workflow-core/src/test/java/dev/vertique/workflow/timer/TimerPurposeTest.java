// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that all four documented {@link TimerPurpose} enum constants are present.
 */
class TimerPurposeTest {

    @Test
    @DisplayName("all 4 documented constants are present")
    void allConstantsPresent() {
        assertThat(TimerPurpose.values())
                .extracting(TimerPurpose::name)
                .containsExactlyInAnyOrder("STANDALONE", "SIGNAL_TIMEOUT", "TASK_DUE", "TASK_REMINDER");
    }

    @Test
    @DisplayName("name() round-trips via valueOf for every constant")
    void nameRoundTrips() {
        for (TimerPurpose purpose : TimerPurpose.values()) {
            assertThat(TimerPurpose.valueOf(purpose.name())).isSameAs(purpose);
        }
    }
}

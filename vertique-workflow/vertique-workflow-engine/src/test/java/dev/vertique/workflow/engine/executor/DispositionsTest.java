// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies the backoff of a transient retry. */
class DispositionsTest {

    @ParameterizedTest(name = "base {0} ms, max {1} ms, count {2} gives {3} ms")
    @DisplayName("the transient backoff doubles from the base and caps at the maximum")
    @CsvSource({
        "200, 30000, 1, 200",
        "200, 30000, 2, 400",
        "200, 30000, 3, 800",
        "200, 30000, 4, 1600",
        "200, 30000, 5, 3200",
        "200, 30000, 6, 6400",
        "200, 30000, 7, 12800",
        "200, 30000, 8, 25600",
        "200, 30000, 9, 30000",
        "200, 30000, 10, 30000",
        "200, 30000, 70, 30000",
        "200, 30000, 2147483647, 30000",
        "10, 50, 1, 10",
        "10, 50, 2, 20",
        "10, 50, 3, 40",
        "10, 50, 4, 50",
        "10, 50, 5, 50",
        "500, 500, 1, 500",
        "500, 500, 3, 500"
    })
    void transientBackoffDoublesFromBaseAndCapsAtMax(long baseMs, long maxMs, int transientCount, long expectedMs) {
        // Given a base, a maximum and the transient count after the failure

        // When the backoff is computed
        long backoffMs = Dispositions.transientBackoffMs(transientCount, baseMs, maxMs);

        // Then it is the base doubled once per earlier failure, capped at the maximum
        assertThat(backoffMs).isEqualTo(expectedMs);
    }
}

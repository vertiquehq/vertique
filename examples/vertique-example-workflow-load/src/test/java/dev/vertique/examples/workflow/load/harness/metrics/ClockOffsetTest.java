// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the clock offset estimate and the quantile rule. */
class ClockOffsetTest {

    @Test
    @DisplayName("the estimate comes from the reading with the shortest round trip")
    void shortestRoundTrip() {
        ClockOffset offset = ClockOffset.estimate(List.of(
                new ClockOffset.Reading(1000, 1750, 1100),
                new ClockOffset.Reading(2000, 2504, 2008),
                new ClockOffset.Reading(3000, 3900, 3200)));

        assertEquals(500.0, offset.offsetMs());
        assertEquals(8.0, offset.roundTripMs());
        assertEquals(1500.0, offset.toDatabase(1000));
        assertEquals(1000.0, offset.toHost(1500));
    }

    @Test
    @DisplayName("no readings is an error")
    void noReadings() {
        assertThrows(IllegalArgumentException.class, () -> ClockOffset.estimate(List.of()));
    }

    @Test
    @DisplayName("quantiles follow the nearest-rank rule")
    void quantiles() {
        double[] values = {5, 1, 4, 2, 3, 10, 9, 8, 7, 6};

        assertEquals(5.0, Quantiles.of(values, 0.5));
        assertEquals(10.0, Quantiles.of(values, 0.95));
        assertEquals(1.0, Quantiles.of(values, 0.0));
        assertEquals(10.0, Quantiles.of(values, 1.0));
        assertEquals(5.0, Quantiles.median(values));
        assertEquals(Double.NaN, Quantiles.of(new double[0], 0.5));
        assertEquals(3.0, Quantiles.of(new double[] {1, 3}, 0.75));
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the detection of sustained dips and excursions, and the slope of a series. */
class StallsTest {

    @Test
    @DisplayName("a stretch of seconds well below the median is a dip")
    void dip() {
        double[] series = new double[60];
        Arrays.fill(series, 100);
        series[20] = 0;
        series[21] = 10;
        series[22] = 20;

        List<Stalls.Interval> dips = Stalls.dips(series, 0, 60, 0.5, 2);

        assertEquals(1, dips.size());
        assertEquals(20, dips.get(0).startIndex());
        assertEquals(23, dips.get(0).endIndex());
        assertEquals(3, dips.get(0).seconds());
        assertEquals(0.0, dips.get(0).extreme());
        assertEquals(100.0, dips.get(0).reference());
    }

    @Test
    @DisplayName("one low second is not sustained")
    void singleSecond() {
        double[] series = new double[30];
        Arrays.fill(series, 100);
        series[10] = 0;

        assertTrue(Stalls.dips(series, 0, 30, 0.5, 2).isEmpty());
    }

    @Test
    @DisplayName("a dip at the end of the range is closed by the range")
    void dipAtEnd() {
        double[] series = new double[20];
        Arrays.fill(series, 100);
        series[17] = 0;
        series[18] = 0;
        series[19] = 0;

        assertEquals(1, Stalls.dips(series, 0, 20, 0.5, 2).size());
    }

    @Test
    @DisplayName("a median of zero finds no dip")
    void zeroMedian() {
        assertTrue(Stalls.dips(new double[30], 0, 30, 0.5, 2).isEmpty());
    }

    @Test
    @DisplayName("seconds with a slow 95th percentile make an excursion, seconds without data do not")
    void excursion() {
        double[] p95 = new double[30];
        Arrays.fill(p95, 20);
        p95[5] = 1500;
        p95[6] = 2500;
        p95[7] = Double.NaN;
        p95[8] = 1800;

        List<Stalls.Interval> found = Stalls.excursions(p95, 0, 30, 1000, 2);

        assertEquals(1, found.size());
        assertEquals(5, found.get(0).startIndex());
        assertEquals(7, found.get(0).endIndex());
        assertEquals(2500.0, found.get(0).extreme());
    }

    @Test
    @DisplayName("the slope is the least-squares growth per second and ignores missing seconds")
    void slope() {
        double[] growing = {0, 2, 4, 6, 8, 10};
        double[] withGap = {0, 2, Double.NaN, 6, 8, 10};

        assertEquals(2.0, Stalls.slope(growing, 0, 6), 1e-9);
        assertEquals(2.0, Stalls.slope(withGap, 0, 6), 1e-9);
        assertEquals(0.0, Stalls.slope(new double[] {5}, 0, 1));
        assertEquals(0.0, Stalls.slope(new double[] {5, 5, 5, 5}, 0, 4), 1e-9);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.metrics.Quantiles;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the sustained throughput dips and latency excursions of a run.
 *
 * <p>A dip is a stretch of at least a minimum number of consecutive seconds in which the completions per second stay
 * below a fraction of the typical rate of the stretch it lies in, the 75th percentile of its seconds, so that an
 * outage that fills half of the stretch is still a dip against the seconds that ran. An excursion is a stretch of at least a minimum number of consecutive
 * seconds in which the 95th percentile of recorded-to-segment-start exceeds a limit. Both are found on the data
 * alone; what coincided with them is the business of {@link Evidence}.
 */
public final class Stalls {

    private Stalls() {}

    /**
     * A stretch of seconds.
     *
     * @param kind {@code dip} or {@code excursion}
     * @param startIndex the first second, as an index into the series
     * @param endIndex the index after the last second
     * @param extreme the lowest value of a dip, or the highest of an excursion
     * @param reference the typical rate a dip is judged against, or the limit an excursion exceeds
     */
    public record Interval(String kind, int startIndex, int endIndex, double extreme, double reference) {

        /**
         * Returns the length of the stretch.
         *
         * @return the seconds
         */
        public int seconds() {
            return endIndex - startIndex;
        }
    }

    /**
     * Finds the dips in a series of completions per second.
     *
     * @param perSecond the completions of each second
     * @param from the first second to judge
     * @param to the index after the last second to judge
     * @param fraction a second below this share of the typical rate counts as low
     * @param minSeconds the consecutive low seconds that make a dip
     * @return the dips in order; none when the typical rate is zero
     */
    public static List<Interval> dips(double[] perSecond, int from, int to, double fraction, int minSeconds) {
        List<Interval> found = new ArrayList<>();
        if (to <= from) {
            return found;
        }
        double typical = Quantiles.of(java.util.Arrays.copyOfRange(perSecond, from, to), 0.75);
        if (!(typical > 0)) {
            return found;
        }
        double bar = fraction * typical;
        int runStart = -1;
        double lowest = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            boolean low = i < to && perSecond[i] < bar;
            if (low) {
                if (runStart < 0) {
                    runStart = i;
                    lowest = Double.MAX_VALUE;
                }
                lowest = Math.min(lowest, perSecond[i]);
            } else if (runStart >= 0) {
                if (i - runStart >= minSeconds) {
                    found.add(new Interval("dip", runStart, i, lowest, typical));
                }
                runStart = -1;
            }
        }
        return found;
    }

    /**
     * Finds the excursions in a series of per-second 95th percentiles.
     *
     * @param p95PerSecond the 95th percentile of each second in milliseconds; {@code NaN} for a second without data
     * @param from the first second to judge
     * @param to the index after the last second to judge
     * @param limitMs a second above this many milliseconds counts as slow
     * @param minSeconds the consecutive slow seconds that make an excursion
     * @return the excursions in order
     */
    public static List<Interval> excursions(double[] p95PerSecond, int from, int to, double limitMs, int minSeconds) {
        List<Interval> found = new ArrayList<>();
        int runStart = -1;
        double highest = 0;
        for (int i = from; i <= to; i++) {
            boolean slow = i < to && !Double.isNaN(p95PerSecond[i]) && p95PerSecond[i] > limitMs;
            if (slow) {
                if (runStart < 0) {
                    runStart = i;
                    highest = 0;
                }
                highest = Math.max(highest, p95PerSecond[i]);
            } else if (runStart >= 0) {
                if (i - runStart >= minSeconds) {
                    found.add(new Interval("excursion", runStart, i, highest, limitMs));
                }
                runStart = -1;
            }
        }
        return found;
    }

    /**
     * Returns the least-squares slope of a series.
     *
     * @param values the values, one per second
     * @param from the first index
     * @param to the index after the last
     * @return the change per second; zero for fewer than two values
     */
    public static double slope(double[] values, int from, int to) {
        int n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (int i = from; i < to; i++) {
            if (Double.isNaN(values[i])) {
                continue;
            }
            n++;
            double x = i - from;
            sx += x;
            sy += values[i];
            sxx += x * x;
            sxy += x * values[i];
        }
        if (n < 2) {
            return 0;
        }
        double denominator = n * sxx - sx * sx;
        return denominator == 0 ? 0 : (n * sxy - sx * sy) / denominator;
    }
}

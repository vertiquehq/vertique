// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.metrics;

import java.util.Arrays;

/** Order statistics by the nearest-rank rule, the rule the database queries use as well. */
public final class Quantiles {

    private Quantiles() {}

    /**
     * Returns a quantile of unsorted values.
     *
     * @param values the values; not modified
     * @param p the quantile, from 0 to 1
     * @return the smallest value such that at least {@code p} of the values are less than or equal to it, or
     *     {@code NaN} for no values
     */
    public static double of(double[] values, double p) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return ofSorted(sorted, p);
    }

    /**
     * Returns a quantile of sorted values.
     *
     * @param sorted the values in ascending order
     * @param p the quantile, from 0 to 1
     * @return the nearest-rank quantile, or {@code NaN} for no values
     */
    public static double ofSorted(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }

    /**
     * Returns the median of values.
     *
     * @param values the values; not modified
     * @return the nearest-rank median, or {@code NaN} for no values
     */
    public static double median(double[] values) {
        return of(values, 0.5);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.eval.Gates.Result;
import dev.vertique.examples.workflow.load.harness.eval.Gates.Verdict;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Combines the runs of one point into the point's verdict on a gate.
 *
 * <p>The rule is fixed in advance. Only valid runs count. A point with fewer than the minimum number of valid runs is
 * {@code INCONCLUSIVE}. Otherwise the point passes when every valid run passes, fails when every valid run fails, and
 * is {@code INCONCLUSIVE} when the valid runs disagree or any of them is inconclusive. A point whose set is diagnostic
 * carries no verdict.
 */
public final class PointAggregator {

    private PointAggregator() {}

    /**
     * One run's verdict on a gate.
     *
     * @param valid whether the run is valid
     * @param verdict the run's verdict on the gate
     */
    public record RunVerdict(boolean valid, Verdict verdict) {}

    /**
     * Combines runs.
     *
     * @param gate the name of the gate
     * @param runs the runs of the point, valid or not
     * @param diagnostic whether the set is diagnostic
     * @param limits the thresholds
     * @return the point's verdict
     */
    public static Result aggregate(String gate, List<RunVerdict> runs, boolean diagnostic, Thresholds limits) {
        if (diagnostic) {
            return new Result(gate, Verdict.NOT_APPLICABLE, "diagnostic set: no gate verdict");
        }
        List<Verdict> valid = new ArrayList<>();
        for (RunVerdict run : runs) {
            if (run.valid()) {
                valid.add(run.verdict());
            }
        }
        if (valid.size() < limits.minValidRuns()) {
            return new Result(
                    gate,
                    Verdict.INCONCLUSIVE,
                    valid.size() + " valid runs of " + runs.size() + ", " + limits.minValidRuns() + " needed");
        }
        if (valid.stream().allMatch(v -> v == Verdict.NOT_APPLICABLE)) {
            return new Result(gate, Verdict.NOT_APPLICABLE, "the gate did not apply to the runs");
        }
        if (valid.stream().allMatch(v -> v == Verdict.PASS)) {
            return new Result(gate, Verdict.PASS, "all " + valid.size() + " valid runs pass");
        }
        if (valid.stream().allMatch(v -> v == Verdict.FAIL)) {
            return new Result(gate, Verdict.FAIL, "all " + valid.size() + " valid runs fail");
        }
        return new Result(gate, Verdict.INCONCLUSIVE, "the valid runs disagree or are inconclusive: " + valid);
    }

    /**
     * Combines the figures of the valid runs of a point into one value.
     *
     * @param values the figure of each valid run
     * @return the median of the values; {@code NaN} for none
     */
    public static double combine(Map<String, Double> values) {
        return dev.vertique.examples.workflow.load.harness.metrics.Quantiles.median(
                values.values().stream().mapToDouble(Double::doubleValue).toArray());
    }
}

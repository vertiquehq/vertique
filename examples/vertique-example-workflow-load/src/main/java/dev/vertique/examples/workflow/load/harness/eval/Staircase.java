// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a bracket off a staircase: the highest offered rate that was carried before the first step that was not, and
 * that first step.
 *
 * <p>A step is carried when its completion rate reaches {@code completionRateBar} and the slope of its due depth stays
 * within {@code depthSlopeFraction} of the offered starts per second. A step whose depth could not be read for enough of
 * its seconds has an unresolved slope and is not carried. The bracket stops at the first step that is not carried,
 * because the backlog an overloaded step leaves contaminates the steps after it; a carried step after one that was not
 * is reported and not used.
 *
 * <p>This is an observation of one measured stretch per step, not a capacity. When every step is carried the result is a
 * lower bound: at least the highest rate tested. Independent fixed-rate runs at and around the boundary have to confirm
 * it before any rate is derived from it.
 */
public final class Staircase {

    private Staircase() {}

    /**
     * One step as measured.
     *
     * @param label the step
     * @param offeredPerSecond the starts offered per second
     * @param offered the starts scheduled in the measured part of the step
     * @param completed the instances completed in the same part
     * @param measuredSeconds the length of the measured part
     * @param depthSlopePerSecond the least-squares slope of the due depth over the measured part
     * @param depthReadings the seconds of the measured part that have a depth reading
     * @param refused the starts refused during the step
     */
    public record Step(
            String label,
            double offeredPerSecond,
            long offered,
            long completed,
            double measuredSeconds,
            double depthSlopePerSecond,
            int depthReadings,
            long refused) {}

    /**
     * A step with its judgement.
     *
     * @param step the measurement
     * @param completionRate completed over offered
     * @param slopeLimit the largest depth slope that still counts as flat
     * @param slopeResolved whether enough seconds have a depth reading for the slope to mean anything
     * @param carried whether the step meets both conditions
     */
    public record Judged(Step step, double completionRate, double slopeLimit, boolean slopeResolved, boolean carried) {}

    /**
     * The result of a staircase.
     *
     * @param steps every step with its judgement
     * @param carriedLowerBoundPerSecond the highest carried rate before the first step that is not carried; {@code NaN}
     *     when the first step is not carried
     * @param firstNotCarriedPerSecond the rate of the first step that is not carried; {@code NaN} when all are carried
     * @param highestCarriedPerSecond the highest carried rate of any step; {@code NaN} when none is carried
     * @param carriedAfterFailure whether a carried step follows one that was not
     */
    public record Result(
            List<Judged> steps,
            double carriedLowerBoundPerSecond,
            double firstNotCarriedPerSecond,
            double highestCarriedPerSecond,
            boolean carriedAfterFailure) {

        /**
         * Returns the kind of the result.
         *
         * @return {@code bracket} when a carried rate and a first rate not carried were found, {@code atLeast} when every
         *     step was carried, {@code below} when the first step was not carried
         */
        public String kind() {
            if (Double.isNaN(carriedLowerBoundPerSecond)) {
                return "below";
            }
            return Double.isNaN(firstNotCarriedPerSecond) ? "atLeast" : "bracket";
        }

        /**
         * Renders the result.
         *
         * @return the result as JSON
         */
        public JsonObject toJson() {
            JsonArray array = new JsonArray();
            for (Judged j : steps) {
                array.add(new JsonObject()
                        .put("step", j.step().label())
                        .put("offeredPerSecond", j.step().offeredPerSecond())
                        .put("offered", j.step().offered())
                        .put("completed", j.step().completed())
                        .put("measuredSeconds", j.step().measuredSeconds())
                        .put("completionRate", j.completionRate())
                        .put("depthSlopePerSecond", j.step().depthSlopePerSecond())
                        .put("depthReadings", j.step().depthReadings())
                        .put("slopeResolved", j.slopeResolved())
                        .put("slopeLimit", j.slopeLimit())
                        .put("refused", j.step().refused())
                        .put("carried", j.carried()));
            }
            return new JsonObject()
                    .put("steps", array)
                    .put("kind", kind())
                    .put(
                            "carriedLowerBoundStartsPerSecond",
                            Double.isNaN(carriedLowerBoundPerSecond) ? null : carriedLowerBoundPerSecond)
                    .put(
                            "firstNotCarriedStartsPerSecond",
                            Double.isNaN(firstNotCarriedPerSecond) ? null : firstNotCarriedPerSecond)
                    .put(
                            "highestCarriedStartsPerSecond",
                            Double.isNaN(highestCarriedPerSecond) ? null : highestCarriedPerSecond)
                    .put("carriedAfterFailure", carriedAfterFailure)
                    .put(
                            "description",
                            "an observation of the measured part of each step, not a capacity: the boundary lies in the bracket"
                                    + " (atLeast: no upper bound was found; below: the lowest tested rate was not carried)."
                                    + " Confirm it with independent fixed-rate runs before deriving any rate from it.");
        }
    }

    /**
     * Judges the steps of a staircase.
     *
     * @param steps the steps in order
     * @param limits the thresholds
     * @return the judgement
     */
    public static Result analyze(List<Step> steps, Thresholds limits) {
        List<Judged> judged = new ArrayList<>();
        double lower = Double.NaN;
        double firstNot = Double.NaN;
        double highest = Double.NaN;
        boolean failed = false;
        boolean after = false;
        for (Step step : steps) {
            double rate = step.offered() == 0 ? 0 : (double) step.completed() / step.offered();
            double slopeLimit = limits.depthSlopeFraction() * step.offeredPerSecond();
            boolean resolved = step.depthReadings() >= 2
                    && step.depthReadings() >= limits.minDepthReadingFraction() * step.measuredSeconds();
            boolean carried = step.offered() > 0
                    && rate >= limits.completionRateBar()
                    && resolved
                    && step.depthSlopePerSecond() <= slopeLimit;
            judged.add(new Judged(step, rate, slopeLimit, resolved, carried));
            if (carried) {
                highest = Double.isNaN(highest) ? step.offeredPerSecond() : Math.max(highest, step.offeredPerSecond());
                if (failed) {
                    after = true;
                } else {
                    lower = step.offeredPerSecond();
                }
            } else if (!failed) {
                failed = true;
                firstNot = step.offeredPerSecond();
            }
        }
        return new Result(List.copyOf(judged), lower, firstNot, highest, after);
    }
}

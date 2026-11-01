// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.metrics.Quantiles;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The gates a point is judged by: overload, scaling and claim latency, and the notification lock.
 *
 * <p>Each gate gives {@code PASS}, {@code FAIL} or {@code INCONCLUSIVE}; a gate that does not apply to a run gives
 * {@code NOT_APPLICABLE}. A gate is inconclusive rather than failed whenever the evidence cannot show a failure:
 * the latency measures are upper bounds, so exceeding the limit leaves the claim latency unresolved, and a point
 * with fewer valid runs than the minimum is never judged.
 */
public final class Gates {

    private Gates() {}

    /** The verdict of a gate. */
    public enum Verdict {
        /** The evidence meets the gate. */
        PASS,
        /** The evidence shows the gate is not met. */
        FAIL,
        /** The evidence cannot show either. */
        INCONCLUSIVE,
        /** The gate does not apply to this run. */
        NOT_APPLICABLE
    }

    /**
     * A gate's verdict with the reason.
     *
     * @param gate the name of the gate
     * @param verdict the verdict
     * @param detail what the verdict rests on
     */
    public record Result(String gate, Verdict verdict, String detail) {

        /**
         * Renders the result.
         *
         * @return the result as JSON
         */
        public JsonObject toJson() {
            return new JsonObject()
                    .put("verdict", verdict.name().toLowerCase(Locale.ROOT))
                    .put("detail", detail);
        }
    }

    /**
     * What the claim-latency gate reads from one run.
     *
     * @param recordedToStartP95Ms the 95th percentile of recorded to segment transaction start
     * @param recordedQualifying the commands behind it
     * @param recordedExpected the starts and signals the cohort should hold: those that did not complete cleanly
     *     (unsettled, parked, dead, failed, retried, deferred) and those never created are counted in
     * @param timerLatenessP95Ms the 95th percentile of timer transaction-start lateness
     * @param timersClean the timers behind it
     * @param timersExpected the timers the cohort should hold, those left out of the quantile and those never created
     *     included
     */
    public record ClaimLatency(
            double recordedToStartP95Ms,
            long recordedQualifying,
            long recordedExpected,
            double timerLatenessP95Ms,
            long timersClean,
            long timersExpected) {}

    /**
     * Judges claim latency.
     *
     * <p>Both measures stop at the start of the segment's transaction, which is after the claim, so they bound the
     * claim latency from above. A bound within the limit supports the gate; a bound above it proves nothing, and
     * neither does a quantile that leaves out rows that never finished.
     *
     * @param in the figures
     * @param limits the thresholds
     * @return {@code PASS} or {@code INCONCLUSIVE}; never {@code FAIL}
     */
    public static Result claimLatency(ClaimLatency in, Thresholds limits) {
        List<String> reasons = new ArrayList<>();
        if (in.recordedQualifying() == 0 || Double.isNaN(in.recordedToStartP95Ms())) {
            reasons.add("no qualifying commands");
        } else if (in.recordedToStartP95Ms() >= limits.g3RecordedToStartP95Ms()) {
            reasons.add("recorded to segment transaction start p95 " + fmt(in.recordedToStartP95Ms())
                    + " ms is not below " + fmt(limits.g3RecordedToStartP95Ms())
                    + " ms, an upper bound, so claim latency stays unresolved");
        }
        if (in.timersClean() == 0 || Double.isNaN(in.timerLatenessP95Ms())) {
            reasons.add("no clean timers");
        } else if (in.timerLatenessP95Ms() >= limits.g3TimerLatenessP95Ms()) {
            reasons.add("timer transaction-start lateness p95 " + fmt(in.timerLatenessP95Ms()) + " ms is not below "
                    + fmt(limits.g3TimerLatenessP95Ms()) + " ms, an upper bound, so timer lateness stays unresolved");
        }
        long commandsOut = in.recordedExpected() - in.recordedQualifying();
        long timersOut = in.timersExpected() - in.timersClean();
        if (excluded(commandsOut, in.recordedExpected(), limits) || excluded(timersOut, in.timersExpected(), limits)) {
            reasons.add(commandsOut + " of " + in.recordedExpected() + " commands and " + timersOut + " of "
                    + in.timersExpected() + " timers of the cohort are outside the quantiles, more than "
                    + fmt(limits.maxExcludedFraction() * 100) + "%, rows never created included");
        }
        if (reasons.isEmpty()) {
            return new Result(
                    "claim-latency",
                    Verdict.PASS,
                    "upper bounds p95 " + fmt(in.recordedToStartP95Ms()) + " ms and timers p95 "
                            + fmt(in.timerLatenessP95Ms()) + " ms are within the limits");
        }
        return new Result("claim-latency", Verdict.INCONCLUSIVE, String.join("; ", reasons));
    }

    /**
     * What the overload gate reads from one run.
     *
     * @param highMark the due commands at which intake is refused
     * @param lowMark the due commands at which intake is admitted again
     * @param sampleIntervalMs the sample interval of the intake limit
     * @param acceptedStartsPerSecondOver the starts accepted per second while the load was above capacity
     * @param refusedOver the starts refused while the load was above capacity
     * @param peakDepthOver the greatest due depth read while the load was above capacity
     * @param depthAfterDrop the due depth read each second from the moment the load fell; {@code NaN} for a second the
     *     database did not answer
     * @param peakReadingFraction the share of the seconds above capacity that have a depth reading
     */
    public record Overload(
            int highMark,
            int lowMark,
            long sampleIntervalMs,
            double acceptedStartsPerSecondOver,
            long refusedOver,
            double peakDepthOver,
            double[] depthAfterDrop,
            double peakReadingFraction) {}

    /**
     * Judges overload behavior: peak depth within the high mark plus the stated overshoot while above capacity, and
     * a return to the low mark within the recovery time after the load fell.
     *
     * @param in the figures
     * @param limits the thresholds
     * @return {@code INCONCLUSIVE} when refusal and high-mark pressure were not exercised, {@code FAIL} when the
     *     peak or the recovery misses its limit, otherwise {@code PASS}
     */
    public static Result overload(Overload in, Thresholds limits) {
        double overshoot = 5.0 * in.sampleIntervalMs() / 1000.0 * in.acceptedStartsPerSecondOver();
        double bound = in.highMark() + overshoot;
        if (in.peakDepthOver() > bound) {
            return new Result(
                    "overload",
                    Verdict.FAIL,
                    "peak due depth " + fmt(in.peakDepthOver()) + " exceeds the high mark " + in.highMark()
                            + " plus the overshoot " + fmt(overshoot));
        }
        boolean enoughPeakReadings = in.peakReadingFraction() >= limits.minDepthReadingFraction();
        if (in.refusedOver() == 0 || in.peakDepthOver() < in.highMark() || !enoughPeakReadings) {
            return new Result(
                    "overload",
                    Verdict.INCONCLUSIVE,
                    in.refusedOver() == 0 || in.peakDepthOver() < in.highMark() && enoughPeakReadings
                            ? "refusal and high-mark pressure were not exercised: " + in.refusedOver()
                                    + " refusals, peak depth " + fmt(in.peakDepthOver()) + " against high mark "
                                    + in.highMark()
                            : "only " + fmt(in.peakReadingFraction() * 100)
                                    + "% of the seconds above capacity have a depth" + " reading");
        }
        int limit = limits.g1RecoverySeconds();
        double[] after = in.depthAfterDrop();
        int checked = Math.min(after.length, limit + 1);
        int finite = 0;
        Integer back = null;
        for (int i = 0; i < after.length; i++) {
            double d = after[i];
            if (!Double.isNaN(d)) {
                if (i < checked) {
                    finite++;
                }
                if (back == null && d <= in.lowMark()) {
                    back = i;
                }
            }
        }
        boolean readingsAdequate = checked > 0 && (double) finite / checked >= limits.minDepthReadingFraction();
        if (back == null) {
            if (after.length < limit) {
                return new Result(
                        "overload",
                        Verdict.INCONCLUSIVE,
                        "the run ended " + after.length + " s after the drop, before the " + limit + " s limit");
            }
            return readingsAdequate
                    ? new Result(
                            "overload",
                            Verdict.FAIL,
                            "depth did not return to the low mark " + in.lowMark() + " within " + limit + " s")
                    : new Result(
                            "overload",
                            Verdict.INCONCLUSIVE,
                            "depth never read at or below the low mark, and only " + finite + " of " + checked
                                    + " seconds after the drop have a reading");
        }
        if (back > limit) {
            return readingsAdequate
                    ? new Result(
                            "overload",
                            Verdict.FAIL,
                            "depth returned to the low mark after " + back + " s, over the limit of " + limit + " s")
                    : new Result(
                            "overload",
                            Verdict.INCONCLUSIVE,
                            "the first reading at the low mark came after " + back + " s but " + (checked - finite)
                                    + " of the first " + checked + " seconds have no reading");
        }
        return new Result(
                "overload",
                Verdict.PASS,
                "peak " + fmt(in.peakDepthOver()) + " within " + fmt(bound) + "; back to the low mark after " + back
                        + " s");
    }

    /**
     * What the notification-lock gate reads from one run.
     *
     * @param watched whether the lock watch ran
     * @param samples the watch samples that succeeded
     * @param expectedSamples the samples the watch should have taken over the run
     * @param queryErrors the watch samples that failed
     * @param nodeWaiterObservations the waiter observations attributed to a node
     * @param otherWaiterObservations the waiter observations of any other application
     * @param byApplication the waiter observations by application name
     */
    public record Lock(
            boolean watched,
            long samples,
            long expectedSamples,
            long queryErrors,
            long nodeWaiterObservations,
            long otherWaiterObservations,
            Map<String, Long> byApplication) {}

    /**
     * Judges the notification lock: no session of a node waits on object {@code 1262:0}.
     *
     * @param in the figures
     * @return {@code NOT_APPLICABLE} without a watch, {@code FAIL} when a node waited, otherwise {@code PASS} with the
     *     number of samples it rests on
     */
    public static Result notificationLock(Lock in, Thresholds limits) {
        if (!in.watched()) {
            return new Result("notification-lock", Verdict.NOT_APPLICABLE, "the lock watch was off");
        }
        if (in.nodeWaiterObservations() > 0) {
            return new Result(
                    "notification-lock",
                    Verdict.FAIL,
                    in.nodeWaiterObservations() + " waiter observations on 1262:0 by node sessions: "
                            + new TreeMap<>(in.byApplication()));
        }
        long attempts = in.samples() + in.queryErrors();
        double coverage = in.expectedSamples() > 0 ? (double) in.samples() / in.expectedSamples() : 0;
        double errors = attempts > 0 ? (double) in.queryErrors() / attempts : 1;
        if (in.samples() == 0
                || coverage < limits.lockWatchMinCoverage()
                || errors > limits.lockWatchMaxErrorFraction()) {
            return new Result(
                    "notification-lock",
                    Verdict.INCONCLUSIVE,
                    "no waiter seen, but the watch is not enough to say none waited: " + in.samples() + " of about "
                            + in.expectedSamples() + " samples succeeded and " + in.queryErrors() + " failed");
        }
        return new Result(
                "notification-lock",
                Verdict.PASS,
                "no node session waited on 1262:0 in " + in.samples() + " samples (" + fmt(coverage * 100)
                        + "% of those expected); a shorter wait can be missed");
    }

    /**
     * The runs of one node count at one offered load.
     *
     * @param nodes the node count
     * @param offeredPerSecond the offered starts per second
     * @param validCompletedPerSecond the completed instances per second of each valid run
     */
    public record Scaling(int nodes, double offeredPerSecond, List<Double> validCompletedPerSecond) {}

    /**
     * Judges scaling at one offered rate: the largest node count completes at least a share of what the best smaller
     * node count completes.
     *
     * <p>A pass holds at that offered rate only. It is not a capacity claim, because demand can cap the faster counts.
     *
     * @param points one entry per node count, all at the same offered rate
     * @param limits the thresholds
     * @return the verdict
     */
    public static Result scaling(List<Scaling> points, Thresholds limits) {
        if (points.size() < 2) {
            return new Result("scaling", Verdict.INCONCLUSIVE, "fewer than two node counts");
        }
        List<Scaling> sorted = points.stream()
                .sorted((a, b) -> Integer.compare(a.nodes(), b.nodes()))
                .toList();
        Scaling largest = sorted.get(sorted.size() - 1);
        double rate = largest.offeredPerSecond();
        if (!(rate > 0) || Double.isInfinite(rate)) {
            return new Result("scaling", Verdict.INCONCLUSIVE, "the offered rate is not a positive number");
        }
        for (Scaling p : sorted) {
            if (!(Math.abs(p.offeredPerSecond() - rate) <= 1e-9)) {
                return new Result("scaling", Verdict.INCONCLUSIVE, "the node counts were not offered the same rate");
            }
        }
        if (largest.validCompletedPerSecond().size() < limits.minValidRuns()) {
            return new Result(
                    "scaling",
                    Verdict.INCONCLUSIVE,
                    largest.nodes() + " nodes have "
                            + largest.validCompletedPerSecond().size() + " valid runs, " + limits.minValidRuns()
                            + " needed");
        }
        double best = Double.NaN;
        int bestNodes = 0;
        boolean missing = false;
        for (Scaling p : sorted.subList(0, sorted.size() - 1)) {
            if (p.validCompletedPerSecond().size() < limits.minValidRuns()) {
                missing = true;
                continue;
            }
            double median = Quantiles.median(p.validCompletedPerSecond().stream()
                    .mapToDouble(Double::doubleValue)
                    .toArray());
            if (Double.isNaN(best) || median > best) {
                best = median;
                bestNodes = p.nodes();
            }
        }
        if (Double.isNaN(best)) {
            return new Result("scaling", Verdict.INCONCLUSIVE, "no smaller node count has enough valid runs");
        }
        double top = Quantiles.median(largest.validCompletedPerSecond().stream()
                .mapToDouble(Double::doubleValue)
                .toArray());
        if (!(best > 0) || Double.isInfinite(best) || Double.isNaN(top) || Double.isInfinite(top)) {
            return new Result(
                    "scaling",
                    Verdict.INCONCLUSIVE,
                    "the comparison has no finite positive throughput to divide by: best smaller count " + fmt(best)
                            + "/s, " + largest.nodes() + " nodes " + fmt(top) + "/s");
        }
        double ratio = top / best;
        String detail = largest.nodes() + " nodes complete " + fmt(top) + "/s against " + fmt(best) + "/s at "
                + bestNodes + " nodes: " + fmt(ratio * 100) + "% of the best smaller count, at " + fmt(rate)
                + " starts/s offered";
        if (ratio < limits.g2Ratio()) {
            return new Result("scaling", Verdict.FAIL, detail);
        }
        if (missing) {
            return new Result(
                    "scaling",
                    Verdict.INCONCLUSIVE,
                    detail + "; a smaller count without enough valid runs could be better");
        }
        return new Result("scaling", Verdict.PASS, detail + " (holds at this rate only)");
    }

    private static boolean excluded(long out, long total, Thresholds limits) {
        return out > 0 && total > 0 && (double) out / total > limits.maxExcludedFraction();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

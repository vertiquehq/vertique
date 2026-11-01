// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.eval.Gates.Verdict;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Verifies the gates: when each passes, fails or stays inconclusive. */
class GatesTest {

    private final Thresholds limits = Thresholds.defaults();

    @Nested
    @DisplayName("claim latency")
    class ClaimLatency {

        @Test
        @DisplayName("bounds inside the limits pass")
        void passes() {
            Gates.Result r = Gates.claimLatency(new Gates.ClaimLatency(22, 900, 900, 13, 451, 451), limits);

            assertEquals(Verdict.PASS, r.verdict());
        }

        @Test
        @DisplayName("a bound above the limit is inconclusive, never a failure")
        void aboveLimitIsUnresolved() {
            Gates.Result r = Gates.claimLatency(new Gates.ClaimLatency(374, 900, 900, 13, 451, 451), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
            assertTrue(r.detail().contains("upper bound"), r.detail());
        }

        @Test
        @DisplayName("timers over the limit are inconclusive for the same reason")
        void timersAboveLimit() {
            Gates.Result r = Gates.claimLatency(new Gates.ClaimLatency(22, 900, 900, 1500, 451, 451), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
        }

        @Test
        @DisplayName("rows left out of the quantiles beyond the share make a pass impossible")
        void survivors() {
            Gates.Result r = Gates.claimLatency(new Gates.ClaimLatency(22, 900, 950, 13, 451, 451), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
            assertTrue(r.detail().contains("outside the quantiles"), r.detail());
        }

        @Test
        @DisplayName("a small share left out is tolerated")
        void smallShareTolerated() {
            Gates.Result r = Gates.claimLatency(new Gates.ClaimLatency(22, 995, 1000, 13, 451, 451), limits);

            assertEquals(Verdict.PASS, r.verdict());
        }

        @Test
        @DisplayName("no qualifying commands is inconclusive")
        void nothingToJudge() {
            Gates.Result r = Gates.claimLatency(new Gates.ClaimLatency(Double.NaN, 0, 0, Double.NaN, 0, 0), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
        }
    }

    @Nested
    @DisplayName("overload")
    class Overload {

        private double[] back(int secondsAboveLow, int total) {
            double[] depth = new double[total];
            Arrays.fill(depth, 100);
            for (int i = 0; i < total; i++) {
                depth[i] = i < secondsAboveLow ? 8000 : 4000;
            }
            return depth;
        }

        @Test
        @DisplayName("without refusals or the high mark reached, the gate is inconclusive")
        void notExercised() {
            Gates.Result r =
                    Gates.overload(new Gates.Overload(10_000, 5_000, 1_000, 300, 0, 9_000, back(10, 90), 1.0), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
            assertTrue(r.detail().contains("not exercised"), r.detail());
        }

        @Test
        @DisplayName("a peak within the high mark plus the overshoot and a return inside the limit pass")
        void passes() {
            // overshoot = 5 x 1 s x 300/s = 1500
            Gates.Result r = Gates.overload(
                    new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 11_400, back(20, 90), 1.0), limits);

            assertEquals(Verdict.PASS, r.verdict());
        }

        @Test
        @DisplayName("a peak above the bound fails")
        void peakTooHigh() {
            Gates.Result r = Gates.overload(
                    new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 11_600, back(20, 90), 1.0), limits);

            assertEquals(Verdict.FAIL, r.verdict());
        }

        @Test
        @DisplayName("a return later than the limit fails")
        void recoveryTooSlow() {
            Gates.Result r = Gates.overload(
                    new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 10_500, back(75, 120), 1.0), limits);

            assertEquals(Verdict.FAIL, r.verdict());
        }

        @Test
        @DisplayName(
                "never returning inside a run that covered the limit fails; inside a shorter run it is inconclusive")
        void neverReturns() {
            double[] stuck = new double[120];
            Arrays.fill(stuck, 8000);
            assertEquals(
                    Verdict.FAIL,
                    Gates.overload(new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 10_500, stuck, 1.0), limits)
                            .verdict());
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.overload(
                                    new Gates.Overload(
                                            10_000, 5_000, 1_000, 300, 2000, 10_500, Arrays.copyOf(stuck, 30), 1.0),
                                    limits)
                            .verdict());
        }

        @Test
        @DisplayName(
                "a missing reading is not a depth above the low mark: with too few readings the recovery is inconclusive")
        void missingReadingsAreNotAFailure() {
            double[] depth = new double[120];
            Arrays.fill(depth, Double.NaN);
            for (int i = 0; i < 120; i += 10) {
                depth[i] = 8000;
            }

            Gates.Result r =
                    Gates.overload(new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 10_500, depth, 1.0), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict(), r.detail());
            assertTrue(r.detail().contains("have a reading"), r.detail());
        }

        @Test
        @DisplayName("a pressure phase without depth readings cannot show that the high mark was not reached")
        void peakWithoutReadings() {
            Gates.Result r = Gates.overload(
                    new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 3_000, back(5, 90), 0.2), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
            assertTrue(r.detail().contains("depth reading"), r.detail());
        }

        @Test
        @DisplayName("seconds the database did not answer do not count as a return")
        void unansweredSeconds() {
            double[] depth = new double[90];
            Arrays.fill(depth, Double.NaN);
            Arrays.fill(depth, 30, 90, 4000);

            Gates.Result r =
                    Gates.overload(new Gates.Overload(10_000, 5_000, 1_000, 300, 2000, 10_500, depth, 1.0), limits);

            assertEquals(Verdict.PASS, r.verdict());
            assertTrue(r.detail().contains("after 30 s"), r.detail());
        }
    }

    @Nested
    @DisplayName("notification lock")
    class Lock {

        @Test
        @DisplayName("without a watch the gate does not apply")
        void notWatched() {
            assertEquals(
                    Verdict.NOT_APPLICABLE,
                    Gates.notificationLock(new Gates.Lock(false, 0, 0, 0, 0, 0, Map.of()), limits)
                            .verdict());
        }

        @Test
        @DisplayName("a waiter from a node fails the gate and is named")
        void nodeWaited() {
            Gates.Result r = Gates.notificationLock(
                    new Gates.Lock(true, 1000, 1000, 0, 12, 0, Map.of("load-node-3", 12L)), limits);

            assertEquals(Verdict.FAIL, r.verdict());
            assertTrue(r.detail().contains("load-node-3"), r.detail());
        }

        @Test
        @DisplayName("no waiter in the samples taken passes with the sample count")
        void nobodyWaited() {
            Gates.Result r = Gates.notificationLock(new Gates.Lock(true, 1000, 1000, 0, 0, 0, Map.of()), limits);

            assertEquals(Verdict.PASS, r.verdict());
            assertTrue(r.detail().contains("1000 samples"), r.detail());
        }

        @Test
        @DisplayName("one good sample followed by failures is not evidence that nobody waited")
        void failingWatchIsInconclusive() {
            Gates.Result r = Gates.notificationLock(new Gates.Lock(true, 1, 2000, 1999, 0, 0, Map.of()), limits);

            assertEquals(Verdict.INCONCLUSIVE, r.verdict());
            assertTrue(r.detail().contains("1 of about 2000"), r.detail());
        }

        @Test
        @DisplayName("a watch that covered too little of the run is inconclusive")
        void sparseWatchIsInconclusive() {
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.notificationLock(new Gates.Lock(true, 1000, 2000, 0, 0, 0, Map.of()), limits)
                            .verdict());
        }

        @Test
        @DisplayName("waiters seen are a failure even when the watch was patchy")
        void waitersSeenFail() {
            assertEquals(
                    Verdict.FAIL,
                    Gates.notificationLock(new Gates.Lock(true, 10, 2000, 500, 3, 0, Map.of("load-node-1", 3L)), limits)
                            .verdict());
        }

        @Test
        @DisplayName("waiters of other applications alone do not fail the gate")
        void othersOnly() {
            Gates.Result r = Gates.notificationLock(
                    new Gates.Lock(true, 1000, 1000, 0, 0, 4, Map.of("load-injector", 4L)), limits);

            assertEquals(Verdict.PASS, r.verdict());
        }
    }

    @Nested
    @DisplayName("scaling")
    class Scaling {

        private Gates.Scaling point(int nodes, double rate, Double... runs) {
            return new Gates.Scaling(nodes, rate, List.of(runs));
        }

        @Test
        @DisplayName("sixteen nodes at 90% of the best smaller count pass, at this rate only")
        void passes() {
            Gates.Result r = Gates.scaling(
                    List.of(
                            point(1, 600, 590.0, 600.0, 595.0),
                            point(4, 600, 600.0, 599.0),
                            point(16, 600, 560.0, 548.0)),
                    limits);

            assertEquals(Verdict.PASS, r.verdict(), r.detail());
            assertTrue(r.detail().contains("this rate only"), r.detail());
        }

        @Test
        @DisplayName("below 90% fails")
        void fails() {
            Gates.Result r = Gates.scaling(List.of(point(4, 600, 600.0, 599.0), point(16, 600, 400.0, 420.0)), limits);

            assertEquals(Verdict.FAIL, r.verdict());
        }

        @Test
        @DisplayName("a point with fewer than two valid runs is never judged")
        void tooFewRuns() {
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(List.of(point(4, 600, 600.0, 599.0), point(16, 600, 560.0)), limits)
                            .verdict());
        }

        @Test
        @DisplayName("a pass that leaves out a smaller count without enough runs is inconclusive, a failure stands")
        void missingSmallerCount() {
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(
                                    List.of(
                                            point(1, 600, 650.0),
                                            point(4, 600, 600.0, 599.0),
                                            point(16, 600, 560.0, 548.0)),
                                    limits)
                            .verdict());
            assertEquals(
                    Verdict.FAIL,
                    Gates.scaling(
                                    List.of(
                                            point(1, 600, 650.0),
                                            point(4, 600, 600.0, 599.0),
                                            point(16, 600, 400.0, 420.0)),
                                    limits)
                            .verdict());
        }

        @Test
        @DisplayName("no throughput to divide by is inconclusive, never a pass")
        void zeroThroughput() {
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(List.of(point(4, 600, 0.0, 0.0), point(16, 600, 0.0, 0.0)), limits)
                            .verdict());
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(List.of(point(4, 600, 600.0, 599.0), point(16, 600, Double.NaN, Double.NaN)), limits)
                            .verdict());
            assertEquals(
                    Verdict.FAIL,
                    Gates.scaling(List.of(point(4, 600, 600.0, 599.0), point(16, 600, 0.0, 0.0)), limits)
                            .verdict());
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(
                                    List.of(point(4, Double.NaN, 600.0, 599.0), point(16, Double.NaN, 580.0, 570.0)),
                                    limits)
                            .verdict());
        }

        @Test
        @DisplayName("a run without a throughput does not count toward the two-run minimum")
        void nanRunsDoNotCount() {
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(List.of(point(4, 600, 600.0, 599.0), point(16, 600, 560.0, Double.NaN)), limits)
                            .verdict());
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(List.of(point(4, 600, 600.0, Double.NaN), point(16, 600, 560.0, 548.0)), limits)
                            .verdict());
        }

        @Test
        @DisplayName("different offered rates cannot be compared")
        void differentRates() {
            assertEquals(
                    Verdict.INCONCLUSIVE,
                    Gates.scaling(List.of(point(4, 600, 600.0, 599.0), point(16, 700, 600.0, 600.0)), limits)
                            .verdict());
        }
    }
}

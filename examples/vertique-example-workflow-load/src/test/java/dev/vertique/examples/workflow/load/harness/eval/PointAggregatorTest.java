// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.examples.workflow.load.harness.eval.Gates.Verdict;
import dev.vertique.examples.workflow.load.harness.eval.PointAggregator.RunVerdict;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how the runs of one point combine into the point's verdict. */
class PointAggregatorTest {

    private final Thresholds limits = Thresholds.defaults();

    private Verdict combine(boolean diagnostic, RunVerdict... runs) {
        return PointAggregator.aggregate("gate", List.of(runs), diagnostic, limits)
                .verdict();
    }

    @Test
    @DisplayName("two valid runs that pass make the point pass")
    void allPass() {
        assertEquals(
                Verdict.PASS, combine(false, new RunVerdict(true, Verdict.PASS), new RunVerdict(true, Verdict.PASS)));
    }

    @Test
    @DisplayName("two valid runs that fail make the point fail")
    void allFail() {
        assertEquals(
                Verdict.FAIL, combine(false, new RunVerdict(true, Verdict.FAIL), new RunVerdict(true, Verdict.FAIL)));
    }

    @Test
    @DisplayName("fewer than two valid runs is inconclusive, however the invalid ones came out")
    void tooFewValidRuns() {
        assertEquals(
                Verdict.INCONCLUSIVE,
                combine(
                        false,
                        new RunVerdict(true, Verdict.PASS),
                        new RunVerdict(false, Verdict.PASS),
                        new RunVerdict(false, Verdict.PASS)));
        assertEquals(Verdict.INCONCLUSIVE, combine(false, new RunVerdict(true, Verdict.FAIL)));
        assertEquals(Verdict.INCONCLUSIVE, combine(false));
    }

    @Test
    @DisplayName("runs that disagree, or that are inconclusive, leave the point inconclusive")
    void disagreement() {
        assertEquals(
                Verdict.INCONCLUSIVE,
                combine(false, new RunVerdict(true, Verdict.PASS), new RunVerdict(true, Verdict.FAIL)));
        assertEquals(
                Verdict.INCONCLUSIVE,
                combine(false, new RunVerdict(true, Verdict.PASS), new RunVerdict(true, Verdict.INCONCLUSIVE)));
    }

    @Test
    @DisplayName("a diagnostic set carries no verdict")
    void diagnostic() {
        assertEquals(
                Verdict.NOT_APPLICABLE,
                combine(true, new RunVerdict(true, Verdict.PASS), new RunVerdict(true, Verdict.PASS)));
    }

    @Test
    @DisplayName("a gate that did not apply to the runs does not apply to the point")
    void notApplicable() {
        assertEquals(
                Verdict.NOT_APPLICABLE,
                combine(
                        false,
                        new RunVerdict(true, Verdict.NOT_APPLICABLE),
                        new RunVerdict(true, Verdict.NOT_APPLICABLE)));
    }

    @Test
    @DisplayName("the figures of a point combine by median")
    void median() {
        assertEquals(600.0, PointAggregator.combine(Map.of("a", 590.0, "b", 600.0, "c", 700.0)));
    }
}

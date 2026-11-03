// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how a staircase gives the capacity: the highest rate carried before the first step that is not. */
class StaircaseTest {

    private final Thresholds limits = Thresholds.defaults();

    private static Staircase.Step step(String label, double rate, double completedShare, double slope) {
        long offered = Math.round(rate * 50);
        return new Staircase.Step(label, rate, offered, Math.round(offered * completedShare), 50, slope, 50, 0);
    }

    @Test
    @DisplayName("the result is a bracket: the highest rate carried before the first step that is not, and that step")
    void bracket() {
        Staircase.Result r = Staircase.analyze(
                List.of(
                        step("s1", 100, 1.0, 0),
                        step("s2", 200, 0.995, 0.5),
                        step("s3", 300, 0.9, 12),
                        step("s4", 400, 0.5, 90)),
                limits);

        assertEquals("bracket", r.kind());
        assertEquals(200.0, r.carriedLowerBoundPerSecond());
        assertEquals(300.0, r.firstNotCarriedPerSecond());
        assertEquals(200.0, r.highestCarriedPerSecond());
        assertFalse(r.carriedAfterFailure());
        assertTrue(r.steps().get(1).carried());
        assertFalse(r.steps().get(2).carried());
        assertTrue(r.toJson().getString("description").contains("not a capacity"));
        assertFalse(r.toJson().containsKey("capacityStartsPerSecond"));
    }

    @Test
    @DisplayName("when every step is carried the result is only a lower bound")
    void everyStepCarried() {
        Staircase.Result r = Staircase.analyze(List.of(step("s1", 100, 1.0, 0), step("s2", 200, 1.0, 0)), limits);

        assertEquals("atLeast", r.kind());
        assertEquals(200.0, r.carriedLowerBoundPerSecond());
        assertTrue(Double.isNaN(r.firstNotCarriedPerSecond()));
        assertEquals(null, r.toJson().getValue("firstNotCarriedStartsPerSecond"));
    }

    @Test
    @DisplayName("a step with fewer than two depth readings has no slope and is not carried")
    void unresolvedSlope() {
        Staircase.Step blind = new Staircase.Step("s1", 100, 5000, 5000, 50, 0.0, 1, 0);

        Staircase.Result r = Staircase.analyze(List.of(blind), limits);

        assertFalse(r.steps().get(0).slopeResolved());
        assertFalse(r.steps().get(0).carried());
        assertEquals("below", r.kind());
    }

    @Test
    @DisplayName("a step that completes enough but whose depth grows is not carried")
    void depthSlope() {
        // slope limit is 0.5% of 200/s = 1 per second
        Staircase.Result r = Staircase.analyze(List.of(step("s1", 200, 1.0, 1.5)), limits);

        assertFalse(r.steps().get(0).carried());
        assertTrue(Double.isNaN(r.carriedLowerBoundPerSecond()));
    }

    @Test
    @DisplayName("a carried step after a failed one is reported and not used")
    void carriedAfterFailure() {
        Staircase.Result r = Staircase.analyze(
                List.of(step("s1", 100, 1.0, 0), step("s2", 200, 0.8, 5), step("s3", 300, 1.0, 0)), limits);

        assertEquals(100.0, r.carriedLowerBoundPerSecond());
        assertEquals(300.0, r.highestCarriedPerSecond());
        assertTrue(r.carriedAfterFailure());
    }

    @Test
    @DisplayName("the result renders every step with its figures")
    void json() {
        Staircase.Result r = Staircase.analyze(List.of(step("s1", 100, 1.0, 0)), limits);

        assertEquals(1, r.toJson().getJsonArray("steps").size());
        assertEquals(100.0, r.toJson().getDouble("carriedLowerBoundStartsPerSecond"));
        assertEquals(1.0, r.toJson().getJsonArray("steps").getJsonObject(0).getDouble("completionRate"));
    }
}

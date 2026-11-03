// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the shipped proposal for the calibration matrix loads, builds every point, and states every threshold. */
class ProposedMatrixTest {

    private static final Path FILE = Path.of("scripts", "matrix", "proposed-calibration.json");

    @Test
    @DisplayName("the proposal loads strictly and every point builds as a run specification")
    void loads() throws IOException {
        Matrix matrix = Matrix.load(FILE);

        assertEquals(4, matrix.points().size());
        assertEquals(64, matrix.sha256().length());
        for (Matrix.Point point : matrix.points()) {
            RunSpec spec = matrix.specOf(point.id());
            assertEquals(matrix.sha256(), spec.matrixSha256());
            assertEquals(64, spec.fingerprint().length());
        }
        assertNotEquals(matrix.fingerprintOf("cal-on-4"), matrix.fingerprintOf("cal-off-4"));
        assertNotEquals(matrix.fingerprintOf("cal-on-4"), matrix.fingerprintOf("sim-compare-on-4"));
    }

    @Test
    @DisplayName("the proposal names every threshold, and they equal the defaults the harness ships with")
    void thresholdsAreStatedInFull() throws IOException {
        Matrix matrix = Matrix.load(FILE);

        assertEquals(Thresholds.defaults(), matrix.thresholds());
        Set<String> stated = matrix.source().getJsonObject("thresholds").fieldNames();
        Set<String> all = java.util.Arrays.stream(Thresholds.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(all, stated, "a threshold that the file does not state would be a hidden default");
    }

    @Test
    @DisplayName("the staircase steps are the proposed ones: 20 to 200 starts per second in steps of 20, 60 s each")
    void staircaseShape() throws IOException {
        RunSpec spec = Matrix.load(FILE).specOf("cal-on-4");

        assertEquals(10, spec.load().profile().segmentCount());
        assertEquals(20.0, spec.load().profile().segment(0).ratePerSec());
        assertEquals(200.0, spec.load().profile().segment(9).ratePerSec());
        assertEquals(60.0, spec.load().profile().segment(0).durationSec());
        assertEquals(10.0, spec.load().stepMeasureSkipSec());
    }
}

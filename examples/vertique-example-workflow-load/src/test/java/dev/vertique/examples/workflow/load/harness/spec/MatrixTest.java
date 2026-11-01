// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the frozen matrix: one file for the points and the thresholds, a hash, fingerprints and strict reading. */
class MatrixTest {

    private static JsonObject file() {
        return new JsonObject()
                .put("title", "T")
                .put("thresholds", new JsonObject().put("minValidRuns", 2))
                .put(
                        "points",
                        new JsonArray()
                                .add(new JsonObject()
                                        .put("id", "on-4")
                                        .put("set", "calibration")
                                        .put("replicates", 2)
                                        .put(
                                                "spec",
                                                new JsonObject()
                                                        .put("arm", "ON")
                                                        .put("nodes", 4)
                                                        .put(
                                                                "load",
                                                                new JsonObject()
                                                                        .put("kind", "fixed")
                                                                        .put("ratePerSec", 100))))
                                .add(new JsonObject()
                                        .put("id", "on-16")
                                        .put("set", "calibration")
                                        .put(
                                                "spec",
                                                new JsonObject()
                                                        .put("arm", "ON")
                                                        .put("nodes", 16)
                                                        .put(
                                                                "load",
                                                                new JsonObject()
                                                                        .put("kind", "fixed")
                                                                        .put("ratePerSec", 100)))))
                .put(
                        "order",
                        new JsonArray()
                                .add(new JsonObject().put("point", "on-4").put("replicate", 2)));
    }

    private static Matrix load(Path dir, JsonObject json) throws IOException {
        Path path = dir.resolve("m.json");
        Files.writeString(path, json.encodePrettily());
        return Matrix.load(path);
    }

    @Test
    @DisplayName(
            "a run is built from a point with the matrix's thresholds, the hash of the file, its replicate and attempt")
    void buildsARun(@TempDir Path dir) throws IOException {
        Matrix matrix = load(dir, file());

        JsonObject spec = matrix.specFor("on-4", 2, 3);
        RunSpec run = RunSpec.parse(spec);

        assertEquals("on-4-r2-a3", run.name());
        assertEquals("on-4", run.point());
        assertEquals(2, run.replicate());
        assertEquals(3, run.attempt());
        assertEquals(matrix.sha256(), run.matrixSha256());
        assertEquals(64, matrix.sha256().length());
        assertEquals(2, run.thresholds().minValidRuns());
        assertEquals("calibration", run.set());
    }

    @Test
    @DisplayName("the fingerprint ignores the identity of a run and changes with any input of its configuration")
    void fingerprint(@TempDir Path dir) throws IOException {
        Matrix matrix = load(dir, file());
        String first = RunSpec.parse(matrix.specFor("on-4", 1, 1)).fingerprint();

        assertEquals(
                first, RunSpec.parse(matrix.specFor("on-4", 2, 3)).fingerprint(), "replicate and attempt are identity");
        assertEquals(first, matrix.fingerprintOf("on-4"));
        assertNotEquals(first, matrix.fingerprintOf("on-16"), "another node count is another configuration");

        JsonObject changed = file();
        changed.getJsonArray("points")
                .getJsonObject(0)
                .getJsonObject("spec")
                .put("node", new JsonObject().put("poolSize", 5));
        assertNotEquals(first, load(dir, changed).fingerprintOf("on-4"));

        JsonObject threshold = file();
        threshold.getJsonObject("thresholds").put("generatorMaxLatenessMs", 100);
        assertNotEquals(
                first, load(dir, threshold).fingerprintOf("on-4"), "the thresholds are part of the configuration");
    }

    @Test
    @DisplayName("the hash is the hash of the file's bytes")
    void hashOfTheFile(@TempDir Path dir) throws IOException {
        Matrix a = load(dir, file());
        String hash = a.sha256();
        Files.writeString(dir.resolve("m.json"), file().encodePrettily() + "\n");

        assertNotEquals(hash, Matrix.load(dir.resolve("m.json")).sha256());
    }

    @Test
    @DisplayName("an unknown input anywhere in the matrix is rejected")
    void strict(@TempDir Path dir) {
        JsonObject typo = file().put("thresholdz", new JsonObject());
        assertThrows(ConfigurationException.class, () -> load(dir, typo));

        JsonObject inThreshold = file();
        inThreshold.getJsonObject("thresholds").put("minValidRunz", 2);
        assertThrows(ConfigurationException.class, () -> load(dir, inThreshold));

        JsonObject inSpec = file();
        inSpec.getJsonArray("points").getJsonObject(0).getJsonObject("spec").put("nodez", 2);
        assertThrows(ConfigurationException.class, () -> load(dir, inSpec));
    }

    @Test
    @DisplayName("a point may not set what the matrix owns: its identity or thresholds")
    void pointCannotSetIdentity(@TempDir Path dir) {
        JsonObject withName = file();
        withName.getJsonArray("points").getJsonObject(0).getJsonObject("spec").put("name", "x");
        assertThrows(ConfigurationException.class, () -> load(dir, withName));

        JsonObject withThresholds = file();
        withThresholds
                .getJsonArray("points")
                .getJsonObject(0)
                .getJsonObject("spec")
                .put("thresholds", new JsonObject());
        assertThrows(ConfigurationException.class, () -> load(dir, withThresholds));
    }

    @Test
    @DisplayName("the order names planned replicates only, and a replicate beyond the plan has no run")
    void orderAndReplicates(@TempDir Path dir) throws IOException {
        JsonObject bad = file().put(
                        "order",
                        new JsonArray()
                                .add(new JsonObject().put("point", "on-16").put("replicate", 2)));
        assertThrows(ConfigurationException.class, () -> load(dir, bad));

        Matrix matrix = load(dir, file());
        assertThrows(ConfigurationException.class, () -> matrix.specFor("on-16", 2, 1));
        assertThrows(ConfigurationException.class, () -> matrix.specFor("nowhere", 1, 1));
        assertTrue(matrix.points().size() == 2);
    }
}

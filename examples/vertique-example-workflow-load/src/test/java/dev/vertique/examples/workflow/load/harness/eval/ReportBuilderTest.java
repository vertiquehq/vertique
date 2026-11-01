// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.spec.Matrix;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the report lists every point of the matrix, combines only runs that compare, takes the thresholds from
 * the matrix, and enforces the replicate and retry rules.
 */
class ReportBuilderTest {

    private static JsonObject pointSpec(int nodes, double rate) {
        return new JsonObject()
                .put("arm", "ON")
                .put("nodes", nodes)
                .put("load", new JsonObject().put("kind", "fixed").put("ratePerSec", rate));
    }

    private static JsonObject point(String id, boolean diagnostic, int nodes, double rate, int replicates) {
        return new JsonObject()
                .put("id", id)
                .put("set", "scaling")
                .put("diagnostic", diagnostic)
                .put("replicates", replicates)
                .put("spec", pointSpec(nodes, rate));
    }

    private static Matrix matrix(Path dir, JsonObject thresholds, JsonObject... points) throws IOException {
        JsonArray array = new JsonArray();
        List<String> ids = new ArrayList<>();
        for (JsonObject p : points) {
            array.add(p);
            ids.add(p.getString("id"));
        }
        JsonObject file = new JsonObject()
                .put("title", "T")
                .put("session", "s1")
                .put("thresholds", thresholds)
                .put("points", array)
                .put(
                        "scalingGroups",
                        new JsonArray()
                                .add(new JsonObject()
                                        .put("id", "g")
                                        .put("points", new JsonArray().add("a").add("b"))));
        Path path = dir.resolve("matrix-" + System.nanoTime() + ".json");
        Files.writeString(path, file.encodePrettily());
        return Matrix.load(path);
    }

    private static JsonObject run(
            Matrix m, String point, int replicate, int attempt, String validity, double completed, String claim) {
        return new JsonObject()
                .put("name", point + "-r" + replicate + "-a" + attempt)
                .put("point", point)
                .put("replicate", replicate)
                .put("attempt", attempt)
                .put("matrixSha256", m.sha256())
                .put("fingerprint", m.fingerprintOf(point))
                .put("code", new JsonObject().put("classpathSha256", "build-1"))
                .put("validity", validity)
                .put("outcome", "valid".equals(validity) ? "measured" : "invalid")
                .put("violations", new JsonArray())
                .put("load", new JsonObject().put("completedInstancesPerSecond", completed))
                .put("stalls", new JsonObject().put("count", 0).put("withoutCoincidentCondition", 0))
                .put(
                        "gates",
                        new JsonObject()
                                .put(
                                        "claimLatency",
                                        new JsonObject().put("verdict", claim).put("detail", "d")));
    }

    @Test
    @DisplayName("every point of the matrix is in the report: measured, invalid, diagnostic, inconclusive and not run")
    void everyPoint(@TempDir Path dir) throws IOException {
        Matrix m = matrix(
                dir,
                new JsonObject(),
                point("a", false, 4, 600, 3),
                point("b", false, 16, 600, 3),
                point("c", true, 4, 500, 3),
                point("d", false, 8, 600, 3),
                point("e", false, 2, 600, 3));
        List<JsonObject> results = new ArrayList<>(List.of(
                run(m, "a", 1, 1, "valid", 600, "pass"),
                run(m, "a", 2, 1, "valid", 598, "pass"),
                run(m, "b", 1, 1, "valid", 580, "pass"),
                run(m, "b", 2, 1, "valid", 570, "pass"),
                run(m, "c", 1, 1, "valid", 500, "pass"),
                run(m, "d", 1, 1, "invalid", 0, "pass"),
                run(m, "d", 2, 1, "invalid", 0, "pass")));

        JsonObject report = ReportBuilder.build(m, results);

        JsonArray points = report.getJsonArray("points");
        assertEquals(5, points.size());
        assertEquals("measured", points.getJsonObject(0).getString("status"));
        assertEquals(
                "pass", points.getJsonObject(0).getJsonObject("claimLatency").getString("verdict"));
        assertEquals(600.0, points.getJsonObject(0).getDouble("offeredStartsPerSecond"));
        assertEquals("diagnostic", points.getJsonObject(2).getString("status"));
        assertEquals(
                "not_applicable",
                points.getJsonObject(2).getJsonObject("claimLatency").getString("verdict"));
        assertEquals("invalid", points.getJsonObject(3).getString("status"));
        assertEquals("not run", points.getJsonObject(4).getString("status"));
        assertEquals(
                "pass",
                report.getJsonArray("scalingGroups")
                        .getJsonObject(0)
                        .getJsonObject("scaling")
                        .getString("verdict"));
        assertEquals(m.sha256(), report.getString("matrixSha256"));
        String text = ReportBuilder.markdown(report);
        for (String id : List.of("| a |", "| b |", "| c |", "| d |", "| e |")) {
            assertTrue(text.contains(id), id);
        }
    }

    @Test
    @DisplayName("a point with one valid replicate is inconclusive, however that run came out")
    void oneValidReplicate(@TempDir Path dir) throws IOException {
        Matrix m = matrix(dir, new JsonObject(), point("a", false, 4, 600, 3), point("b", false, 16, 600, 3));

        JsonObject report = ReportBuilder.build(
                m, List.of(run(m, "a", 1, 1, "valid", 600, "pass"), run(m, "a", 2, 1, "invalid", 0, "pass")));

        JsonObject row = report.getJsonArray("points").getJsonObject(0);
        assertEquals("inconclusive", row.getString("status"));
        assertEquals("inconclusive", row.getJsonObject("claimLatency").getString("verdict"));
    }

    @Test
    @DisplayName(
            "a run of another configuration, another matrix file or another executable is excluded, never combined")
    void incomparableRunsAreExcluded(@TempDir Path dir) throws IOException {
        Matrix m = matrix(dir, new JsonObject(), point("a", false, 4, 600, 3), point("b", false, 16, 600, 3));
        JsonObject otherConfiguration = run(m, "a", 1, 1, "valid", 600, "pass").put("fingerprint", "0".repeat(64));
        JsonObject otherMatrix = run(m, "a", 2, 1, "valid", 600, "pass").put("matrixSha256", "f".repeat(64));
        JsonObject otherBuild =
                run(m, "a", 3, 1, "valid", 600, "pass").put("code", new JsonObject().put("classpathSha256", "build-2"));
        List<JsonObject> results = List.of(
                otherConfiguration,
                otherMatrix,
                otherBuild,
                run(m, "b", 1, 1, "valid", 600, "pass"),
                run(m, "b", 2, 1, "valid", 600, "pass"),
                run(m, "b", 3, 1, "valid", 600, "pass"));

        JsonObject report = ReportBuilder.build(m, results);

        assertEquals(3, report.getJsonArray("excludedRuns").size());
        String reasons = report.getJsonArray("excludedRuns").encode();
        assertTrue(reasons.contains("fingerprint differs"), reasons);
        assertTrue(reasons.contains("another matrix file"), reasons);
        assertTrue(reasons.contains("another executable"), reasons);
        assertEquals("not run", report.getJsonArray("points").getJsonObject(0).getString("status"));
        assertEquals("measured", report.getJsonArray("points").getJsonObject(1).getString("status"));
    }

    @Test
    @DisplayName("the thresholds are the matrix's: a stricter minimum of valid runs is applied")
    void thresholdsComeFromTheMatrix(@TempDir Path dir) throws IOException {
        Matrix m = matrix(
                dir,
                new JsonObject().put("minValidRuns", 3),
                point("a", false, 4, 600, 3),
                point("b", false, 16, 600, 3));

        JsonObject report = ReportBuilder.build(
                m, List.of(run(m, "a", 1, 1, "valid", 600, "pass"), run(m, "a", 2, 1, "valid", 600, "pass")));

        assertEquals(
                "inconclusive", report.getJsonArray("points").getJsonObject(0).getString("status"));
        assertEquals(3, report.getJsonObject("thresholds").getInteger("minValidRuns"));
    }

    @Test
    @DisplayName("the offered rate of a scaling comparison comes from the matrix, so unequal rates are never compared")
    void scalingRateFromTheMatrix(@TempDir Path dir) throws IOException {
        Matrix m = matrix(dir, new JsonObject(), point("a", false, 4, 600, 3), point("b", false, 16, 700, 3));
        List<JsonObject> results = List.of(
                run(m, "a", 1, 1, "valid", 600, "pass"),
                run(m, "a", 2, 1, "valid", 600, "pass"),
                run(m, "b", 1, 1, "valid", 600, "pass"),
                run(m, "b", 2, 1, "valid", 600, "pass"));

        JsonObject report = ReportBuilder.build(m, results);

        assertEquals(
                "inconclusive",
                report.getJsonArray("scalingGroups")
                        .getJsonObject(0)
                        .getJsonObject("scaling")
                        .getString("verdict"));
    }

    @Test
    @DisplayName(
            "only the first valid attempt of a replicate counts; a retry after it, or over the ceiling, is a violation")
    void retryRules(@TempDir Path dir) throws IOException {
        Matrix m = matrix(dir, new JsonObject(), point("a", false, 4, 600, 2), point("b", false, 16, 600, 2));
        List<JsonObject> results = List.of(
                run(m, "a", 1, 1, "invalid", 0, "pass"),
                run(m, "a", 1, 2, "valid", 600, "pass"),
                run(m, "a", 1, 3, "valid", 600, "pass"),
                run(m, "a", 2, 1, "invalid", 0, "pass"),
                run(m, "a", 2, 2, "invalid", 0, "pass"),
                run(m, "a", 2, 3, "invalid", 0, "pass"),
                run(m, "a", 2, 4, "valid", 600, "pass"));

        JsonObject report = ReportBuilder.build(m, results);

        JsonObject row = report.getJsonArray("points").getJsonObject(0);
        assertEquals(1, row.getInteger("replicatesCounted"));
        String violations = report.getJsonArray("violations").encode();
        assertTrue(violations.contains("a-r1-a3") && violations.contains("after a valid attempt"), violations);
        assertTrue(violations.contains("a-r2-a4") && violations.contains("over the ceiling"), violations);
    }
}

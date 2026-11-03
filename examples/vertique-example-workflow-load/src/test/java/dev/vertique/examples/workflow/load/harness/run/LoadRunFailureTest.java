// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that a run that cannot start still leaves an attempt record, a timeline, a manifest and a verdict that names
 * the failure, and that a run the harness would not allow leaves nothing behind.
 */
class LoadRunFailureTest {

    private static JsonObject spec(String container) {
        return new JsonObject()
                .put("name", "failing")
                .put("arm", "ON")
                .put("nodes", 1)
                .put("load", new JsonObject().put("kind", "fixed").put("ratePerSec", 10))
                .put("database", new JsonObject().put("container", container));
    }

    @Test
    @DisplayName("a container that is not running ends the run as a harness precondition, with every record written")
    void missingEnvironmentLeavesARecord(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spec.json");
        Files.writeString(file, spec("wf017-this-container-does-not-exist").encode());
        Path module = Path.of("").toAbsolutePath();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = LoadRun.run(
                new String[] {
                    "--spec", file.toString(), "--out", dir.resolve("out").toString(), "--module-dir", module.toString()
                },
                new PrintStream(out),
                new PrintStream(err));

        assertEquals(0, code, err.toString());
        Path run = dir.resolve("out/failing");
        JsonObject result = new JsonObject(Files.readString(run.resolve("result.json")));
        assertEquals("invalid", result.getString("validity"));
        assertEquals(
                "harness.precondition",
                result.getJsonArray("violations").getJsonObject(0).getString("rule"));
        assertTrue(result.getJsonArray("violations")
                .getJsonObject(0)
                .getString("detail")
                .contains("docker"));
        JsonObject attempt = new JsonObject(Files.readString(run.resolve("attempt.json")));
        assertEquals("finished", attempt.getString("status"));
        assertEquals("invalid", attempt.getString("validity"));
        assertTrue(attempt.getLong("startedAtMs") > 0
                && attempt.getLong("finishedAtMs") >= attempt.getLong("startedAtMs"));
        for (String file1 : new String[] {"timeline.json", "manifest.json", "spec.json", "validity.json"}) {
            assertTrue(Files.exists(run.resolve(file1)), file1);
        }
        assertTrue(out.toString().contains("\"validity\":\"invalid\""));
    }

    @Test
    @DisplayName("a specification with an unknown input is rejected before any directory is made")
    void rejectedSpecLeavesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spec.json");
        Files.writeString(file, spec("wf017-pg").put("nodez", 3).encode());
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = LoadRun.run(
                new String[] {
                    "--spec", file.toString(), "--out", dir.resolve("out").toString()
                },
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));

        assertEquals(2, code);
        assertFalse(Files.exists(dir.resolve("out")));
        assertTrue(err.toString().contains("nodez"), err.toString());
    }

    @Test
    @DisplayName("a classpath that is not the one recorded at build time is refused before anything starts")
    void changedBuildIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spec.json");
        Files.writeString(file, spec("wf017-pg").encode());
        Path identity = dir.resolve("identity.json");
        Files.writeString(
                identity,
                new JsonObject()
                        .put("classpathSha256", "0".repeat(64))
                        .put("moduleSha256", "x")
                        .put("entries", new JsonArray())
                        .encode());
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = LoadRun.run(
                new String[] {
                    "--spec", file.toString(), "--out", dir.resolve("out").toString(), "--identity", identity.toString()
                },
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));

        assertEquals(2, code);
        assertTrue(err.toString().contains("not the one recorded"), err.toString());
        assertFalse(Files.exists(dir.resolve("out")));
    }

    @Test
    @DisplayName("an attempt over the ceiling, or a repeat of a replicate that is already valid, is refused")
    void retryCeiling(@TempDir Path dir) throws IOException {
        JsonObject matrix = new JsonObject()
                .put(
                        "points",
                        new JsonArray()
                                .add(new JsonObject()
                                        .put("id", "p")
                                        .put("replicates", 2)
                                        .put(
                                                "spec",
                                                new JsonObject()
                                                        .put("arm", "ON")
                                                        .put("nodes", 1)
                                                        .put(
                                                                "load",
                                                                new JsonObject()
                                                                        .put("kind", "fixed")
                                                                        .put("ratePerSec", 10)))));
        Path file = dir.resolve("m.json");
        Files.writeString(file, matrix.encode());
        Path out = Files.createDirectories(dir.resolve("out"));

        assertEquals(2, attempt(file, out, 1, 4), "over the ceiling of three attempts");

        Path earlier = Files.createDirectories(out.resolve("p-r1-a1"));
        Files.writeString(
                earlier.resolve("result.json"),
                new JsonObject().put("validity", "valid").encode());
        assertEquals(2, attempt(file, out, 1, 2), "replicate 1 is valid already");
    }

    private static int attempt(Path matrix, Path out, int replicate, int attempt) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LoadRun.run(
                new String[] {
                    "--matrix",
                    matrix.toString(),
                    "--point",
                    "p",
                    "--replicate",
                    Integer.toString(replicate),
                    "--attempt",
                    Integer.toString(attempt),
                    "--out",
                    out.toString()
                },
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));
        assertFalse(err.toString().isEmpty());
        return code;
    }
}

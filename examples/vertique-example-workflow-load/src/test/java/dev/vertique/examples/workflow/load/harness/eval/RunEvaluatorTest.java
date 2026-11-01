// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the verdict on whole runs whose recorded figures are known: a generator failure, a collector failure, a
 * configuration error and an identified outside process make a run invalid, while everything the system does, a database
 * that stops answering for longer than the whole window included, leaves it valid with the behavior kept in the result.
 */
class RunEvaluatorTest {

    private JsonObject judge(SyntheticRun run, Path dir) throws IOException {
        run.write(dir);
        return RunEvaluator.evaluate(dir);
    }

    private static List<String> rules(JsonObject result) {
        return result.getJsonArray("violations").stream()
                .map(o -> ((JsonObject) o).getString("rule"))
                .toList();
    }

    @Test
    @DisplayName("a healthy run is valid, passes claim latency and writes every verdict file")
    void healthy(@TempDir Path dir) throws IOException {
        JsonObject result = judge(new SyntheticRun(), dir);

        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        assertEquals("measured", result.getString("outcome"));
        assertEquals(
                "pass",
                result.getJsonObject("gates").getJsonObject("claimLatency").getString("verdict"));
        assertEquals(
                "not_applicable",
                result.getJsonObject("gates").getJsonObject("notificationLock").getString("verdict"));
        assertEquals(0, result.getJsonObject("stalls").getInteger("count"));
        assertEquals("unchanged", result.getJsonObject("listeners").getString("status"));
        assertEquals(100.0, result.getJsonObject("load").getDouble("completedInstancesPerSecond"));
        assertEquals(100.0, result.getJsonObject("load").getDouble("offeredStartsPerSecond"));
        assertEquals(300.0, result.getJsonObject("load").getDouble("offeredCommandEquivalentsPerSecond"));
        assertEquals(
                100.0,
                result.getJsonObject("load")
                        .getJsonObject("recordedCommandsPerSecondInWindowByKind")
                        .getDouble("START"));
        assertEquals("p-on-2", result.getString("point"));
        assertEquals(64, result.getString("fingerprint").length());
        assertEquals("abc", result.getJsonObject("code").getString("classpathSha256"));
        for (String file : List.of("result.json", "validity.json", "stalls.json", "listeners.json")) {
            assertTrue(Files.exists(dir.resolve(file)), file);
        }
    }

    @Test
    @DisplayName("a generator failure marks the run invalid and names the rule")
    void generatorFailureIsInvalid(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.generatorLatenessMs = 2984;
        run.lateOverThreshold = 118;

        JsonObject result = judge(run, dir);

        assertEquals("invalid", result.getString("validity"));
        assertTrue(rules(result).contains("generator.lateness"), rules(result).toString());
        assertTrue(
                rules(result).contains("generator.late-arrivals"), rules(result).toString());
    }

    @Test
    @DisplayName("a driver that wrote no summary marks the run invalid")
    void missingDriverSummary(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.summaryPresent = false;

        assertEquals(List.of("generator.did-not-finish"), rules(judge(run, dir)));
    }

    @Test
    @DisplayName("a database stall leaves the run valid and is reported with the evidence that coincided")
    void databaseStallStaysValid(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.stallFromSec = 20;
        run.stallToSec = 24;

        JsonObject result = judge(run, dir);

        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        assertEquals(1, result.getJsonObject("stalls").getInteger("count"));
        JsonObject stall = result.getJsonObject("stalls").getJsonArray("list").getJsonObject(0);
        assertEquals("dip", stall.getString("kind"));
        assertEquals("unresolved", stall.getString("cause"));
        assertFalse(stall.getBoolean("noCoincidentCondition"));
        assertTrue(stall.getJsonArray("coincident").contains("database-unanswered"), stall.encode());
        JsonObject detail = new JsonArray(Files.readString(dir.resolve("stalls.json"))).getJsonObject(0);
        assertNotNull(detail.getJsonArray("driver"));
        assertFalse(detail.getJsonArray("collector").isEmpty());
        assertFalse(detail.getJsonArray("heartbeat").isEmpty());
        assertFalse(detail.getJsonArray("host").isEmpty());
        assertFalse(detail.getJsonArray("vm").isEmpty());
    }

    @Test
    @DisplayName(
            "a database outage that outlasts the window, and leaves no sampling tick for half a minute, cannot invalidate its own evidence")
    void outageLongerThanTheLimitsStaysValid(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.stallFromSec = 20;
        run.stallToSec = 52;

        JsonObject result = judge(run, dir);

        // The old rule counted the samples of the database inside the window: 40 s without a tick breaks both its
        // coverage and its gap limit. The heartbeat is the collector's liveness and it never stopped.
        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        JsonObject database = result.getJsonObject("system").getJsonObject("database");
        assertTrue(database.getInteger("heartbeatsWithAQueryOutstanding") >= 25, database.encode());
        assertTrue(database.getDouble("longestTimeSinceASuccessfulSampleMs") >= 25_000, database.encode());
        assertTrue(database.getInteger("unansweredTicks") > 0);
        assertTrue(result.getJsonObject("stalls").getInteger("count") >= 1);
    }

    @Test
    @DisplayName("a collector whose heartbeat stops is a collector failure and invalidates the run")
    void missingHeartbeatIsInvalid(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.heartbeatGap = true;

        List<String> rules = rules(judge(run, dir));

        assertTrue(rules.contains("collector.heartbeat-gap"), rules.toString());
        assertTrue(rules.contains("collector.heartbeat-coverage"), rules.toString());
    }

    @Test
    @DisplayName(
            "a dip with nothing around it stays in the result with no coincident condition, and the run stays valid")
    void dipWithoutACoincidentCondition(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.dipWithoutCause = true;

        JsonObject result = judge(run, dir);

        assertEquals("valid", result.getString("validity"));
        assertEquals(1, result.getJsonObject("stalls").getInteger("withoutCoincidentCondition"));
        assertTrue(result.getJsonObject("stalls").getString("cause").contains("unresolved"));
    }

    @Test
    @DisplayName("failed requests and a node that exited are results, not validity rules")
    void systemFailuresStayValid(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.failedRequests = 250;
        run.node1Exit = "exit=137 atMs=1800000040000";

        JsonObject result = judge(run, dir);

        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        assertEquals(250, result.getJsonObject("system").getJsonObject("driver").getInteger("failed"));
        assertEquals(
                "exit=137 atMs=1800000040000",
                result.getJsonObject("system").getJsonObject("nodeExits").getString("node-1"));
        assertEquals(
                "running at the end",
                result.getJsonObject("system").getJsonObject("nodeExits").getString("node-2"));
    }

    @Test
    @DisplayName(
            "a node that never became ready for a reason of its own is a system-failed result, valid, with every gate inconclusive")
    void startupFailureIsASystemResult(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.systemFailureReason = "node 7: the node exited with code 137";

        JsonObject result = judge(run, dir);

        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        assertEquals("system-failed", result.getString("outcome"));
        assertEquals("nodes", result.getJsonObject("systemFailure").getString("phase"));
        assertEquals(
                "inconclusive",
                result.getJsonObject("gates").getJsonObject("claimLatency").getString("verdict"));
    }

    @Test
    @DisplayName("a node's own configuration error, or values it did not pick up, make the run invalid")
    void configurationErrorsAreInvalid(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.aborted = true;
        run.startupConfigurationError = "nodes: node 2 reported a configuration error: ConfigurationException x";
        run.configMismatches = List.of("node-1 executor.pollIntervalMs: intended 5000 but the node reported 1000");

        JsonObject result = judge(run, dir);

        assertEquals("invalid", result.getString("validity"));
        assertTrue(
                rules(result).contains("configuration.mismatch"), rules(result).toString());
        assertTrue(
                rules(result).contains("configuration.startup"), rules(result).toString());
        assertFalse(result.containsKey("gates"));
    }

    @Test
    @DisplayName("a missing environment before any node started makes the run invalid and is written down")
    void preconditionFailure(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.aborted = true;
        run.preconditionFailure = "docker: the container wf017-pg is not running";

        JsonObject result = judge(run, dir);

        assertEquals(List.of("harness.precondition"), rules(result));
    }

    @Test
    @DisplayName("a collector that did not stop cleanly marks the run invalid")
    void collectorFailure(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.collectorFinished = false;

        assertEquals(List.of("collector.did-not-finish"), rules(judge(run, dir)));
    }

    @Test
    @DisplayName("one identifiable outside process busy for a long time makes the run invalid, with its identity kept")
    void identifiableInterference(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.independentSeconds = 20;

        JsonObject result = judge(run, dir);

        assertEquals(List.of("interference.independent-process"), rules(result));
        assertTrue(result.getJsonArray("violations")
                .getJsonObject(0)
                .getString("detail")
                .contains("consecutive"));
    }

    @Test
    @DisplayName(
            "a busy kernel, or an outside process that changes from sample to sample, is evidence and not interference")
    void systemCpuAndChangingProcessesAreNotInterference(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.kernelBusy = true;
        run.independentSeconds = 20;
        run.independentPidChanges = 8;

        JsonObject result = judge(run, dir);

        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        assertTrue(result.getJsonObject("system").getJsonObject("host").getDouble("maxSystemCpuPercent") >= 300);
    }

    @Test
    @DisplayName("a diagnostic set carries no gate verdict")
    void diagnosticHasNoGates(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.diagnostic = true;

        JsonObject result = judge(run, dir);

        assertEquals("valid", result.getString("validity"));
        assertTrue(result.getJsonObject("gates").containsKey("note"));
        assertFalse(result.getJsonObject("gates").containsKey("claimLatency"));
    }

    @Test
    @DisplayName("a latency bound above the limit leaves claim latency inconclusive and the run valid")
    void slowBoundIsUnresolved(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.recordedP95 = 374;

        JsonObject result = judge(run, dir);

        assertEquals("valid", result.getString("validity"));
        assertEquals(
                "inconclusive",
                result.getJsonObject("gates").getJsonObject("claimLatency").getString("verdict"));
    }

    @Test
    @DisplayName(
            "rows never created count as excluded: a callback missing for more than one start in a hundred makes claim latency inconclusive")
    void rowsNeverCreatedAreExcluded(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.signalsNeverCreated = 200;

        JsonObject result = judge(run, dir);

        JsonObject gate = result.getJsonObject("gates").getJsonObject("claimLatency");
        assertEquals("inconclusive", gate.getString("verdict"));
        assertTrue(gate.getString("detail").contains("never created"), gate.getString("detail"));
    }

    @Test
    @DisplayName(
            "a database that is down when the figures are read leaves the run valid, without figures, with the reason, and every gate inconclusive")
    void sqlUnavailableAtTheEnd(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.sqlPresent = false;
        run.sqlError = "metrics: Connection refused";

        JsonObject result = judge(run, dir);

        assertEquals(
                "valid",
                result.getString("validity"),
                result.getJsonArray("violations").encode());
        assertEquals("metrics: Connection refused", result.getString("sqlUnavailable"));
        assertEquals(
                "metrics: Connection refused",
                result.getJsonObject("system").getJsonObject("database").getString("sqlUnavailableAtTheEnd"));
        assertEquals(
                "inconclusive",
                result.getJsonObject("gates").getJsonObject("claimLatency").getString("verdict"));
        assertTrue(Files.exists(dir.resolve("result.json")));
    }

    @Test
    @DisplayName("waiters on the notification lock from a node fail the lock gate")
    void lockWaiters(@TempDir Path dir) throws IOException {
        SyntheticRun run = new SyntheticRun();
        run.lockWatch = true;
        run.nodeWaiters = 57;

        JsonObject result = judge(run, dir);

        JsonObject gate = result.getJsonObject("gates").getJsonObject("notificationLock");
        assertEquals("fail", gate.getString("verdict"));
        assertTrue(gate.getString("detail").contains("load-node-2"), gate.getString("detail"));
    }
}

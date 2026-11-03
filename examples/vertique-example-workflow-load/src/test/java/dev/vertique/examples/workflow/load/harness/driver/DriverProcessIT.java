// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.eval.Validity;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import dev.vertique.examples.workflow.load.support.ChildJvm;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the driver as its own process against stub ingresses and checks what a run depends on: the schedule holds while
 * nodes stall, a paused driver reports its lateness and makes the run invalid, and a refusal is counted and replayed
 * in a labelled phase outside the measurement window.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DriverProcessIT {

    private static Vertx vertx;
    private static final List<HttpServer> servers = new ArrayList<>();
    private static final int[] ports = new int[2];
    private static final AtomicLong holdMillis = new AtomicLong();
    private static final Set<String> refusedOnce = ConcurrentHashMap.newKeySet();
    private static volatile boolean refuseEveryFourth;
    private static final AtomicLong holdRefusalsMillis = new AtomicLong();
    private static final Map<String, Integer> received = new ConcurrentHashMap<>();

    @BeforeAll
    static void startStubs() throws Exception {
        vertx = Vertx.vertx();
        for (int i = 0; i < ports.length; i++) {
            int node = i;
            HttpServer server = vertx.createHttpServer()
                    .requestHandler(request -> {
                        String key = request.getParam("key");
                        received.merge(key, 1, Integer::sum);
                        long hold = node == 0 ? holdMillis.get() : 0;
                        boolean willRefuse = refuseEveryFourth
                                && Integer.parseInt(key.substring(key.lastIndexOf('-') + 1)) % 4 == 0
                                && !refusedOnce.contains(key);
                        if (willRefuse) {
                            hold = Math.max(hold, holdRefusalsMillis.get());
                        }
                        Runnable answer = () -> {
                            int number = Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
                            boolean refuse = refuseEveryFourth && number % 4 == 0 && refusedOnce.add(key);
                            request.response()
                                    .setStatusCode(refuse ? 503 : 200)
                                    .putHeader("content-type", "application/json")
                                    .end(
                                            refuse
                                                    ? "{\"outcome\":\"refused\"}"
                                                    : "{\"outcome\":\"accepted\",\"workflowId\":\"w\"}");
                        };
                        if (hold > 0) {
                            vertx.setTimer(hold, id -> answer.run());
                        } else {
                            answer.run();
                        }
                    })
                    .listen(0, "127.0.0.1")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            servers.add(server);
            ports[i] = server.actualPort();
        }
    }

    @AfterAll
    static void stopStubs() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private JsonObject spec(Path dir, double rate, double seconds, int recoverySec, double replayRate) {
        return new JsonObject()
                .put("name", "it")
                .put("outDir", dir.toString())
                .put("startAtEpochMs", System.currentTimeMillis() + 800)
                .put(
                        "ingresses",
                        new JsonArray()
                                .add(new JsonObject()
                                        .put("node", 1)
                                        .put("host", "127.0.0.1")
                                        .put("port", ports[0]))
                                .add(new JsonObject()
                                        .put("node", 2)
                                        .put("host", "127.0.0.1")
                                        .put("port", ports[1])))
                .put(
                        "segments",
                        new JsonArray()
                                .add(new JsonObject()
                                        .put("label", "window")
                                        .put("durationSec", seconds)
                                        .put("ratePerSec", rate)
                                        .put("window", true)))
                .put("recoverySec", recoverySec)
                .put("replayRatePerSec", replayRate)
                .put("requestTimeoutMs", 8000)
                .put("graceSec", 4)
                .put("lateArrivalMs", 50);
    }

    private ChildJvm start(Path dir, JsonObject spec) throws IOException {
        Path file = dir.resolve("driver-spec.json");
        Files.writeString(file, spec.encode());
        return ChildJvm.start(
                "driver",
                dir.resolve("driver.out"),
                Path.of("").toAbsolutePath(),
                Map.of(),
                LoadDriver.class.getName(),
                List.of("--spec", file.toString()));
    }

    private static JsonObject summary(Path dir) throws IOException {
        return new JsonObject(Files.readString(dir.resolve("summary.json")));
    }

    @Test
    @DisplayName(
            "nodes that stall do not move the schedule: every arrival starts on time, and the stall shows as response time")
    void scheduleHoldsWhileNodesStall(@TempDir Path dir) throws Exception {
        holdMillis.set(1500);
        try (ChildJvm driver = start(dir, spec(dir, 50, 4, 0, 50))) {
            assertEquals(0, driver.waitFor(Duration.ofSeconds(18)), driver.output());
        } finally {
            holdMillis.set(0);
        }

        JsonObject main = summary(dir).getJsonObject("main");
        assertEquals(200, main.getInteger("planned"));
        assertEquals(200, main.getInteger("started"));
        assertEquals(0, main.getInteger("notSubmitted"));
        assertEquals(200, main.getInteger("accepted"));
        assertTrue(main.getDouble("latenessMaxMs") < 250, "lateness " + main.getDouble("latenessMaxMs"));
        assertTrue(main.getDouble("responseMaxMs") > 1400, "responses were held");
        assertTrue(main.getInteger("maxInFlight") >= 25, "starts in flight " + main.getInteger("maxInFlight"));
        assertFalse(summary(dir).getBoolean("recorderLoss"));
        assertEquals(200, Files.readAllLines(dir.resolve("arrivals.csv")).size() - 1);
    }

    @Test
    @DisplayName("a paused driver reports its lateness, and a run with that lateness is invalid")
    void pausedDriverIsLateAndInvalid(@TempDir Path dir) throws Exception {
        try (ChildJvm driver = start(dir, spec(dir, 50, 6, 0, 50))) {
            Thread.sleep(2_300);
            signal("-STOP", driver.pid());
            Thread.sleep(2_000);
            signal("-CONT", driver.pid());
            assertEquals(0, driver.waitFor(Duration.ofSeconds(18)), driver.output());
        }

        JsonObject main = summary(dir).getJsonObject("main");
        assertEquals(300, main.getInteger("started"), "nothing was dropped");
        assertTrue(main.getDouble("latenessMaxMs") > 1_500, "lateness " + main.getDouble("latenessMaxMs"));
        assertTrue(main.getInteger("lateOverThreshold") > 50, "late arrivals " + main.getInteger("lateOverThreshold"));
        Validity.Result validity = Validity.evaluate(
                new Validity.GeneratorFacts(
                        true,
                        main.getLong("planned"),
                        main.getLong("started"),
                        main.getLong("notSubmitted"),
                        main.getDouble("latenessMaxMs"),
                        main.getLong("lateOverThreshold"),
                        summary(dir).getBoolean("recorderLoss")),
                new Validity.CollectionFacts(true, 0, 0, 0, 0, false, 0, 0, 0, List.of()),
                new Validity.ConfigurationFacts(List.of(), "", ""),
                Thresholds.defaults());
        assertFalse(validity.valid());
        assertTrue(
                validity.violations().stream().anyMatch(v -> v.rule().equals("generator.lateness")),
                validity.toJson().encode());
    }

    @Test
    @DisplayName("a refusal is counted, its key is kept, and the replay is labelled and lies outside the window")
    void refusalIsReplayedOutsideTheWindow(@TempDir Path dir) throws Exception {
        refuseEveryFourth = true;
        refusedOnce.clear();
        received.clear();
        try (ChildJvm driver = start(dir, spec(dir, 40, 2, 1, 40))) {
            assertEquals(0, driver.waitFor(Duration.ofSeconds(18)), driver.output());
        } finally {
            refuseEveryFourth = false;
        }

        JsonObject summary = summary(dir);
        JsonObject main = summary.getJsonObject("main");
        JsonObject replay = summary.getJsonObject("replay");
        assertEquals(80, main.getInteger("planned"));
        assertEquals(20, main.getInteger("refused"));
        assertEquals(60, main.getInteger("accepted"));
        assertEquals(0, main.getInteger("failed"));
        assertEquals(20, replay.getInteger("planned"));
        assertEquals(
                20,
                replay.getInteger("accepted"),
                Files.readAllLines(dir.resolve("arrivals.csv")).stream()
                                .filter(r -> r.contains("FAILED"))
                                .toList()
                        + dir.resolve("driver.out").toFile().getName()
                        + Files.readString(dir.resolve("driver.out")));
        assertEquals(0, replay.getInteger("refused"));
        double windowEndSec = summary.getDouble("windowEndSec");
        double recoverySec = summary.getDouble("recoverySec");
        assertTrue(
                summary.getDouble("replayStartSec") >= windowEndSec + recoverySec,
                "replay starts after window and recovery");

        List<String> rows = Files.readAllLines(dir.resolve("arrivals.csv"));
        assertEquals(100 + 1, rows.size(), "every planned arrival and every replay has a row");
        long replayRows =
                rows.stream().filter(r -> r.split(",")[1].equals("replay")).count();
        assertEquals(20, replayRows);
        for (String row : rows.subList(1, rows.size())) {
            String[] cells = row.split(",");
            double scheduledMs = Double.parseDouble(cells[5]);
            if (cells[1].equals("replay")) {
                assertTrue(scheduledMs >= (windowEndSec + recoverySec) * 1000, row);
                assertFalse(cells[3].isEmpty(), "a replay names the arrival it repeats: " + row);
                assertEquals("ACCEPTED", cells[10], row);
            } else {
                assertTrue(scheduledMs < windowEndSec * 1000, row);
            }
        }
        // The refused keys were sent again, the others once.
        assertEquals(2, received.get("it-4"));
        assertEquals(1, received.get("it-5"));
    }

    @Test
    @DisplayName(
            "a refusal that arrives after the recovery has ended is still replayed, and the driver reports schedule-to-answer time and socket failures")
    void lateRefusalIsReplayed(@TempDir Path dir) throws Exception {
        refuseEveryFourth = true;
        refusedOnce.clear();
        received.clear();
        holdRefusalsMillis.set(2_800);
        try (ChildJvm driver = start(dir, spec(dir, 20, 1, 1, 20))) {
            assertEquals(0, driver.waitFor(Duration.ofSeconds(18)), driver.output());
        } finally {
            refuseEveryFourth = false;
            holdRefusalsMillis.set(0);
        }

        JsonObject summary = summary(dir);
        JsonObject main = summary.getJsonObject("main");
        JsonObject replay = summary.getJsonObject("replay");
        assertEquals(5, main.getInteger("refused"), main.encode());
        assertEquals(
                5, replay.getInteger("planned"), "the refusals that came late were not missed: " + replay.encode());
        assertEquals(5, replay.getInteger("accepted"));
        assertTrue(main.getDouble("scheduleToAnswerMaxMs") >= 2_700, main.encode());
        assertTrue(main.getDouble("wireToAnswerP99Ms") >= 2_700, main.encode());
        assertEquals(false, summary.getBoolean("socketExhaustion"));
        assertTrue(summary.getJsonObject("fileDescriptors").getLong("openPeak") > 0, summary.encode());
        assertTrue(Files.readAllLines(dir.resolve("arrivals.csv")).get(1).split(",").length >= 14);
    }

    @Test
    @DisplayName(
            "a refusal answered after the grace for the main phase is reported as not replayed, and the ledger closes")
    void refusalAfterTheGraceIsReported(@TempDir Path dir) throws Exception {
        refuseEveryFourth = true;
        refusedOnce.clear();
        received.clear();
        holdRefusalsMillis.set(5_500);
        try (ChildJvm driver = start(dir, spec(dir, 20, 1, 1, 20))) {
            assertEquals(0, driver.waitFor(Duration.ofSeconds(19)), driver.output());
        } finally {
            refuseEveryFourth = false;
            holdRefusalsMillis.set(0);
        }

        JsonObject summary = summary(dir);
        JsonObject main = summary.getJsonObject("main");
        int refused = main.getInteger("refused");
        int notReplayed = summary.getInteger("refusedNotReplayed");
        assertTrue(notReplayed > 0, "some refusal came after the grace: " + summary.encode());
        assertEquals(
                refused,
                summary.getJsonObject("replay").getInteger("planned") + notReplayed,
                "refused = replayed + not replayed");
        assertEquals(
                notReplayed, summary.getJsonArray("refusedNotReplayedArrivals").size());
        assertEquals(
                main.getInteger("started"),
                main.getInteger("accepted") + refused + main.getInteger("failed") + main.getInteger("unanswered"));
    }

    private static void signal(String signal, long pid) throws Exception {
        Process p = new ProcessBuilder("kill", signal, Long.toString(pid))
                .inheritIO()
                .start();
        assertEquals(0, p.waitFor());
    }
}

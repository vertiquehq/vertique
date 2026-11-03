// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.spec.RunSpec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the files of a finished run into a directory, without running anything, so that the evaluator can be tested
 * on runs whose every figure is known.
 *
 * <p>The run has two nodes, the shipped arm {@code ON}, a 5 s warm-up and a 30 s window at 100 starts per second, and
 * a timeline that starts at {@link #T0}. Each knob changes one thing about what the sources recorded.
 */
final class SyntheticRun {

    static final long T0 = 1_800_000_000_000L;
    static final int WARMUP = 5;
    static final int WINDOW = 30;
    static final int RATE = 100;

    boolean diagnostic;
    double generatorLatenessMs = 12;
    long lateOverThreshold;
    boolean summaryPresent = true;
    int stallFromSec = -1;
    int stallToSec = -1;
    boolean stallAnswersTicks = false;
    boolean dipWithoutCause;
    boolean collectorFinished = true;
    List<String> configMismatches = new ArrayList<>();
    boolean aborted;
    String startupConfigurationError = "";
    String preconditionFailure = "";
    String systemFailureReason;
    boolean heartbeatGap;
    int heartbeatGapFromSec = 10;
    int heartbeatGapToSec = 20;
    int independentSeconds;
    int independentPidChanges;
    boolean kernelBusy;
    String sqlError;
    int expectedPerKind = WINDOW * RATE;
    long signalsNeverCreated;
    boolean waitingStall;
    long failedRequests;
    String node1Exit;
    boolean sqlPresent = true;
    double recordedP95 = 22;
    boolean lockWatch;
    long nodeWaiters;
    List<String> specInjections = new ArrayList<>();

    void write(Path dir) throws IOException {
        for (String sub : List.of("driver", "collector", "nodes", "sql")) {
            Files.createDirectories(dir.resolve(sub));
        }
        JsonObject spec = new JsonObject()
                .put("name", "synthetic")
                .put("set", "scaling")
                .put("point", "p-on-2")
                .put("diagnostic", diagnostic)
                .put("arm", "ON")
                .put("nodes", 2)
                .put(
                        "load",
                        new JsonObject()
                                .put("kind", "fixed")
                                .put("ratePerSec", RATE)
                                .put("warmupSec", WARMUP)
                                .put("windowSec", WINDOW))
                .put("timeline", new JsonObject().put("settleSec", 5).put("recoverySec", 5))
                .put("collector", new JsonObject().put("lockWatch", lockWatch));
        RunSpec parsed = RunSpec.parse(spec);
        Files.writeString(dir.resolve("spec.json"), parsed.resolved().encodePrettily());

        long loadStart = T0 + 10_000;
        JsonObject timeline = new JsonObject()
                .put("runStartMs", T0)
                .put("settleStartMs", T0 + 500)
                .put("loadStartMs", loadStart)
                .put("windowStartMs", loadStart + WARMUP * 1000L)
                .put("windowEndMs", loadStart + (WARMUP + WINDOW) * 1000L)
                .put("scheduleEndMs", loadStart + (WARMUP + WINDOW) * 1000L)
                .put("recoveryEndMs", loadStart + (WARMUP + WINDOW + 5) * 1000L)
                .put("replayStartMs", loadStart + (WARMUP + WINDOW + 5) * 1000L)
                .put("driverEndMs", loadStart + (WARMUP + WINDOW + 6) * 1000L)
                .put("drainEndMs", loadStart + (WARMUP + WINDOW + 10) * 1000L)
                .put("runEndMs", loadStart + (WARMUP + WINDOW + 14) * 1000L)
                .put("dbOffsetStartMs", 0.0)
                .put("dbOffsetEndMs", 0.0)
                .put("loadNeverStarted", aborted)
                .put("configMismatches", new JsonArray(new ArrayList<Object>(configMismatches)))
                .put("startupConfigurationError", startupConfigurationError)
                .put("preconditionFailure", preconditionFailure)
                .put("drainCompleted", true)
                .put(
                        "drainRemaining",
                        new JsonObject().put("commands", 0).put("outbox", 0).put("instances", 0));
        if (aborted) {
            timeline.put("abortReason", "test");
        }
        if (systemFailureReason != null) {
            timeline.put("systemFailure", new JsonObject().put("phase", "nodes").put("reason", systemFailureReason));
        }
        if (sqlError != null) {
            timeline.put("sqlError", sqlError);
        }
        Files.writeString(dir.resolve("timeline.json"), timeline.encodePrettily());
        Files.writeString(
                dir.resolve("manifest.json"),
                new JsonObject()
                        .put("code", new JsonObject().put("identity", new JsonObject().put("classpathSha256", "abc")))
                        .encodePrettily());
        if (aborted) {
            return;
        }
        if (systemFailureReason != null) {
            return;
        }
        writeDriver(dir, loadStart);
        writeCollector(dir, timeline);
        writeSql(dir, loadStart);
        if (node1Exit != null) {
            Files.writeString(dir.resolve("nodes/node-1.exit"), node1Exit);
        }
        for (String log : List.of(
                "nodes/node-1.jvm.log",
                "nodes/node-2.jvm.log",
                "driver/driver.jvm.log",
                "collector/collector.jvm.log")) {
            Files.writeString(dir.resolve(log), "[2026-11-01T12:25:08.726+0000][0.003s] Using G1\n");
        }
        for (String node : List.of("nodes/node-1.log", "nodes/node-2.log")) {
            Files.writeString(dir.resolve(node), "");
        }
        if (collectorFinished) {
            Files.writeString(dir.resolve("collector/stopped"), "1");
        }
    }

    private void writeDriver(Path dir, long loadStart) throws IOException {
        if (summaryPresent) {
            JsonObject window = counts(WINDOW * RATE, 0, failedRequests);
            JsonObject warmup = counts(WARMUP * RATE, 0, 0);
            JsonObject main = counts((WARMUP + WINDOW) * RATE, 0, failedRequests)
                    .put("latenessMaxMs", generatorLatenessMs)
                    .put("lateOverThreshold", lateOverThreshold)
                    .put(
                            "failureDetails",
                            failedRequests > 0 ? new JsonObject().put("http-500", failedRequests) : new JsonObject())
                    .put("bySegment", new JsonObject().put("warmup", warmup).put("window", window))
                    .put("maxInFlight", 3);
            JsonObject summary = new JsonObject()
                    .put("main", main)
                    .put("replay", counts(0, 0, 0))
                    .put("recorderLoss", false)
                    .put("replayStartSec", (double) (WARMUP + WINDOW + 5));
            Files.writeString(dir.resolve("driver/summary.json"), summary.encodePrettily());
        }
        List<String> rows = new ArrayList<>();
        for (int s = 0; s < WARMUP + WINDOW + 5; s++) {
            int scheduled = s < WARMUP + WINDOW ? RATE : 0;
            rows.add(s + "," + scheduled + "," + scheduled + "," + scheduled + ",0,0,"
                    + (scheduled > 0 ? generatorLatenessMs : 0) + ",3");
        }
        Files.writeString(
                dir.resolve("driver/seconds.csv"),
                "sec,scheduled,started,accepted,refused,failed,max_late_ms,in_flight\n" + String.join("\n", rows)
                        + "\n");
    }

    private JsonObject counts(long planned, long refused, long failed) {
        return new JsonObject()
                .put("planned", planned)
                .put("started", planned)
                .put("notSubmitted", 0)
                .put("accepted", planned - refused - failed)
                .put("refused", refused)
                .put("failed", failed)
                .put("unanswered", 0)
                .put("latenessMaxMs", generatorLatenessMs);
    }

    private void writeCollector(Path dir, JsonObject tl) throws IOException {
        long runStart = tl.getLong("runStartMs");
        long runEnd = tl.getLong("runEndMs");
        long loadStart = tl.getLong("loadStartMs");
        List<String> ticks = new ArrayList<>();
        List<String> beats = new ArrayList<>();
        List<String> listeners = new ArrayList<>();
        List<String> host = new ArrayList<>();
        List<String> vm = new ArrayList<>();
        long lastSuccess = runStart;
        for (long t = runStart + 300; t < runEnd; t += 1000) {
            long sec = (t - loadStart) / 1000;
            boolean stalled = stallFromSec >= 0 && sec >= stallFromSec && sec < stallToSec;
            boolean ok = !stalled || stallAnswersTicks;
            if (ok) {
                lastSuccess = t;
                ticks.add(t + ",5,true,," + t + ",1,10,0,0,0,1000,0,0,0,0,0,0,0,30,5,0,0,x=1");
                listeners.add(t + ",true,11,1,load-node-1,workflow");
                listeners.add(t + ",true,12,1,load-node-1,outbox");
                listeners.add(t + ",true,21,1,load-node-2,workflow");
                listeners.add(t + ",true,22,1,load-node-2,outbox");
            } else if (sec % 5 == 0) {
                // A stalled worker answers rarely, and only with an error; a long outage leaves long gaps here.
                ticks.add(t + ",5,false,PSQLException,,,,,,,,,,,,,,,,,,,");
                listeners.add(t + ",false,0,0,,");
            }
            boolean silent = heartbeatGap && sec >= heartbeatGapFromSec && sec < heartbeatGapToSec;
            if (!silent) {
                beats.add(t + ",2," + (ok ? "idle,0," : "querying," + (t - lastSuccess) + ",") + (t - lastSuccess) + ","
                        + (ok ? 0 : 3) + "," + ticks.size() + ",0");
            }
        }
        long independentUntil = loadStart + (WARMUP + independentSeconds) * 1000L;
        int n = 0;
        for (long t = runStart + 100; t < runEnd; t += 1060) {
            boolean independent = independentSeconds > 0 && t >= loadStart + WARMUP * 1000L && t < independentUntil;
            String pid = independent
                    ? Long.toString(90_000 + (independentPidChanges > 0 ? (n / 5) % (independentPidChanges + 1) : 0))
                    : "";
            host.add(t + ",3,10,5,85,1,1,1,1000,0,5,100,1000,50,1," + (kernelBusy ? 300 : 20) + ",0,50,"
                    + (independent ? 400 : 0) + ",10,40," + pid + "," + (independent ? "mvn,mika,400" : ",,"));
            n++;
        }
        for (long t = runStart + 700; t < runEnd; t += 1000) {
            vm.add(t + ",1000,20,8000,5000,0,30,200");
        }
        Files.writeString(
                dir.resolve("collector/seconds.csv"),
                "t_ms,late_ms,db_ok,error,db_clock_ms,rtt_ms,due,outbox_pending,outbox_processing,notification_queue_usage,"
                        + "xact_commit,xact_rollback,deadlocks,blks_read,blks_hit,tup_inserted,tup_updated,temp_files,backends,"
                        + "active,idle_in_transaction,lock_waiting,connections_by_application\n"
                        + String.join("\n", ticks) + "\n");
        Files.writeString(
                dir.resolve("collector/heartbeat.csv"),
                "t_ms,late_ms,worker_state,worker_busy_ms,since_last_success_ms,consecutive_failures,rows_written,recorder_errors\n"
                        + String.join("\n", beats) + "\n");
        Files.writeString(
                dir.resolve("collector/listeners.csv"),
                "t_ms,answered,pid,backend_start_ms,application,channel\n" + String.join("\n", listeners) + "\n");
        Files.writeString(
                dir.resolve("collector/host.csv"),
                "t_ms,load1,cpu_user,cpu_sys,cpu_idle,mem_used_mb,mem_wired_mb,mem_compressor_mb,mem_unused_mb,swapins,"
                        + "swapouts,swap_used_mb,swap_total_mb,mem_free_pct,pressure_level,system_cpu_pct,vm_process_cpu_pct,"
                        + "harness_cpu_pct,independent_cpu_pct,unattributed_cpu_pct,rows_seen,indep_pid,indep_command,"
                        + "indep_user,indep_cpu_pct\n"
                        + String.join("\n", host) + "\n");
        Files.writeString(
                dir.resolve("collector/vm.csv"),
                "t_ms,interval_ms,vm_cpu_busy_pct,vm_mem_total_mb,vm_mem_available_mb,vm_swap_used_mb,"
                        + "container_cpu_pct_of_one_core,container_mem_mb\n" + String.join("\n", vm) + "\n");
        Files.writeString(dir.resolve("collector/procs.csv"), "t_ms,pid,role,rss_kb,cpu_s\n");
        if (lockWatch) {
            Files.writeString(
                    dir.resolve("collector/lockwatch-summary.json"),
                    new JsonObject()
                            .put("intervalMs", 50)
                            .put("samples", 2000)
                            .put("queryErrors", 0)
                            .put("samplesWithWaiters", nodeWaiters > 0 ? 40 : 0)
                            .put(
                                    "waiterObservationsByApplication",
                                    nodeWaiters > 0
                                            ? new JsonObject().put("load-node-2", nodeWaiters)
                                            : new JsonObject())
                            .encodePrettily());
        }
    }

    private void writeSql(Path dir, long loadStart) throws IOException {
        if (!sqlPresent) {
            return;
        }
        JsonArray completions = new JsonArray();
        JsonArray waitEnd = new JsonArray();
        for (long s = loadStart / 1000 + 1; s < loadStart / 1000 + WARMUP + WINDOW + 9; s++) {
            long sec = s - loadStart / 1000 - 1;
            boolean stalled = stallFromSec >= 0 && sec >= stallFromSec && sec < stallToSec;
            boolean dip = dipWithoutCause && sec >= 20 && sec < 24;
            int count = sec < WARMUP + WINDOW + 1 ? RATE : (sec < WARMUP + WINDOW + 3 ? RATE / 2 : 0);
            if (stalled || dip) {
                count = 0;
            }
            if (count > 0) {
                completions.add(new JsonArray().add(s).add(count));
                waitEnd.add(new JsonArray()
                        .add(s)
                        .add(recordedP95)
                        .add((double) s - 1)
                        .add(count)
                        .add(recordedP95 + 5));
            }
        }
        int expected = expectedPerKind;
        JsonObject start = commands("START", expected, expected, expected);
        JsonObject signal = commands(
                "SIGNAL", expected, expected - (int) signalsNeverCreated, expected - (int) signalsNeverCreated);
        JsonObject latency = new JsonObject()
                .put("count", 2 * expected - signalsNeverCreated)
                .put("p50Ms", 7.0)
                .put("p95Ms", recordedP95)
                .put("p99Ms", 100.0)
                .put("maxMs", 120.0);
        JsonObject metrics = new JsonObject()
                .put("snapshotAtEpochSec", loadStart / 1000.0 + 60)
                .put("cohort", new JsonObject().put("starts", expected).put("completedAtSnapshot", expected))
                .put("starts", start)
                .put("signals", signal)
                .put("recordedToSegmentTransactionStart", latency)
                .put(
                        "timers",
                        new JsonObject()
                                .put("expected", expected)
                                .put("neverCreated", 0)
                                .put("total", expected)
                                .put("clean", expected)
                                .put("transactionStartLateness", latency.copy().put("p95Ms", 13.0)))
                .put("completedInWindow", stallFromSec >= 0 ? WINDOW * RATE - 400 : WINDOW * RATE)
                .put("completedPerSecond", stallFromSec >= 0 ? (WINDOW * RATE - 400) / (double) WINDOW : (double) RATE)
                .put("recordedCommandsPerSecondInWindowByKind", new JsonObject().put("START", (double) RATE))
                .put("completionsPerSecond", completions)
                .put("recordedCommandsPerSecond", new JsonArray())
                .put("recordedToSegmentStartByWaitEndSecond", waitEnd)
                .put("instancesByStatus", new JsonObject().put("COMPLETED", (WARMUP + WINDOW) * RATE))
                .put("commandsByKindAndState", new JsonObject())
                .put("outboxByState", new JsonObject());
        Files.writeString(dir.resolve("sql/metrics.json"), metrics.encodePrettily());
    }

    private static JsonObject commands(String kind, int expected, int total, int qualifying) {
        return new JsonObject()
                .put("kind", kind)
                .put("expected", expected)
                .put("neverCreated", expected - total)
                .put("total", total)
                .put("qualifying", qualifying)
                .put("retriedOrDeferred", 0)
                .put("parkedEver", 0)
                .put("unsettled", 0)
                .put("parked", 0)
                .put("dead", 0)
                .put("otherTerminal", 0);
    }
}

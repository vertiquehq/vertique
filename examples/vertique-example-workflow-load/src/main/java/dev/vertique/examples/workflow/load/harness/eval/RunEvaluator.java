// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.driver.LoadProfile;
import dev.vertique.examples.workflow.load.harness.listen.ListenerEvent;
import dev.vertique.examples.workflow.load.harness.listen.ListenerExpectation;
import dev.vertique.examples.workflow.load.harness.listen.ListenerLedger;
import dev.vertique.examples.workflow.load.harness.listen.ListenerLogScan;
import dev.vertique.examples.workflow.load.harness.listen.ListenerReport;
import dev.vertique.examples.workflow.load.harness.listen.ListenerSample;
import dev.vertique.examples.workflow.load.harness.listen.ListenerSession;
import dev.vertique.examples.workflow.load.harness.spec.LoadSpec;
import dev.vertique.examples.workflow.load.harness.spec.RunSpec;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Judges a finished run from the files it left, and writes the verdict.
 *
 * <p>It reads the resolved specification, the timeline, the driver's summary, the collector's files, the JVM logs and
 * the figures read from the database, and writes {@code result.json}, {@code validity.json}, {@code stalls.json} and
 * {@code listeners.json}. Everything it uses is in the run directory, so a run can be judged again, or reviewed, from
 * its raw files alone.
 *
 * <p>The result keeps the system's behavior beside the verdict on validity: refusals, failures, unanswered database
 * ticks, deadlocks, node exits, JVM pauses, resource peaks and every stall with the evidence that coincided with it.
 * None of those makes a run invalid.
 */
public final class RunEvaluator {

    private RunEvaluator() {}

    /**
     * Judges the runs in the directories named on the command line again, from their raw files, and prints each
     * verdict.
     *
     * @param args run directories
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        for (String dir : args) {
            JsonObject result = evaluate(Path.of(dir));
            System.out.println(dir + " " + result.getString("validity") + " "
                    + result.getJsonArray("violations").encode());
        }
    }

    /**
     * Judges the run in a directory and writes the verdict files there.
     *
     * @param dir the run directory
     * @return the content of {@code result.json}
     * @throws IOException if a file cannot be read or written
     */
    public static JsonObject evaluate(Path dir) throws IOException {
        RunSpec spec = RunSpec.parse(readJson(dir.resolve("spec.json")));
        Thresholds th = spec.thresholds();
        JsonObject tl = readJson(dir.resolve("timeline.json"));
        boolean loadNeverStarted = tl.getBoolean("loadNeverStarted", false);
        JsonObject systemFailure = tl.getJsonObject("systemFailure");
        // A run that failed before its load, or whose system could not be operated, has no window to hold to account.
        loadNeverStarted = loadNeverStarted || systemFailure != null;
        long runStart = tl.getLong("runStartMs");
        long runEnd = tl.getLong("runEndMs");
        long loadStart = tl.getLong("loadStartMs");
        long windowStart = tl.getLong("windowStartMs");
        long windowEnd = tl.getLong("windowEndMs");
        double offsetMs = (tl.getDouble("dbOffsetStartMs", 0.0) + tl.getDouble("dbOffsetEndMs", 0.0)) / 2;

        JsonObject driverSummary =
                exists(dir.resolve("driver/summary.json")) ? readJson(dir.resolve("driver/summary.json")) : null;
        CsvTable driverSeconds = CsvTable.read(dir.resolve("driver/seconds.csv"));
        CsvTable ticks = CsvTable.read(dir.resolve("collector/seconds.csv"));
        CsvTable heartbeat = CsvTable.read(dir.resolve("collector/heartbeat.csv"));
        CsvTable statistics = CsvTable.read(dir.resolve("collector/statistics.csv"));
        CsvTable host = CsvTable.read(dir.resolve("collector/host.csv"));
        CsvTable procs = CsvTable.read(dir.resolve("collector/procs.csv"));
        CsvTable vm = CsvTable.read(dir.resolve("collector/vm.csv"));
        CsvTable lockwatch = CsvTable.read(dir.resolve("collector/lockwatch.csv"));
        JsonObject sql = exists(dir.resolve("sql/metrics.json")) ? readJson(dir.resolve("sql/metrics.json")) : null;

        Map<String, List<JvmLog.Pause>> pauses = new LinkedHashMap<>();
        List<String> logsMissing = new ArrayList<>();
        if (!loadNeverStarted) {
            for (int i = 1; i <= spec.nodes(); i++) {
                pausesOf(dir.resolve("nodes/node-" + i + ".jvm.log"), "node-" + i, th, pauses, logsMissing);
            }
            if (spec.nodes() > 0) {
                pausesOf(dir.resolve("driver/driver.jvm.log"), "driver", th, pauses, logsMissing);
            }
            pausesOf(dir.resolve("collector/collector.jvm.log"), "collector", th, pauses, logsMissing);
        }

        // --- Listeners ---
        List<String> apps = new ArrayList<>();
        for (int i = 1; i <= spec.nodes(); i++) {
            apps.add(RunSpec.nodeName(i));
        }
        ListenerReport listenerReport = null;
        if (spec.nodes() > 0 && exists(dir.resolve("collector/listeners.csv"))) {
            listenerReport = ListenerLedger.analyze(
                    listenerSamples(CsvTable.read(dir.resolve("collector/listeners.csv"))),
                    ListenerExpectation.of(spec.arm(), apps, spec.node().relayInstances()),
                    loadStart,
                    tl.getLong("drainEndMs"));
        }
        JsonObject listenersJson =
                listenerReport == null ? new JsonObject().put("skipped", true) : listenerReport.toJson();
        JsonArray logFindings = new JsonArray();
        for (int i = 1; i <= spec.nodes(); i++) {
            for (ListenerLogScan.Finding f : ListenerLogScan.scan(dir.resolve("nodes/node-" + i + ".log"))) {
                // The runner stops the nodes at the end; the product warns when its connections close then.
                boolean atShutdown = tl.containsKey("nodesStopMs") && f.timeMs() >= tl.getLong("nodesStopMs") - 500;
                logFindings.add(f.toJson().put("node", i).put("atShutdown", atShutdown));
            }
        }
        listenersJson.put("nodeLogFindings", logFindings);
        Files.writeString(dir.resolve("listeners.json"), listenersJson.encodePrettily());

        // --- Validity ---
        Validity.GeneratorFacts generator = generatorFacts(driverSummary, spec, loadNeverStarted);
        long interval = spec.collector().sampleIntervalMs();
        long expectedBeats = loadNeverStarted ? 0 : (windowEnd - windowStart) / interval;
        long windowBeats = 0;
        List<Double> beatTimes = new ArrayList<>();
        for (int i = 0; i < heartbeat.size(); i++) {
            double t = heartbeat.number(i, "t_ms");
            if (t >= windowStart && t < windowEnd) {
                windowBeats++;
            }
            if (t >= windowStart - interval && t <= windowEnd + interval) {
                beatTimes.add(t);
            }
        }
        long maxGap = 0;
        for (int i = 1; i < beatTimes.size(); i++) {
            maxGap = Math.max(maxGap, (long) (beatTimes.get(i) - beatTimes.get(i - 1)));
        }
        long recorderErrors = 0;
        for (int i = 0; i < heartbeat.size(); i++) {
            recorderErrors = Math.max(recorderErrors, (long) nz(heartbeat.number(i, "recorder_errors")));
        }
        long hostSeconds = 0;
        long independentRun = 0;
        long runStartT = 0;
        long runLastT = 0;
        String runPid = "";
        for (int i = 0; i < host.size(); i++) {
            double t = host.number(i, "t_ms");
            if (t >= windowStart && t < windowEnd) {
                hostSeconds++;
                String pid = host.text(i, "indep_pid");
                boolean busy = !pid.isEmpty() && host.number(i, "indep_cpu_pct") >= th.outsideCpuPercent();
                if (busy && pid.equals(runPid)) {
                    runLastT = (long) t;
                } else if (busy) {
                    runPid = pid;
                    runStartT = (long) t;
                    runLastT = (long) t;
                } else {
                    runPid = "";
                }
                if (busy) {
                    independentRun = Math.max(independentRun, (runLastT - runStartT) / 1000 + 1);
                }
            }
        }
        boolean hostRequired = spec.collector().hostSampling() && !loadNeverStarted;
        Validity.CollectionFacts collection = new Validity.CollectionFacts(
                loadNeverStarted || exists(dir.resolve("collector/stopped")),
                expectedBeats,
                windowBeats,
                loadNeverStarted ? 0 : maxGap,
                recorderErrors,
                hostRequired,
                loadNeverStarted ? 0 : (windowEnd - windowStart) / 1000,
                hostSeconds,
                independentRun,
                logsMissing);
        List<String> mismatches = new ArrayList<>();
        tl.getJsonArray("configMismatches", new JsonArray()).forEach(m -> mismatches.add(m.toString()));
        Validity.ConfigurationFacts configuration = new Validity.ConfigurationFacts(
                mismatches, tl.getString("startupConfigurationError", ""), tl.getString("preconditionFailure", ""));
        Validity.Result validity = Validity.evaluate(generator, collection, configuration, th);
        Files.writeString(dir.resolve("validity.json"), validity.toJson().encodePrettily());

        JsonObject manifest =
                exists(dir.resolve("manifest.json")) ? readJson(dir.resolve("manifest.json")) : new JsonObject();
        JsonObject result = new JsonObject()
                .put("name", spec.name())
                .put("set", spec.set())
                .put("point", spec.point())
                .put("session", spec.session())
                .put("replicate", spec.replicate())
                .put("attempt", spec.attempt())
                .put("matrixSha256", spec.matrixSha256())
                .put("fingerprint", spec.fingerprint())
                .put("code", manifest.getJsonObject("code", new JsonObject()).getValue("identity"))
                .put("diagnostic", spec.diagnostic())
                .put("arm", spec.arm().name())
                .put("nodes", spec.nodes())
                .put("validity", validity.valid() ? "valid" : "invalid")
                .put("violations", validity.toJson().getJsonArray("violations"))
                .put("injections", injectionsJson(spec))
                .put("observer", spec.observer())
                .put("outcome", systemFailure != null ? "system-failed" : (validity.valid() ? "measured" : "invalid"));
        if (systemFailure != null) {
            result.put("systemFailure", systemFailure);
            result.put(
                    "system",
                    systemResults(spec, tl, driverSummary, ticks, heartbeat, host, vm, procs, pauses, sql, dir));
            result.put("listeners", listenersJson);
            result.put(
                    "gates",
                    new JsonObject()
                            .put(
                                    "note",
                                    "the system could not be operated at this configuration; no gate can be judged")
                            .put(
                                    "claimLatency",
                                    new Gates.Result(
                                                    "claim-latency",
                                                    Gates.Verdict.INCONCLUSIVE,
                                                    "system failed: " + systemFailure.getString("reason"))
                                            .toJson()));
            Files.writeString(dir.resolve("result.json"), result.encodePrettily());
            return result;
        }
        if (loadNeverStarted) {
            result.put("aborted", tl.getString("abortReason", "stopped before the load"));
            Files.writeString(dir.resolve("result.json"), result.encodePrettily());
            return result;
        }
        if (sql == null) {
            result.put("sqlUnavailable", tl.getString("sqlError", "the figures could not be read from the database"));
        }

        // --- Series on one grid of host seconds ---
        long baseSec = Math.floorDiv(runStart, 1000L);
        int n = (int) Math.ceil((runEnd - baseSec * 1000) / 1000.0) + 2;
        double[] completed = new double[n];
        double[] p95WaitEnd = new double[n];
        Arrays.fill(p95WaitEnd, Double.NaN);
        double[] originOfWaitEnd = new double[n];
        Arrays.fill(originOfWaitEnd, Double.NaN);
        double[] depth = new double[n];
        Arrays.fill(depth, Double.NaN);
        if (sql != null) {
            for (Object o : sql.getJsonArray("completionsPerSecond", new JsonArray())) {
                JsonArray pair = (JsonArray) o;
                int idx = gridIndex(pair.getLong(0) * 1000.0 - offsetMs, baseSec);
                if (idx >= 0 && idx < n) {
                    completed[idx] += pair.getDouble(1);
                }
            }
            for (Object o : sql.getJsonArray("recordedToSegmentStartByWaitEndSecond", new JsonArray())) {
                JsonArray row = (JsonArray) o;
                int idx = gridIndex(row.getLong(0) * 1000.0 - offsetMs, baseSec);
                if (idx >= 0 && idx < n) {
                    p95WaitEnd[idx] = row.getDouble(1);
                    originOfWaitEnd[idx] = row.getDouble(2) * 1000.0 - offsetMs;
                }
            }
        }
        for (int i = 0; i < ticks.size(); i++) {
            if ("true".equals(ticks.text(i, "db_ok"))) {
                int idx = gridIndex(ticks.number(i, "t_ms"), baseSec);
                if (idx >= 0 && idx < n) {
                    depth[idx] = ticks.number(i, "due");
                }
            }
        }

        // --- Stalls ---
        List<ListenerEvent> events = listenerReport == null ? List.of() : listenerReport.events();
        Evidence.Sources sources = new Evidence.Sources(
                driverSeconds, loadStart, ticks, heartbeat, host, vm, procs, lockwatch, pauses, events);
        JsonArray stalls = new JsonArray();
        LoadProfile profile = spec.load().profile();
        double skip = spec.load().stepMeasureSkipSec();
        for (int s = 0; s < profile.segmentCount(); s++) {
            if (!profile.segment(s).window()) {
                continue;
            }
            int from = gridIndex(loadStart + (profile.segmentStartSec(s) + skip) * 1000, baseSec);
            int to = gridIndex(loadStart + profile.segmentEndSec(s) * 1000, baseSec);
            if (to - from < 3) {
                continue;
            }
            List<Stalls.Interval> found =
                    new ArrayList<>(Stalls.dips(completed, from, to, th.dipFractionOfTypical(), th.dipMinSeconds()));
            found.addAll(Stalls.excursions(p95WaitEnd, from, to, th.excursionMs(), th.excursionMinSeconds()));
            for (Stalls.Interval stallInterval : found) {
                long fromMs = (baseSec + stallInterval.startIndex()) * 1000;
                long toMs = (baseSec + stallInterval.endIndex()) * 1000;
                long originMs = fromMs;
                if (stallInterval.kind().equals("excursion")) {
                    for (int i = stallInterval.startIndex(); i < stallInterval.endIndex(); i++) {
                        if (!Double.isNaN(originOfWaitEnd[i])) {
                            originMs = Math.min(originMs, (long) originOfWaitEnd[i]);
                        }
                    }
                }
                JsonObject stall = Evidence.collect(stallInterval.kind(), fromMs, toMs, originMs, sources, th);
                stall.put("segment", profile.segment(s).label())
                        .put("seconds", stallInterval.seconds())
                        .put(
                                stallInterval.kind().equals("dip") ? "lowestCompletionsPerSecond" : "highestP95WaitMs",
                                stallInterval.extreme())
                        .put(
                                stallInterval.kind().equals("dip") ? "segmentTypicalCompletionsPerSecond" : "limitMs",
                                stallInterval.reference())
                        .put(
                                "timeAxis",
                                stallInterval.kind().equals("dip")
                                        ? "seconds in which fewer instances completed"
                                        : "seconds in which a slow wait ended; the waits began earlier, at originFromMs");
                stalls.add(stall);
            }
        }
        Files.writeString(dir.resolve("stalls.json"), stalls.encodePrettily());

        // --- Load, completion and metrics ---
        result.put("load", loadFigures(spec, driverSummary, sql));
        if (sql != null) {
            result.put("sql", summarizeSql(sql));
        }
        result.put("idle", idle(vm, windowStart, windowEnd));
        result.put("tableStatistics", statisticsEvolution(statistics, loadStart));
        result.put("stalls", stallSummary(stalls));
        result.put("listeners", listenersJson);
        result.put(
                "system", systemResults(spec, tl, driverSummary, ticks, heartbeat, host, vm, procs, pauses, sql, dir));
        if (driverSummary != null && exists(dir.resolve("sql/durable-starts.csv.gz"))) {
            result.put(
                    "reconciliation",
                    Reconciliation.compute(
                            dir.resolve("driver/arrivals.csv"), dir.resolve("sql/durable-starts.csv.gz"), spec.name()));
        }
        if (spec.load().kind() == LoadSpec.Kind.STAIRCASE) {
            result.put(
                    "staircase",
                    staircase(spec, driverSummary, completed, depth, loadStart, baseSec, th)
                            .toJson());
        }
        if (spec.diagnostic()) {
            result.put("gates", new JsonObject().put("note", "diagnostic set: no gate verdict"));
        } else {
            JsonObject gates = new JsonObject();
            gates.put("claimLatency", claimLatency(sql, th).toJson());
            Gates.Result overload = overload(spec, driverSummary, depth, loadStart, baseSec, n, th);
            if (overload != null) {
                gates.put("overload", overload.toJson());
            }
            gates.put("notificationLock", lock(spec, dir, tl, th).toJson());
            result.put("gates", gates);
        }
        Files.writeString(dir.resolve("result.json"), result.encodePrettily());
        return result;
    }

    // --- Facts ---

    private static Validity.GeneratorFacts generatorFacts(JsonObject summary, RunSpec spec, boolean neverStarted) {
        if (neverStarted || spec.nodes() == 0) {
            return new Validity.GeneratorFacts(true, 0, 0, 0, 0, 0, false);
        }
        if (summary == null) {
            return new Validity.GeneratorFacts(false, 0, 0, 0, 0, 0, false);
        }
        JsonObject main = summary.getJsonObject("main");
        return new Validity.GeneratorFacts(
                true,
                main.getLong("planned", 0L),
                main.getLong("started", 0L),
                main.getLong("notSubmitted", 0L),
                main.getDouble("latenessMaxMs", 0.0),
                main.getLong("lateOverThreshold", 0L),
                summary.getBoolean("recorderLoss", false));
    }

    private static void pausesOf(
            Path file, String source, Thresholds th, Map<String, List<JvmLog.Pause>> pauses, List<String> missing)
            throws IOException {
        Path actual = file;
        if (!exists(actual) && exists(Path.of(file + ".gz"))) {
            actual = Path.of(file + ".gz");
        }
        if (!exists(actual) || Files.size(actual) == 0) {
            missing.add(source);
            return;
        }
        pauses.put(source, JvmLog.read(actual, th.jvmPauseReportMs()));
    }

    private static List<ListenerSample> listenerSamples(CsvTable table) {
        List<ListenerSample> samples = new ArrayList<>();
        long current = Long.MIN_VALUE;
        boolean answered = false;
        List<ListenerSession> sessions = new ArrayList<>();
        for (int i = 0; i < table.size(); i++) {
            long t = (long) table.number(i, "t_ms");
            if (t != current) {
                if (current != Long.MIN_VALUE) {
                    samples.add(new ListenerSample(current, answered, List.copyOf(sessions)));
                }
                current = t;
                sessions = new ArrayList<>();
            }
            answered = "true".equals(table.text(i, "answered"));
            int pid = (int) table.number(i, "pid");
            if (pid > 0) {
                sessions.add(new ListenerSession(
                        pid,
                        (long) table.number(i, "backend_start_ms"),
                        table.text(i, "application"),
                        table.text(i, "channel")));
            }
        }
        if (current != Long.MIN_VALUE) {
            samples.add(new ListenerSample(current, answered, List.copyOf(sessions)));
        }
        return samples;
    }

    // --- Figures ---

    private static JsonObject loadFigures(RunSpec spec, JsonObject driverSummary, JsonObject sql) {
        LoadProfile profile = spec.load().profile();
        long offered = 0;
        for (int s = 0; s < profile.segmentCount(); s++) {
            if (profile.segment(s).window()) {
                offered += profile.segment(s).arrivals();
            }
        }
        double windowSec = profile.windowEndSec() - profile.windowStartSec();
        double offeredPerSecond = windowSec > 0 ? offered / windowSec : 0;
        JsonObject load = new JsonObject()
                .put("kind", spec.load().kind().name())
                .put("windowSec", windowSec)
                .put("offeredStartsInWindow", offered)
                .put("offeredStartsPerSecond", offeredPerSecond)
                .put("offeredCommandEquivalentsPerSecond", offeredPerSecond * LoadSpec.COMMANDS_PER_INSTANCE);
        if (driverSummary != null) {
            JsonObject main = driverSummary.getJsonObject("main");
            long accepted = 0;
            long refused = 0;
            long failed = 0;
            for (int s = 0; s < profile.segmentCount(); s++) {
                if (profile.segment(s).window()) {
                    JsonObject seg = main.getJsonObject("bySegment")
                            .getJsonObject(profile.segment(s).label());
                    accepted += seg.getLong("accepted", 0L);
                    refused += seg.getLong("refused", 0L);
                    failed += seg.getLong("failed", 0L);
                }
            }
            load.put("acceptedInWindow", accepted)
                    .put("refusedInWindow", refused)
                    .put("failedInWindow", failed)
                    .put("acceptedStartsPerSecond", windowSec > 0 ? accepted / windowSec : 0);
        }
        if (sql != null) {
            double completedPerSecond = sql.getDouble("completedPerSecond", Double.NaN);
            load.put("completedInstancesPerSecond", completedPerSecond);
            load.put("completionRate", offered > 0 ? sql.getLong("completedInWindow", 0L) / (double) offered : null);
            load.put(
                    "startsRecordedInWindow",
                    sql.getJsonObject("cohort", new JsonObject()).getValue("starts"));
            load.put(
                    "recordedCommandsPerSecondInWindowByKind", sql.getValue("recordedCommandsPerSecondInWindowByKind"));
            load.put(
                    "populations",
                    new JsonObject()
                            .put(
                                    "completedInstancesPerSecond",
                                    "instances completed during the window, whatever their start")
                            .put(
                                    "recordedCommandsPerSecond",
                                    "commands recorded during the window, by kind, counted from their rows")
                            .put(
                                    "latencyAndCountFigures",
                                    "the cohort: instances whose START was recorded in the window"));
        }
        return load;
    }

    private static JsonObject summarizeSql(JsonObject sql) {
        JsonObject copy = sql.copy();
        for (String series : List.of(
                "completionsPerSecond",
                "recordedCommandsPerSecond",
                "recordedToSegmentStartP95PerSecondMs",
                "recordedToSegmentStartByWaitEndSecond",
                "settledPerSecondByKind")) {
            copy.remove(series);
        }
        return copy;
    }

    private static JsonObject stallSummary(JsonArray stalls) {
        int none = 0;
        for (Object o : stalls) {
            if (((JsonObject) o).getBoolean("noCoincidentCondition", false)) {
                none++;
            }
        }
        JsonArray brief = new JsonArray();
        for (Object o : stalls) {
            JsonObject s = (JsonObject) o;
            brief.add(new JsonObject()
                    .put("kind", s.getString("kind"))
                    .put("cause", "unresolved")
                    .put("segment", s.getString("segment"))
                    .put("fromMs", s.getLong("fromMs"))
                    .put("toMs", s.getLong("toMs"))
                    .put("originFromMs", s.getLong("originFromMs"))
                    .put("seconds", s.getInteger("seconds"))
                    .put("coincident", s.getJsonArray("coincident"))
                    .put("noCoincidentCondition", s.getBoolean("noCoincidentCondition")));
        }
        return new JsonObject()
                .put("count", stalls.size())
                .put("withoutCoincidentCondition", none)
                .put("cause", "unresolved for every stall: a coincident condition is not an explanation")
                .put("detail", "stalls.json")
                .put("list", brief);
    }

    /**
     * How the table statistics evolved during the run. A database that is dropped and created for each run starts without
     * statistics, so the first minutes of a run are a cold start; this shows when the first automatic analyze came and how
     * often it ran, so that a result is read together with the state of the statistics it ran on.
     */
    private static JsonObject statisticsEvolution(CsvTable stats, long loadStart) {
        Map<String, JsonObject> byTable = new TreeMap<>();
        for (int i = 0; i < stats.size(); i++) {
            String table = stats.text(i, "relname");
            JsonObject t = byTable.computeIfAbsent(
                    table, k -> new JsonObject().put("firstAutoanalyzeSecAfterLoadStart", null));
            double at = stats.number(i, "t_ms");
            if (t.getValue("firstAutoanalyzeSecAfterLoadStart") == null && stats.number(i, "autoanalyze_count") > 0) {
                t.put("firstAutoanalyzeSecAfterLoadStart", (at - loadStart) / 1000.0);
            }
            t.put("autoanalyzeCountAtEnd", stats.number(i, "autoanalyze_count"))
                    .put("analyzeCountAtEnd", stats.number(i, "analyze_count"))
                    .put("autovacuumCountAtEnd", stats.number(i, "autovacuum_count"))
                    .put("liveTuplesAtEnd", stats.number(i, "n_live_tup"))
                    .put("modificationsSinceAnalyzeAtEnd", stats.number(i, "n_mod_since_analyze"))
                    .put("lastReadMs", (long) at);
        }
        JsonObject out = new JsonObject();
        byTable.forEach(out::put);
        return new JsonObject()
                .put(
                        "note",
                        "the database is created empty for each run: the statistics start empty and grow with the run")
                .put("tables", out);
    }

    private static JsonObject injectionsJson(RunSpec spec) {
        JsonArray array = new JsonArray();
        for (RunSpec.Injection i : spec.injections()) {
            array.add(new JsonObject()
                    .put("kind", i.kind().name())
                    .put("atSec", i.atSec())
                    .put("durationSec", i.durationSec())
                    .put("node", i.node())
                    .put("channel", i.channel()));
        }
        return new JsonObject().put("list", array);
    }

    /**
     * The CPU of the database container over the window, in percent of one core. An idle cost is this figure net of the
     * same figure of an empty database measured in the same session.
     */
    private static JsonObject idle(CsvTable vm, long windowStart, long windowEnd) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < vm.size(); i++) {
            double t = vm.number(i, "t_ms");
            double cpu = vm.number(i, "container_cpu_pct_of_one_core");
            if (t >= windowStart && t < windowEnd && !Double.isNaN(cpu)) {
                sum += cpu;
                n++;
            }
        }
        return new JsonObject()
                .put("databaseContainerCpuPercentOfOneCoreMean", n == 0 ? null : sum / n)
                .put("samples", n)
                .put("note", "gross; subtract the empty-database floor measured in the same session");
    }

    private static JsonObject systemResults(
            RunSpec spec,
            JsonObject tl,
            JsonObject driverSummary,
            CsvTable ticks,
            CsvTable heartbeat,
            CsvTable host,
            CsvTable vm,
            CsvTable procs,
            Map<String, List<JvmLog.Pause>> pauses,
            JsonObject sql,
            Path dir)
            throws IOException {
        JsonObject out = new JsonObject();
        if (driverSummary != null) {
            JsonObject main = driverSummary.getJsonObject("main");
            out.put(
                    "driver",
                    new JsonObject()
                            .put("accepted", main.getLong("accepted", 0L))
                            .put("refused", main.getLong("refused", 0L))
                            .put("failed", main.getLong("failed", 0L))
                            .put("unanswered", main.getLong("unanswered", 0L))
                            .put("failureDetails", main.getJsonObject("failureDetails"))
                            .put("socketExhaustion", driverSummary.getBoolean("socketExhaustion", false))
                            .put("fileDescriptors", driverSummary.getJsonObject("fileDescriptors"))
                            .put("responseP50Ms", main.getValue("responseP50Ms"))
                            .put("responseP99Ms", main.getValue("responseP99Ms"))
                            .put("responseMaxMs", main.getValue("responseMaxMs"))
                            .put("wireToAnswerP99Ms", main.getValue("wireToAnswerP99Ms"))
                            .put("scheduleToAnswerP50Ms", main.getValue("scheduleToAnswerP50Ms"))
                            .put("scheduleToAnswerP99Ms", main.getValue("scheduleToAnswerP99Ms"))
                            .put("scheduleToAnswerMaxMs", main.getValue("scheduleToAnswerMaxMs"))
                            .put("maxInFlight", main.getInteger("maxInFlight", 0))
                            .put("latenessMaxMs", main.getValue("latenessMaxMs"))
                            .put("lateOverThreshold", main.getLong("lateOverThreshold", 0L))
                            .put(
                                    "requestsSentAgainOnNewConnection",
                                    driverSummary.getValue("requestsSentAgainOnNewConnection"))
                            .put("replay", driverSummary.getJsonObject("replay")));
        }
        int unanswered = 0;
        int longest = 0;
        int run = 0;
        double deadlocksFirst = Double.NaN;
        double deadlocksLast = Double.NaN;
        double maxBackends = 0;
        String maxBackendApps = "";
        double maxDue = 0;
        double maxOutbox = 0;
        double maxQueue = 0;
        double maxTickLate = 0;
        for (int i = 0; i < ticks.size(); i++) {
            if ("true".equals(ticks.text(i, "db_ok"))) {
                run = 0;
                double d = ticks.number(i, "deadlocks");
                deadlocksFirst = Double.isNaN(deadlocksFirst) ? d : deadlocksFirst;
                deadlocksLast = d;
                if (ticks.number(i, "backends") > maxBackends) {
                    maxBackends = ticks.number(i, "backends");
                    maxBackendApps = ticks.text(i, "connections_by_application");
                }
                maxDue = Math.max(maxDue, nz(ticks.number(i, "due")));
                maxOutbox = Math.max(maxOutbox, nz(ticks.number(i, "outbox_pending")));
                maxQueue = Math.max(maxQueue, nz(ticks.number(i, "notification_queue_usage")));
            } else {
                unanswered++;
                run++;
                longest = Math.max(longest, run);
            }
            maxTickLate = Math.max(maxTickLate, nz(ticks.number(i, "late_ms")));
        }
        double longestOutageMs = 0;
        long outstandingBeats = 0;
        double maxHeartbeatLate = 0;
        for (int i = 0; i < heartbeat.size(); i++) {
            longestOutageMs = Math.max(longestOutageMs, nz(heartbeat.number(i, "since_last_success_ms")));
            if ("querying".equals(heartbeat.text(i, "worker_state"))) {
                outstandingBeats++;
            }
            maxHeartbeatLate = Math.max(maxHeartbeatLate, nz(heartbeat.number(i, "late_ms")));
        }
        out.put(
                "database",
                new JsonObject()
                        .put("ticks", ticks.size())
                        .put("unansweredTicks", unanswered)
                        .put("longestUnansweredRun", longest)
                        .put("heartbeats", heartbeat.size())
                        .put("heartbeatsWithAQueryOutstanding", outstandingBeats)
                        .put("longestTimeSinceASuccessfulSampleMs", longestOutageMs)
                        .put("sqlUnavailableAtTheEnd", tl.getString("sqlError"))
                        .put("deadlocks", Double.isNaN(deadlocksLast) ? null : deadlocksLast - deadlocksFirst)
                        .put("peakBackends", maxBackends)
                        .put("connectionsByApplicationAtPeak", maxBackendApps)
                        .put("peakDueDepth", maxDue)
                        .put("peakOutboxPending", maxOutbox)
                        .put("peakNotificationQueueUsage", maxQueue)
                        .put("collectorMaxTickLateMs", maxTickLate)
                        .put("collectorMaxHeartbeatLateMs", maxHeartbeatLate));
        JsonObject hostJson = new JsonObject();
        if (host.size() > 0) {
            hostJson.put("maxLoad1", max(host.numbers("load1")))
                    .put("minCpuIdlePercent", min(host.numbers("cpu_idle")))
                    .put("minMemUnusedMb", min(host.numbers("mem_unused_mb")))
                    .put("maxMemUsedMb", max(host.numbers("mem_used_mb")))
                    .put("maxSwapUsedMb", max(host.numbers("swap_used_mb")))
                    .put("swapoutsDuringRun", last(host.numbers("swapouts")) - first(host.numbers("swapouts")))
                    .put("swapinsDuringRun", last(host.numbers("swapins")) - first(host.numbers("swapins")))
                    .put("maxPressureLevel", max(host.numbers("pressure_level")))
                    .put("minMemFreePercent", min(host.numbers("mem_free_pct")))
                    .put("maxSystemCpuPercent", max(host.numbers("system_cpu_pct")))
                    .put("maxIndependentCpuPercent", max(host.numbers("independent_cpu_pct")))
                    .put("maxUnattributedCpuPercent", max(host.numbers("unattributed_cpu_pct")))
                    .put("processesListedPerSample", max(host.numbers("rows_seen")));
        }
        out.put("host", hostJson);
        JsonObject vmJson = new JsonObject();
        if (vm.size() > 0) {
            vmJson.put("maxCpuBusyPercent", max(vm.numbers("vm_cpu_busy_pct")))
                    .put("minMemAvailableMb", min(vm.numbers("vm_mem_available_mb")))
                    .put("maxSwapUsedMb", max(vm.numbers("vm_swap_used_mb")))
                    .put("maxContainerMemMb", max(vm.numbers("container_mem_mb")))
                    .put("maxContainerCpuPercentOfOneCore", max(vm.numbers("container_cpu_pct_of_one_core")))
                    .put("maxIntervalMs", max(vm.numbers("interval_ms")));
        }
        out.put("vm", vmJson);
        Map<String, Double> rss = new TreeMap<>();
        for (int i = 0; i < procs.size(); i++) {
            rss.merge(procs.text(i, "role"), procs.number(i, "rss_kb") / 1024, Math::max);
        }
        JsonObject rssJson = new JsonObject();
        rss.forEach(rssJson::put);
        out.put("peakRssMbByProcess", rssJson);
        JsonObject jvm = new JsonObject();
        pauses.forEach((source, list) -> {
            double total = 0;
            double max = 0;
            for (JvmLog.Pause p : list) {
                if (p.kind().equals("safepoint")) {
                    total += p.durationMs();
                    max = Math.max(max, p.durationMs());
                }
            }
            jvm.put(
                    source,
                    new JsonObject()
                            .put("pausesKept", list.size())
                            .put("maxSafepointMs", max)
                            .put("totalSafepointMs", total));
        });
        out.put("jvmPauses", jvm);
        JsonObject exits = new JsonObject();
        for (int i = 1; i <= spec.nodes(); i++) {
            Path exit = dir.resolve("nodes/node-" + i + ".exit");
            exits.put("node-" + i, exists(exit) ? Files.readString(exit).trim() : "running at the end");
        }
        out.put("nodeExits", exits);
        out.put(
                "drain",
                new JsonObject()
                        .put("completed", tl.getBoolean("drainCompleted", false))
                        .put("remaining", tl.getValue("drainRemaining")));
        if (sql != null) {
            out.put(
                    "unfinishedRows",
                    new JsonObject()
                            .put("instancesByStatus", sql.getJsonObject("instancesByStatus"))
                            .put("commandsByKindAndState", sql.getJsonObject("commandsByKindAndState"))
                            .put("outboxByState", sql.getJsonObject("outboxByState")));
        }
        return out;
    }

    private static Staircase.Result staircase(
            RunSpec spec,
            JsonObject driverSummary,
            double[] completed,
            double[] depth,
            long loadStart,
            long baseSec,
            Thresholds th) {
        LoadProfile profile = spec.load().profile();
        double skip = spec.load().stepMeasureSkipSec();
        List<Staircase.Step> steps = new ArrayList<>();
        for (int s = 0; s < profile.segmentCount(); s++) {
            LoadProfile.Segment seg = profile.segment(s);
            double measured = seg.durationSec() - skip;
            int from = gridIndex(loadStart + (profile.segmentStartSec(s) + skip) * 1000, baseSec);
            int to = Math.min(gridIndex(loadStart + profile.segmentEndSec(s) * 1000, baseSec), depth.length);
            double done = 0;
            int readings = 0;
            for (int i = from; i < to && i < completed.length; i++) {
                done += completed[i];
                if (!Double.isNaN(depth[i])) {
                    readings++;
                }
            }
            long refused = driverSummary == null
                    ? 0
                    : driverSummary
                            .getJsonObject("main")
                            .getJsonObject("bySegment")
                            .getJsonObject(seg.label())
                            .getLong("refused", 0L);
            steps.add(new Staircase.Step(
                    seg.label(),
                    seg.ratePerSec(),
                    Math.round(seg.ratePerSec() * measured),
                    Math.round(done),
                    measured,
                    Stalls.slope(depth, from, to),
                    readings,
                    refused));
        }
        return Staircase.analyze(steps, th);
    }

    // --- Gates ---

    private static Gates.Result claimLatency(JsonObject sql, Thresholds th) {
        if (sql == null) {
            return new Gates.Result("claim-latency", Gates.Verdict.INCONCLUSIVE, "no figures from the database");
        }
        JsonObject starts = sql.getJsonObject("starts");
        JsonObject signals = sql.getJsonObject("signals");
        JsonObject recorded = sql.getJsonObject("recordedToSegmentTransactionStart");
        JsonObject timers = sql.getJsonObject("timers");
        JsonObject lateness = timers.getJsonObject("transactionStartLateness");
        return Gates.claimLatency(
                new Gates.ClaimLatency(
                        number(recorded.getValue("p95Ms")),
                        recorded.getLong("count", 0L),
                        starts.getLong("expected", 0L) + signals.getLong("expected", 0L),
                        number(lateness.getValue("p95Ms")),
                        timers.getLong("clean", 0L),
                        timers.getLong("expected", 0L)),
                th);
    }

    private static Gates.Result overload(
            RunSpec spec,
            JsonObject driverSummary,
            double[] depth,
            long loadStart,
            long baseSec,
            int n,
            Thresholds th) {
        LoadProfile profile = spec.load().profile();
        int over = -1;
        int under = -1;
        for (int s = 0; s < profile.segmentCount(); s++) {
            if (profile.segment(s).label().equals("over")) {
                over = s;
            } else if (profile.segment(s).label().equals("under")) {
                under = s;
            }
        }
        if (over < 0 || under < 0 || driverSummary == null) {
            return null;
        }
        JsonObject overCounts =
                driverSummary.getJsonObject("main").getJsonObject("bySegment").getJsonObject("over");
        int from = gridIndex(loadStart + profile.segmentStartSec(over) * 1000, baseSec);
        int to = Math.min(gridIndex(loadStart + profile.segmentEndSec(over) * 1000, baseSec), n);
        double peak = 0;
        int readings = 0;
        for (int i = from; i < to; i++) {
            if (!Double.isNaN(depth[i])) {
                peak = Math.max(peak, depth[i]);
                readings++;
            }
        }
        int drop = gridIndex(loadStart + profile.segmentStartSec(under) * 1000, baseSec);
        double[] after = Arrays.copyOfRange(depth, Math.min(drop, n), n - 1);
        double duration = profile.segment(over).durationSec();
        return Gates.overload(
                new Gates.Overload(
                        spec.node().intakeHighMark(),
                        spec.node().intakeLowMark(),
                        spec.node().intakeSampleIntervalMs(),
                        overCounts.getLong("accepted", 0L) / duration,
                        overCounts.getLong("refused", 0L),
                        peak,
                        after,
                        to > from ? (double) readings / (to - from) : 0),
                th);
    }

    private static Gates.Result lock(RunSpec spec, Path dir, JsonObject tl, Thresholds th) throws IOException {
        if (!spec.collector().lockWatch()) {
            return Gates.notificationLock(new Gates.Lock(false, 0, 0, 0, 0, 0, Map.of()), th);
        }
        Path file = dir.resolve("collector/lockwatch-summary.json");
        if (!exists(file)) {
            return new Gates.Result("notification-lock", Gates.Verdict.INCONCLUSIVE, "the lock watch left no summary");
        }
        JsonObject summary = readJson(file);
        Map<String, Long> byApp = new TreeMap<>();
        long node = 0;
        long other = 0;
        JsonObject apps = summary.getJsonObject("waiterObservationsByApplication", new JsonObject());
        for (String app : apps.fieldNames()) {
            long count = apps.getLong(app);
            byApp.put(app, count);
            if (app.startsWith("load-node-")) {
                node += count;
            } else {
                other += count;
            }
        }
        long expected =
                (tl.getLong("runEndMs") - tl.getLong("runStartMs")) / Math.max(1, summary.getLong("intervalMs", 50L));
        return Gates.notificationLock(
                new Gates.Lock(
                        true,
                        summary.getLong("samples", 0L),
                        expected,
                        summary.getLong("queryErrors", 0L),
                        node,
                        other,
                        byApp),
                th);
    }

    // --- Helpers ---

    static int gridIndex(double epochMs, long baseSec) {
        return (int) Math.floor((epochMs - baseSec * 1000.0) / 1000.0);
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static Object max(double[] values) {
        return Arrays.stream(values).filter(v -> !Double.isNaN(v)).max().isPresent()
                ? Arrays.stream(values).filter(v -> !Double.isNaN(v)).max().getAsDouble()
                : null;
    }

    private static Object min(double[] values) {
        return Arrays.stream(values).filter(v -> !Double.isNaN(v)).min().isPresent()
                ? Arrays.stream(values).filter(v -> !Double.isNaN(v)).min().getAsDouble()
                : null;
    }

    private static double first(double[] values) {
        for (double v : values) {
            if (!Double.isNaN(v)) {
                return v;
            }
        }
        return 0;
    }

    private static double last(double[] values) {
        for (int i = values.length - 1; i >= 0; i--) {
            if (!Double.isNaN(values[i])) {
                return values[i];
            }
        }
        return 0;
    }

    private static boolean exists(Path path) {
        return Files.exists(path);
    }

    private static JsonObject readJson(Path file) throws IOException {
        return new JsonObject(Files.readString(file));
    }
}

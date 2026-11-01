// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.examples.workflow.load.harness.collect.HarnessPids;
import dev.vertique.examples.workflow.load.harness.driver.DriverSpec;
import dev.vertique.examples.workflow.load.harness.driver.LoadProfile;
import dev.vertique.examples.workflow.load.harness.eval.RunEvaluator;
import dev.vertique.examples.workflow.load.harness.listen.ListenerExpectation;
import dev.vertique.examples.workflow.load.harness.listen.ListenerLedger;
import dev.vertique.examples.workflow.load.harness.listen.ListenerProbe;
import dev.vertique.examples.workflow.load.harness.listen.ListenerReport;
import dev.vertique.examples.workflow.load.harness.listen.ListenerSample;
import dev.vertique.examples.workflow.load.harness.metrics.ClockOffset;
import dev.vertique.examples.workflow.load.harness.metrics.SqlMetrics;
import dev.vertique.examples.workflow.load.harness.spec.Matrix;
import dev.vertique.examples.workflow.load.harness.spec.NodeConf;
import dev.vertique.examples.workflow.load.harness.spec.RunSpec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one measurement from start to verdict.
 *
 * <p>The steps, in order:
 *
 * <ol>
 *   <li>Read and validate the specification, or build it from the frozen matrix. An unknown or invalid input, a
 *       classpath that is not the one recorded at build time, or an attempt over the retry ceiling ends the run here,
 *       before any process or database is touched.</li>
 *   <li>Reset the database by the stated policy: drop it, create it empty.</li>
 *   <li>In the quiet step, with no node running, migrate once and set the arm's trigger state, reading it back.</li>
 *   <li>Start the nodes with migrations off, wait until each answers ready, and compare what each reports at
 *       {@code GET /effective} with what the run intended. A difference ends the run before any load.</li>
 *   <li>Start the collector, let the nodes settle, and check the listeners.</li>
 *   <li>Start the driver and offer the load; carry out any injected failure at its time.</li>
 *   <li>Drain to a deadline, stop the collector and the nodes, read the figures from the database in one snapshot, and
 *       write the manifest and the verdict.</li>
 * </ol>
 *
 * <p><b>Nothing a run meets goes unrecorded.</b> An attempt record is written before the first step and finished on every
 * exit, and the verdict is written even when a step failed. A failure is classified by what it says about the system:
 * a configuration error that is established independently of the system's behavior (the node reports other values than
 * the run intended, or a node exits with a configuration error of its own) and a failure of the harness's own
 * environment make the run {@code invalid}; a node that does not become ready, a listener that is not in place, or a
 * database that goes away is a failure to operate the intended configuration and is kept as a system-failed result.
 * Reading the figures from a database that is down leaves the run without them and says so; it does not lose the run.
 *
 * <p>Only processes this runner started are signalled, through their handles. The database container must exist
 * already and its name must start with {@code wf017}; the runner never creates or removes containers.
 */
public final class LoadRun {

    private static final String RUNNER_NAME = "load-runner";
    private static final String LAUNCHER = "dev.vertique.launcher.VertiqueApplication";
    private static final String ADMIN = "dev.vertique.examples.workflow.load.admin.LoadAdmin";
    private static final String DRIVER = "dev.vertique.examples.workflow.load.harness.driver.LoadDriver";
    private static final String COLLECTOR = "dev.vertique.examples.workflow.load.harness.collect.Collector";
    private static final long MIN_DESCRIPTORS = 4_096;

    /** A step that failed, with what the failure says about the measurement. */
    static final class PhaseFailure extends Exception {
        private static final long serialVersionUID = 1L;

        /** What a failure says. */
        enum Kind {
            /** The harness's own environment was not there. The run is invalid. */
            PRECONDITION,
            /** A configuration error established independently of the system's behavior. The run is invalid. */
            CONFIGURATION,
            /** The system could not be operated as configured. The run is a system-failed result. */
            SYSTEM
        }

        final transient Kind kind;
        final String phase;
        final transient JsonObject detail;

        PhaseFailure(Kind kind, String phase, String message, JsonObject detail) {
            super(message);
            this.kind = kind;
            this.phase = phase;
            this.detail = detail;
        }

        PhaseFailure(Kind kind, String phase, String message) {
            this(kind, phase, message, new JsonObject());
        }
    }

    private final RunSpec spec;
    private final JsonObject specInput;
    private final Path runDir;
    private final Path moduleDir;
    private final String classpath;
    private final Path pidsFile;
    private final Path matrixFile;
    private final List<Process> started = new ArrayList<>();
    private final Process[] nodes;
    private final int[] ports;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final JsonObject timeline = new JsonObject();
    private final JsonObject attempt = new JsonObject();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private Process driver;
    private Process collector;
    private boolean finished;

    private LoadRun(RunSpec spec, JsonObject specInput, Path runDir, Path moduleDir, Path matrixFile) {
        this.spec = spec;
        this.specInput = specInput;
        this.runDir = runDir;
        this.moduleDir = moduleDir;
        this.matrixFile = matrixFile;
        this.classpath = System.getProperty("java.class.path");
        this.pidsFile = runDir.resolve("pids.txt");
        this.nodes = new Process[spec.nodes() + 1];
        this.ports = new int[spec.nodes() + 1];
    }

    /**
     * Runs one measurement from the command line.
     *
     * <p>Exit codes: 0 when the run finished and its verdict is written (valid, invalid or system-failed), 1 when the
     * harness itself failed, though the attempt record and the verdict are written as far as they could be, 2 when the
     * command line, the specification or the code identity is wrong.
     *
     * @param args {@code (--spec <file> | --matrix <file> --point <id> --replicate <n> --attempt <n>) --out <dir>
     *     [--module-dir <dir>] [--identity <file>]}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one measurement.
     *
     * @param args the command line, see {@link #main}
     * @param out where the verdict line is printed
     * @param err where problems are printed
     * @return the exit code
     */
    public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        Path specFile = null;
        Path matrixFile = null;
        String point = null;
        int replicate = 1;
        int attemptNumber = 1;
        Path outRoot = null;
        Path identityFile = null;
        Path moduleDir = Path.of("").toAbsolutePath();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--spec" -> specFile = Path.of(value(args, ++i));
                    case "--matrix" -> matrixFile = Path.of(value(args, ++i));
                    case "--point" -> point = value(args, ++i);
                    case "--replicate" -> replicate = Integer.parseInt(value(args, ++i));
                    case "--attempt" -> attemptNumber = Integer.parseInt(value(args, ++i));
                    case "--out" -> outRoot = Path.of(value(args, ++i));
                    case "--identity" -> identityFile = Path.of(value(args, ++i));
                    case "--module-dir" -> moduleDir = Path.of(value(args, ++i)).toAbsolutePath();
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
        } catch (RuntimeException e) {
            err.println(e.getMessage() + "; usage: (--spec <file> | --matrix <file> --point <id> --replicate <n>"
                    + " --attempt <n>) --out <dir> [--module-dir <dir>] [--identity <file>]");
            return 2;
        }
        if (outRoot == null || (specFile == null) == (matrixFile == null) || (matrixFile != null && point == null)) {
            err.println("give --out and either --spec, or --matrix with --point");
            return 2;
        }
        RunSpec spec;
        JsonObject input;
        try {
            if (identityFile != null) {
                String mismatch = Identity.mismatch(System.getProperty("java.class.path"), identityFile);
                if (!mismatch.isEmpty()) {
                    err.println("Refusing to run a build that is not the one recorded: " + mismatch);
                    return 2;
                }
            }
            if (matrixFile != null) {
                Matrix matrix = Matrix.load(matrixFile);
                input = matrix.specFor(point, replicate, attemptNumber);
                spec = RunSpec.parse(input);
                String refusal = retryRefusal(outRoot, point, replicate, attemptNumber, spec);
                if (!refusal.isEmpty()) {
                    err.println(refusal);
                    return 2;
                }
            } else {
                input = new JsonObject(Files.readString(specFile));
                spec = RunSpec.parse(input);
            }
            if (spec.nodes() > 0 && !Files.isDirectory(moduleDir.resolve("config"))) {
                throw new ConfigurationException(
                        "No config directory at " + moduleDir.resolve("config") + "; pass --module-dir");
            }
        } catch (IOException | RuntimeException e) {
            err.println("The run is rejected before anything started: " + e.getMessage());
            return 2;
        }
        Path runDir = outRoot.resolve(spec.name());
        try {
            if (Files.exists(runDir) && Files.list(runDir).findAny().isPresent()) {
                err.println("The run directory " + runDir + " is not empty; give the attempt its own name");
                return 2;
            }
            Files.createDirectories(runDir);
            for (String sub : List.of("driver", "collector", "nodes", "sql")) {
                Files.createDirectories(runDir.resolve(sub));
            }
            if (matrixFile != null) {
                Files.copy(matrixFile, runDir.resolve("matrix.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            err.println("Cannot prepare the run directory: " + e);
            return 1;
        }
        LoadRun run = new LoadRun(spec, input, runDir, moduleDir, matrixFile);
        try {
            JsonObject verdict = run.execute();
            out.println(verdict.encode());
            return run.unexpected == null ? 0 : 1;
        } catch (Exception e) {
            err.println("The harness failed: " + e);
            e.printStackTrace(err);
            return 1;
        } finally {
            run.stopAll();
        }
    }

    /**
     * Refuses an attempt that would exceed the retry ceiling, or that repeats a replicate that already has a valid run.
     */
    static String retryRefusal(Path outRoot, String point, int replicate, int attempt, RunSpec spec)
            throws IOException {
        int ceiling = spec.thresholds().maxAttemptsPerReplicate();
        if (attempt > ceiling) {
            return "Attempt " + attempt + " is over the ceiling of " + ceiling + " attempts for point " + point
                    + " replicate " + replicate;
        }
        if (!Files.isDirectory(outRoot)) {
            return "";
        }
        try (var dirs = Files.list(outRoot)) {
            for (Path dir : dirs.toList()) {
                String name = dir.getFileName().toString();
                if (name.startsWith(point + "-r" + replicate + "-a") && Files.exists(dir.resolve("result.json"))) {
                    JsonObject earlier = new JsonObject(Files.readString(dir.resolve("result.json")));
                    if ("valid".equals(earlier.getString("validity"))) {
                        return "Replicate " + replicate + " of " + point + " already has a valid run, " + name
                                + "; it is not run again";
                    }
                }
            }
        }
        return "";
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[index - 1]);
        }
        return args[index];
    }

    // --- The run ---

    private Exception unexpected;
    private boolean loadStarted;
    private boolean collectorStarted;
    private String phase = "setup";

    private JsonObject execute() throws Exception {
        long now = System.currentTimeMillis();
        attempt.put("name", spec.name())
                .put("point", spec.point())
                .put("replicate", spec.replicate())
                .put("attempt", spec.attempt())
                .put("matrixSha256", spec.matrixSha256())
                .put("startedAtMs", now)
                .put("pid", ProcessHandle.current().pid());
        writeAttempt("started");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!finished) {
                try {
                    writeAttempt("interrupted");
                } catch (IOException ignored) {
                    // nothing more can be done at exit
                }
            }
            stopAll();
        }));
        for (String key : List.of(
                "runStartMs",
                "settleStartMs",
                "loadStartMs",
                "windowStartMs",
                "windowEndMs",
                "scheduleEndMs",
                "recoveryEndMs",
                "replayStartMs",
                "driverEndMs",
                "drainEndMs",
                "runEndMs")) {
            timeline.put(key, now);
        }
        timeline.put("dbOffsetStartMs", 0.0).put("dbOffsetEndMs", 0.0);
        timeline.put("configMismatches", new JsonArray()).put("drainCompleted", false);
        Files.writeString(runDir.resolve("spec.json"), spec.resolved().encodePrettily());
        HarnessPids.add(pidsFile, ProcessHandle.current().pid(), RUNNER_NAME);
        try {
            phases();
        } catch (PhaseFailure f) {
            record(f);
        } catch (Exception e) {
            unexpected = e;
            timeline.put("preconditionFailure", "unexpected harness error in " + phase + ": " + e);
        }
        return conclude();
    }

    private void phases() throws Exception {
        step("docker");
        try {
            Docker.requireRunning(spec.database().container());
        } catch (IOException e) {
            throw new PhaseFailure(PhaseFailure.Kind.PRECONDITION, phase, e.getMessage());
        }
        step("limits");
        long descriptors = descriptorLimit();
        timeline.put("fileDescriptorLimit", descriptors);
        if (descriptors < MIN_DESCRIPTORS) {
            throw new PhaseFailure(
                    PhaseFailure.Kind.PRECONDITION,
                    phase,
                    "the soft limit of open files is " + descriptors + ", under " + MIN_DESCRIPTORS
                            + "; raise it with ulimit -n before the run");
        }
        step("database-reset");
        try {
            resetDatabase();
        } catch (SQLException e) {
            throw new PhaseFailure(
                    PhaseFailure.Kind.PRECONDITION, phase, "the database could not be reset: " + e.getMessage());
        }
        step("quiet-step");
        try {
            quietStep();
        } catch (IOException | SQLException e) {
            throw new PhaseFailure(PhaseFailure.Kind.PRECONDITION, phase, "the quiet step failed: " + e.getMessage());
        }
        step("nodes");
        startNodes();
        step("effective");
        List<String> mismatches = compareEffective();
        if (!mismatches.isEmpty()) {
            throw new PhaseFailure(
                    PhaseFailure.Kind.CONFIGURATION,
                    phase,
                    "the configuration of a node differs from what the run intended",
                    new JsonObject().put("mismatches", new JsonArray(new ArrayList<Object>(mismatches))));
        }
        step("collector");
        try {
            startCollector();
        } catch (IOException e) {
            throw new PhaseFailure(
                    PhaseFailure.Kind.PRECONDITION, phase, "the collector did not start: " + e.getMessage());
        }
        step("settle");
        try (Connection c = connect(spec.database().name())) {
            timeline.put("dbOffsetStartMs", ClockOffset.measure(c, 9).offsetMs());
        } catch (SQLException e) {
            throw new PhaseFailure(
                    PhaseFailure.Kind.SYSTEM,
                    phase,
                    "the database did not answer after the nodes started: " + e.getMessage());
        }
        timeline.put("settleStartMs", System.currentTimeMillis());
        Thread.sleep(spec.timeline().settleSec() * 1000L);
        step("listeners-before-load");
        checkListenersBeforeLoad();
        if (spec.diagnostics().statements()) {
            try (Connection c = connect(spec.database().name());
                    Statement s = c.createStatement()) {
                s.execute("SELECT pg_stat_statements_reset()");
            } catch (SQLException e) {
                throw new PhaseFailure(
                        PhaseFailure.Kind.PRECONDITION, phase, "statements could not be reset: " + e.getMessage());
            }
        }
        step("load");
        offerLoad();
        step("drain");
        drain();
    }

    private void step(String name) throws IOException {
        phase = name;
        attempt.put("phase", name);
        writeAttempt("started");
    }

    private void record(PhaseFailure f) {
        switch (f.kind) {
            case PRECONDITION -> timeline.put("preconditionFailure", f.phase + ": " + f.getMessage());
            case CONFIGURATION -> {
                if (f.detail.containsKey("mismatches")) {
                    timeline.put("configMismatches", f.detail.getJsonArray("mismatches"));
                } else {
                    timeline.put("startupConfigurationError", f.phase + ": " + f.getMessage());
                }
            }
            case SYSTEM ->
                timeline.put(
                        "systemFailure",
                        new JsonObject()
                                .put("phase", f.phase)
                                .put("reason", f.getMessage())
                                .mergeIn(f.detail));
        }
    }

    private JsonObject conclude() throws Exception {
        attempt.put("phase", "conclude");
        writeAttempt("started");
        if (collectorStarted) {
            stopCollector();
        }
        long end = System.currentTimeMillis();
        timeline.put("runEndMs", end);
        if (loadStarted) {
            finalReads(end);
        }
        stopNodes();
        timeline.put("loadNeverStarted", !loadStarted);
        Files.writeString(runDir.resolve("timeline.json"), timeline.encodePrettily());
        try {
            writeManifest();
        } catch (RuntimeException e) {
            Files.writeString(runDir.resolve("manifest-error.txt"), e.toString());
        }
        JsonObject result;
        try {
            result = RunEvaluator.evaluate(runDir);
        } catch (IOException | RuntimeException e) {
            // The raw files stay; the run can be judged again from them.
            attempt.put("evaluationError", e.toString());
            unexpected = unexpected == null ? new IOException("The verdict could not be written: " + e, e) : unexpected;
            writeAttempt("failed");
            finished = true;
            return new JsonObject()
                    .put("run", spec.name())
                    .put("validity", "unknown")
                    .put("error", e.toString());
        }
        attempt.put("validity", result.getString("validity")).put("outcome", result.getString("outcome"));
        if (unexpected != null) {
            attempt.put("error", unexpected.toString());
        }
        writeAttempt(unexpected == null ? "finished" : "failed");
        finished = true;
        return summary(result);
    }

    private void writeAttempt(String status) throws IOException {
        attempt.put("status", status).put("updatedAtMs", System.currentTimeMillis());
        if (!status.equals("started")) {
            attempt.put("finishedAtMs", System.currentTimeMillis());
        }
        Path tmp = runDir.resolve("attempt.json.tmp");
        Files.writeString(tmp, attempt.encodePrettily());
        Files.move(
                tmp,
                runDir.resolve("attempt.json"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    // --- Steps ---

    private long descriptorLimit() {
        String text = Shell.run(5, "sh", "-c", "ulimit -n");
        if (text.equals("unlimited")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void resetDatabase() throws SQLException {
        RunSpec.Database d = spec.database();
        try (Connection c = connect(d.adminDatabase());
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + quote(d.name()) + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + quote(d.name()));
        }
    }

    private void quietStep() throws Exception {
        RunSpec.Database d = spec.database();
        List<String> extensions = new ArrayList<>(d.extensions());
        if (spec.diagnostics().statements() && !extensions.contains("pg_stat_statements")) {
            extensions.add("pg_stat_statements");
        }
        if (!extensions.isEmpty()) {
            try (Connection c = connect(d.name());
                    Statement s = c.createStatement()) {
                if (spec.diagnostics().statements()) {
                    try (ResultSet r = s.executeQuery("SHOW shared_preload_libraries")) {
                        r.next();
                        if (!r.getString(1).contains("pg_stat_statements")) {
                            throw new IOException("the server does not preload pg_stat_statements");
                        }
                    }
                }
                for (String extension : extensions) {
                    s.execute("CREATE EXTENSION IF NOT EXISTS " + quote(extension));
                }
            }
        }
        String conf = new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", d.host())
                                .put("port", d.port())
                                .put("database", d.name())
                                .put("user", d.user())
                                .put("password", d.password())
                                .put("maxPoolSize", 4))
                .encode();
        for (List<String> command : List.of(
                List.of("migrate", "--conf", conf),
                List.of("arm", spec.arm().name(), "--conf", conf),
                List.of("triggers", "--conf", conf))) {
            Path log = runDir.resolve("nodes/admin-" + command.get(0) + ".log");
            Process p = startJava(
                    "admin-" + command.get(0), log, List.of("-Xmx256m", "-XX:TieredStopAtLevel=1"), ADMIN, command);
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the admin step " + command.get(0) + " timed out");
            }
            if (p.exitValue() != 0) {
                throw new IOException("the admin step " + command.get(0) + " failed:\n" + Files.readString(log));
            }
        }
    }

    private void startNodes() throws Exception {
        if (spec.nodes() == 0) {
            return;
        }
        for (int i = 1; i <= spec.nodes(); i++) {
            Path portFile = runDir.resolve("nodes/node-" + i + ".port");
            JsonObject overlay = NodeConf.overlay(spec, i, portFile.toString());
            List<String> jvm = new ArrayList<>();
            jvm.add("-Xms" + spec.node().heapMinMb() + "m");
            jvm.add("-Xmx" + spec.node().heapMaxMb() + "m");
            jvm.add("-XX:ActiveProcessorCount=" + spec.node().activeProcessors());
            jvm.addAll(spec.node().jvmFlags());
            jvm.add("-Xlog:gc,safepoint:file=" + runDir.resolve("nodes/node-" + i + ".jvm.log") + ":utctime,uptime");
            int index = i;
            Process p = startJava(
                    "node-" + i,
                    runDir.resolve("nodes/node-" + i + ".log"),
                    jvm,
                    LAUNCHER,
                    List.of("--conf", overlay.encode()));
            nodes[i] = p;
            p.onExit().thenAccept(done -> {
                if (!stopping.get()) {
                    try {
                        Files.writeString(
                                runDir.resolve("nodes/node-" + index + ".exit"),
                                "exit=" + done.exitValue() + " atMs=" + System.currentTimeMillis());
                    } catch (IOException ignored) {
                        // The missing marker reads as "running at the end".
                    }
                }
            });
        }
        long deadline = System.nanoTime() + spec.timeline().startupTimeoutSec() * 1_000_000_000L;
        boolean[] ready = new boolean[spec.nodes() + 1];
        int remaining = spec.nodes();
        while (remaining > 0) {
            for (int i = 1; i <= spec.nodes(); i++) {
                if (ready[i]) {
                    continue;
                }
                if (!nodes[i].isAlive()) {
                    throw startupFailure(i, "the node exited with code " + nodes[i].exitValue());
                }
                if (probeReady(i)) {
                    ready[i] = true;
                    remaining--;
                }
            }
            if (remaining > 0) {
                if (System.nanoTime() > deadline) {
                    int first = 1;
                    while (ready[first]) {
                        first++;
                    }
                    throw startupFailure(
                            first,
                            "the node did not answer ready within "
                                    + spec.timeline().startupTimeoutSec() + " s");
                }
                Thread.sleep(200);
            }
        }
    }

    private boolean probeReady(int node) {
        Path portFile = runDir.resolve("nodes/node-" + node + ".port");
        try {
            if (Files.exists(portFile)) {
                int port = Integer.parseInt(Files.readString(portFile).trim());
                HttpResponse<String> reply = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready"))
                                .timeout(Duration.ofSeconds(3))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (reply.statusCode() == 200) {
                    ports[node] = port;
                    return true;
                }
            }
        } catch (IOException | NumberFormatException e) {
            // not up yet
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * Classifies a node that did not start. A configuration error the node reports itself is established independently of
     * how the system behaves under load; a node that is slow, dead without such an error, or out of memory is a failure
     * to operate the intended configuration.
     */
    private PhaseFailure startupFailure(int node, String what) throws IOException {
        Path log = runDir.resolve("nodes/node-" + node + ".log");
        List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
        String configurationError = lines.stream()
                .filter(l -> l.contains("ConfigurationException"))
                .findFirst()
                .map(l -> l.length() > 300 ? l.substring(0, 300) : l)
                .orElse(null);
        if (configurationError != null) {
            return new PhaseFailure(
                    PhaseFailure.Kind.CONFIGURATION,
                    phase,
                    "node " + node + " reported a configuration error: " + configurationError);
        }
        List<String> tail = lines.subList(Math.max(0, lines.size() - 25), lines.size());
        return new PhaseFailure(
                PhaseFailure.Kind.SYSTEM,
                phase,
                "node " + node + ": " + what,
                new JsonObject().put("node", node).put("logTail", new JsonArray(new ArrayList<Object>(tail))));
    }

    private List<String> compareEffective() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (int i = 1; i <= spec.nodes(); i++) {
            HttpResponse<String> reply;
            try {
                reply = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + "/effective"))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new PhaseFailure(
                        PhaseFailure.Kind.SYSTEM, phase, "node " + i + " did not answer GET /effective: " + e);
            }
            JsonObject effective = new JsonObject(reply.body());
            Files.writeString(runDir.resolve("nodes/node-" + i + ".effective.json"), effective.encodePrettily());
            for (String m : NodeConf.mismatches(NodeConf.intended(spec, i), effective)) {
                mismatches.add("node-" + i + " " + m);
            }
        }
        return mismatches;
    }

    private void startCollector() throws Exception {
        RunSpec.Database d = spec.database();
        RunSpec.Collector c = spec.collector();
        Path dir = runDir.resolve("collector");
        JsonObject collectorSpec = new JsonObject()
                .put("outDir", dir.toString())
                .put("stopFile", runDir.resolve("collector.stop").toString())
                .put("pidsFile", pidsFile.toString())
                .put("host", d.host())
                .put("port", d.port())
                .put("database", d.name())
                .put("user", d.user())
                .put("password", d.password())
                .put("container", d.container())
                .put("sampleIntervalMs", c.sampleIntervalMs())
                .put("depthCap", c.depthCap())
                .put("lockWatch", c.lockWatch())
                .put("lockWatchIntervalMs", c.lockWatchIntervalMs())
                .put("queryTimeoutMs", c.queryTimeoutMs())
                .put("hostSampling", c.hostSampling())
                .put("vmSampling", c.vmSampling())
                .put("topRows", c.topRows());
        Path specFile = dir.resolve("collector-spec.json");
        Files.writeString(specFile, collectorSpec.encodePrettily());
        collector = startJava(
                "collector",
                dir.resolve("collector.out"),
                List.of(
                        "-Xms64m",
                        "-Xmx256m",
                        "-Xlog:gc,safepoint:file=" + dir.resolve("collector.jvm.log") + ":utctime,uptime"),
                COLLECTOR,
                List.of("--spec", specFile.toString()));
        collectorStarted = true;
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!Files.exists(dir.resolve("ready"))) {
            if (!collector.isAlive() || System.nanoTime() > deadline) {
                throw new IOException(
                        "the collector did not start:\n" + Files.readString(dir.resolve("collector.out")));
            }
            Thread.sleep(100);
        }
        timeline.put(
                "runStartMs",
                Long.parseLong(Files.readString(dir.resolve("ready")).trim()));
    }

    /** Checks that the dedicated listeners the arm expects are in place before any load is offered. */
    private void checkListenersBeforeLoad() throws Exception {
        if (spec.nodes() == 0) {
            return;
        }
        List<String> apps = new ArrayList<>();
        for (int i = 1; i <= spec.nodes(); i++) {
            apps.add(RunSpec.nodeName(i));
        }
        ListenerExpectation expectation =
                ListenerExpectation.of(spec.arm(), apps, spec.node().relayInstances());
        String problem = "";
        for (int attemptNo = 0; attemptNo < 5; attemptNo++) {
            try (Connection c = connect(spec.database().name())) {
                long now = System.currentTimeMillis();
                ListenerReport report = ListenerLedger.analyze(
                        List.of(new ListenerSample(now, true, ListenerProbe.probe(c))), expectation, now + 1, now + 2);
                if (report.before()) {
                    return;
                }
                problem = report.beforeDescription();
            } catch (SQLException e) {
                problem = "the database did not answer: " + e.getMessage();
            }
            Thread.sleep(1_000);
        }
        throw new PhaseFailure(
                PhaseFailure.Kind.SYSTEM, phase, "the listeners were not in place before the load: " + problem);
    }

    private void offerLoad() throws Exception {
        LoadProfile profile = spec.load().profile();
        RunSpec.Timeline t = spec.timeline();
        long loadStart = System.currentTimeMillis() + t.driverStartLeadMs();
        timeline.put("loadStartMs", loadStart)
                .put("windowStartMs", loadStart + Math.round(profile.windowStartSec() * 1000))
                .put("windowEndMs", loadStart + Math.round(profile.windowEndSec() * 1000))
                .put("scheduleEndMs", loadStart + Math.round(profile.endSec() * 1000))
                .put("recoveryEndMs", loadStart + Math.round(profile.endSec() * 1000) + t.recoverySec() * 1000L);
        loadStarted = true;
        Files.writeString(runDir.resolve("timeline.json"), timeline.encodePrettily());
        if (spec.nodes() == 0) {
            Thread.sleep(Math.max(0, timeline.getLong("windowEndMs") - System.currentTimeMillis()));
            timeline.put("driverEndMs", System.currentTimeMillis()).put("replayStartMs", System.currentTimeMillis());
            return;
        }
        List<DriverSpec.Ingress> ingresses = new ArrayList<>();
        for (int i = 1; i <= spec.nodes(); i++) {
            ingresses.add(new DriverSpec.Ingress(i, "127.0.0.1", ports[i]));
        }
        DriverSpec driverSpec = new DriverSpec(
                spec.name(),
                runDir.resolve("driver"),
                loadStart,
                ingresses,
                profile,
                t.recoverySec(),
                t.replayRatePerSec(),
                (int) spec.driver().requestTimeoutMs(),
                spec.driver().graceSec(),
                spec.thresholds().lateArrivalMs());
        Path driverFile = runDir.resolve("driver/driver-spec.json");
        Files.writeString(driverFile, driverSpec.toJson().encodePrettily());
        driver = startJava(
                "driver",
                runDir.resolve("driver/driver.out"),
                List.of(
                        "-Xms128m",
                        "-Xmx512m",
                        "-Xlog:gc,safepoint:file=" + runDir.resolve("driver/driver.jvm.log") + ":utctime,uptime"),
                DRIVER,
                List.of("--spec", driverFile.toString()));

        ExecutorService injections =
                Executors.newCachedThreadPool(r -> Thread.ofPlatform().daemon().unstarted(r));
        Injector injector = new Injector(
                spec,
                runDir.resolve("injections.log"),
                i -> nodes[i],
                driver,
                jdbcUrl(spec.database().name()));
        for (RunSpec.Injection injection : spec.injections()) {
            injections.submit(() -> {
                try {
                    Thread.sleep(
                            Math.max(0, loadStart + Math.round(injection.atSec() * 1000) - System.currentTimeMillis()));
                    injector.run(injection);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        long replaySlackMs = Math.round(profile.total() / t.replayRatePerSec() * 1000);
        long limit =
                (long) ((profile.endSec() + t.recoverySec() + 2L * spec.driver().graceSec()) * 1000)
                        + t.driverStartLeadMs()
                        + replaySlackMs
                        + 120_000;
        if (!driver.waitFor(limit, TimeUnit.MILLISECONDS)) {
            driver.destroyForcibly();
            timeline.put("driverTimedOut", true);
        }
        injections.shutdown();
        injections.awaitTermination(30, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();
        timeline.put("driverEndMs", end);
        Path summary = runDir.resolve("driver/summary.json");
        if (Files.exists(summary)) {
            timeline.put(
                    "replayStartMs",
                    loadStart
                            + Math.round(new JsonObject(Files.readString(summary)).getDouble("replayStartSec") * 1000));
        }
    }

    private void drain() throws Exception {
        long deadline = System.currentTimeMillis() + spec.timeline().drainDeadlineSec() * 1000L;
        JsonObject remaining = null;
        try (Connection c = connect(spec.database().name());
                Statement s = c.createStatement()) {
            while (true) {
                remaining = unfinished(s);
                if (remaining.getLong("commands") + remaining.getLong("outbox") + remaining.getLong("instances") == 0) {
                    timeline.put("drainCompleted", true);
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    break;
                }
                Thread.sleep(500);
            }
        } catch (SQLException e) {
            // The database going away while draining is a result of the system and is kept.
            timeline.put("drainError", e.toString());
        }
        timeline.put("drainRemaining", remaining);
        timeline.put("drainEndMs", System.currentTimeMillis());
        // Two more beats, so the last listener reading comes after the drain.
        Thread.sleep(2 * spec.collector().sampleIntervalMs() + 500);
    }

    private static JsonObject unfinished(Statement s) throws SQLException {
        try (ResultSet r = s.executeQuery("SELECT"
                + " (SELECT count(*) FROM (SELECT 1 FROM workflow_commands WHERE state IN ('PENDING','DEFERRED','CLAIMED') LIMIT 1000000) a),"
                + " (SELECT count(*) FROM (SELECT 1 FROM outbox WHERE state IN ('PENDING','PROCESSING') LIMIT 1000000) b),"
                + " (SELECT count(*) FROM (SELECT 1 FROM workflow_instances WHERE status NOT IN"
                + " ('COMPLETED','FAILED','CANCELLED','EXPIRED','COMPENSATED') LIMIT 1000000) c)")) {
            r.next();
            return new JsonObject()
                    .put("commands", r.getLong(1))
                    .put("outbox", r.getLong(2))
                    .put("instances", r.getLong(3));
        }
    }

    private void stopCollector() throws IOException {
        Files.writeString(runDir.resolve("collector.stop"), "stop");
        try {
            if (collector != null && !collector.waitFor(30, TimeUnit.SECONDS)) {
                collector.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            collector.destroyForcibly();
        }
    }

    /** Reads the clock offset and the figures; a database that is down leaves them out and the run says so. */
    private void finalReads(long end) {
        try (Connection c = connect(spec.database().name())) {
            timeline.put("dbOffsetEndMs", ClockOffset.measure(c, 9).offsetMs());
        } catch (SQLException e) {
            timeline.put("sqlError", "clock offset: " + e.getMessage());
            return;
        }
        double offset = (timeline.getDouble("dbOffsetStartMs") + timeline.getDouble("dbOffsetEndMs")) / 2;
        try (Connection c = connect(spec.database().name())) {
            SqlMetrics.Report report = SqlMetrics.read(
                    c,
                    (timeline.getLong("windowStartMs") + offset) / 1000.0,
                    (timeline.getLong("windowEndMs") + offset) / 1000.0,
                    (timeline.getLong("runStartMs") + offset) / 1000.0,
                    (end + offset) / 1000.0,
                    spec.node().handlerDelayMs(),
                    relayLeaseTimeoutMs(),
                    runDir.resolve("sql/durable-starts.csv.gz"));
            Files.writeString(
                    runDir.resolve("sql/metrics.json"), report.toJson().encodePrettily());
            timeline.put("snapshotAtEpochSec", report.snapshotAtEpochSec());
        } catch (SQLException | IOException e) {
            timeline.put("sqlError", "metrics: " + e.getMessage());
        }
        if (spec.diagnostics().statements()) {
            try (Connection c = connect(spec.database().name())) {
                Files.writeString(
                        runDir.resolve("sql/statements.json"),
                        Statements.read(c).encodePrettily());
            } catch (SQLException | IOException e) {
                timeline.put("statementsError", e.getMessage());
            }
        }
    }

    private long relayLeaseTimeoutMs() {
        long lease = spec.node()
                .confOverride()
                .getJsonObject("inboxOutbox", new JsonObject())
                .getJsonObject("relay", new JsonObject())
                .getLong("leaseTimeoutMs", 0L);
        if (lease > 0) {
            return lease;
        }
        for (int i = 1; i <= spec.nodes(); i++) {
            Path effective = runDir.resolve("nodes/node-" + i + ".effective.json");
            try {
                if (Files.exists(effective)) {
                    return new JsonObject(Files.readString(effective))
                            .getJsonObject("relay")
                            .getLong("leaseTimeoutMs");
                }
            } catch (IOException | RuntimeException e) {
                // fall through to the default
            }
        }
        return 30_000L;
    }

    private void writeManifest() throws IOException {
        Files.writeString(
                runDir.resolve("manifest.json"),
                RunManifest.build(spec, specInput, timeline, moduleDir, classpath, matrixFile)
                        .encodePrettily());
    }

    private static JsonObject summary(JsonObject result) {
        return new JsonObject()
                .put("run", result.getString("name"))
                .put("validity", result.getString("validity"))
                .put("outcome", result.getString("outcome"))
                .put("violations", result.getJsonArray("violations"))
                .put("systemFailure", result.getValue("systemFailure"))
                .put("gates", result.getValue("gates"))
                .put(
                        "completedInstancesPerSecond",
                        result.getJsonObject("load", new JsonObject()).getValue("completedInstancesPerSecond"))
                .put("stalls", result.getJsonObject("stalls", new JsonObject()).getValue("count"));
    }

    // --- Processes and connections ---

    private Process startJava(String name, Path log, List<String> jvmArgs, String mainClass, List<String> args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(moduleDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment()
                .put("VERTX_CONFIG_LOCATIONS", moduleDir.resolve("config").toString());
        Process process = builder.start();
        synchronized (started) {
            started.add(process);
        }
        HarnessPids.add(pidsFile, process.pid(), name);
        return process;
    }

    private void stopNodes() {
        if (!stopping.getAndSet(true)) {
            timeline.put("nodesStopMs", System.currentTimeMillis());
        }
        for (int i = 1; i < nodes.length; i++) {
            if (nodes[i] != null) {
                nodes[i].destroy();
            }
        }
        for (int i = 1; i < nodes.length; i++) {
            if (nodes[i] != null) {
                try {
                    if (!nodes[i].waitFor(20, TimeUnit.SECONDS)) {
                        nodes[i].destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    nodes[i].destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void stopAll() {
        stopNodes();
        synchronized (started) {
            for (Process p : started) {
                if (p.isAlive()) {
                    p.destroy();
                }
            }
        }
    }

    private String jdbcUrl(String database) {
        RunSpec.Database d = spec.database();
        return "jdbc:postgresql://" + d.host() + ":" + d.port() + "/" + database + "?ApplicationName=" + RUNNER_NAME
                + "&sslmode=disable&connectTimeout=5&socketTimeout=60";
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl(database), spec.database().user(), spec.database().password());
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}

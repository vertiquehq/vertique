// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.examples.workflow.load.config.LoadArm;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything one run is told: the arm, the node count, the load, the timeline, the node, database, driver and
 * collector settings, the thresholds that decide validity, and any injected failure.
 *
 * <p>The specification is read strictly by {@link #parse(JsonObject)}: an input the harness does not know fails the
 * run before anything starts, and the resolved tree, which holds every value including the defaults, is what the
 * manifest records.
 *
 * @param name the name of the run; letters, digits, dot, underscore and hyphen
 * @param set the name of the measurement set the run belongs to
 * @param point the point of the run matrix the run is one attempt at; its own name when not given
 * @param session the measuring session; an idle cost is net of a floor measured in the same session
 * @param diagnostic whether the set is diagnostic and carries no gate verdict
 * @param replicate the replicate of the point this run belongs to, from 1
 * @param attempt the attempt at that replicate, from 1; a replicate is retried at most twice
 * @param matrixSha256 the hash of the frozen matrix file the run was built from, or empty
 * @param arm the operating configuration
 * @param nodes the node processes; zero measures the database alone
 * @param load the load offered
 * @param timeline the phases around the load
 * @param node the settings every node shares
 * @param database the database and its reset policy
 * @param collector the collector settings
 * @param driver the driver settings
 * @param thresholds the thresholds of validity and judgement
 * @param observer the observer bound for the run, or {@code none}
 * @param injections the failures to inject, none in a measurement
 * @param diagnostics extra recording that changes the database setup, none in a measurement
 * @param resolved every value of the specification, defaults included
 */
public record RunSpec(
        String name,
        String set,
        String point,
        String session,
        boolean diagnostic,
        int replicate,
        int attempt,
        String matrixSha256,
        LoadArm arm,
        int nodes,
        LoadSpec load,
        Timeline timeline,
        NodeSpec node,
        Database database,
        Collector collector,
        Driver driver,
        Thresholds thresholds,
        String observer,
        List<Injection> injections,
        Diagnostics diagnostics,
        JsonObject resolved) {

    private static final Pattern RUN_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern CONTAINER = Pattern.compile("wf017[A-Za-z0-9_.-]*");

    /**
     * The phases around the load.
     *
     * @param settleSec the seconds nodes run before load, with the listeners checked
     * @param recoverySec the seconds after the load in which nothing is offered
     * @param replayRatePerSec the rate at which refused starts are replayed afterwards
     * @param drainDeadlineSec the longest wait for the work to finish after the last arrival
     * @param startupTimeoutSec the longest wait for every node to answer ready
     * @param driverStartLeadMs the time between starting the driver and its first arrival
     */
    public record Timeline(
            int settleSec,
            int recoverySec,
            double replayRatePerSec,
            int drainDeadlineSec,
            int startupTimeoutSec,
            int driverStartLeadMs) {}

    /** How a run resets its database. */
    public enum ResetPolicy {
        /** Drop the database, create it empty, migrate once, set the arm; no analyze, default autovacuum. */
        DROP_CREATE
    }

    /**
     * The database the run uses.
     *
     * @param container the name of the container, which must start with {@code wf017}
     * @param host the host the database is published on
     * @param port the published port
     * @param adminDatabase the database the reset connects to
     * @param name the database of the run
     * @param user the user every process connects as
     * @param password the password of the user
     * @param reset the reset policy
     * @param extensions the extensions created in the run's database in the quiet step
     */
    public record Database(
            String container,
            String host,
            int port,
            String adminDatabase,
            String name,
            String user,
            String password,
            ResetPolicy reset,
            List<String> extensions) {}

    /**
     * The collector settings.
     *
     * @param sampleIntervalMs the time between two samples
     * @param depthCap the most rows a depth reading counts
     * @param lockWatch whether the lock watch runs; off in idle runs
     * @param lockWatchIntervalMs the time between two lock watch samples
     * @param queryTimeoutMs the longest a collector query may take
     * @param hostSampling whether the host is sampled
     * @param vmSampling whether the database VM is sampled
     * @param topRows the processes listed per host sample
     */
    public record Collector(
            long sampleIntervalMs,
            int depthCap,
            boolean lockWatch,
            long lockWatchIntervalMs,
            long queryTimeoutMs,
            boolean hostSampling,
            boolean vmSampling,
            int topRows) {}

    /**
     * The driver settings.
     *
     * @param requestTimeoutMs the longest a start may take before it counts as failed
     * @param graceSec the wait for outstanding starts after the last arrival
     */
    public record Driver(long requestTimeoutMs, int graceSec) {}

    /**
     * Extra recording for a diagnostic run.
     *
     * @param statements whether the run records every statement the database ran, with its calls and time; it needs
     *     the {@code pg_stat_statements} library loaded by the server and changes the database setup
     */
    public record Diagnostics(boolean statements) {}

    /** The failures a run can inject. */
    public enum InjectionKind {
        /** Holds an exclusive lock on the command table. */
        DB_TABLE_LOCK,
        /** Stops a node process, then resumes it. */
        NODE_PAUSE,
        /** Stops the driver process, then resumes it. */
        DRIVER_PAUSE,
        /** Terminates the database backend of one dedicated listener of a node. */
        LISTENER_KILL,
        /** Stops the database container, and starts it again after the duration. */
        DB_CONTAINER_STOP
    }

    /**
     * One injected failure.
     *
     * @param kind the failure
     * @param atSec the time after the start of the load
     * @param durationSec how long the failure lasts
     * @param node the node it concerns, from 1; zero when none
     * @param channel for a listener kill, {@code workflow} or {@code outbox}
     */
    public record Injection(InjectionKind kind, double atSec, double durationSec, int node, String channel) {}

    /**
     * Parses and validates a specification.
     *
     * @param json the specification
     * @return the specification, with its resolved tree
     * @throws ConfigurationException naming the first unknown or invalid input
     */
    public static RunSpec parse(JsonObject json) {
        SpecReader r = SpecReader.root(json);
        String name = r.requiredString("name");
        if (!RUN_NAME.matcher(name).matches()) {
            throw new ConfigurationException("Input name must be 1 to 64 characters of letters, digits, . _ and -");
        }
        String set = r.string("set", "adhoc");
        String point = r.string("point", name);
        String session = r.string("session", "");
        boolean diagnostic = r.bool("diagnostic", false);
        int replicate = r.intValue("replicate", 1, 1, 1000);
        int attempt = r.intValue("attempt", 1, 1, 100);
        String matrixSha256 = r.string("matrixSha256", "");
        LoadArm arm = LoadArm.parse(r.requiredString("arm"));
        r.resolved().put("arm", arm.name());
        int nodes = r.intValue("nodes", 1, 0, 64);
        LoadSpec load = LoadSpec.read(r.object("load"));
        SpecReader t = r.object("timeline");
        Timeline timeline = new Timeline(
                t.intValue("settleSec", 15, 0, 3_600),
                t.intValue("recoverySec", 30, 0, 3_600),
                t.doubleValue("replayRatePerSec", 50, 0.001, 100_000),
                t.intValue("drainDeadlineSec", 60, 1, 7_200),
                t.intValue("startupTimeoutSec", 120, 5, 3_600),
                t.intValue("driverStartLeadMs", 3_000, 500, 600_000));
        NodeSpec node = NodeSpec.read(r.object("node"));
        SpecReader d = r.object("database");
        String container = d.string("container", "wf017-pg");
        if (!CONTAINER.matcher(container).matches()) {
            throw new ConfigurationException("Input database.container must start with wf017");
        }
        // The throwaway database of the harness script has a password equal to its user name.
        String user = d.string("user", "postgres");
        Database database = new Database(
                container,
                d.string("host", "127.0.0.1"),
                d.intValue("port", 55417, 1, 65_535),
                d.string("adminDatabase", "postgres"),
                d.string("name", "workflow_load"),
                user,
                d.string("password", user),
                d.enumValue("reset", ResetPolicy.class, ResetPolicy.DROP_CREATE),
                d.stringList("extensions", List.of()));
        SpecReader c = r.object("collector");
        Collector collector = new Collector(
                c.longValue("sampleIntervalMs", 1_000, 100, 60_000),
                c.intValue("depthCap", 100_000, 10, 100_000_000),
                c.bool("lockWatch", false),
                c.longValue("lockWatchIntervalMs", 50, 10, 10_000),
                c.longValue("queryTimeoutMs", 2_000, 100, 60_000),
                c.bool("hostSampling", true),
                c.bool("vmSampling", true),
                c.intValue("topRows", 40, 1, 200));
        SpecReader dr = r.object("driver");
        Driver driver = new Driver(
                dr.longValue("requestTimeoutMs", 10_000, 100, 600_000), dr.intValue("graceSec", 20, 1, 3_600));
        Thresholds thresholds = Thresholds.read(r.object("thresholds"));
        String observer = r.string("observer", "none");
        List<Injection> injections = new ArrayList<>();
        for (SpecReader i : r.objects("injections")) {
            injections.add(new Injection(
                    i.enumValue("kind", InjectionKind.class, null),
                    i.requiredDouble("atSec", 0, 86_400),
                    i.doubleValue("durationSec", 3, 0.1, 3_600),
                    i.intValue("node", 0, 0, 64),
                    i.string("channel", "workflow")));
        }
        Diagnostics diagnostics = new Diagnostics(r.object("diagnostics").bool("statements", false));
        r.finish();
        validate(name, diagnostic, nodes, load, node, collector, observer, injections, set, diagnostics);
        return new RunSpec(
                name,
                set,
                point,
                session,
                diagnostic,
                replicate,
                attempt,
                matrixSha256,
                arm,
                nodes,
                load,
                timeline,
                node,
                database,
                collector,
                driver,
                thresholds,
                observer,
                List.copyOf(injections),
                diagnostics,
                r.resolved());
    }

    private static void validate(
            String name,
            boolean diagnostic,
            int nodes,
            LoadSpec load,
            NodeSpec node,
            Collector collector,
            String observer,
            List<Injection> injections,
            String set,
            Diagnostics diagnostics) {
        if (nodes == 0 && load.kind() != LoadSpec.Kind.NONE) {
            throw new ConfigurationException("A run without nodes cannot offer load");
        }
        if (!injections.isEmpty() && !diagnostic) {
            throw new ConfigurationException("A run with injected failures must be marked diagnostic");
        }
        if (diagnostics.statements() && !diagnostic) {
            throw new ConfigurationException("A run that records statements must be marked diagnostic");
        }
        if (!"none".equals(observer) && !diagnostic) {
            throw new ConfigurationException("A run that binds an observer must be marked diagnostic");
        }
        for (Injection injection : injections) {
            boolean needsNode =
                    injection.kind() == InjectionKind.NODE_PAUSE || injection.kind() == InjectionKind.LISTENER_KILL;
            if (needsNode && (injection.node() < 1 || injection.node() > nodes)) {
                throw new ConfigurationException("Injection " + injection.kind() + " needs a node from 1 to " + nodes);
            }
            if (injection.kind() == InjectionKind.LISTENER_KILL
                    && !List.of("workflow", "outbox").contains(injection.channel())) {
                throw new ConfigurationException("Injection channel must be workflow or outbox");
            }
        }
        if (node.heapMinMb() > node.heapMaxMb()) {
            throw new ConfigurationException("heapMinMb exceeds heapMaxMb in " + name);
        }
        if (collector.lockWatch() && nodes == 0) {
            throw new ConfigurationException("The lock watch needs nodes");
        }
        if (collector.lockWatch() && "idle".equals(set)) {
            throw new ConfigurationException("The lock watch is off in idle runs");
        }
    }

    private static final java.util.Set<String> IDENTITY_KEYS =
            java.util.Set.of("name", "attempt", "replicate", "point", "session", "matrixSha256", "set", "diagnostic");

    /**
     * Returns the fingerprint of the configuration of this run: a hash of everything that is not the identity of the
     * run, so that two runs compare only when their fingerprints are equal. The name, the attempt, the replicate, the
     * point, the session and the matrix hash are identity and are left out.
     *
     * @return the SHA-256 in hexadecimal
     */
    public String fingerprint() {
        return Canonical.sha256(resolved, IDENTITY_KEYS);
    }

    /**
     * Returns the application name of a node.
     *
     * @param index the node, from 1
     * @return the name its connections carry
     */
    public static String nodeName(int index) {
        return "load-node-" + index;
    }
}

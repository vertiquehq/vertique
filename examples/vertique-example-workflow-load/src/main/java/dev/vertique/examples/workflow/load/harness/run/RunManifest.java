// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import dev.vertique.examples.workflow.load.harness.spec.RunSpec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Writes what a run was: the code, the configuration, the arm, the node count, the load, the database image and
 * settings, the JVM and thread-pool settings, the host, the reset policy and any bound observer.
 *
 * <p>The resolved specification holds every input including the defaults, so nothing a run used is implicit. The
 * manifest adds what the specification cannot know: the commit of the code, the host, the container and the
 * database's own settings.
 */
final class RunManifest {

    private RunManifest() {}

    static JsonObject build(
            RunSpec spec, JsonObject input, JsonObject timeline, Path moduleDir, String classpath, Path matrixFile) {
        JsonObject manifest = new JsonObject();
        manifest.put("run", spec.name())
                .put("set", spec.set())
                .put("diagnostic", spec.diagnostic())
                .put("attempt", spec.attempt())
                .put("arm", spec.arm().name())
                .put("nodes", spec.nodes())
                .put("observerBound", spec.observer())
                .put(
                        "databaseResetPolicy",
                        spec.database().reset().name()
                                + ": drop the database, create it empty, migrate once, set the arm; no analyze, default autovacuum")
                .put("input", input)
                .put("resolvedSpecification", spec.resolved())
                .put("thresholds", spec.resolved().getJsonObject("thresholds"))
                .put("timeline", timeline)
                .put("code", code(moduleDir, classpath))
                .put("fingerprint", spec.fingerprint())
                .put("replicate", spec.replicate())
                .put(
                        "matrix",
                        new JsonObject()
                                .put("sha256", spec.matrixSha256())
                                .put("file", matrixFile == null ? null : "matrix.json"))
                .put("fileDescriptorLimit", timeline.getValue("fileDescriptorLimit"))
                .put(
                        "diagnostics",
                        new JsonObject()
                                .put("statements", spec.diagnostics().statements())
                                .put(
                                        "extensions",
                                        new JsonArray(new java.util.ArrayList<Object>(
                                                spec.database().extensions()))))
                .put(
                        "cpuQuota",
                        "none: -XX:ActiveProcessorCount sets the JVM's own sizing and grants no CPU; nodes, driver, collector and the database VM share the host's cores")
                .put("nodeJvm", nodeJvm(spec))
                .put("host", host())
                .put("database", database(spec))
                .put(
                        "collection",
                        new JsonObject()
                                .put(
                                        "clock",
                                        "host epoch milliseconds; database times are moved by the offset in timeline")
                                .put("sampleIntervalMs", spec.collector().sampleIntervalMs())
                                .put("lockWatch", spec.collector().lockWatch())
                                .put(
                                        "cost",
                                        "the collector reads the database once per interval and the host with top and ps"
                                                + " once per second; a lock watch adds a query per lock watch interval"));
        return manifest;
    }

    private static JsonObject code(Path moduleDir, String classpath) {
        String commit = Shell.run(10, moduleDir, "git", "rev-parse", "HEAD");
        String status = Shell.run(10, moduleDir, "git", "status", "--porcelain");
        return new JsonObject()
                .put("commit", commit.isEmpty() ? "unknown" : commit)
                .put("harnessAndProductAreOneRepository", true)
                .put("uncommittedChanges", !status.isEmpty())
                .put("branch", Shell.run(10, moduleDir, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .put("identity", identity(classpath));
    }

    private static JsonObject identity(String classpath) {
        try {
            return Identity.compute(classpath);
        } catch (java.io.IOException e) {
            return new JsonObject().put("error", e.toString());
        }
    }

    private static JsonObject nodeJvm(RunSpec spec) {
        JsonArray flags = new JsonArray();
        flags.add("-Xms" + spec.node().heapMinMb() + "m");
        flags.add("-Xmx" + spec.node().heapMaxMb() + "m");
        flags.add("-XX:ActiveProcessorCount=" + spec.node().activeProcessors());
        spec.node().jvmFlags().forEach(flags::add);
        flags.add("-Xlog:gc,safepoint (utctime, uptime) per node");
        return new JsonObject()
                .put("flags", flags)
                .put("javaVersion", System.getProperty("java.version"))
                .put("javaVendor", System.getProperty("java.vendor"))
                .put(
                        "vertxThreads",
                        new JsonObject()
                                .put("eventLoop", spec.node().eventLoopThreads())
                                .put("worker", spec.node().workerThreads())
                                .put("internalBlocking", spec.node().internalBlockingThreads()))
                .put("connectionPoolSize", spec.node().poolSize());
    }

    private static JsonObject host() {
        return new JsonObject()
                .put("model", Shell.run(5, "sysctl", "-n", "hw.model"))
                .put("logicalCpus", Runtime.getRuntime().availableProcessors())
                .put("memoryBytes", Shell.run(5, "sysctl", "-n", "hw.memsize"))
                .put("os", Shell.run(5, "sw_vers", "-productVersion"))
                .put("kernel", Shell.run(5, "uname", "-sr"))
                .put("docker", Docker.info())
                .put(
                        "topology",
                        "nodes, driver and collector run on the host; PostgreSQL runs in a container of the Docker VM");
    }

    private static JsonObject database(RunSpec spec) {
        JsonObject db = new JsonObject()
                .put("container", spec.database().container())
                .put("containerImage", Docker.describe(spec.database().container()));
        String url = "jdbc:postgresql://" + spec.database().host() + ":"
                + spec.database().port() + "/" + spec.database().name()
                + "?ApplicationName=load-runner&sslmode=disable&connectTimeout=5";
        try (Connection c = DriverManager.getConnection(
                        url, spec.database().user(), spec.database().password());
                Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT version()")) {
                r.next();
                db.put("version", r.getString(1));
            }
            JsonObject settings = new JsonObject();
            try (ResultSet r = s.executeQuery(
                    "SELECT name, setting, unit FROM pg_settings WHERE source <> 'default' ORDER BY name")) {
                while (r.next()) {
                    settings.put(
                            r.getString(1),
                            r.getString(2) + (r.getString(3) == null ? "" : " " + r.getString(3))
                                    + " (setting in unit)");
                }
            }
            db.put("nonDefaultSettings", settings);
        } catch (SQLException e) {
            db.put("settingsError", e.toString());
        }
        return db;
    }
}

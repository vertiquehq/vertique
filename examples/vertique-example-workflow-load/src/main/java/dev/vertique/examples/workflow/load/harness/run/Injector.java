// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import dev.vertique.examples.workflow.load.harness.listen.ListenerProbe;
import dev.vertique.examples.workflow.load.harness.listen.ListenerSession;
import dev.vertique.examples.workflow.load.harness.spec.RunSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Carries out the failures a diagnostic run asks for, at the time it asks for them, and writes down when each began
 * and ended.
 *
 * <p>A stalled database, a stopped node, a stopped driver and a terminated listener backend are the failures the
 * harness must tell apart: the first, second and fourth are results of the system, the third spoils the measurement.
 * Only processes this harness started are signalled, by their handles.
 */
final class Injector {

    private final RunSpec spec;
    private final Path log;
    private final IntFunction<Process> nodes;
    private final Process driver;
    private final String jdbcUrl;

    Injector(RunSpec spec, Path log, IntFunction<Process> nodes, Process driver, String jdbcUrl) {
        this.spec = spec;
        this.log = log;
        this.nodes = nodes;
        this.driver = driver;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Runs one injection; returns when it has ended.
     *
     * @param injection the failure
     * @throws InterruptedException if interrupted
     */
    void run(RunSpec.Injection injection) throws InterruptedException {
        long began = System.currentTimeMillis();
        String outcome = "ok";
        try {
            switch (injection.kind()) {
                case DB_TABLE_LOCK -> tableLock(injection);
                case NODE_PAUSE -> pause(nodes.apply(injection.node()), injection);
                case DRIVER_PAUSE -> pause(driver, injection);
                case LISTENER_KILL -> kill(injection);
                case DB_CONTAINER_STOP -> stopContainer(injection);
            }
        } catch (IOException | SQLException | RuntimeException e) {
            outcome = "failed: " + e;
        }
        record(injection, began, System.currentTimeMillis(), outcome);
    }

    private void tableLock(RunSpec.Injection injection) throws SQLException, InterruptedException {
        try (Connection c = DriverManager.getConnection(
                jdbcUrl.replace("ApplicationName=load-runner", "ApplicationName=load-injector"),
                spec.database().user(),
                spec.database().password())) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("LOCK TABLE workflow_commands IN ACCESS EXCLUSIVE MODE");
            }
            Thread.sleep((long) (injection.durationSec() * 1000));
            c.commit();
        }
    }

    private static void pause(Process process, RunSpec.Injection injection) throws IOException, InterruptedException {
        if (process == null || !process.isAlive()) {
            throw new IOException("no live process to pause");
        }
        signal("-STOP", process.pid());
        try {
            Thread.sleep((long) (injection.durationSec() * 1000));
        } finally {
            signal("-CONT", process.pid());
        }
    }

    private static void signal(String signal, long pid) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("kill", signal, Long.toString(pid))
                .inheritIO()
                .start();
        if (p.waitFor() != 0) {
            throw new IOException("kill " + signal + " " + pid + " failed");
        }
    }

    private void stopContainer(RunSpec.Injection injection) throws InterruptedException {
        String container = spec.database().container();
        Shell.run(120, "docker", "stop", container);
        // A duration that outlasts the run leaves the database down for the rest of it; the script that started the
        // container starts it again.
        Thread.sleep((long) (injection.durationSec() * 1000));
        Shell.run(120, "docker", "start", container);
    }

    private void kill(RunSpec.Injection injection) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                jdbcUrl.replace("ApplicationName=load-runner", "ApplicationName=load-injector"),
                spec.database().user(),
                spec.database().password())) {
            List<ListenerSession> sessions = ListenerProbe.probe(c);
            String app = RunSpec.nodeName(injection.node());
            for (ListenerSession s : sessions) {
                if (s.applicationName().equals(app) && s.channel().equals(injection.channel())) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT pg_terminate_backend(?)")) {
                        ps.setInt(1, s.pid());
                        ps.execute();
                    }
                    return;
                }
            }
            throw new SQLException("no " + injection.channel() + " listener of " + app + " found");
        }
    }

    private synchronized void record(RunSpec.Injection injection, long began, long ended, String outcome) {
        String line = injection.kind() + " node=" + injection.node() + " channel=" + injection.channel() + " beganMs="
                + began + " endedMs=" + ended + " " + outcome + "\n";
        try {
            Files.writeString(log, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The run result lists the planned injection; a missing line shows as a missing record.
        }
    }
}

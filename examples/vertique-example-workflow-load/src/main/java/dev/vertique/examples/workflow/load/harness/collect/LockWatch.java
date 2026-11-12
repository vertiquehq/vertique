// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches for sessions that wait on the database-wide notification lock, object {@code 1262:0}, and says which
 * application each one is.
 *
 * <p>PostgreSQL takes that lock for the length of a commit that sends a notification, so every commit with a pending
 * {@code NOTIFY} queues behind the others. A waiter is found in {@code pg_locks} by lock type {@code object}, class
 * {@code 1262} and object {@code 0}, and named by the {@code application_name} of its session. A node's pooled
 * business sessions carry the node's name, so a waiter is attributed to a node; the watcher's own session, the
 * collector and the driver carry their own names and are not mistaken for one.
 *
 * <p>The watch samples at a short fixed interval. A wait that is shorter than the interval can be missed, so "no waiter
 * seen" is a statement about the samples taken, and the summary counts them. The watch is off in idle runs, because
 * its queries cost load.
 */
final class LockWatch implements Runnable {

    static final String APPLICATION_NAME = "load-lockwatch";

    private static final String SQL = "SELECT l.pid, a.application_name, a.state, left(a.query, 60)"
            + " FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid"
            + " WHERE NOT l.granted AND l.locktype = 'object' AND l.classid = 1262 AND l.objid = 0"
            + " AND l.pid <> pg_backend_pid()";

    private final CollectorSpec spec;
    private final Path dir;
    private final AtomicBoolean stop;
    private Connection connection;

    LockWatch(CollectorSpec spec, Path dir, AtomicBoolean stop) {
        this.spec = spec;
        this.dir = dir;
        this.stop = stop;
    }

    @Override
    public void run() {
        long samples = 0;
        long withWaiters = 0;
        long observations = 0;
        long errors = 0;
        int maxAtOnce = 0;
        Map<String, Long> byApplication = new TreeMap<>();
        try (CsvOut out = new CsvOut(dir.resolve("lockwatch.csv"), "t_ms,pid,application,state,query")) {
            while (!stop.get()) {
                long start = System.nanoTime();
                try {
                    long now = System.currentTimeMillis();
                    int seen = 0;
                    try (Statement st = connection().createStatement()) {
                        st.setQueryTimeout(2);
                        try (ResultSet r = st.executeQuery(SQL)) {
                            while (r.next()) {
                                seen++;
                                String app = r.getString(2) == null ? "" : r.getString(2);
                                byApplication.merge(app, 1L, Long::sum);
                                out.row(now, r.getInt(1), app, r.getString(3), r.getString(4));
                            }
                        }
                    }
                    samples++;
                    if (seen > 0) {
                        withWaiters++;
                        observations += seen;
                        maxAtOnce = Math.max(maxAtOnce, seen);
                    }
                } catch (SQLException | RuntimeException e) {
                    errors++;
                    reset();
                }
                long spent = (System.nanoTime() - start) / 1_000_000;
                long pause = spec.lockWatchIntervalMs() - spent;
                if (pause > 0) {
                    try {
                        Thread.sleep(pause);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (IOException e) {
            errors++;
        } finally {
            reset();
        }
        JsonObject byApp = new JsonObject();
        byApplication.forEach(byApp::put);
        JsonObject summary = new JsonObject()
                .put("intervalMs", spec.lockWatchIntervalMs())
                .put("lock", "object 1262:0")
                .put("samples", samples)
                .put("samplesWithWaiters", withWaiters)
                .put("waiterObservations", observations)
                .put("maxWaitersAtOnce", maxAtOnce)
                .put("queryErrors", errors)
                .put("waiterObservationsByApplication", byApp);
        try {
            Files.writeString(dir.resolve("lockwatch-summary.json"), summary.encodePrettily());
        } catch (IOException e) {
            // The summary is rebuilt from lockwatch.csv when this file is missing.
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(spec.jdbcUrl(APPLICATION_NAME), spec.user(), spec.password());
        }
        return connection;
    }

    private void reset() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already broken
            }
            connection = null;
        }
    }
}

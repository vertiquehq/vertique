// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import dev.vertique.examples.workflow.load.harness.listen.ListenerProbe;
import dev.vertique.examples.workflow.load.harness.listen.ListenerSession;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the database once per interval: queue depths, database counters, connections by application, notification
 * queue use and the dedicated listeners.
 *
 * <p>Every tick writes a row, whatever the database answered: a row with {@code db_ok=false} and the error says that
 * the database did not answer, which is a result, while a missing row would say the collector failed. The tick's own
 * lateness is recorded, so a collector that fell behind shows itself. The sampler keeps one connection under its own
 * application name and opens a new one after any failure.
 */
final class SqlSampler implements Runnable {

    static final String APPLICATION_NAME = "load-collector";

    private static final String DEPTH_SQL = "SELECT extract(epoch from clock_timestamp()) * 1000,"
            + " (SELECT count(*) FROM (SELECT 1 FROM workflow_commands c WHERE c.state IN ('PENDING','DEFERRED')"
            + "   AND c.available_at <= now() ORDER BY c.available_at LIMIT ?) d),"
            + " (SELECT count(*) FROM (SELECT 1 FROM outbox o WHERE o.state = 'PENDING' ORDER BY o.available_at, o.id"
            + "   LIMIT ?) p),"
            + " (SELECT count(*) FROM (SELECT 1 FROM outbox o WHERE o.state = 'PROCESSING' LIMIT ?) q)";

    private static final String STATS_SQL = "SELECT xact_commit, xact_rollback, deadlocks, blks_read, blks_hit,"
            + " tup_inserted, tup_updated, temp_files, numbackends, pg_notification_queue_usage()"
            + " FROM pg_stat_database WHERE datname = current_database()";

    private static final String ACTIVITY_SQL = "SELECT count(*) FILTER (WHERE state = 'active'),"
            + " count(*) FILTER (WHERE state = 'idle in transaction'),"
            + " count(*) FILTER (WHERE wait_event_type = 'Lock')"
            + " FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()";

    private static final String STATISTICS_SQL = "SELECT relname, analyze_count, autoanalyze_count,"
            + " (extract(epoch from last_autoanalyze) * 1000)::bigint, n_live_tup, n_mod_since_analyze, n_dead_tup,"
            + " vacuum_count, autovacuum_count FROM pg_stat_user_tables WHERE relname IN"
            + " ('workflow_commands', 'workflow_instances', 'outbox', 'workflow_command_queues', 'workflow_history')"
            + " ORDER BY relname";

    private static final String APPS_SQL = "SELECT application_name, count(*) FROM pg_stat_activity"
            + " WHERE datname = current_database() GROUP BY 1 ORDER BY 1";

    private final CollectorSpec spec;
    private final Path dir;
    private final AtomicBoolean stop;
    private final CsvOut ticks;
    private final CsvOut listeners;
    private final CsvOut statistics;
    private Connection connection;

    SqlSampler(CollectorSpec spec, Path dir, AtomicBoolean stop) throws IOException {
        this.spec = spec;
        this.dir = dir;
        this.stop = stop;
        this.ticks = new CsvOut(
                dir.resolve("seconds.csv"),
                "t_ms,late_ms,db_ok,error,db_clock_ms,rtt_ms,due,outbox_pending,outbox_processing,notification_queue_usage,"
                        + "xact_commit,xact_rollback,deadlocks,blks_read,blks_hit,tup_inserted,tup_updated,temp_files,"
                        + "backends,active,idle_in_transaction,lock_waiting,connections_by_application");
        this.listeners =
                new CsvOut(dir.resolve("listeners.csv"), "t_ms,answered,pid,backend_start_ms,application,channel");
        this.statistics = new CsvOut(
                dir.resolve("statistics.csv"),
                "t_ms,relname,analyze_count,autoanalyze_count,last_autoanalyze_ms,n_live_tup,n_mod_since_analyze,"
                        + "n_dead_tup,vacuum_count,autovacuum_count");
    }

    private volatile long attemptStartedMs;
    private volatile long lastSuccessMs;
    private volatile int consecutiveFailures;
    private final java.util.concurrent.atomic.AtomicLong rows = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong recorderErrors = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Runs the heartbeat. The collector's own liveness is a row written on schedule whatever the database does; the
     * queries run on a worker thread, so a database that does not answer delays the worker and not the heartbeat. Each
     * heartbeat row says whether the worker is waiting on the database, for how long, and when it last succeeded, so
     * an outage is recorded as system evidence at every beat instead of as a missing row.
     */
    @Override
    public void run() {
        Thread worker = Thread.ofPlatform().name("sql-worker").daemon().start(this::work);
        long interval = spec.sampleIntervalMs();
        long next = System.currentTimeMillis();
        try (CsvOut beats = new CsvOut(
                dir.resolve("heartbeat.csv"),
                "t_ms,late_ms,worker_state,worker_busy_ms,since_last_success_ms,consecutive_failures,rows_written,recorder_errors")) {
            while (!stop.get()) {
                long now = System.currentTimeMillis();
                if (now < next) {
                    try {
                        Thread.sleep(Math.min(next - now, 50));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                long started = attemptStartedMs;
                long ok = lastSuccessMs;
                beats.row(
                        now,
                        now - next,
                        started == 0 ? "idle" : "querying",
                        started == 0 ? 0 : now - started,
                        ok == 0 ? "" : Long.toString(now - ok),
                        consecutiveFailures,
                        rows.get(),
                        recorderErrors.get());
                next += interval;
                if (next < System.currentTimeMillis() - interval) {
                    next = System.currentTimeMillis();
                }
            }
        } catch (IOException e) {
            recorderErrors.incrementAndGet();
        }
        stop.set(true);
        worker.interrupt();
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close();
    }

    private void work() {
        long interval = spec.sampleIntervalMs();
        long next = System.currentTimeMillis() + 200;
        while (!stop.get()) {
            long now = System.currentTimeMillis();
            if (now < next) {
                try {
                    Thread.sleep(Math.min(next - now, 100));
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            long lateMs = now - next;
            attemptStartedMs = now;
            tick(now, lateMs);
            attemptStartedMs = 0;
            next += interval;
            if (next < System.currentTimeMillis() - interval) {
                // Far behind: skip the ticks that cannot be taken in time rather than burst; the heartbeat says so.
                next = System.currentTimeMillis();
            }
        }
    }

    private void tick(long startMs, long lateMs) {
        try {
            Connection c = connection();
            Object[] row = new Object[23];
            row[0] = startMs;
            row[1] = lateMs;
            row[2] = true;
            row[3] = "";
            long before = System.currentTimeMillis();
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout((int) Math.max(1, (spec.queryTimeoutMs() + 999) / 1000));
                double dbClock = 0;
                try (java.sql.PreparedStatement ps = c.prepareStatement(DEPTH_SQL)) {
                    ps.setQueryTimeout((int) Math.max(1, (spec.queryTimeoutMs() + 999) / 1000));
                    ps.setInt(1, spec.depthCap());
                    ps.setInt(2, spec.depthCap());
                    ps.setInt(3, spec.depthCap());
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        dbClock = r.getDouble(1);
                        row[6] = r.getLong(2);
                        row[7] = r.getLong(3);
                        row[8] = r.getLong(4);
                    }
                }
                long after = System.currentTimeMillis();
                row[4] = dbClock;
                row[5] = (double) (after - before);
                try (ResultSet r = st.executeQuery(STATS_SQL)) {
                    if (r.next()) {
                        row[9] = r.getDouble(10);
                        for (int i = 0; i < 9; i++) {
                            row[10 + i] = r.getLong(i + 1);
                        }
                    }
                }
                try (ResultSet r = st.executeQuery(ACTIVITY_SQL)) {
                    r.next();
                    row[19] = r.getLong(1);
                    row[20] = r.getLong(2);
                    row[21] = r.getLong(3);
                }
                StringJoiner apps = new StringJoiner(";");
                try (ResultSet r = st.executeQuery(APPS_SQL)) {
                    while (r.next()) {
                        apps.add(r.getString(1) + "=" + r.getLong(2));
                    }
                }
                row[22] = apps.toString();
            }
            List<ListenerSession> sessions = ListenerProbe.probe(c);
            List<Object[]> stats = new java.util.ArrayList<>();
            try (Statement st = c.createStatement();
                    ResultSet r = st.executeQuery(STATISTICS_SQL)) {
                while (r.next()) {
                    stats.add(new Object[] {
                        startMs,
                        r.getString(1),
                        r.getLong(2),
                        r.getLong(3),
                        r.getLong(4),
                        r.getLong(5),
                        r.getLong(6),
                        r.getLong(7),
                        r.getLong(8),
                        r.getLong(9)
                    });
                }
            }
            lastSuccessMs = System.currentTimeMillis();
            consecutiveFailures = 0;
            record(() -> {
                ticks.row(row);
                writeListeners(startMs, true, sessions);
                stats.forEach(statistics::row);
            });
        } catch (SQLException | RuntimeException e) {
            reset();
            consecutiveFailures++;
            record(() -> {
                ticks.row(startMs, lateMs, false, message(e));
                writeListeners(startMs, false, List.of());
            });
        }
    }

    private void record(Runnable write) {
        try {
            write.run();
            rows.incrementAndGet();
        } catch (java.io.UncheckedIOException e) {
            recorderErrors.incrementAndGet();
        }
    }

    private void writeListeners(long timeMs, boolean answered, List<ListenerSession> sessions) {
        if (sessions.isEmpty()) {
            listeners.row(timeMs, answered, 0, 0, "", "");
            return;
        }
        for (ListenerSession s : sessions) {
            listeners.row(timeMs, answered, s.pid(), s.backendStartMs(), s.applicationName(), s.channel());
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(spec.jdbcUrl(APPLICATION_NAME), spec.user(), spec.password());
            connection.setAutoCommit(true);
        }
        return connection;
    }

    private void reset() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // the connection is broken already
            }
            connection = null;
        }
    }

    private static String message(Exception e) {
        String text = e.getClass().getSimpleName() + ": "
                + String.valueOf(e.getMessage()).replace('\n', ' ');
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private void close() {
        reset();
        try {
            java.nio.file.Files.writeString(
                    dir.resolve("collector-summary.json"),
                    new io.vertx.core.json.JsonObject()
                            .put("rowsWritten", rows.get())
                            .put("recorderErrors", recorderErrors.get())
                            .encodePrettily());
            ticks.close();
            listeners.close();
            statistics.close();
        } catch (IOException ignored) {
            // the files are closed at exit
        }
    }
}

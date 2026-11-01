// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.admin.LoadAdmin;
import dev.vertique.examples.workflow.load.support.ItDatabase;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs the SQL metrics against the baseline schema, migrated as a run migrates it, on rows whose every time is known:
 * the cohort and the rows never created, the qualifying and excluded counts, the quantiles, the unsettled, failed, dead
 * and parked rows that stay visible, the dispatch rows joined to their start with {@code outbox.scope_key} present, the
 * first claims that cannot be established, the per-second series, and the one snapshot.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class SqlMetricsIT {

    private static final double BASE = 1_900_000_000.0;
    private static ItDatabase db;

    @BeforeAll
    static void provision() throws Exception {
        db = new ItDatabase().start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = LoadAdmin.run(
                new String[] {
                    "migrate",
                    "--conf",
                    new JsonObject().put("db", db.dbSection(null, 2)).encode()
                },
                new PrintStream(out),
                new PrintStream(out));
        assertEquals(0, code, out.toString());
        seed();
    }

    @AfterAll
    static void release() {
        db.close();
    }

    private static String ts(double offsetSec) {
        return "to_timestamp(" + (BASE + offsetSec) + ")";
    }

    private static void instance(Statement s, int i, String status, Double completedAt) throws Exception {
        s.execute("INSERT INTO workflow_instances (id, definition_id, definition_version, plan_hash, status,"
                + " current_step_id, state_json, completed_at) VALUES ('00000000-0000-0000-0000-0000000000"
                + String.format("%02d", i) + "', 'load', 1, repeat('a', 64), '" + status + "', 'x', '{}'::jsonb, "
                + (completedAt == null ? "NULL" : ts(completedAt)) + ")");
    }

    private static void command(
            Statement s,
            int i,
            int seq,
            String kind,
            String state,
            double recorded,
            double available,
            Double settled,
            int attempts,
            int transients,
            int deferrals,
            Double fireAt)
            throws Exception {
        s.execute("INSERT INTO workflow_commands (workflow_id, seq, kind, timer_kind, state, recorded_at, available_at,"
                + " fire_at, settled_at, attempts, transient_count, deferral_count) VALUES ('00000000-0000-0000-0000-0000000000"
                + String.format("%02d", i) + "', " + seq + ", '" + kind + "', "
                + (kind.equals("TIMER") ? "'FIRE'" : "NULL") + ", '" + state + "', " + ts(recorded) + ", "
                + ts(available)
                + ", " + (fireAt == null ? "NULL" : ts(fireAt)) + ", " + (settled == null ? "NULL" : ts(settled)) + ", "
                + attempts + ", " + transients + ", " + deferrals + ")");
    }

    private static void outbox(
            Statement s, int i, String state, int attempt, Double claimed, Double published, double created)
            throws Exception {
        s.execute("INSERT INTO outbox (carrier_id, aggregate_type, aggregate_id, event_type, destination,"
                + " destination_type, payload, state, attempt, claimed_at, published_at, created_at) VALUES"
                + " (gen_random_uuid(), 'workflow', '00000000-0000-0000-0000-0000000000" + String.format("%02d", i)
                + "', 'work', 'load.work', 'SERVICE', '{}'::jsonb, '" + state + "', " + attempt + ", "
                + (claimed == null ? "NULL" : ts(claimed)) + ", " + (published == null ? "NULL" : ts(published)) + ", "
                + ts(created) + ")");
    }

    private static void history(Statement s, int i, String entryType) throws Exception {
        s.execute("INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json)"
                + " SELECT workflow_id, 1, '" + entryType + "', jsonb_build_object('commandId', id)"
                + " FROM workflow_commands WHERE kind = 'SIGNAL' AND workflow_id = '00000000-0000-0000-0000-0000000000"
                + String.format("%02d", i) + "'");
    }

    /**
     * Instances 1 to 10 are clean: the start settles after 10 ms times the instance number, the callback signal after
     * 5 ms times it, the timer 2 ms times it after its fire time, and the dispatch is claimed 2 ms times it after the
     * start (instance 10: five seconds after, so that its first claim cannot be established) and published 30 ms later.
     * Instances 11 to 16 are the rows that must stay visible: a deferred start, a dead one, a pending one, a signal that
     * is parked, a retried timer, and a signal that was parked and then completed with its attempts refunded.
     */
    private static void seed() throws Exception {
        try (Connection c = db.connect();
                Statement s = c.createStatement()) {
            for (int i = 1; i <= 10; i++) {
                double start = i;
                double timer = i + 1.2;
                instance(s, i, "COMPLETED", timer + 0.002 * i);
                command(s, i, 1, "START", "COMPLETE", start, start, start + 0.010 * i, 1, 0, 0, null);
                command(
                        s,
                        i,
                        2,
                        "SIGNAL",
                        "COMPLETE",
                        start + 0.1,
                        start + 0.1,
                        start + 0.1 + 0.005 * i,
                        1,
                        0,
                        0,
                        null);
                command(s, i, 3, "TIMER", "COMPLETE", start + 0.2, timer, timer + 0.002 * i, 1, 0, 0, timer);
                double claimed = i == 10 ? start + 5.0 : start + 0.002 * i;
                outbox(s, i, "PUBLISHED", 0, claimed, claimed + 0.030, start);
            }
            instance(s, 11, "WAITING", null);
            command(s, 11, 1, "START", "COMPLETE", 11, 11, 11.5, 1, 0, 1, null);
            outbox(s, 11, "PUBLISHED", 1, 11.4, 11.5, 11);
            instance(s, 12, "FAILED", null);
            command(s, 12, 1, "START", "DEAD", 12, 12, null, 3, 0, 0, null);
            outbox(s, 12, "DEAD_LETTER", 3, 12.3, null, 12);
            instance(s, 13, "WAITING", null);
            command(s, 13, 1, "START", "PENDING", 13, 13, null, 0, 0, 0, null);
            outbox(s, 13, "PENDING", 0, null, null, 13);
            instance(s, 14, "WAITING", null);
            command(s, 14, 1, "START", "COMPLETE", 14, 14, 14.01, 1, 0, 0, null);
            command(s, 14, 2, "SIGNAL", "PARKED", 14.1, 14.1, null, 0, 0, 0, null);
            instance(s, 15, "WAITING", null);
            command(s, 15, 1, "START", "COMPLETE", 15, 15, 15.01, 1, 0, 0, null);
            command(s, 15, 2, "TIMER", "COMPLETE", 15, 17.5, 17.9, 1, 1, 0, 16.0);
            instance(s, 16, "WAITING", null);
            command(s, 16, 1, "START", "COMPLETE", 16, 16, 16.01, 1, 0, 0, null);
            command(s, 16, 2, "SIGNAL", "COMPLETE", 16.1, 16.1, 16.15, 1, 0, 0, null);
            history(s, 16, "SIGNAL_PARKED");
        }
    }

    private SqlMetrics.Report read() throws Exception {
        try (Connection c = db.connect()) {
            return SqlMetrics.read(c, BASE, BASE + 100, BASE - 10, BASE + 120, 20, 1_000, null);
        }
    }

    @Test
    @DisplayName(
            "the cohort is the instances started in the window; rows that were never created are counted as missing")
    void cohortAndRowsNeverCreated() throws Exception {
        SqlMetrics.Report report = read();

        assertEquals(16, report.cohortStarts());
        assertEquals(10, report.cohortCompleted());
        assertEquals(16, report.starts().expected());
        assertEquals(0, report.starts().neverCreated());
        assertEquals(16, report.signals().expected());
        assertEquals(12, report.signals().total());
        assertEquals(4, report.signals().neverCreated());
        assertEquals(16, report.timers().expected());
        assertEquals(11, report.timers().total());
        assertEquals(5, report.timers().neverCreated());
        assertEquals(16, report.outbox().expected());
        assertEquals(13, report.outbox().total());
        assertEquals(3, report.outbox().neverCreated(), "the starts of instances 14 to 16 have no dispatch row");
        JsonObject json = report.toJson();
        assertEquals(4, json.getJsonObject("cohort").getInteger("signalsNeverCreated"));
        assertTrue(json.getString("isolation").contains("REPEATABLE READ"));
        assertTrue(json.getDouble("snapshotAtEpochSec") > 1_700_000_000);
    }

    @Test
    @DisplayName(
            "start and signal counts separate the qualifying commands from the retried, unsettled, parked and dead")
    void commandCounts() throws Exception {
        SqlMetrics.Report report = read();

        SqlMetrics.CommandCounts starts = report.starts();
        assertEquals(16, starts.total());
        assertEquals(13, starts.qualifying(), "instances 1 to 10, 14, 15 and 16");
        assertEquals(1, starts.retriedOrDeferred());
        assertEquals(1, starts.unsettled());
        assertEquals(1, starts.dead());
        assertEquals(3, starts.excluded());
        SqlMetrics.CommandCounts signals = report.signals();
        assertEquals(10, signals.qualifying());
        assertEquals(1, signals.parked(), "parked now, with no history entry");
        assertEquals(
                1, signals.parkedEver(), "completed with its attempts refunded, but the history says it was parked");
        assertEquals(6, signals.excluded(), "the parked, the parked-ever and the four never created");
    }

    @Test
    @DisplayName(
            "recorded to segment transaction start is the settled time minus the recorded time of the qualifying rows of the cohort")
    void recordedToStart() throws Exception {
        SqlMetrics.Report report = read();

        List<Double> expected = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            expected.add(10.0 * i);
            expected.add(5.0 * i);
        }
        expected.addAll(List.of(10.0, 10.0, 10.0));
        double[] values = expected.stream().mapToDouble(Double::doubleValue).toArray();
        SqlMetrics.Latency latency = report.recordedToStart();
        assertEquals(values.length, latency.count());
        assertEquals(Quantiles.of(values, 0.5), latency.p50(), 0.5);
        assertEquals(Quantiles.of(values, 0.95), latency.p95(), 0.5);
        assertEquals(100.0, latency.max(), 0.5);
        assertEquals(100.0, report.recordedToStartByKind().get("START").max(), 0.5);
        assertEquals(50.0, report.recordedToStartByKind().get("SIGNAL").max(), 0.5);
        assertEquals(10, report.recordedToStartByKind().get("SIGNAL").count(), "the parked-ever signal is not in it");
    }

    @Test
    @DisplayName(
            "timer lateness uses the fire time, and the retried timer is reported apart against its stable fire time")
    void timers() throws Exception {
        SqlMetrics.TimerCounts timers = read().timers();

        assertEquals(10, timers.clean());
        assertEquals(20.0, timers.lateness().max(), 0.5);
        assertEquals(1, timers.retriedOrDeferred());
        assertEquals(1, timers.retriedOrDeferredLateness().count());
        assertEquals(1900.0, timers.retriedOrDeferredLateness().max(), 0.5);
    }

    @Test
    @DisplayName(
            "a first claim later than the relay's lease after the start is unresolved and left out of start-to-claim")
    void outbox() throws Exception {
        SqlMetrics.OutboxCounts outbox = read().outbox();

        assertEquals(9, outbox.startToClaim().count(), "instances 1 to 9: first claim established");
        assertEquals(18.0, outbox.startToClaim().max(), 0.5);
        assertEquals(1, outbox.firstClaimUnresolved(), "instance 10 was claimed five seconds after its start");
        assertEquals(10, outbox.dispatchRaw().count());
        assertEquals(30.0, outbox.dispatchRaw().p50(), 0.5);
        assertEquals(10.0, outbox.dispatchRaw().minus(20).p50(), 0.5, "less the handler delay it is a residual proxy");
        assertEquals(1, outbox.publishedAfterRetry());
        assertEquals(1, outbox.pending());
        assertEquals(1, outbox.deadLetter());
        assertEquals(0, outbox.processing());
        assertTrue(outbox.peakConcurrentDispatchesPerRelay() >= 1);
        assertTrue(read().toJson().getJsonObject("outbox").getLong("peakConcurrentDispatchesPerRelay") >= 1);
        assertEquals(java.util.Map.of("default", 13L), outbox.scopeKeys());
    }

    @Test
    @DisplayName("completions, per-second series, recorded commands by kind and the status of every instance are read")
    void completionsAndSeries() throws Exception {
        SqlMetrics.Report report = read();

        assertEquals(10, report.completedInWindow());
        assertEquals(0.1, report.completedPerSecond(), 1e-9);
        assertEquals(16L, report.recordedInWindowByKind().get("START"));
        assertEquals(12L, report.recordedInWindowByKind().get("SIGNAL"));
        assertEquals(11L, report.recordedInWindowByKind().get("TIMER"));
        assertEquals(
                10,
                report.completionsPerSecond().values().stream()
                        .mapToLong(Long::longValue)
                        .sum());
        assertEquals(
                11,
                report.settledPerSecondByKind().get("TIMER").values().stream()
                        .mapToLong(Long::longValue)
                        .sum(),
                "the retried timer settled too");
        assertTrue(report.recordedCommandsPerSecond().size() >= 10);
        assertTrue(report.recordedToStartP95ByRecordedSecond().size() >= 10);
        assertTrue(report.recordedToStartByWaitEnd().size() >= 10);
        double[] firstWait =
                report.recordedToStartByWaitEnd().values().iterator().next();
        assertTrue(firstWait[1] <= BASE + 20, "the earliest recording of the commands whose wait ended that second");
        assertEquals(10L, report.instancesByStatus().get("COMPLETED"));
        assertEquals(5L, report.instancesByStatus().get("WAITING"));
        assertEquals(1L, report.instancesByStatus().get("FAILED"));
        assertEquals(1L, report.commandsByKindAndState().get("START/DEAD"));
        assertEquals(1L, report.outboxByState().get("DEAD_LETTER"));
        JsonObject json = report.toJson();
        assertEquals(11, json.getJsonObject("timers").getInteger("total"));
        assertEquals(
                16.0 / 100,
                json.getJsonObject("recordedCommandsPerSecondInWindowByKind").getDouble("START"),
                1e-9);
        assertTrue(json.getJsonObject("names").containsKey("recordedToSegmentTransactionStart"));
    }

    @Test
    @DisplayName("a window with no rows gives empty distributions and zero counts")
    void emptyWindow() throws Exception {
        try (Connection c = db.connect()) {
            SqlMetrics.Report report =
                    SqlMetrics.read(c, BASE + 1000, BASE + 1100, BASE + 1000, BASE + 1100, 20, 1_000, null);

            assertEquals(0, report.cohortStarts());
            assertEquals(0, report.starts().total());
            assertEquals(0, report.recordedToStart().count());
            assertTrue(Double.isNaN(report.recordedToStart().p95()));
            assertEquals(0, report.outbox().total());
            assertEquals(0, report.completedInWindow());
        }
    }

    @Test
    @DisplayName(
            "the figures of one read agree with one another while a node commits, because they come from one snapshot")
    void oneSnapshot() throws Exception {
        double from = System.currentTimeMillis() / 1000.0 - 5;
        AtomicBoolean writing = new AtomicBoolean(true);
        Thread writer = new Thread(() -> {
            try (Connection c = db.connect()) {
                c.setAutoCommit(false);
                int n = 0;
                while (writing.get()) {
                    String id = "'10000000-0000-0000-0000-" + String.format("%012d", n++) + "'";
                    try (Statement s = c.createStatement()) {
                        s.execute(
                                "INSERT INTO workflow_instances (id, definition_id, definition_version, plan_hash, status,"
                                        + " current_step_id, state_json, completed_at) VALUES (" + id
                                        + ", 'load', 1, repeat('a', 64), 'COMPLETED', 'x', '{}'::jsonb, now())");
                        for (String[] row : new String[][] {
                            {"1", "START", "NULL"}, {"2", "SIGNAL", "NULL"}, {"3", "TIMER", "'FIRE'"}
                        }) {
                            s.execute(
                                    "INSERT INTO workflow_commands (workflow_id, seq, kind, timer_kind, state, recorded_at,"
                                            + " available_at, fire_at, settled_at, attempts) VALUES (" + id + ", "
                                            + row[0] + ", '"
                                            + row[1] + "', " + row[2] + ", 'COMPLETE', now(), now(), now(), now(), 1)");
                        }
                    }
                    c.commit();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writer.start();
        try {
            Thread.sleep(200);
            for (int i = 0; i < 12; i++) {
                try (Connection c = db.connect()) {
                    SqlMetrics.Report r = SqlMetrics.read(c, from, from + 3_600, from, from + 3_600, 20, 1_000, null);
                    long cohort = r.cohortStarts();
                    assertEquals(cohort, r.starts().total(), "starts");
                    assertEquals(cohort, r.signals().total(), "signals");
                    assertEquals(cohort, r.timers().total(), "timers");
                    assertEquals(cohort, r.cohortCompleted(), "completed instances of the cohort");
                    assertEquals(cohort, r.completedInWindow(), "instances completed in the window");
                    assertEquals(cohort, r.recordedInWindowByKind().get("TIMER"), "timers recorded in the window");
                    assertTrue(c.getAutoCommit(), "the connection is restored");
                    assertFalse(c.isReadOnly());
                }
                Thread.sleep(50);
            }
        } finally {
            writing.set(false);
            writer.join();
        }
    }

    @Test
    @DisplayName("the start keys of the snapshot are written with the status of each instance")
    void durableStarts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        try (Connection c = db.connect();
                Statement s = c.createStatement()) {
            s.execute("INSERT INTO workflow_dedup (kind, dedup_namespace, key, workflow_id) VALUES"
                    + " ('start', 'load', 'r-7', '00000000-0000-0000-0000-000000000001'),"
                    + " ('start', 'load', 'r-8', '00000000-0000-0000-0000-000000000011')");
        }
        java.nio.file.Path file = dir.resolve("d.csv.gz");
        try (Connection c = db.connect()) {
            SqlMetrics.read(c, BASE, BASE + 100, BASE, BASE + 120, 20, 1_000, file);
        }

        String text;
        try (var in = new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(text.contains("r-7,COMPLETED"), text);
        assertTrue(text.contains("r-8,WAITING"), text);
    }

    @Test
    @DisplayName("the database clock offset is measured")
    void clockOffset() throws Exception {
        try (Connection c = db.connect()) {
            ClockOffset offset = ClockOffset.measure(c, 5);

            assertTrue(Math.abs(offset.offsetMs()) < 2_000, "offset " + offset.offsetMs());
            assertTrue(offset.roundTripMs() < 1_000);
        }
    }
}

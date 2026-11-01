// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.metrics;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The figures a run reads from its database after the load: latencies from the engine's own rows, counts of
 * everything that did not finish, and per-second series.
 *
 * <p>The names say what is measured, because most of these are bounds and proxies:
 *
 * <ul>
 *   <li><b>Recorded to segment transaction start</b> is {@code settled_at - recorded_at} of a {@code START} or
 *       {@code SIGNAL} command that completed at its first attempt, with no transient retry and no deferral. The
 *       segment's transaction starts after the claim, so this is an upper bound on recorded-to-claimed.</li>
 *   <li><b>Timer transaction-start lateness</b> is {@code settled_at - available_at} of a {@code TIMER} command,
 *       which holds the fire time; retried and deferred timers are reported apart, against their stable
 *       {@code fire_at}.</li>
 *   <li><b>Start to outbox claim transaction start</b> is {@code outbox.claimed_at - recorded_at}, joining the
 *       instance's {@code START} to its one dispatch row by aggregate id and destination, at the first attempt. It is
 *       a correlated proxy, not the moment the relay picked the row up.</li>
 *   <li><b>Dispatch, raw</b> is {@code published_at - claimed_at} of a row published at the first attempt, with the
 *       handler's delay included. Less the delay it is a residual proxy, not the cost of the reply.</li>
 *   <li><b>Completed</b> is an instance with status {@code COMPLETED} counted at its {@code completed_at}, the start of
 *       the final segment's transaction.</li>
 * </ul>
 *
 * <p>Rows that did not finish, failed, were parked or are {@code DEAD} are counted beside every quantile, because a
 * dead row has no {@code settled_at} and would otherwise vanish from the figures. So are the rows that were never
 * created: a start in the cohort without a callback signal, a timer or a dispatch row.
 *
 * <p><b>Populations.</b> The command, timer and dispatch figures describe one cohort: the instances whose
 * {@code START} was recorded in the window, followed to their {@code SIGNAL}, {@code TIMER} and dispatch rows as the
 * snapshot found them. Completed per second describes another: the instances completed during the window, whatever
 * their start. The per-second series cover the whole run.
 *
 * <p><b>One snapshot.</b> Every figure is read in one read-only {@code REPEATABLE READ} transaction, so the nodes may
 * go on writing without the figures disagreeing with one another. The cutoff is {@code snapshotAtEpochSec}: what a
 * node committed after it is absent from all of them.
 *
 * <p><b>Limits of the counters.</b> {@code attempts} is decremented when a command is parked and again incremented at
 * its next claim, so a parked command looks like a first attempt. A command that has a {@code SIGNAL_PARKED} or
 * {@code COMMAND_DEAD_LETTERED} history entry is therefore counted as parked and left out of the clean figures. An outbox
 * row that is reclaimed after a stale lease keeps its {@code attempt} and has its {@code claimed_at} replaced by the
 * next claim; a row whose claim came later than the relay's lease after the start cannot be shown to be a first claim and
 * is counted as unresolved.
 *
 * <p>Times are epoch seconds on the database's clock; {@link ClockOffset} moves them to the host's.
 */
public final class SqlMetrics {

    private static final String CLEAN = "c.state = 'COMPLETE' AND c.attempts = 1 AND c.transient_count = 0"
            + " AND c.deferral_count = 0 AND c.settled_at IS NOT NULL";

    /** A command that was parked or dead-lettered at some time, by its history entry. */
    private static final String PARKED_EVER =
            "EXISTS (SELECT 1 FROM workflow_history h WHERE h.workflow_id = c.workflow_id"
                    + " AND h.entry_type IN ('SIGNAL_PARKED', 'COMMAND_DEAD_LETTERED')"
                    + " AND h.payload_json->>'commandId' = c.id::text)";

    /** The cohort: the instances whose START was recorded in the window. Takes the window as two parameters. */
    private static final String COHORT = "(SELECT workflow_id, recorded_at AS started_at FROM workflow_commands"
            + " WHERE kind = 'START' AND recorded_at >= to_timestamp(?) AND recorded_at < to_timestamp(?))";

    private static final String CATEGORY = "CASE WHEN " + PARKED_EVER + " THEN 'parkedEver'"
            + " WHEN " + CLEAN + " THEN 'qualifying'"
            + " WHEN c.state = 'COMPLETE' THEN 'retriedOrDeferred'"
            + " WHEN c.state IN ('PENDING', 'DEFERRED', 'CLAIMED') THEN 'unsettled'"
            + " WHEN c.state = 'PARKED' THEN 'parked'"
            + " WHEN c.state = 'DEAD' THEN 'dead' ELSE 'otherTerminal' END";

    private SqlMetrics() {}

    /**
     * A latency distribution in milliseconds.
     *
     * @param count the values
     * @param p50 the median, {@code NaN} when there are no values
     * @param p95 the 95th percentile
     * @param p99 the 99th percentile
     * @param max the greatest value
     */
    public record Latency(long count, double p50, double p95, double p99, double max) {

        /** A distribution without values. */
        public static final Latency NONE = new Latency(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        /**
         * Returns the distribution moved by an amount.
         *
         * @param deltaMs the amount in milliseconds, subtracted from every quantile
         * @return the shifted distribution
         */
        public Latency minus(double deltaMs) {
            return count == 0 ? this : new Latency(count, p50 - deltaMs, p95 - deltaMs, p99 - deltaMs, max - deltaMs);
        }

        JsonObject toJson() {
            return new JsonObject()
                    .put("count", count)
                    .put("p50Ms", number(p50))
                    .put("p95Ms", number(p95))
                    .put("p99Ms", number(p99))
                    .put("maxMs", number(max));
        }
    }

    /**
     * What happened to the commands of one kind that belong to the cohort.
     *
     * @param kind the command kind
     * @param expected the rows the cohort should hold: one per instance of the cohort
     * @param total the rows that exist in the snapshot
     * @param qualifying completed at the first attempt, with no retry, deferral or parking
     * @param retriedOrDeferred completed after a retry or a deferral
     * @param parkedEver parked at some time, by its history entry, whatever its state now
     * @param unsettled still pending, deferred or claimed
     * @param parked parked now, with no history entry
     * @param dead dead-lettered
     * @param otherTerminal any other final state
     */
    public record CommandCounts(
            String kind,
            long expected,
            long total,
            long qualifying,
            long retriedOrDeferred,
            long parkedEver,
            long unsettled,
            long parked,
            long dead,
            long otherTerminal) {

        JsonObject toJson() {
            return new JsonObject()
                    .put("kind", kind)
                    .put("expected", expected)
                    .put("neverCreated", neverCreated())
                    .put("total", total)
                    .put("qualifying", qualifying)
                    .put("retriedOrDeferred", retriedOrDeferred)
                    .put("parkedEver", parkedEver)
                    .put("unsettled", unsettled)
                    .put("parked", parked)
                    .put("dead", dead)
                    .put("otherTerminal", otherTerminal);
        }

        /**
         * Returns the rows the cohort should hold and that were never created, for example a callback signal for a
         * start whose dispatch has not been delivered.
         *
         * @return the count
         */
        public long neverCreated() {
            return Math.max(0, expected - total);
        }

        /**
         * Returns the commands that are not in the figures of the qualifying ones, the rows never created included.
         *
         * @return the excluded count
         */
        public long excluded() {
            return expected - qualifying;
        }
    }

    /**
     * The outcome of the dispatch rows of the cohort.
     *
     * @param expected one dispatch row per start of the cohort
     * @param total the dispatch rows that exist
     * @param startToClaim the start to outbox claim transaction start, over rows whose first claim is established
     * @param firstClaimUnresolved rows at the first attempt whose claim came later than the relay's lease after the start,
     *     so that it may be a second claim after a reclaim
     * @param dispatchRaw published minus claimed, handler delay included, first attempt
     * @param publishedAfterRetry rows published after a retry
     * @param pending rows not yet claimed
     * @param processing rows claimed and not published
     * @param deadLetter rows dead-lettered
     * @param otherStates rows in any other state
     * @param scopeKeys the dispatch rows by {@code scope_key}
     * @param peakConcurrentDispatchesPerRelay the most dispatches one relay held between claim and publication at one
     *     moment, over the rows of the cohort that were published at the first attempt
     */
    public record OutboxCounts(
            long expected,
            long total,
            Latency startToClaim,
            long firstClaimUnresolved,
            Latency dispatchRaw,
            long publishedAfterRetry,
            long pending,
            long processing,
            long deadLetter,
            long otherStates,
            Map<String, Long> scopeKeys,
            long peakConcurrentDispatchesPerRelay) {

        /**
         * Returns the starts of the cohort that have no dispatch row.
         *
         * @return the count
         */
        public long neverCreated() {
            return Math.max(0, expected - total);
        }
    }

    /**
     * The timer commands of the cohort.
     *
     * @param expected one timer per instance of the cohort
     * @param total the timers that exist
     * @param clean the timers completed at the first attempt, with no retry, deferral or parking
     * @param lateness the transaction-start lateness of the clean ones
     * @param retriedOrDeferred the timers completed after a retry or deferral, apart
     * @param retriedOrDeferredLateness their lateness against the stable fire time
     * @param parkedEver the timers parked at some time
     * @param unsettled the timers still pending, deferred or claimed
     * @param parked the timers parked now
     * @param dead the timers dead-lettered
     * @param otherTerminal the timers in any other final state
     */
    public record TimerCounts(
            long expected,
            long total,
            long clean,
            Latency lateness,
            long retriedOrDeferred,
            Latency retriedOrDeferredLateness,
            long parkedEver,
            long unsettled,
            long parked,
            long dead,
            long otherTerminal) {

        /**
         * Returns the timers the cohort should hold and that were never created.
         *
         * @return the count
         */
        public long neverCreated() {
            return Math.max(0, expected - total);
        }
    }

    /**
     * Everything read from the database for one run.
     *
     * @param snapshotAtEpochSec the cutoff: the database clock when the snapshot was taken
     * @param windowStartEpochSec the window start on the database clock
     * @param windowEndEpochSec the window end on the database clock
     * @param cohortStarts the instances whose START was recorded in the window
     * @param cohortCompleted the instances of the cohort with status COMPLETED at the snapshot
     * @param starts the START commands of the cohort
     * @param signals the SIGNAL commands of the cohort
     * @param recordedToStart the distribution over the qualifying START and SIGNAL commands of the cohort
     * @param recordedToStartByKind the same per kind
     * @param timers the timer commands of the cohort
     * @param outbox the dispatch rows of the cohort
     * @param completedInWindow the instances completed in the window, whatever their start
     * @param completedPerSecond the completed instances per second of the window
     * @param recordedInWindowByKind the commands of any instance recorded in the window, by kind
     * @param recordedCommandsPerSecond the commands recorded per second of the run, by database epoch second
     * @param completionsPerSecond the instances completed per second of the run, by database epoch second
     * @param settledPerSecondByKind the commands settled successfully per second of the run, by kind and database epoch
     *     second
     * @param recordedToStartP95ByRecordedSecond the 95th percentile per second of recording over the run
     * @param recordedToStartByWaitEnd the 95th percentile per second at which the wait ended, with the earliest
     *     recording among the commands that ended it, the count and the greatest wait
     * @param instancesByStatus every instance by status
     * @param commandsByKindAndState every command by kind and state
     * @param outboxByState every outbox row by state
     */
    public record Report(
            double snapshotAtEpochSec,
            double windowStartEpochSec,
            double windowEndEpochSec,
            long cohortStarts,
            long cohortCompleted,
            CommandCounts starts,
            CommandCounts signals,
            Latency recordedToStart,
            Map<String, Latency> recordedToStartByKind,
            TimerCounts timers,
            OutboxCounts outbox,
            long completedInWindow,
            double completedPerSecond,
            Map<String, Long> recordedInWindowByKind,
            Map<Long, Long> recordedCommandsPerSecond,
            Map<Long, Long> completionsPerSecond,
            Map<String, Map<Long, Long>> settledPerSecondByKind,
            Map<Long, Double> recordedToStartP95ByRecordedSecond,
            Map<Long, double[]> recordedToStartByWaitEnd,
            Map<String, Long> instancesByStatus,
            Map<String, Long> commandsByKindAndState,
            Map<String, Long> outboxByState) {

        /**
         * Renders the report; the per-second series are rendered as arrays of pairs.
         *
         * @return the report as JSON
         */
        public JsonObject toJson() {
            JsonObject byKind = new JsonObject();
            recordedToStartByKind.forEach((k, v) -> byKind.put(k, v.toJson()));
            double windowSec = windowEndEpochSec - windowStartEpochSec;
            JsonObject recordedRate = new JsonObject();
            recordedInWindowByKind.forEach((k, v) -> recordedRate.put(k, windowSec > 0 ? v / windowSec : null));
            JsonObject settled = new JsonObject();
            settledPerSecondByKind.forEach((k, v) -> settled.put(k, pairs(v)));
            JsonArray waitEnd = new JsonArray();
            recordedToStartByWaitEnd.forEach((k, v) -> waitEnd.add(
                    new JsonArray().add(k).add(v[0]).add(v[1]).add((long) v[2]).add(v[3])));
            return new JsonObject()
                    .put("snapshotAtEpochSec", snapshotAtEpochSec)
                    .put("isolation", "one read-only REPEATABLE READ transaction for every figure")
                    .put("windowStartEpochSec", windowStartEpochSec)
                    .put("windowEndEpochSec", windowEndEpochSec)
                    .put("names", names())
                    .put(
                            "cohort",
                            new JsonObject()
                                    .put("definition", "instances whose START was recorded in the window")
                                    .put("starts", cohortStarts)
                                    .put("completedAtSnapshot", cohortCompleted)
                                    .put("signalsNeverCreated", signals.neverCreated())
                                    .put("timersNeverCreated", timers.neverCreated())
                                    .put("dispatchesNeverCreated", outbox.neverCreated()))
                    .put("starts", starts.toJson())
                    .put("signals", signals.toJson())
                    .put("recordedToSegmentTransactionStart", recordedToStart.toJson())
                    .put("recordedToSegmentTransactionStartByKind", byKind)
                    .put(
                            "timers",
                            new JsonObject()
                                    .put("expected", timers.expected())
                                    .put("neverCreated", timers.neverCreated())
                                    .put("total", timers.total())
                                    .put("clean", timers.clean())
                                    .put(
                                            "transactionStartLateness",
                                            timers.lateness().toJson())
                                    .put("retriedOrDeferred", timers.retriedOrDeferred())
                                    .put(
                                            "retriedOrDeferredLatenessAgainstFireAt",
                                            timers.retriedOrDeferredLateness().toJson())
                                    .put("parkedEver", timers.parkedEver())
                                    .put("unsettled", timers.unsettled())
                                    .put("parked", timers.parked())
                                    .put("dead", timers.dead())
                                    .put("otherTerminal", timers.otherTerminal()))
                    .put(
                            "outbox",
                            new JsonObject()
                                    .put("expected", outbox.expected())
                                    .put("neverCreated", outbox.neverCreated())
                                    .put("total", outbox.total())
                                    .put(
                                            "startToClaimTransactionStart",
                                            outbox.startToClaim().toJson())
                                    .put("firstClaimUnresolved", outbox.firstClaimUnresolved())
                                    .put(
                                            "dispatchPublishedMinusClaimedRaw",
                                            outbox.dispatchRaw().toJson())
                                    .put("publishedAfterRetry", outbox.publishedAfterRetry())
                                    .put("pending", outbox.pending())
                                    .put("processing", outbox.processing())
                                    .put("deadLetter", outbox.deadLetter())
                                    .put("otherStates", outbox.otherStates())
                                    .put(
                                            "scopeKeys",
                                            new JsonObject(new LinkedHashMap<String, Object>(outbox.scopeKeys())))
                                    .put("peakConcurrentDispatchesPerRelay", outbox.peakConcurrentDispatchesPerRelay()))
                    .put("completedInWindow", completedInWindow)
                    .put("completedPerSecond", completedPerSecond)
                    .put(
                            "recordedInWindowByKind",
                            new JsonObject(new LinkedHashMap<String, Object>(recordedInWindowByKind)))
                    .put("recordedCommandsPerSecondInWindowByKind", recordedRate)
                    .put("instancesByStatus", new JsonObject(new LinkedHashMap<String, Object>(instancesByStatus)))
                    .put(
                            "commandsByKindAndState",
                            new JsonObject(new LinkedHashMap<String, Object>(commandsByKindAndState)))
                    .put("outboxByState", new JsonObject(new LinkedHashMap<String, Object>(outboxByState)))
                    .put("completionsPerSecond", pairs(completionsPerSecond))
                    .put("recordedCommandsPerSecond", pairs(recordedCommandsPerSecond))
                    .put("settledPerSecondByKind", settled)
                    .put("recordedToSegmentStartP95PerSecondMs", pairsD(recordedToStartP95ByRecordedSecond))
                    .put("recordedToSegmentStartByWaitEndSecond", waitEnd);
        }
    }

    /**
     * Reads the figures of a run in one snapshot.
     *
     * @param c a connection to the run's database; its transaction settings are changed for the read and restored
     * @param windowStartEpochSec the start of the window on the database clock
     * @param windowEndEpochSec the end of the window on the database clock
     * @param runStartEpochSec the start of the run on the database clock, for the per-second series
     * @param runEndEpochSec the end of the run on the database clock
     * @param handlerDelayMs the handler delay the dispatch figures include
     * @param relayLeaseTimeoutMs the relay's lease; a first claim later than this after the start is unresolved
     * @param durableStarts where to write the start keys of the snapshot with the status of each instance, or {@code null}
     * @return the report
     * @throws SQLException if a query fails
     * @throws java.io.IOException if the keys cannot be written
     */
    public static Report read(
            Connection c,
            double windowStartEpochSec,
            double windowEndEpochSec,
            double runStartEpochSec,
            double runEndEpochSec,
            long handlerDelayMs,
            long relayLeaseTimeoutMs,
            java.nio.file.Path durableStarts)
            throws SQLException, java.io.IOException {
        boolean autoCommit = c.getAutoCommit();
        boolean readOnly = c.isReadOnly();
        int isolation = c.getTransactionIsolation();
        c.setAutoCommit(false);
        c.setReadOnly(true);
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            return readInSnapshot(
                    c,
                    windowStartEpochSec,
                    windowEndEpochSec,
                    runStartEpochSec,
                    runEndEpochSec,
                    relayLeaseTimeoutMs,
                    durableStarts);
        } finally {
            c.rollback();
            c.setTransactionIsolation(isolation);
            c.setReadOnly(readOnly);
            c.setAutoCommit(autoCommit);
        }
    }

    private static Report readInSnapshot(
            Connection c,
            double from,
            double to,
            double runStart,
            double runEnd,
            long leaseMs,
            java.nio.file.Path durableStarts)
            throws SQLException, java.io.IOException {
        double snapshotAt;
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT extract(epoch from clock_timestamp())")) {
            r.next();
            snapshotAt = r.getDouble(1);
        }
        long cohortStarts = scalar(c, "SELECT count(*) FROM " + COHORT + " k", from, to);
        long cohortCompleted = scalar(
                c,
                "SELECT count(*) FROM " + COHORT + " k JOIN workflow_instances i ON i.id = k.workflow_id"
                        + " WHERE i.status = 'COMPLETED'",
                from,
                to);
        CommandCounts starts = commandCounts(c, "START", cohortStarts, from, to);
        CommandCounts signals = commandCounts(c, "SIGNAL", cohortStarts, from, to);
        Map<String, Latency> byKind = new LinkedHashMap<>();
        byKind.put("START", recordedToStart(c, "START", from, to));
        byKind.put("SIGNAL", recordedToStart(c, "SIGNAL", from, to));
        Latency combined = recordedToStart(c, null, from, to);
        TimerCounts timers = timers(c, cohortStarts, from, to);
        OutboxCounts outbox = outbox(c, cohortStarts, leaseMs, from, to);
        long completed = scalar(
                c,
                "SELECT count(*) FROM workflow_instances WHERE status = 'COMPLETED'"
                        + " AND completed_at >= to_timestamp(?) AND completed_at < to_timestamp(?)",
                from,
                to);
        double windowSec = to - from;
        Map<String, Long> recordedByKind = new LinkedHashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT kind, count(*) FROM workflow_commands WHERE recorded_at >= to_timestamp(?)"
                        + " AND recorded_at < to_timestamp(?) GROUP BY 1 ORDER BY 1")) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    recordedByKind.put(r.getString(1), r.getLong(2));
                }
            }
        }
        Map<Long, Long> recorded = series(
                c,
                "SELECT floor(extract(epoch from recorded_at))::bigint, count(*) FROM workflow_commands"
                        + " WHERE recorded_at >= to_timestamp(?) AND recorded_at < to_timestamp(?) GROUP BY 1 ORDER BY 1",
                runStart,
                runEnd);
        Map<Long, Long> completions = series(
                c,
                "SELECT floor(extract(epoch from completed_at))::bigint, count(*) FROM workflow_instances"
                        + " WHERE status = 'COMPLETED' AND completed_at >= to_timestamp(?)"
                        + " AND completed_at < to_timestamp(?) GROUP BY 1 ORDER BY 1",
                runStart,
                runEnd);
        Map<String, Map<Long, Long>> settled = new LinkedHashMap<>();
        for (String kind : List.of("START", "SIGNAL", "TIMER")) {
            settled.put(
                    kind,
                    series(
                            c,
                            "SELECT floor(extract(epoch from settled_at))::bigint, count(*) FROM workflow_commands"
                                    + " WHERE kind = '" + kind
                                    + "' AND state = 'COMPLETE' AND settled_at >= to_timestamp(?)"
                                    + " AND settled_at < to_timestamp(?) GROUP BY 1 ORDER BY 1",
                            runStart,
                            runEnd));
        }
        String wait = "extract(epoch from c.settled_at - c.recorded_at) * 1000";
        Map<Long, Double> byRecorded = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT floor(extract(epoch from c.recorded_at))::bigint, percentile_disc(0.95) WITHIN GROUP (ORDER BY "
                        + wait + ") FROM workflow_commands c WHERE c.kind IN ('START', 'SIGNAL') AND " + CLEAN
                        + " AND c.recorded_at >= to_timestamp(?) AND c.recorded_at < to_timestamp(?) GROUP BY 1 ORDER BY 1")) {
            ps.setDouble(1, runStart);
            ps.setDouble(2, runEnd);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    byRecorded.put(r.getLong(1), r.getDouble(2));
                }
            }
        }
        Map<Long, double[]> byWaitEnd = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT floor(extract(epoch from c.settled_at))::bigint, percentile_disc(0.95) WITHIN GROUP (ORDER BY "
                        + wait + "), min(extract(epoch from c.recorded_at)), count(*), max(" + wait
                        + ") FROM workflow_commands c WHERE c.kind IN ('START', 'SIGNAL') AND " + CLEAN
                        + " AND c.settled_at >= to_timestamp(?) AND c.settled_at < to_timestamp(?) GROUP BY 1 ORDER BY 1")) {
            ps.setDouble(1, runStart);
            ps.setDouble(2, runEnd);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    byWaitEnd.put(
                            r.getLong(1), new double[] {r.getDouble(2), r.getDouble(3), r.getLong(4), r.getDouble(5)});
                }
            }
        }
        if (durableStarts != null) {
            writeDurableStarts(c, durableStarts);
        }
        return new Report(
                snapshotAt,
                from,
                to,
                cohortStarts,
                cohortCompleted,
                starts,
                signals,
                combined,
                byKind,
                timers,
                outbox,
                completed,
                windowSec > 0 ? completed / windowSec : Double.NaN,
                recordedByKind,
                recorded,
                completions,
                settled,
                byRecorded,
                byWaitEnd,
                counts(c, "SELECT status, count(*) FROM workflow_instances GROUP BY 1 ORDER BY 1"),
                counts(c, "SELECT kind || '/' || state, count(*) FROM workflow_commands GROUP BY 1 ORDER BY 1"),
                counts(c, "SELECT state, count(*) FROM outbox GROUP BY 1 ORDER BY 1"));
    }

    /** Writes the start key and the instance status of every durable start, gzip-compressed, one per line. */
    private static void writeDurableStarts(Connection c, java.nio.file.Path file)
            throws SQLException, java.io.IOException {
        try (var out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file)),
                        java.nio.charset.StandardCharsets.UTF_8));
                Statement s = c.createStatement()) {
            s.setFetchSize(10_000);
            try (ResultSet r = s.executeQuery("SELECT d.key, i.status FROM workflow_dedup d"
                    + " JOIN workflow_instances i ON i.id = d.workflow_id WHERE d.kind = 'start'")) {
                while (r.next()) {
                    out.write(r.getString(1) + "," + r.getString(2) + "\n");
                }
            }
        }
    }

    private static CommandCounts commandCounts(Connection c, String kind, long expected, double from, double to)
            throws SQLException {
        Map<String, Long> by = new LinkedHashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT " + CATEGORY + " AS cat, count(*) FROM workflow_commands c" + " JOIN "
                        + COHORT + " k ON k.workflow_id = c.workflow_id WHERE c.kind = ? GROUP BY 1")) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            ps.setString(3, kind);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    by.put(r.getString(1), r.getLong(2));
                }
            }
        }
        long total = by.values().stream().mapToLong(Long::longValue).sum();
        return new CommandCounts(
                kind,
                expected,
                total,
                by.getOrDefault("qualifying", 0L),
                by.getOrDefault("retriedOrDeferred", 0L),
                by.getOrDefault("parkedEver", 0L),
                by.getOrDefault("unsettled", 0L),
                by.getOrDefault("parked", 0L),
                by.getOrDefault("dead", 0L),
                by.getOrDefault("otherTerminal", 0L));
    }

    private static Latency recordedToStart(Connection c, String kind, double from, double to) throws SQLException {
        String sql = "SELECT " + quantiles("extract(epoch from c.settled_at - c.recorded_at) * 1000")
                + " FROM workflow_commands c JOIN " + COHORT + " k ON k.workflow_id = c.workflow_id WHERE "
                + (kind == null ? "c.kind IN ('START', 'SIGNAL')" : "c.kind = '" + kind + "'") + " AND " + CLEAN
                + " AND NOT " + PARKED_EVER;
        return latency(c, sql, from, to);
    }

    private static TimerCounts timers(Connection c, long expected, double from, double to) throws SQLException {
        Map<String, Long> by = new LinkedHashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT " + CATEGORY + ", count(*) FROM workflow_commands c JOIN " + COHORT
                        + " k ON k.workflow_id = c.workflow_id WHERE c.kind = 'TIMER' GROUP BY 1")) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    by.put(r.getString(1), r.getLong(2));
                }
            }
        }
        long total = by.values().stream().mapToLong(Long::longValue).sum();
        String base = " FROM workflow_commands c JOIN " + COHORT
                + " k ON k.workflow_id = c.workflow_id WHERE c.kind = 'TIMER'";
        Latency lateness = latency(
                c,
                "SELECT " + quantiles("extract(epoch from c.settled_at - c.available_at) * 1000") + base + " AND "
                        + CLEAN + " AND NOT " + PARKED_EVER,
                from,
                to);
        Latency apart = latency(
                c,
                "SELECT " + quantiles("extract(epoch from c.settled_at - COALESCE(c.fire_at, c.available_at)) * 1000")
                        + base + " AND c.state = 'COMPLETE' AND c.settled_at IS NOT NULL AND NOT (c.attempts = 1"
                        + " AND c.transient_count = 0 AND c.deferral_count = 0) AND NOT " + PARKED_EVER,
                from,
                to);
        return new TimerCounts(
                expected,
                total,
                by.getOrDefault("qualifying", 0L),
                lateness,
                by.getOrDefault("retriedOrDeferred", 0L),
                apart,
                by.getOrDefault("parkedEver", 0L),
                by.getOrDefault("unsettled", 0L),
                by.getOrDefault("parked", 0L),
                by.getOrDefault("dead", 0L),
                by.getOrDefault("otherTerminal", 0L));
    }

    private static OutboxCounts outbox(Connection c, long expected, long leaseMs, double from, double to)
            throws SQLException {
        String join = " FROM " + COHORT + " k JOIN outbox o ON o.aggregate_id = k.workflow_id::text"
                + " AND o.destination = 'load.work'";
        long total;
        long afterRetry;
        long pending;
        long processing;
        long dead;
        long other;
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*),"
                + " count(*) FILTER (WHERE o.state = 'PUBLISHED' AND o.attempt > 0),"
                + " count(*) FILTER (WHERE o.state = 'PENDING'), count(*) FILTER (WHERE o.state = 'PROCESSING'),"
                + " count(*) FILTER (WHERE o.state = 'DEAD_LETTER'),"
                + " count(*) FILTER (WHERE o.state NOT IN ('PUBLISHED', 'PENDING', 'PROCESSING', 'DEAD_LETTER'))"
                + join)) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                total = r.getLong(1);
                afterRetry = r.getLong(2);
                pending = r.getLong(3);
                processing = r.getLong(4);
                dead = r.getLong(5);
                other = r.getLong(6);
            }
        }
        // A reclaim after a stale lease puts the row back without raising attempt, and the next claim replaces
        // claimed_at.
        // A claim within the lease of the start cannot follow a reclaim, so only such a claim is shown to be the first.
        String established = " AND o.attempt = 0 AND o.claimed_at IS NOT NULL AND o.claimed_at - k.started_at < "
                + leaseMs + " * interval '1 millisecond'";
        Latency startToClaim = latency(
                c,
                "SELECT " + quantiles("extract(epoch from o.claimed_at - k.started_at) * 1000") + join + " WHERE true"
                        + established,
                from,
                to);
        long unresolved = scalar(
                c,
                "SELECT count(*)" + join
                        + " WHERE o.attempt = 0 AND o.claimed_at IS NOT NULL AND o.claimed_at - k.started_at >= "
                        + leaseMs + " * interval '1 millisecond'",
                from,
                to);
        Latency raw = latency(
                c,
                "SELECT " + quantiles("extract(epoch from o.published_at - o.claimed_at) * 1000") + join
                        + " WHERE o.state = 'PUBLISHED' AND o.attempt = 0 AND o.claimed_at IS NOT NULL"
                        + " AND o.published_at IS NOT NULL",
                from,
                to);
        Map<String, Long> scopes = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT o.scope_key, count(*)" + join + " GROUP BY 1")) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    scopes.put(r.getString(1), r.getLong(2));
                }
            }
        }
        long peak = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(running), 0) FROM (SELECT sum(d) OVER"
                + " (PARTITION BY node ORDER BY t, d) AS running FROM (SELECT o.claimed_by AS node, o.claimed_at AS t, 1 AS d"
                + join + " WHERE o.state = 'PUBLISHED' AND o.attempt = 0 AND o.claimed_at IS NOT NULL"
                + " AND o.published_at IS NOT NULL UNION ALL SELECT o.claimed_by, o.published_at, -1" + join
                + " WHERE o.state = 'PUBLISHED' AND o.attempt = 0 AND o.claimed_at IS NOT NULL"
                + " AND o.published_at IS NOT NULL) e) r")) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            ps.setDouble(3, from);
            ps.setDouble(4, to);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                peak = r.getLong(1);
            }
        }
        return new OutboxCounts(
                expected,
                total,
                startToClaim,
                unresolved,
                raw,
                afterRetry,
                pending,
                processing,
                dead,
                other,
                scopes,
                peak);
    }

    private static String quantiles(String expression) {
        return "count(*), percentile_disc(0.5) WITHIN GROUP (ORDER BY " + expression + "),"
                + " percentile_disc(0.95) WITHIN GROUP (ORDER BY " + expression + "),"
                + " percentile_disc(0.99) WITHIN GROUP (ORDER BY " + expression + "), max(" + expression + ")";
    }

    private static Latency latency(Connection c, String sql, double from, double to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                long count = r.getLong(1);
                if (count == 0) {
                    return Latency.NONE;
                }
                return new Latency(count, r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5));
            }
        }
    }

    private static long scalar(Connection c, String sql, double from, double to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static Map<Long, Long> series(Connection c, String sql, double from, double to) throws SQLException {
        Map<Long, Long> result = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, from);
            ps.setDouble(2, to);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    result.put(r.getLong(1), r.getLong(2));
                }
            }
        }
        return result;
    }

    private static Map<String, Long> counts(Connection c, String sql) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
                ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                result.put(r.getString(1), r.getLong(2));
            }
        }
        return result;
    }

    private static JsonObject names() {
        return new JsonObject()
                .put(
                        "recordedToSegmentTransactionStart",
                        "settled_at - recorded_at: an upper bound on recorded-to-claimed")
                .put("timerTransactionStartLateness", "settled_at - available_at: an upper bound on lateness at claim")
                .put(
                        "startToOutboxClaimTransactionStart",
                        "outbox.claimed_at - START recorded_at, first claim established: a correlated proxy")
                .put("dispatchRaw", "published_at - claimed_at with the handler delay included")
                .put("completed", "instances with status COMPLETED, counted at completed_at");
    }

    private static Object number(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private static JsonArray pairs(Map<Long, Long> map) {
        JsonArray array = new JsonArray();
        map.forEach((k, v) -> array.add(new JsonArray().add(k).add(v)));
        return array;
    }

    private static JsonArray pairsD(Map<Long, Double> map) {
        JsonArray array = new JsonArray();
        map.forEach((k, v) -> array.add(new JsonArray().add(k).add(v)));
        return array;
    }
}

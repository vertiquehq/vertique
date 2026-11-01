// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.admin.LoadAdmin;
import dev.vertique.examples.workflow.load.harness.eval.CsvTable;
import dev.vertique.examples.workflow.load.harness.eval.Validity;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import dev.vertique.examples.workflow.load.support.ItDatabase;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real SQL sampler against a database whose command table is locked for longer than the sampler's query can
 * wait, and shows that the collector's heartbeat does not stop while the database samples do, so that a database outage
 * is system evidence at every beat and cannot be mistaken for a collector failure.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CollectorHeartbeatIT {

    private static ItDatabase db;

    @BeforeAll
    static void provision() {
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
    }

    @AfterAll
    static void release() {
        db.close();
    }

    @Test
    @DisplayName(
            "while the database does not answer for longer than the old coverage and gap limits allowed, the heartbeat goes on")
    void heartbeatSurvivesAnOutage(@TempDir Path dir) throws Exception {
        JsonObject d = db.dbSection(null, 1);
        CollectorSpec spec = CollectorSpec.read(new JsonObject()
                .put("outDir", dir.toString())
                .put("stopFile", dir.resolve("stop").toString())
                .put("pidsFile", dir.resolve("pids").toString())
                .put("host", d.getString("host"))
                .put("port", d.getInteger("port"))
                .put("database", d.getString("database"))
                .put("user", d.getString("user"))
                .put("password", d.getString("password"))
                .put("sampleIntervalMs", 500)
                .put("queryTimeoutMs", 3000)
                .put("hostSampling", false)
                .put("vmSampling", false));
        AtomicBoolean stop = new AtomicBoolean();
        SqlSampler sampler = new SqlSampler(spec, dir, stop);
        Thread thread = new Thread(sampler, "sampler");
        thread.start();
        Thread.sleep(1_500);
        long lockedAt = System.currentTimeMillis();
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("LOCK TABLE workflow_commands IN ACCESS EXCLUSIVE MODE");
                Thread.sleep(7_000);
            }
            c.commit();
        }
        long unlockedAt = System.currentTimeMillis();
        Thread.sleep(1_500);
        stop.set(true);
        thread.join(8_000);

        CsvTable beats = CsvTable.read(dir.resolve("heartbeat.csv"));
        CsvTable samples = CsvTable.read(dir.resolve("seconds.csv"));
        long beatGap = maxGap(beats.numbers("t_ms"), lockedAt, unlockedAt);
        long sampleGap = maxGap(samples.numbers("t_ms"), lockedAt, unlockedAt);
        assertTrue(beatGap < 1_500, "the heartbeat kept its schedule: longest gap " + beatGap + " ms");
        assertTrue(sampleGap > 3_000, "the database samples broke the old gap limit: " + sampleGap + " ms");
        boolean outstanding = false;
        double longestSinceSuccess = 0;
        for (int i = 0; i < beats.size(); i++) {
            outstanding |= "querying".equals(beats.text(i, "worker_state"));
            double since = beats.number(i, "since_last_success_ms");
            if (!Double.isNaN(since)) {
                longestSinceSuccess = Math.max(longestSinceSuccess, since);
            }
        }
        assertTrue(outstanding, "a beat says a database query was outstanding");
        assertTrue(longestSinceSuccess > 5_000, "and how long the database had not answered: " + longestSinceSuccess);
        long failures = 0;
        for (int i = 0; i < samples.size(); i++) {
            failures += "false".equals(samples.text(i, "db_ok")) ? 1 : 0;
        }
        assertTrue(failures >= 1, "the failed samples are rows, with their error");
        double last = beats.number(beats.size() - 1, "since_last_success_ms");
        assertTrue(last < 2_500, "sampling resumed after the lock: " + last);

        // The verdict on the collector rests on the heartbeat: the same outage that broke the sample limits is valid.
        Thresholds limits = Thresholds.defaults();
        Validity.Result verdict = Validity.evaluate(
                new Validity.GeneratorFacts(true, 0, 0, 0, 0, 0, false),
                new Validity.CollectionFacts(true, 10, 10, beatGap, 0, false, 0, 0, 0, List.of()),
                new Validity.ConfigurationFacts(List.of(), "", ""),
                limits);
        assertTrue(verdict.valid(), verdict.toJson().encode());
    }

    private static long maxGap(double[] times, long from, long to) {
        long gap = 0;
        double previous = Double.NaN;
        for (double t : times) {
            if (t >= from - 500 && t <= to + 500) {
                if (!Double.isNaN(previous)) {
                    gap = Math.max(gap, (long) (t - previous));
                }
                previous = t;
            }
        }
        return gap;
    }
}

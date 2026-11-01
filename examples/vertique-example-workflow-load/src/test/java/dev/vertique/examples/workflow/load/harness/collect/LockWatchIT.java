// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.eval.Gates;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import dev.vertique.examples.workflow.load.support.ItDatabase;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Makes sessions of two nodes queue on the notification lock, object {@code 1262:0}, and checks that the lock watch
 * sees the waiters, names the application of each one, and that the gate attributes them to nodes.
 *
 * <p>The commit delay makes every commit that sends a notification hold the lock for 50 ms, so that eight sessions
 * committing at once always leave waiters behind.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LockWatchIT {

    private static ItDatabase db;

    @BeforeAll
    static void provision() {
        db = new ItDatabase().start();
    }

    @AfterAll
    static void release() {
        db.close();
    }

    private static String url(String application) {
        JsonObject d = db.dbSection(application, 1);
        return "jdbc:postgresql://" + d.getString("host") + ":" + d.getInteger("port") + "/" + d.getString("database")
                + "?ApplicationName=" + application + "&sslmode=disable";
    }

    private static CollectorSpec spec(Path dir) {
        JsonObject d = db.dbSection(null, 1);
        return CollectorSpec.read(new JsonObject()
                .put("outDir", dir.toString())
                .put("stopFile", dir.resolve("stop").toString())
                .put("pidsFile", dir.resolve("pids").toString())
                .put("host", d.getString("host"))
                .put("port", d.getInteger("port"))
                .put("database", d.getString("database"))
                .put("user", d.getString("user"))
                .put("password", d.getString("password"))
                .put("lockWatch", true)
                .put("lockWatchIntervalMs", 20));
    }

    @Test
    @DisplayName("a waiter on 1262:0 during a run with the triggers on is attributed to the node whose session waited")
    void waiterIsAttributedToANode(@TempDir Path dir) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        LockWatch watch = new LockWatch(spec(dir), dir, stop);
        Thread watcher = new Thread(watch, "watch");
        AtomicBoolean writers = new AtomicBoolean(true);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String application = "load-node-" + (1 + i % 2);
            Thread t = new Thread(() -> commitNotifications(application, writers));
            threads.add(t);
            t.start();
        }
        watcher.start();
        Thread.sleep(3_000);
        writers.set(false);
        for (Thread t : threads) {
            t.join();
        }
        stop.set(true);
        watcher.join();

        JsonObject summary = new JsonObject(Files.readString(dir.resolve("lockwatch-summary.json")));
        assertTrue(summary.getLong("samples") > 20, summary.encode());
        assertTrue(summary.getLong("samplesWithWaiters") > 0, "no waiter seen: " + summary.encode());
        JsonObject byApp = summary.getJsonObject("waiterObservationsByApplication");
        assertFalse(byApp.isEmpty());
        for (String app : byApp.fieldNames()) {
            assertTrue(app.startsWith("load-node-"), "waiter attributed to " + app);
        }
        List<String> rows = Files.readAllLines(dir.resolve("lockwatch.csv"));
        assertEquals("t_ms,pid,application,state,query", rows.get(0));
        assertTrue(rows.size() > 1);
        assertTrue(rows.get(1).contains("load-node-"), rows.get(1));

        Map<String, Long> counts = new TreeMap<>();
        byApp.fieldNames().forEach(app -> counts.put(app, byApp.getLong(app)));
        long node = counts.values().stream().mapToLong(Long::longValue).sum();
        Gates.Result gate = Gates.notificationLock(
                new Gates.Lock(true, summary.getLong("samples"), summary.getLong("samples"), 0, node, 0, counts),
                Thresholds.defaults());
        assertEquals(Gates.Verdict.FAIL, gate.verdict());
        assertTrue(gate.detail().contains("load-node-"), gate.detail());
        assertEquals(Thresholds.defaults().g2Ratio(), 0.9);
    }

    @Test
    @DisplayName("with nothing committing a notification the watch takes samples and sees no waiter")
    void quiet(@TempDir Path dir) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread watcher = new Thread(new LockWatch(spec(dir), dir, stop), "watch");
        watcher.start();
        Thread.sleep(500);
        stop.set(true);
        watcher.join();

        JsonObject summary = new JsonObject(Files.readString(dir.resolve("lockwatch-summary.json")));
        assertTrue(summary.getLong("samples") > 5);
        assertEquals(0, summary.getLong("samplesWithWaiters"));
        assertEquals(0, summary.getLong("queryErrors"));
    }

    private static void commitNotifications(String application, AtomicBoolean running) {
        try (Connection c = DriverManager.getConnection(
                        url(application),
                        db.dbSection(null, 1).getString("user"),
                        db.dbSection(null, 1).getString("password"));
                Statement s = c.createStatement()) {
            s.execute("SET commit_delay = 50000");
            s.execute("SET commit_siblings = 1");
            c.setAutoCommit(false);
            while (running.get()) {
                s.execute("SELECT pg_notify('probe', 'x')");
                c.commit();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

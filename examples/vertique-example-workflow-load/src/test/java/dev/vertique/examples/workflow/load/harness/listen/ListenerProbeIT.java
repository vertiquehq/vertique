// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.config.LoadArm;
import dev.vertique.examples.workflow.load.support.ItDatabase;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Finds listeners by what their sessions ran, from a session of the harness, and records a killed listener as a loss and
 * its replacement as a reconnect after the set is complete again.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ListenerProbeIT {

    private static ItDatabase db;

    @BeforeAll
    static void provision() {
        db = new ItDatabase().start();
    }

    @AfterAll
    static void release() {
        db.close();
    }

    private static Connection open(String application) throws SQLException {
        JsonObject d = db.dbSection(null, 1);
        return DriverManager.getConnection(
                "jdbc:postgresql://" + d.getString("host") + ":" + d.getInteger("port") + "/" + d.getString("database")
                        + "?ApplicationName=" + application + "&sslmode=disable",
                d.getString("user"),
                d.getString("password"));
    }

    private static Connection listener(String application, String channel) throws SQLException {
        Connection c = open(application);
        try (Statement s = c.createStatement()) {
            s.execute("LISTEN " + channel);
        }
        return c;
    }

    @Test
    @DisplayName(
            "a pooled session of the same application is not a listener, a session that ran LISTEN is, with pid and start")
    void identifiesByWhatTheSessionRan() throws Exception {
        try (Connection pooled = open("load-node-1");
                Connection workflow = listener("load-node-1", ListenerSession.WORKFLOW_CHANNEL);
                Connection outbox = listener("load-node-1", ListenerSession.OUTBOX_CHANNEL);
                Connection probe = open("load-collector");
                Statement s = pooled.createStatement()) {
            s.execute("SELECT 1");

            List<ListenerSession> sessions = ListenerProbe.probe(probe);

            assertEquals(2, sessions.size(), sessions.toString());
            assertTrue(sessions.stream().allMatch(x -> x.applicationName().equals("load-node-1")));
            assertEquals(
                    List.of("outbox", "workflow"),
                    sessions.stream().map(ListenerSession::channel).sorted().toList());
            assertTrue(sessions.stream().allMatch(x -> x.pid() > 0 && x.backendStartMs() > 1_600_000_000_000L));
        }
    }

    @Test
    @DisplayName("a killed listener backend is recorded as lost, and its replacement as a reconnect, after recovery")
    void killedListenerIsRecorded() throws Exception {
        List<ListenerSample> samples = new ArrayList<>();
        ListenerExpectation expectation = ListenerExpectation.of(LoadArm.ON, List.of("load-node-7"), 1);
        try (Connection probe = open("load-collector");
                Connection workflow = listener("load-node-7", ListenerSession.WORKFLOW_CHANNEL);
                Connection outbox = listener("load-node-7", ListenerSession.OUTBOX_CHANNEL)) {
            samples.add(new ListenerSample(1_000, true, ListenerProbe.probe(probe)));
            samples.add(new ListenerSample(5_000, true, ListenerProbe.probe(probe)));
            int victim = ListenerProbe.probe(probe).stream()
                    .filter(x -> x.channel().equals("workflow"))
                    .findFirst()
                    .orElseThrow()
                    .pid();
            try (Statement s = probe.createStatement()) {
                s.execute("SELECT pg_terminate_backend(" + victim + ")");
            }
            Thread.sleep(300);
            samples.add(new ListenerSample(6_000, true, ListenerProbe.probe(probe)));
            try (Connection replacement = listener("load-node-7", ListenerSession.WORKFLOW_CHANNEL)) {
                samples.add(new ListenerSample(8_000, true, ListenerProbe.probe(probe)));
                samples.add(new ListenerSample(60_000, true, ListenerProbe.probe(probe)));

                ListenerReport report = ListenerLedger.analyze(samples, expectation, 3_000, 50_000);

                assertTrue(report.before());
                assertTrue(report.after(), "the set is complete again at the end");
                assertTrue(report.anyLoss(), report.events().toString());
                assertTrue(report.anyReconnect(), report.events().toString());
                assertEquals("reconnected", report.status());
                ListenerEvent lost = report.events().get(0);
                assertEquals(ListenerEvent.Type.LOST, lost.type());
                assertEquals(victim, lost.pid());
                assertEquals("workflow", lost.channel());
            }
        }
    }

    @Test
    @DisplayName("a killed listener that never comes back is lost at the end")
    void neverComesBack() throws Exception {
        List<ListenerSample> samples = new ArrayList<>();
        ListenerExpectation expectation = ListenerExpectation.of(LoadArm.OFF, List.of("load-node-8"), 1);
        try (Connection probe = open("load-collector");
                Connection workflow = listener("load-node-8", ListenerSession.WORKFLOW_CHANNEL)) {
            samples.add(new ListenerSample(1_000, true, ListenerProbe.probe(probe)));
            int victim = ListenerProbe.probe(probe).get(0).pid();
            try (Statement s = probe.createStatement()) {
                s.execute("SELECT pg_terminate_backend(" + victim + ")");
            }
            Thread.sleep(300);
            samples.add(new ListenerSample(6_000, true, ListenerProbe.probe(probe)));
            samples.add(new ListenerSample(60_000, true, ListenerProbe.probe(probe)));

            ListenerReport report = ListenerLedger.analyze(samples, expectation, 3_000, 50_000);

            assertEquals("lost", report.status());
            assertTrue(report.anyLoss());
        }
    }
}

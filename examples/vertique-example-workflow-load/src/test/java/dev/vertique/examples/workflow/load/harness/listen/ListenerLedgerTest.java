// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.config.LoadArm;
import dev.vertique.examples.workflow.load.harness.listen.ListenerEvent.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that dedicated listeners are identified by what they ran, that the expected set per arm is checked before,
 * during and after the load, and that a reconnect stays in the record after it recovered.
 */
class ListenerLedgerTest {

    private static final long LOAD_START = 10_000;
    private static final long LOAD_END = 50_000;
    private static final List<String> APPS = List.of("load-node-1", "load-node-2");

    private static ListenerSession wf(String app, int pid, long start) {
        return new ListenerSession(pid, start, app, "workflow");
    }

    private static ListenerSession out(String app, int pid, long start) {
        return new ListenerSession(pid, start, app, "outbox");
    }

    private static List<ListenerSession> full() {
        return List.of(
                wf("load-node-1", 11, 1),
                out("load-node-1", 12, 1),
                wf("load-node-2", 21, 1),
                out("load-node-2", 22, 1));
    }

    private ListenerReport analyze(List<ListenerSample> samples) {
        return ListenerLedger.analyze(samples, ListenerExpectation.of(LoadArm.ON, APPS, 1), LOAD_START, LOAD_END);
    }

    @Nested
    @DisplayName("what a listener is")
    class Identification {

        @Test
        @DisplayName("a plain LISTEN names the channel, anything else is not a listener")
        void channelOfStatement() {
            assertEquals("workflow", ListenerSession.channelOf("LISTEN vertique_workflow_commands"));
            assertEquals("outbox", ListenerSession.channelOf("LISTEN transactional_outbox_channel"));
            assertEquals("outbox", ListenerSession.channelOf("  listen \"transactional_outbox_channel\" ; "));
            assertEquals("some_channel", ListenerSession.channelOf("LISTEN some_channel"));
            assertNull(ListenerSession.channelOf("SELECT 1"));
            assertNull(ListenerSession.channelOf("LISTEN a; SELECT 1"));
            assertNull(ListenerSession.channelOf(null));
        }

        @Test
        @DisplayName(
                "every node expects a workflow listener in every arm, and an outbox listener only when the relay listens")
        void expectationPerArm() {
            ListenerExpectation on = ListenerExpectation.of(LoadArm.ON, APPS, 2);
            ListenerExpectation off = ListenerExpectation.of(LoadArm.OFF, APPS, 2);

            assertEquals(1, on.expected("load-node-1", "workflow"));
            assertEquals(2, on.expected("load-node-1", "outbox"));
            assertEquals(1, off.expected("load-node-2", "workflow"));
            assertEquals(0, off.expected("load-node-2", "outbox"));
        }

        @Test
        @DisplayName("a pid and a start time make one connection; the same pid with a later start is another")
        void identity() {
            assertEquals("11@5", wf("a", 11, 5).identity());
            assertFalse(wf("a", 11, 5).identity().equals(wf("a", 11, 9).identity()));
        }
    }

    @Nested
    @DisplayName("the three checks")
    class Checks {

        @Test
        @DisplayName("a complete and steady set passes before, during and after, and leaves no event")
        void steady() {
            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, full()),
                    new ListenerSample(20_000, true, full()),
                    new ListenerSample(40_000, true, full()),
                    new ListenerSample(60_000, true, full())));

            assertTrue(r.before() && r.during() && r.after());
            assertTrue(r.events().isEmpty());
            assertEquals("unchanged", r.status());
            assertEquals(4, r.readings());
        }

        @Test
        @DisplayName("a missing listener before the load is reported with what is missing")
        void missingBefore() {
            List<ListenerSession> missing = new ArrayList<>(full());
            missing.remove(1);

            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, missing),
                    new ListenerSample(20_000, true, full()),
                    new ListenerSample(60_000, true, full())));

            assertFalse(r.before());
            assertTrue(
                    r.beforeDescription().contains("load-node-1/outbox: 0 listening, 1 expected"),
                    r.beforeDescription());
        }

        @Test
        @DisplayName("no reading before the load counts as a failed check")
        void noReadingBefore() {
            ListenerReport r = analyze(List.of(new ListenerSample(20_000, true, full())));

            assertFalse(r.before());
        }

        @Test
        @DisplayName("an extra listener is unexpected")
        void extra() {
            List<ListenerSession> extra = new ArrayList<>(full());
            extra.add(wf("load-node-1", 99, 7));

            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, full()),
                    new ListenerSample(20_000, true, extra),
                    new ListenerSample(60_000, true, full())));

            assertFalse(r.during());
            assertTrue(r.events().stream().anyMatch(e -> e.type() == Type.UNEXPECTED));
        }

        @Test
        @DisplayName("a reading the database did not answer says nothing about the listeners")
        void unanswered() {
            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, full()),
                    new ListenerSample(20_000, false, List.of()),
                    new ListenerSample(40_000, true, full()),
                    new ListenerSample(60_000, true, full())));

            assertTrue(r.during());
            assertTrue(r.events().isEmpty());
            assertEquals(3, r.readings());
        }
    }

    @Nested
    @DisplayName("losing and regaining a listener")
    class Reconnects {

        @Test
        @DisplayName("a killed listener that comes back is a loss and a reconnect, and stays in the record")
        void killedAndRecovered() {
            List<ListenerSession> down = new ArrayList<>(full());
            down.removeIf(s -> s.pid() == 11);
            List<ListenerSession> back = new ArrayList<>(down);
            back.add(wf("load-node-1", 31, 30_000));

            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, full()),
                    new ListenerSample(20_000, true, down),
                    new ListenerSample(35_000, true, back),
                    new ListenerSample(60_000, true, back)));

            assertTrue(r.after(), "the set is complete again at the end");
            assertTrue(r.anyLoss());
            assertTrue(r.anyReconnect());
            assertEquals("reconnected", r.status());
            List<Type> types = r.events().stream().map(ListenerEvent::type).toList();
            assertEquals(List.of(Type.LOST, Type.MISSING, Type.RECONNECTED, Type.RESTORED), types);
            ListenerEvent reconnect = r.events().get(2);
            assertEquals(31, reconnect.pid());
            assertTrue(reconnect.detail().contains("replaces lost pid 11"), reconnect.detail());
        }

        @Test
        @DisplayName("a replacement seen in the same reading is a reconnect without a gap")
        void replacedAtOnce() {
            List<ListenerSession> replaced = new ArrayList<>(full());
            replaced.removeIf(s -> s.pid() == 12);
            replaced.add(out("load-node-1", 41, 18_000));

            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, full()),
                    new ListenerSample(20_000, true, replaced),
                    new ListenerSample(60_000, true, replaced)));

            assertEquals(
                    List.of(Type.LOST, Type.RECONNECTED),
                    r.events().stream().map(ListenerEvent::type).toList());
            assertTrue(r.during());
            assertEquals("reconnected", r.status());
        }

        @Test
        @DisplayName("a listener that never comes back is lost at the end")
        void neverReturns() {
            List<ListenerSession> down = new ArrayList<>(full());
            down.removeIf(s -> s.pid() == 22);

            ListenerReport r = analyze(List.of(
                    new ListenerSample(5_000, true, full()),
                    new ListenerSample(20_000, true, down),
                    new ListenerSample(60_000, true, down)));

            assertFalse(r.after());
            assertEquals("lost", r.status());
            assertTrue(r.anyLoss());
            assertFalse(r.anyReconnect());
            assertEquals("load-node-2", r.events().get(0).application());
            assertEquals("outbox", r.events().get(0).channel());
        }

        @Test
        @DisplayName("the report renders every event")
        void json() {
            List<ListenerSession> down = new ArrayList<>(full());
            down.removeIf(s -> s.pid() == 22);

            ListenerReport r =
                    analyze(List.of(new ListenerSample(5_000, true, full()), new ListenerSample(20_000, true, down)));

            assertEquals("lost", r.toJson().getString("status"));
            assertEquals(2, r.toJson().getJsonArray("events").size());
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the readings of a run into the record of what happened to each dedicated listener.
 *
 * <p>The record is append-only: a listener that vanished stays in it as {@code LOST}, and one that came back stays in
 * it as {@code RECONNECTED}, so a recovery by the end of the run does not erase the interruption. A reading the
 * database did not answer is skipped, because it says nothing about the listeners.
 *
 * <p>Three checks compare the readings with the expected set: the last reading before the load, every reading during
 * it, and the last reading after it.
 */
public final class ListenerLedger {

    private ListenerLedger() {}

    private static final class Slot {
        Set<String> previous;
        final List<ListenerSession> lost = new ArrayList<>();
        boolean missing;
        boolean extra;
    }

    /**
     * Analyzes the readings of a run.
     *
     * @param samples the readings in time order
     * @param expected the expected listeners
     * @param loadStartMs the time the load started, in epoch milliseconds
     * @param loadEndMs the time the load and its recovery ended, in epoch milliseconds
     * @return the record and the three checks
     */
    public static ListenerReport analyze(
            List<ListenerSample> samples, ListenerExpectation expected, long loadStartMs, long loadEndMs) {
        Map<String, Slot> slots = new HashMap<>();
        List<ListenerEvent> events = new ArrayList<>();
        Map<String, ListenerSession> known = new HashMap<>();
        boolean haveBefore = false;
        boolean before = false;
        String beforeDescription = "no reading before the load";
        boolean during = true;
        boolean sawDuring = false;
        boolean after = false;
        int readings = 0;
        for (ListenerSample sample : samples) {
            if (!sample.answered()) {
                continue;
            }
            readings++;
            Map<String, List<ListenerSession>> bySlot = new HashMap<>();
            Set<String> slotNames = new TreeSet<>();
            for (ListenerSession session : sample.sessions()) {
                String name = session.applicationName() + "|" + session.channel();
                bySlot.computeIfAbsent(name, k -> new ArrayList<>()).add(session);
                slotNames.add(name);
                known.put(session.identity(), session);
            }
            expected.byApplication()
                    .forEach((app, channels) -> channels.keySet().forEach(ch -> slotNames.add(app + "|" + ch)));
            slots.keySet().forEach(slotNames::add);
            List<String> problems = new ArrayList<>();
            for (String name : slotNames) {
                int bar = name.indexOf('|');
                String app = name.substring(0, bar);
                String channel = name.substring(bar + 1);
                List<ListenerSession> current = bySlot.getOrDefault(name, List.of());
                Slot slot = slots.computeIfAbsent(name, k -> new Slot());
                observe(sample.timeMs(), app, channel, current, slot, expected.expected(app, channel), events, known);
                int want = expected.expected(app, channel);
                if (current.size() != want) {
                    problems.add(app + "/" + channel + ": " + current.size() + " listening, " + want + " expected");
                }
            }
            boolean exact = problems.isEmpty();
            if (sample.timeMs() < loadStartMs) {
                haveBefore = true;
                before = exact;
                beforeDescription = exact ? "" : String.join("; ", problems);
            } else if (sample.timeMs() <= loadEndMs) {
                sawDuring = true;
                during &= exact;
            } else {
                after = exact;
            }
        }
        if (!haveBefore) {
            before = false;
        }
        return new ListenerReport(
                before, sawDuring ? during : false, after, beforeDescription, List.copyOf(events), readings);
    }

    private static void observe(
            long timeMs,
            String app,
            String channel,
            List<ListenerSession> current,
            Slot slot,
            int want,
            List<ListenerEvent> events,
            Map<String, ListenerSession> known) {
        Set<String> now = new LinkedHashSet<>();
        current.forEach(s -> now.add(s.identity()));
        if (slot.previous != null) {
            for (String id : slot.previous) {
                if (!now.contains(id)) {
                    ListenerSession gone = known.get(id);
                    events.add(new ListenerEvent(
                            timeMs,
                            ListenerEvent.Type.LOST,
                            app,
                            channel,
                            gone.pid(),
                            "listener pid " + gone.pid() + " (backend start " + gone.backendStartMs() + ") is gone"));
                    slot.lost.add(gone);
                }
            }
            for (ListenerSession session : current) {
                if (!slot.previous.contains(session.identity()) && !slot.lost.isEmpty()) {
                    ListenerSession replaced = slot.lost.remove(0);
                    events.add(new ListenerEvent(
                            timeMs,
                            ListenerEvent.Type.RECONNECTED,
                            app,
                            channel,
                            session.pid(),
                            "pid " + session.pid() + " (backend start " + session.backendStartMs()
                                    + ") replaces lost pid " + replaced.pid()));
                }
            }
        }
        if (current.size() < want && !slot.missing) {
            slot.missing = true;
            events.add(new ListenerEvent(
                    timeMs,
                    ListenerEvent.Type.MISSING,
                    app,
                    channel,
                    0,
                    current.size() + " listening, " + want + " expected"));
        } else if (current.size() >= want && slot.missing) {
            slot.missing = false;
            events.add(new ListenerEvent(
                    timeMs, ListenerEvent.Type.RESTORED, app, channel, 0, "back at " + current.size() + " listening"));
        }
        if (current.size() > want && !slot.extra) {
            slot.extra = true;
            events.add(new ListenerEvent(
                    timeMs,
                    ListenerEvent.Type.UNEXPECTED,
                    app,
                    channel,
                    0,
                    current.size() + " listening, " + want + " expected"));
        } else if (current.size() <= want) {
            slot.extra = false;
        }
        slot.previous = now;
    }
}

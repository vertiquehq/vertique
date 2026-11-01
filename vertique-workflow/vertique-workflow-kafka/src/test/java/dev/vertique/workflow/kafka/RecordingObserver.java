// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import dev.vertique.workflow.source.SignalSourceObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps, in order, every outcome the delivery bean reports to its observers, so that a proof reads the events a source
 * fed. An event carries a source name, a transport, a contract name, a signal name, a definition id and an outcome,
 * never a key, a header or a payload.
 */
final class RecordingObserver implements SignalSourceObserver {

    private final List<SignalSourceEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onOutcome(SignalSourceEvent event) {
        events.add(event);
    }

    /**
     * Returns every event so far.
     *
     * @return the events, oldest first
     */
    List<SignalSourceEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the events of one source.
     *
     * @param source the source name
     * @return the events, oldest first
     */
    List<SignalSourceEvent> eventsOf(String source) {
        return events.stream().filter(event -> event.source().equals(source)).toList();
    }

    /**
     * Returns the outcomes of one source, in the order reported.
     *
     * @param source the source name
     * @return the outcomes, oldest first
     */
    List<Outcome> outcomesOf(String source) {
        return eventsOf(source).stream().map(SignalSourceEvent::outcome).toList();
    }

    /**
     * Counts the events of one source with the outcome.
     *
     * @param source the source name
     * @param outcome the outcome
     * @return the number of events
     */
    long count(String source, Outcome outcome) {
        return eventsOf(source).stream()
                .filter(event -> event.outcome() == outcome)
                .count();
    }

    /**
     * Waits until the source has reported the outcome at least {@code count} times.
     *
     * @param source the source name
     * @param outcome the outcome
     * @param count the number of events to wait for
     * @param timeout how long to wait
     * @return whether the events arrived in time
     */
    boolean await(String source, Outcome outcome, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (count(source, outcome) >= count) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return count(source, outcome) >= count;
    }
}

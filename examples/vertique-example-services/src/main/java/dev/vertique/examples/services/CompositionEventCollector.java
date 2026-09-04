// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared test-observable event sequence for the composition example. */
@Singleton
public final class CompositionEventCollector {

    private final CopyOnWriteArrayList<Object> events = new CopyOnWriteArrayList<>();

    /** Creates an empty event sequence. */
    @Inject
    public CompositionEventCollector() {}

    /**
     * Records one event from any of the three observer multibindings.
     *
     * @param event emitted event
     */
    public void record(Object event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /** Clears all recorded events before a new characterization row. */
    public void clear() {
        events.clear();
    }

    /**
     * @return number of recorded events
     */
    public int size() {
        return events.size();
    }

    /**
     * Returns a stable snapshot of the events recorded after an index.
     *
     * @param startIndex first index to include
     * @return immutable row snapshot
     */
    public List<Object> since(int startIndex) {
        List<Object> snapshot = List.copyOf(events);
        return List.copyOf(snapshot.subList(startIndex, snapshot.size()));
    }
}

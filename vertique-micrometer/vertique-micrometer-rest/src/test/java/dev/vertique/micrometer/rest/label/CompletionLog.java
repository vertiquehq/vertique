// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps every {@link RestRequestCompletedEvent} the composition emits, so a test can read the
 * operation identity a rejected request's event carried beside the series the metrics listener
 * recorded from the same event.
 */
public final class CompletionLog implements RestRequestCompletedListener {

    private final List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();

    CompletionLog() {}

    @Override
    public void onCompleted(RestRequestCompletedEvent event) {
        events.add(event);
    }

    /**
     * Returns a snapshot of the events received so far, in dispatch order.
     *
     * @return the REST completion events
     */
    public List<RestRequestCompletedEvent> events() {
        return List.copyOf(events);
    }
}

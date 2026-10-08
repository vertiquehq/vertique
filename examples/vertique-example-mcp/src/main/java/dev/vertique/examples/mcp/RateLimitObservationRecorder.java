// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-observable provider-neutral rate-limit event sink used by the MCP example. */
public final class RateLimitObservationRecorder implements RateLimitObserver {
    private final CopyOnWriteArrayList<RateLimitEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(RateLimitEvent event) {
        events.add(event);
    }

    public List<RateLimitEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-visible lifecycle listener for the MCP resilience integration proof. */
public final class McpResilienceObservationRecorder implements McpRequestCompletedListener {

    private final CopyOnWriteArrayList<McpRequestCompletedEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onCompleted(McpRequestCompletedEvent event) {
        events.add(event);
    }

    public List<McpRequestCompletedEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}

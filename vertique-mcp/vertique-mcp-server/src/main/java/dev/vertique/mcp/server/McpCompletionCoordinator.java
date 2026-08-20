// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import io.vertx.core.Context;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Coordinates one request's failure-isolated terminal and completion observation. */
final class McpCompletionCoordinator {
    private final Context context;
    private final List<McpRequestObservation> observations;
    private final Set<McpRequestCompletedListener> listeners;
    private boolean completed;

    McpCompletionCoordinator(
            Context context,
            Set<McpRequestLifecycleObserver> observers,
            Set<McpRequestCompletedListener> listeners,
            Instant startedAt) {
        this.context = context;
        this.observations = openObservers(observers, startedAt);
        this.listeners = Set.copyOf(listeners);
    }

    /** Settles once, redispatching off-context completion to the request-owning Vert.x context. */
    void complete(
            McpRequestTerminalEvent terminal,
            McpTransportOutcome transport,
            boolean responseCommitted,
            Instant completedAt) {
        context.runOnContext(ignored -> completeOnContext(terminal, transport, responseCommitted, completedAt));
    }

    private void completeOnContext(
            McpRequestTerminalEvent terminal,
            McpTransportOutcome transport,
            boolean responseCommitted,
            Instant completedAt) {
        if (completed) {
            return;
        }
        completed = true;
        McpRequestTerminalObservation observation = new McpRequestTerminalObservation(terminal, null);
        observations.forEach(item -> invoke(() -> item.onTerminal(observation)));
        McpRequestCompletedEvent event = completedEvent(terminal, transport, responseCommitted, completedAt);
        observations.forEach(item -> invoke(() -> item.onCompleted(event)));
        listeners.forEach(listener -> invoke(() -> listener.onCompleted(event)));
    }

    private static List<McpRequestObservation> openObservers(
            Set<McpRequestLifecycleObserver> observers, Instant startedAt) {
        List<McpRequestObservation> sessions = new ArrayList<>();
        observers.forEach(observer -> {
            try {
                McpRequestObservation session = observer.open(startedAt);
                if (session != null) {
                    sessions.add(session);
                }
            } catch (RuntimeException ignored) {
                // Observer failures are deliberately isolated from the protocol outcome.
            }
        });
        return List.copyOf(sessions);
    }

    private static McpRequestCompletedEvent completedEvent(
            McpRequestTerminalEvent terminal,
            McpTransportOutcome transport,
            boolean responseCommitted,
            Instant completedAt) {
        return switch (transport) {
            case WRITTEN -> McpRequestCompletedEvent.written(terminal, completedAt);
            case DISCONNECTED -> McpRequestCompletedEvent.disconnected(terminal, completedAt, responseCommitted);
            case RESET -> McpRequestCompletedEvent.reset(terminal, completedAt, responseCommitted);
            case WRITE_FAILED -> McpRequestCompletedEvent.writeFailed(terminal, completedAt, responseCommitted);
        };
    }

    private static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Framework observer/listener failures must not affect request settlement.
        }
    }
}

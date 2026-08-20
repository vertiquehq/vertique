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
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Coordinates one request's failure-isolated terminal and completion observation. */
final class McpCompletionCoordinator {
    private final Context context;
    private final List<McpRequestObservation> observations;
    private final Set<McpRequestCompletedListener> listeners;
    private final InstantSource clock;
    private boolean completed;

    McpCompletionCoordinator(
            Context context,
            Set<McpRequestLifecycleObserver> observers,
            Set<McpRequestCompletedListener> listeners,
            Instant startedAt) {
        this(context, observers, listeners, startedAt, InstantSource.system());
    }

    /**
     * Constructs a coordinator whose completion timestamps are drawn from {@code clock}.
     *
     * <p>The injectable clock is the settlement seam's deterministic time source: the disconnect,
     * reset, and timeout settlement paths (and the clock-timestamped {@link #complete} overload)
     * read the completion instant from it, so a test can drive the exactly-once settlement races
     * against a manual clock instead of wall time.
     *
     * @param context the request-owning Vert.x context every off-context completion is redispatched onto
     * @param observers the neutral lifecycle observers opened once for this request
     * @param listeners the completion listeners notified once at settlement
     * @param startedAt the instant this request began, passed to each observer's {@code open}
     * @param clock the time source completion instants are read from
     */
    McpCompletionCoordinator(
            Context context,
            Set<McpRequestLifecycleObserver> observers,
            Set<McpRequestCompletedListener> listeners,
            Instant startedAt,
            InstantSource clock) {
        this.context = context;
        this.observations = openObservers(observers, startedAt);
        this.listeners = Set.copyOf(listeners);
        this.clock = clock;
    }

    /** Settles once, redispatching off-context completion to the request-owning Vert.x context. */
    void complete(
            McpRequestTerminalEvent terminal,
            McpTransportOutcome transport,
            boolean responseCommitted,
            Instant completedAt) {
        context.runOnContext(ignored -> completeOnContext(terminal, transport, responseCommitted, completedAt));
    }

    /**
     * Settles once using the injected clock for the completion instant.
     *
     * @param terminal the logical terminal facts to publish before completion
     * @param transport the transport outcome the completion records
     * @param responseCommitted whether any response byte was committed
     */
    void complete(McpRequestTerminalEvent terminal, McpTransportOutcome transport, boolean responseCommitted) {
        complete(terminal, transport, responseCommitted, clock.instant());
    }

    // --- T004 settlement seam ---
    //
    // The disconnect, reset, and timeout settlement entries below are the internal seam the T004
    // dispatcher will call once it wires disconnect/reset/timeout handlers. They are deliberately
    // NON-FUNCTIONAL in this red slice: the frozen lifecycle contract promises exactly-once
    // terminal-before-completion delivery on these paths, but that behavior is implemented in the
    // T004 green slice. Until then these entries publish nothing, so the TP-003/TP-004 race and
    // observation matrices land red on the missing terminal/completion delivery rather than on a
    // setup or compilation error.

    /**
     * Settles the request when the client disconnects before or after the first write.
     *
     * <p>NON-FUNCTIONAL in the T004 red slice — no terminal or completion is delivered yet.
     *
     * @param terminal the synthesized terminal facts the green slice will publish
     * @param responseCommitted whether any response byte had been committed before the disconnect
     */
    void settleDisconnected(McpRequestTerminalEvent terminal, boolean responseCommitted) {
        // Intentionally not implemented in the red slice (see TP-003/TP-004).
    }

    /**
     * Settles the request when the response stream is reset.
     *
     * <p>NON-FUNCTIONAL in the T004 red slice — no terminal or completion is delivered yet.
     *
     * @param terminal the synthesized terminal facts the green slice will publish
     * @param responseCommitted whether any response byte had been committed before the reset
     */
    void settleReset(McpRequestTerminalEvent terminal, boolean responseCommitted) {
        // Intentionally not implemented in the red slice (see TP-003/TP-004).
    }

    /**
     * Settles the request when the whole-request timeout elapses.
     *
     * <p>NON-FUNCTIONAL in the T004 red slice — no terminal or completion is delivered yet.
     *
     * @param terminal the synthesized terminal facts the green slice will publish
     */
    void settleTimeout(McpRequestTerminalEvent terminal) {
        // Intentionally not implemented in the red slice (see TP-003/TP-004).
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

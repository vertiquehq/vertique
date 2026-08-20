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
    // dispatcher calls once a client disconnects, the response stream resets, or the whole-request
    // timeout elapses. Each drives exactly one terminal and exactly one completion through the same
    // first-observed-wins completed-guard as the write path, redispatched onto the request-owning
    // Vert.x context, with the completion instant read from the injected clock. A signal that arrives
    // after settlement has already won is suppressed, so the frozen lifecycle contract's exactly-once
    // terminal-before-completion guarantee holds on every settlement path.

    /**
     * Settles the request when the client disconnects before or after the first write.
     *
     * <p>Publishes exactly one terminal and one {@code DISCONNECTED} completion when this is the first
     * settlement; a later signal is suppressed.
     *
     * @param terminal the synthesized terminal facts to publish
     * @param responseCommitted whether any response byte had been committed before the disconnect
     */
    void settleDisconnected(McpRequestTerminalEvent terminal, boolean responseCommitted) {
        settle(terminal, McpTransportOutcome.DISCONNECTED, responseCommitted);
    }

    /**
     * Settles the request when the response stream is reset.
     *
     * <p>Publishes exactly one terminal and one {@code RESET} completion when this is the first
     * settlement; a later signal is suppressed.
     *
     * @param terminal the synthesized terminal facts to publish
     * @param responseCommitted whether any response byte had been committed before the reset
     */
    void settleReset(McpRequestTerminalEvent terminal, boolean responseCommitted) {
        settle(terminal, McpTransportOutcome.RESET, responseCommitted);
    }

    /**
     * Settles the request when the whole-request timeout elapses.
     *
     * <p>A timeout aborts the request without a successful write, so it records the
     * {@code WRITE_FAILED} transport outcome with an uncommitted response. Publishes exactly one
     * terminal and one completion when this is the first settlement; a later signal is suppressed.
     *
     * @param terminal the synthesized terminal facts to publish
     */
    void settleTimeout(McpRequestTerminalEvent terminal) {
        settle(terminal, McpTransportOutcome.WRITE_FAILED, false);
    }

    /**
     * Redispatches a settlement onto the request-owning context and drives the completed-guard once.
     *
     * @param terminal the terminal facts to publish before completion
     * @param transport the transport outcome the completion records
     * @param responseCommitted whether any response byte had been committed
     */
    private void settle(McpRequestTerminalEvent terminal, McpTransportOutcome transport, boolean responseCommitted) {
        Instant completedAt = clock.instant();
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
        // The completion instant can never precede logical settlement: the frozen lifecycle contract
        // requires completedAt >= terminalAt. Clamp to terminalAt so a settlement clock that reads
        // earlier than the terminal (clock skew, or a deterministic future-dated test terminal) still
        // produces a contract-valid completion rather than throwing between terminal and completion.
        Instant settledAt = completedAt.isBefore(terminal.terminalAt()) ? terminal.terminalAt() : completedAt;
        McpRequestCompletedEvent event = completedEvent(terminal, transport, responseCommitted, settledAt);
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

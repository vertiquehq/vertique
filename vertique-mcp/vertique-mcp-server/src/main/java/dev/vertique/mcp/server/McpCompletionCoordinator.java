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

    // Both latches are mutated only on the request-owning Vert.x context: the write path calls
    // beginWrite/finishWrite synchronously on that context, and every settlement path redispatches
    // onto it via context.runOnContext. They therefore need no memory barrier beyond the context's
    // own serialization.
    private boolean settled;
    private boolean completionEmitted;
    private McpRequestTerminalEvent writeTerminal;

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
     * <p>The injectable clock is the settlement seam's deterministic time source: the disconnect and
     * reset settlement paths read the completion instant from it, so a test can drive the
     * exactly-once settlement races against a manual clock instead of wall time. The two-phase
     * write path ({@link #beginWrite} then {@link #finishWrite}) is timestamped by its caller with
     * the actual transport-completion instant rather than the clock.
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

    // --- T004 two-phase write path ---
    //
    // The successful-write path settles logically before the byte write and completes after it, so
    // the frozen lifecycle contract's ordering — logical settlement, terminal observation, write
    // attempt, transport completion — holds: beginWrite publishes the terminal before end() runs, and
    // finishWrite publishes the completion once end() has resolved. Both phases share the settled
    // latch with the abort settlement paths below, so whichever fires first wins and the loser is
    // suppressed (the abort paths supply no intervening write, so they still fire terminal and
    // completion together).

    /**
     * Claims settlement for the successful-write path and publishes the terminal before the byte
     * write, executed synchronously on the request-owning context ahead of {@code end()}.
     *
     * <p>Returns {@code false} when a settlement (disconnect or reset) has already won, so
     * the caller suppresses the now-superseded client-visible write. Otherwise it wins the shared
     * first-observed latch, stores {@code terminal} for {@link #finishWrite}, publishes the terminal
     * observation, and returns {@code true}.
     *
     * @param terminal the logical terminal facts to publish before the write
     * @return {@code true} when this call wins settlement and the write should proceed, {@code false}
     *     when a prior settlement already won and the write must be suppressed
     */
    boolean beginWrite(McpRequestTerminalEvent terminal) {
        if (settled) {
            return false;
        }
        settled = true;
        writeTerminal = terminal;
        publishTerminal(terminal);
        return true;
    }

    /**
     * Publishes the completion for the successful-write path after {@code end()} has resolved,
     * executed on the request-owning context by the {@link #beginWrite} winner.
     *
     * <p>The completed event is built from the terminal {@code beginWrite} stored, recording the
     * actual transport outcome and commit state the caller observed. Guarded by the completion latch
     * so it publishes exactly once even under a redundant invocation.
     *
     * @param transport the transport outcome the write produced ({@code WRITTEN} or {@code WRITE_FAILED})
     * @param responseCommitted whether any response byte was committed, from the response's actual state
     * @param completedAt the instant the transport completed
     */
    void finishWrite(McpTransportOutcome transport, boolean responseCommitted, Instant completedAt) {
        if (completionEmitted) {
            return;
        }
        completionEmitted = true;
        publishCompletion(writeTerminal, transport, responseCommitted, completedAt);
    }

    // --- T004 settlement seam ---
    //
    // The disconnect and reset settlement entries below are the internal seam the T004 dispatcher
    // calls once a client disconnects or the response stream resets. MCP arms no whole-request timer
    // of its own (T007): a shared HttpConfig idle/read/write liveness expiry closes the connection, so
    // it reaches this same seam through the ordinary disconnect/reset path rather than a distinct
    // timeout entry. Each drives exactly one terminal and exactly one completion through the same
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
        if (settled) {
            return;
        }
        settled = true;
        completionEmitted = true;
        // An abort settlement supplies no intervening write, so the terminal and completion fire
        // together here, unlike the two-phase write path that splits them around end().
        publishTerminal(terminal);
        publishCompletion(terminal, transport, responseCommitted, completedAt);
    }

    /**
     * Publishes the terminal observation to every retained observation, isolating observer failures.
     *
     * @param terminal the terminal facts to publish
     */
    private void publishTerminal(McpRequestTerminalEvent terminal) {
        McpRequestTerminalObservation observation = new McpRequestTerminalObservation(terminal, null);
        observations.forEach(item -> invoke(() -> item.onTerminal(observation)));
    }

    /**
     * Publishes the completion to every retained observation and completion listener, isolating
     * their failures.
     *
     * @param terminal the terminal the completion is built from
     * @param transport the transport outcome the completion records
     * @param responseCommitted whether any response byte was committed
     * @param completedAt the instant the request completed
     */
    private void publishCompletion(
            McpRequestTerminalEvent terminal,
            McpTransportOutcome transport,
            boolean responseCommitted,
            Instant completedAt) {
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

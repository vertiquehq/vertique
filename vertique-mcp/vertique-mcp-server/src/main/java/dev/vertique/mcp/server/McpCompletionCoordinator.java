// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpCancellationSignal;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Coordinates one request's failure-isolated terminal and completion observation, and owns the
 * request's {@link McpCancellationSignal} (T013): a disconnect, a stream reset, or a failed write
 * fires it exactly once, confined to the same first-observed-wins settlement this class already
 * enforces, so a cooperative tool handler can stop early.
 */
final class McpCompletionCoordinator {
    private final Context context;
    private final List<McpRequestObservation> observations;
    private final boolean hasValueObservers;
    private final Set<McpRequestCompletedListener> listeners;
    private final InstantSource clock;
    private final McpRequestCancellationSignal cancellationSignal = new McpRequestCancellationSignal();

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
        this.hasValueObservers =
                this.observations.stream().anyMatch(session -> session instanceof McpToolValueObservation);
        this.listeners = Set.copyOf(listeners);
        this.clock = clock;
    }

    /**
     * Reports whether any retained session for this request implements the opt-in {@link
     * McpToolValueObservation} capability (T018/T020, contract §4.4).
     *
     * <p>Computed once at construction from the opened session set, never per-call: callers use this
     * to skip building a {@link McpToolInputObservation} or {@link McpToolOutputObservation} — and
     * the deep, unmodifiable copy their compact constructors perform — for a request no capable
     * session will ever see, so an attacker-sized argument or result tree is never deep-copied when
     * nothing consumes it.
     *
     * @return {@code true} when at least one retained session implements {@link
     *     McpToolValueObservation}
     */
    boolean hasValueObservers() {
        return hasValueObservers;
    }

    /**
     * Returns this request's {@link McpCancellationSignal} (T013), fired exactly once when the
     * request settles as anything other than a successful write — a disconnect, a stream reset, or a
     * failed write — so a cooperative tool handler invoked with it can stop early. A tool invoked
     * before this coordinator ever settles observes an unfired signal, exactly like every other call.
     *
     * @return the coordinator-owned cancellation signal for this request
     */
    McpCancellationSignal cancellation() {
        return cancellationSignal;
    }

    /**
     * Delivers {@code observation} to every retained session that implements the opt-in {@link
     * McpToolValueObservation} capability, isolating each session's failure exactly like {@link
     * #publishTerminal} (T018, contract §4.4).
     *
     * <p>Least privilege is structural: an ordinary {@link McpRequestObservation} session that does
     * not implement {@link McpToolValueObservation} is never even tested here — the {@code
     * instanceof} guard below is the sole gate, so such a session has no code path through which this
     * method could reach it. The coordinator declares no field for {@code observation}: the parameter
     * exists only on this call's stack and every session's synchronous callback frame, and is
     * unreachable through this instance once every {@code onToolInput} call below has returned.
     *
     * @param observation the bounded, normalized input observation for this request's tool call;
     *     must not be {@code null}
     */
    void publishToolInput(McpToolInputObservation observation) {
        observations.forEach(session -> {
            if (session instanceof McpToolValueObservation capable) {
                invoke(() -> capable.onToolInput(observation));
            }
        });
    }

    /**
     * Delivers {@code observation} to every retained session that implements the opt-in {@link
     * McpToolValueObservation} capability, isolating each session's failure exactly like {@link
     * #publishToolInput} (T020, contract §4.4).
     *
     * <p>Called only after the dispatcher's output stage has already normalized the result exactly
     * once and validated it against the tool's advertised output schema — this method itself performs
     * neither and trusts {@code observation} to already carry only a bounded, schema-valid normalized
     * value. Least privilege is structural, exactly like {@link #publishToolInput}: the {@code
     * instanceof} guard below is the sole gate, so a plain {@link McpRequestObservation} session has no
     * code path through which this method could ever reach it. The coordinator declares no field for
     * {@code observation}: the parameter exists only on this call's stack and every session's
     * synchronous callback frame, and is unreachable through this instance once every {@code
     * onToolOutput} call below has returned.
     *
     * @param observation the bounded, normalized, schema-valid output observation for this request's
     *     tool call; must not be {@code null}
     */
    void publishToolOutput(McpToolOutputObservation observation) {
        observations.forEach(session -> {
            if (session instanceof McpToolValueObservation capable) {
                invoke(() -> capable.onToolOutput(observation));
            }
        });
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
     * Publishes the completion for the successful-write path, executed on the request-owning context.
     *
     * <p>Normally called by the {@link #beginWrite} winner after its {@code end()} resolves. It is also
     * the recovery path for a stalled write: if a close/exception settlement arrives on the
     * request-owning context while {@code end()} is still pending, {@link #completeOnContext} calls
     * this directly with that settlement's transport outcome so the write's observation is not
     * stranded waiting for an {@code end()} that may never resolve.
     *
     * <p>The completed event is built from the terminal {@code beginWrite} stored, recording the
     * actual transport outcome and commit state the caller observed. Guarded by the completion latch
     * so it publishes exactly once even under a redundant invocation (a genuinely resolved {@code
     * end()} arriving after a stalled-write recovery already settled, or vice versa).
     *
     * @param transport the transport outcome the write produced ({@code WRITTEN} or {@code WRITE_FAILED}),
     *     or the outcome of the settlement that recovered a stalled write ({@code DISCONNECTED} or
     *     {@code RESET})
     * @param responseCommitted whether any response byte was committed, from the response's actual state
     * @param completedAt the instant the transport completed
     */
    void finishWrite(McpTransportOutcome transport, boolean responseCommitted, Instant completedAt) {
        if (completionEmitted) {
            return;
        }
        completionEmitted = true;
        if (transport != McpTransportOutcome.WRITTEN) {
            // T013: a failed write, or a disconnect/reset that recovered a stalled write, fires
            // cancellation exactly once, guarded by the same completionEmitted latch as everything
            // else this method publishes — a genuinely successful write never fires it.
            cancellationSignal.cancel();
        }
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
     * settlement. A signal that arrives after {@link #beginWrite} already won settlement, but before
     * that write's {@code end()} has resolved (a stalled write — e.g. a stopped TCP receive window),
     * drives {@link #finishWrite} with this outcome instead of being suppressed, so the stranded
     * observation still settles. A signal that arrives after completion has already been emitted (by
     * a resolved write or an earlier abort settlement) is suppressed.
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
     * settlement. A signal that arrives after {@link #beginWrite} already won settlement, but before
     * that write's {@code end()} has resolved (a stalled write), drives {@link #finishWrite} with this
     * outcome instead of being suppressed, so the stranded observation still settles. A signal that
     * arrives after completion has already been emitted is suppressed.
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
            // beginWrite already won settlement and published the write's terminal. If its end() has
            // not yet resolved, this close/exception signal is the only thing that can ever complete
            // the request: a stalled write (e.g. a client that stopped reading, so end() never
            // resolves) would otherwise strand the connection, the coordinator, and every open
            // observation forever, because finishWrite is the write path's sole completion publisher.
            // Drive it now with this settlement's transport outcome instead of dropping the signal.
            // finishWrite's own completionEmitted guard keeps this exactly-once: if end() later
            // resolves anyway, that redundant finishWrite call is a no-op.
            finishWrite(transport, responseCommitted, completedAt);
            return;
        }
        settled = true;
        completionEmitted = true;
        // T013: an abort settlement (disconnect or reset before any write) is never WRITTEN, so it
        // always fires cancellation, guarded exactly-once by the same completionEmitted latch.
        cancellationSignal.cancel();
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

    /**
     * The coordinator-owned {@link McpCancellationSignal} for one request (T013).
     *
     * <p>{@link #cancel()} is called only from {@link #finishWrite} and the abort branch of {@link
     * #completeOnContext}, both already guarded by {@code completionEmitted} so this fires at most
     * once; the idempotent check here is a defensive second guard, not load-bearing. Every call site
     * runs on the request-owning context (the same invariant every other settlement field relies on),
     * so a handler registered through {@link #cancelled()} observes it on that same context.
     */
    private static final class McpRequestCancellationSignal implements McpCancellationSignal {
        private final Promise<Void> cancelled = Promise.promise();
        private boolean cancelledFlag;

        @Override
        public boolean isCancelled() {
            return cancelledFlag;
        }

        @Override
        public Future<Void> cancelled() {
            return cancelled.future();
        }

        void cancel() {
            if (cancelledFlag) {
                return;
            }
            cancelledFlag = true;
            cancelled.complete();
        }
    }
}

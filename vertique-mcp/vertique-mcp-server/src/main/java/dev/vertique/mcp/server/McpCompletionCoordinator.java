// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.correlation.TraceReference;
import dev.vertique.mcp.lifecycle.McpCompletionScope;
import dev.vertique.mcp.lifecycle.McpRawEvidenceObservation;
import dev.vertique.mcp.lifecycle.McpRequestAdmissionEvidence;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpResponseEvidence;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpCancellationSignal;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates one request's failure-isolated terminal and completion observation, and owns the
 * request's {@link McpCancellationSignal}: a disconnect, a stream reset, or a failed write
 * fires it exactly once, confined to the same first-observed-wins settlement this class already
 * enforces, so a cooperative tool handler can stop early.
 */
@Slf4j
final class McpCompletionCoordinator {
    private final Context context;
    private final List<McpRequestObservation> observations;
    private final boolean hasValueObservers;
    private final boolean hasRawEvidenceObservers;
    private final Set<McpRequestCompletedListener> listeners;
    private final InstantSource clock;
    private final McpRequestCancellationSignal cancellationSignal = new McpRequestCancellationSignal();

    // Both latches are mutated only on the request-owning Vert.x context: the write path calls
    // beginWrite/finishWrite synchronously on that context, and settlement either completes inline
    // when already on it or redispatches there from another context. They therefore need no memory
    // barrier beyond the context's own serialization.
    private boolean settled;
    private boolean completionEmitted;
    private McpRequestTerminalEvent writeTerminal;

    /**
     * This request's optional linked trace reference, matching {@link
     * McpRequestTerminalObservation#linkedTrace()}, captured
     * exactly once by {@link #bindLinkedTrace} and read only by {@link #publishTerminal}. {@code
     * null} whenever {@code McpBodyTracePolicy.IGNORE} is configured (the default) or no valid body
     * trace reference was extracted. Mutated only on the request-owning context, exactly like
     * {@link #settled}/{@link #completionEmitted} above: {@code McpRequestDispatcher#dispatch} calls
     * {@link #bindLinkedTrace} synchronously, immediately after construction, before any interceptor
     * or write-path continuation can observe this coordinator or trigger a terminal publication.
     */
    @Nullable
    private TraceReference linkedTrace;

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
        this.hasRawEvidenceObservers =
                this.observations.stream().anyMatch(session -> session instanceof McpRawEvidenceObservation);
        this.listeners = Set.copyOf(listeners);
        this.clock = clock;
    }

    /**
     * Reports whether any retained session for this request implements the opt-in {@link
     * McpToolValueObservation} capability (contract §4.4).
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
     * Reports whether any retained session for this request implements the opt-in {@link
     * McpRawEvidenceObservation} capability.
     *
     * <p>Computed once at construction from the opened session set, exactly like {@link
     * #hasValueObservers()}: callers use this to skip building a {@link McpRequestAdmissionEvidence}
     * or {@link McpResponseEvidence} — and the raw body/header copy each carries — for a request no
     * capable session will ever see, so a deployment with no audit adapter installed pays nothing for
     * this seam.
     *
     * @return {@code true} when at least one retained session implements {@link
     *     McpRawEvidenceObservation}
     */
    boolean hasRawEvidenceObservers() {
        return hasRawEvidenceObservers;
    }

    /**
     * Delivers {@code evidence} to every retained session that implements the opt-in {@link
     * McpRawEvidenceObservation} capability, isolating each session's failure exactly like {@link
     * #publishToolInput}. Least privilege is structural: the {@code instanceof} guard below is
     * the sole gate, so an ordinary session has no code path through which this method could reach it.
     *
     * @param evidence this request's raw admission-time evidence; must not be {@code null}
     */
    void publishRequestAdmitted(McpRequestAdmissionEvidence evidence) {
        observations.forEach(session -> {
            if (session instanceof McpRawEvidenceObservation capable) {
                invoke(session, () -> capable.onRequestAdmitted(evidence));
            }
        });
    }

    /**
     * Delivers {@code evidence} to every retained session that implements the opt-in {@link
     * McpRawEvidenceObservation} capability, isolating each session's failure exactly like {@link
     * #publishRequestAdmitted}.
     *
     * @param evidence this request's raw response-side evidence; must not be {@code null}
     */
    void publishResponseWritten(McpResponseEvidence evidence) {
        observations.forEach(session -> {
            if (session instanceof McpRawEvidenceObservation capable) {
                invoke(session, () -> capable.onResponseWritten(evidence));
            }
        });
    }

    /**
     * Returns this request's {@link McpCancellationSignal}, fired exactly once when the
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
     * Captures this request's optional linked trace reference, once, for
     * {@link #publishTerminal} to carry on every terminal observation this coordinator later
     * publishes — the write-path terminal ({@link #beginWrite}) and every abort-settlement terminal
     * ({@link #completeOnContext}) alike.
     *
     * <p>Deliberately setter-free in shape: {@code McpRequestDispatcher#dispatch} is this method's
     * sole caller, invoking it exactly once, synchronously, immediately after this coordinator is
     * constructed and before any interceptor or write-path continuation can run — never a
     * general-purpose mutator called an arbitrary number of times over this coordinator's lifetime.
     *
     * @param linkedTrace the normalized W3C trace reference extracted from this request's body, or
     *     {@code null} when {@code McpBodyTracePolicy.IGNORE} is configured or none was captured
     */
    void bindLinkedTrace(@Nullable TraceReference linkedTrace) {
        this.linkedTrace = linkedTrace;
    }

    /**
     * Delivers {@code observation} to every retained session that implements the opt-in {@link
     * McpToolValueObservation} capability, isolating each session's failure exactly like {@link
     * #publishTerminal} (contract §4.4).
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
                invoke(session, () -> capable.onToolInput(observation));
            }
        });
    }

    /**
     * Delivers {@code observation} to every retained session that implements the opt-in {@link
     * McpToolValueObservation} capability, isolating each session's failure exactly like {@link
     * #publishToolInput} (contract §4.4).
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
                invoke(session, () -> capable.onToolOutput(observation));
            }
        });
    }

    // --- Two-phase write path ---
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
            // A failed write, or a disconnect/reset that recovered a stalled write, fires
            // cancellation exactly once, guarded by the same completionEmitted latch as everything
            // else this method publishes — a genuinely successful write never fires it.
            cancellationSignal.cancel();
        }
        publishCompletion(writeTerminal, transport, responseCommitted, completedAt);
    }

    // --- Settlement seam ---
    //
    // The disconnect and reset settlement entries below are the internal seam the dispatcher
    // calls once a client disconnects or the response stream resets. MCP arms no whole-request timer
    // of its own: a shared HttpConfig idle/read/write liveness expiry closes the connection, so
    // it reaches this same seam through the ordinary disconnect/reset path rather than a distinct
    // timeout entry. Each drives exactly one terminal and exactly one completion through the same
    // first-observed-wins completed-guard as the write path, on the request-owning Vert.x context
    // (inline when already current, otherwise redispatched), with the completion instant read from
    // the injected clock. A signal that arrives after settlement has already won is suppressed, so
    // the frozen lifecycle contract's exactly-once terminal-before-completion guarantee holds on every
    // settlement path.

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
     * Drives the completed-guard on the request-owning context, redispatching only from another context.
     *
     * @param terminal the terminal facts to publish before completion
     * @param transport the transport outcome the completion records
     * @param responseCommitted whether any response byte had been committed
     */
    private void settle(McpRequestTerminalEvent terminal, McpTransportOutcome transport, boolean responseCommitted) {
        Instant completedAt = clock.instant();
        if (Vertx.currentContext() == context) {
            completeOnContext(terminal, transport, responseCommitted, completedAt);
            return;
        }
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
        // An abort settlement (disconnect or reset before any write) is never WRITTEN, so it
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
     * <p>Carries {@link #linkedTrace} — this request's linked trace
     * reference, captured once by {@link #bindLinkedTrace} — on every terminal this method ever
     * publishes, whether from the write path ({@link #beginWrite}) or an abort settlement ({@link
     * #completeOnContext}).
     *
     * @param terminal the terminal facts to publish
     */
    private void publishTerminal(McpRequestTerminalEvent terminal) {
        McpRequestTerminalObservation observation = new McpRequestTerminalObservation(terminal, linkedTrace);
        observations.forEach(item -> invoke(item, () -> item.onTerminal(observation)));
    }

    /**
     * Publishes the completion to every retained observation and completion listener, isolating
     * their failures, and bracketing the whole dispatch loop with every retained {@link
     * McpCompletionScope} (contract §4.10).
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
        // The opened-scope list is declared here, before the try, and
        // populated incrementally by openCompletionScopes — which runs inside the try below — so
        // that a session's openCompletionScope() throwing an Error (not merely a RuntimeException)
        // still leaves every already-opened scope reachable to the finally. The shipped OpenTelemetry
        // consumer's scope is
        // span.makeCurrent(): an unclosed one leaves the span attached to the event-loop thread, and
        // every later request dispatched on that same thread inherits it — cross-request trace
        // contamination, not merely a resource leak.
        List<AutoCloseable> openedScopes = new ArrayList<>();
        try {
            openCompletionScopes(openedScopes);
            observations.forEach(item -> invoke(item, () -> item.onCompleted(event)));
            listeners.forEach(listener -> invoke(listener, () -> listener.onCompleted(event)));
        } finally {
            closeCompletionScopes(openedScopes);
        }
    }

    /**
     * Opens every retained session's {@link McpCompletionScope}, appending each opened scope to
     * {@code opened} immediately as it succeeds — so a later session's failure never loses an earlier
     * session's already-opened scope — and isolating each session's open failure (including an
     * {@link Error}, not merely a {@link RuntimeException}) exactly like {@link #closeCompletionScopes}
     * isolates each close. A session whose {@code openCompletionScope} throws, or returns
     * {@code null}, contributes no entry — its absence never affects any other session's scope or the
     * completion dispatch itself.
     *
     * @param opened the mutable, caller-owned accumulator scopes are appended to as they open
     */
    private void openCompletionScopes(List<AutoCloseable> opened) {
        for (McpRequestObservation session : observations) {
            if (session instanceof McpCompletionScope capable) {
                try {
                    AutoCloseable scope = capable.openCompletionScope();
                    if (scope != null) {
                        opened.add(scope);
                    }
                } catch (Throwable failure) {
                    // Throwable, not RuntimeException: an Error here must not abandon the
                    // scopes already opened by earlier sessions in this same loop — never logs the
                    // failure's own message, only the failing session's class.
                    log.warn(
                            "McpCompletionScope open failed on {}",
                            session.getClass().getName());
                }
            }
        }
    }

    /**
     * Closes every scope {@link #openCompletionScopes} opened, in reverse order, isolating each
     * scope's close failure — including an {@link Error} — so one misbehaving scope
     * cannot prevent another from closing.
     *
     * @param scopes the scopes to close, in open order
     */
    private static void closeCompletionScopes(List<AutoCloseable> scopes) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            AutoCloseable scope = scopes.get(i);
            try {
                scope.close();
            } catch (Throwable failure) {
                // Throwable, not Exception: an Error closing one scope must not prevent an
                // earlier-opened scope from closing — never logs the failure's own message, only the
                // failing scope's class.
                log.warn(
                        "McpCompletionScope close failed on {}",
                        scope.getClass().getName());
            }
        }
    }

    /**
     * Opens every contributed lifecycle observer once, isolating each failure so one misbehaving
     * observer never affects the protocol outcome or any other observer's session.
     *
     * <p>Isolates {@code RuntimeException | StackOverflowError}, not
     * {@code RuntimeException} alone. {@code observer.open} is application-supplied code, and a
     * native-recursion {@link StackOverflowError} from it is no less able to strand the request than a
     * plain {@code RuntimeException} would:
     * this method runs from the coordinator's own constructor, inside {@code McpRequestDispatcher#begin},
     * so an escaping {@code Error} aborts the request before the coordinator exists at all — no
     * coordinator, no settlement hooks, no terminal event, and every later observer left unopened.
     *
     * <p>Deliberately narrower than {@link #openCompletionScopes}/{@link #closeCompletionScopes}, which
     * catch {@link Throwable}: those two must additionally survive an {@code Error} that would otherwise
     * abandon a scope this same loop has <em>already opened</em>, an obligation no callback
     * site here carries.
     *
     * @param observers the contributed lifecycle observers
     * @param startedAt the instant this request began
     * @return the successfully opened sessions, in iteration order
     */
    private static List<McpRequestObservation> openObservers(
            Set<McpRequestLifecycleObserver> observers, Instant startedAt) {
        List<McpRequestObservation> sessions = new ArrayList<>();
        observers.forEach(observer -> {
            try {
                McpRequestObservation session = observer.open(startedAt);
                if (session != null) {
                    sessions.add(session);
                }
            } catch (RuntimeException | StackOverflowError failure) {
                // Observer failures are deliberately isolated from the protocol outcome; never logs the
                // failure's own message, only the failing observer's class.
                log.warn(
                        "MCP lifecycle observer open failed on {}",
                        observer.getClass().getName());
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

    /**
     * Runs {@code callback} — one observer/listener lifecycle invocation — isolating a {@link
     * RuntimeException} or {@link StackOverflowError} so a misbehaving observer or listener never
     * affects request settlement.
     *
     * <p>A failing observer/listener callback is logged, never silently and permanently discarded
     * with no operator-visible signal. {@code owner} identifies which retained session or listener failed; never logs the
     * failure's own message, matching {@link #openCompletionScopes}/{@link #closeCompletionScopes}'s
     * established non-leaking pattern for this same class.
     *
     * <p>Isolates {@code RuntimeException | StackOverflowError}, not {@code RuntimeException} alone.
     * Every
     * settlement path funnels through here: {@code onToolInput}, {@code onToolOutput}, {@code
     * onTerminal}, and {@code onCompleted} on every retained session, plus {@code onCompleted} on every
     * completion listener — all application-supplied code. An escaping {@code StackOverflowError} from
     * any one of them mid-loop would strand every session and listener still queued behind it, leave
     * the completion scopes opened around the loop unclosed, and — on the settlement
     * paths — escape inside a {@code context.runOnContext} task with no caller left to
     * catch it. Deliberately not {@link Throwable}: see {@link #openObservers}.
     *
     * @param owner the observation session or completion listener {@code callback} was built from
     * @param callback the lifecycle invocation to run
     */
    private static void invoke(Object owner, Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException | StackOverflowError failure) {
            log.warn("MCP lifecycle callback failed on {}", owner.getClass().getName());
        }
    }

    /**
     * The coordinator-owned {@link McpCancellationSignal} for one request.
     *
     * <p>{@link #cancel()} is called only from {@link #finishWrite} and the abort branch of {@link
     * #completeOnContext}, both already guarded by {@code completionEmitted} so this fires at most
     * once; the idempotent check here is a defensive second guard, not load-bearing. Every call site
     * runs on the request-owning context (the same invariant every other settlement field relies on).
     *
     * <p>{@code cancelled} is a plain, context-less {@link Promise},
     * so {@link #cancelled()} carries no context affinity of its own: a handler registered
     * through it before {@link #cancel()} fires is invoked inline, synchronously, from within {@code
     * cancel()}'s call — and therefore does observe the request-owning context, since {@code cancel()}
     * always runs there — but a handler registered after {@code cancelled} has already completed runs
     * inline on whichever thread performs that late registration, which need not be the request-owning
     * context at all. What {@link Future} actually guarantees here is ordering (cancellation is
     * observed no earlier than {@link #cancel()} ran), not context affinity; a caller that needs the
     * latter must anchor the returned future onto the request-owning context itself.
     */
    private static final class McpRequestCancellationSignal implements McpCancellationSignal {
        private final Promise<Void> cancelled = Promise.promise();

        // This one field alone crosses the context boundary by design: application workers backing a
        // tool invocation may poll isCancelled() from any thread, not only the request-owning Vert.x
        // context. Every other latch in this coordinator stays context-confined — do not "clean this
        // up" into consistency with them.
        private volatile boolean cancelledFlag;

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

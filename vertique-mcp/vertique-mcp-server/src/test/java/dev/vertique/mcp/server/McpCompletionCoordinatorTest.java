// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.mcp.lifecycle.McpCompletionScope;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T004 exactly-once settlement contract of {@link McpCompletionCoordinator}.
 *
 * <p>The coordinator must publish exactly one terminal event before exactly one completion event on
 * every settlement path — the two-phase successful write, disconnect, reset, and a late signal that
 * loses the race — and must suppress every signal that arrives after the first settlement wins. MCP
 * arms no whole-request timer of its own (T007): transport liveness is shared {@code HttpConfig}
 * idle/read/write behavior, so an idle or slow connection reaches the disconnect/reset settlement
 * paths through the ordinary close path rather than a distinct timeout entry. The successful-write
 * path is split around the byte write: {@link
 * McpCompletionCoordinator#beginWrite} publishes the terminal <em>before</em> the write and claims
 * the shared first-observed latch, and {@link McpCompletionCoordinator#finishWrite} publishes the
 * completion <em>after</em> the write resolves, recording the response's actual commit state.
 *
 * <p>Every settlement completion instant is drawn from a manual {@link InstantSource} so the races
 * are deterministic and free of wall-clock sleeps. The two-phase write path and its late-signal
 * suppression are driven on the request-owning Vert.x context (as the production write path is), so
 * the shared latch is read and written on one thread and the reverse-order suppression is
 * deterministic rather than a data race.
 */
class McpCompletionCoordinatorTest {

    private static final String SETTLE_ONCE_ROW = "shouldSettleOnceOnWriteDisconnectResetAndLateSignal";
    private static final String REDISPATCH_ORDER_ROW = "shouldRedispatchSettlementOffContextTerminalBeforeCompletion";

    /** A deterministic timeline: started, logically settled 10ms later, completed 20ms after start. */
    private static final Instant STARTED_AT = Instant.parse("2026-08-21T00:00:00Z");

    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(10);
    private static final Instant COMPLETED_AT = STARTED_AT.plusMillis(20);

    /** Bounded wait for the two settlement callbacks; a red settlement path exhausts it and fails. */
    private static final long SETTLEMENT_WAIT_SECONDS = 2;

    private final Vertx vertx = Vertx.vertx();

    /** Closes the owned {@link Vertx}, waiting for its teardown to settle. */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    private static Stream<String> settlementRows() {
        return Stream.of(SETTLE_ONCE_ROW, REDISPATCH_ORDER_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settlementRows")
    @DisplayName("T004 settlement matrix: exactly-once terminal-before-completion on every race")
    void shouldEnforceT004ContractMatrix(String row) throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);

        switch (row) {
            case SETTLE_ONCE_ROW -> {
                // Given: a two-phase write settlement wins first, so a later duplicate write and a
                // later disconnect must both be suppressed — exactly one terminal, one completion.
                RecordingObserver writeThenLateWrite = new RecordingObserver();
                McpCompletionCoordinator writeWinner = coordinator(context, clock, writeThenLateWrite);
                assertThat(beginWriteOnContext(context, writeWinner, successTerminal()))
                        .as("the first write must win settlement and publish its terminal")
                        .isTrue();
                finishWriteOnContext(context, writeWinner, McpTransportOutcome.WRITTEN, true);
                assertThat(writeThenLateWrite.awaitCallbacks())
                        .as("the winning write settlement must deliver its terminal and completion")
                        .isTrue();
                assertThat(beginWriteOnContext(context, writeWinner, successTerminal()))
                        .as("a duplicate write after settlement must be suppressed")
                        .isFalse();
                finishWriteOnContext(context, writeWinner, McpTransportOutcome.WRITTEN, true);
                writeThenLateWrite.assertExactlyOneTerminalThenOneCompletion();

                RecordingObserver writeThenLateDisconnect = new RecordingObserver();
                McpCompletionCoordinator writeThenDisconnect = coordinator(context, clock, writeThenLateDisconnect);
                assertThat(beginWriteOnContext(context, writeThenDisconnect, successTerminal()))
                        .isTrue();
                finishWriteOnContext(context, writeThenDisconnect, McpTransportOutcome.WRITTEN, true);
                assertThat(writeThenLateDisconnect.awaitCallbacks())
                        .as("the winning write settlement must deliver before any late disconnect")
                        .isTrue();
                writeThenDisconnect.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), true);
                flushContext(context);
                writeThenLateDisconnect.assertExactlyOneTerminalThenOneCompletion();

                // A client disconnect before any response write settles exactly one terminal and one
                // completion on the disconnect path.
                RecordingObserver disconnectAlone = new RecordingObserver();
                McpCompletionCoordinator disconnectWinner = coordinator(context, clock, disconnectAlone);
                disconnectWinner.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);
                assertThat(disconnectAlone.awaitCallbacks())
                        .as("a disconnect before any write must settle exactly one terminal and one completion")
                        .isTrue();
                disconnectAlone.assertExactlyOneTerminalThenOneCompletion();

                RecordingObserver resetAlone = new RecordingObserver();
                McpCompletionCoordinator resetWinner = coordinator(context, clock, resetAlone);
                resetWinner.settleReset(cancelledTerminal(McpErrorType.TRANSPORT), false);
                assertThat(resetAlone.awaitCallbacks())
                        .as("a reset before any write must settle exactly one terminal and one completion")
                        .isTrue();
                resetAlone.assertExactlyOneTerminalThenOneCompletion();
            }
            case REDISPATCH_ORDER_ROW -> {
                // Given: a settlement raised from a non-Vert.x thread must be redispatched onto the
                // request-owning context, notifying the terminal before the completion.
                RecordingObserver observer = new RecordingObserver();
                McpCompletionCoordinator coordinator = coordinator(context, clock, observer);

                coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);

                assertThat(observer.awaitCallbacks())
                        .as("the settlement must deliver both callbacks")
                        .isTrue();
                observer.assertExactlyOneTerminalThenOneCompletion();
                assertThat(observer.callbackContext())
                        .as("callbacks must run redispatched onto the request-owning Vert.x context")
                        .isNotNull()
                        .isSameAs(context);
            }
            default -> fail("unknown T004 settlement row: " + row);
        }
    }

    /**
     * C2: proves that for each abort settlement path — disconnect, reset — a settlement that
     * wins first suppresses a later write. This is the missing reverse-order proof: without the
     * shared latch guard in {@link McpCompletionCoordinator#beginWrite}, a slow handler's late write
     * would return {@code true} and reach a client the settlement already abandoned. Each row asserts
     * {@code beginWrite} returns {@code false} and that no second terminal or completion is emitted.
     *
     * @throws Exception if the context-marshalled write calls do not complete within their bound
     */
    @ParameterizedTest(name = "settlement wins then write is suppressed: {0}")
    @MethodSource("abortSettlements")
    @DisplayName("C2: a settlement that wins first suppresses a later write on every abort path")
    void shouldSuppressLateWriteAfterEachSettlementWins(AbortSettlement settlement) throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        RecordingObserver observer = new RecordingObserver();
        McpCompletionCoordinator coordinator = coordinator(context, clock, observer);

        // The settlement wins first; awaiting its callbacks guarantees the shared latch is set on the
        // request context before the late write is marshalled onto that same context.
        settlement.settle(coordinator);
        assertThat(observer.awaitCallbacks())
                .as("the winning settlement must deliver its terminal and completion")
                .isTrue();

        assertThat(beginWriteOnContext(context, coordinator, successTerminal()))
                .as("a write after the settlement won must be suppressed (returns false)")
                .isFalse();
        // finishWrite from the suppressed write must publish nothing.
        finishWriteOnContext(context, coordinator, McpTransportOutcome.WRITTEN, true);
        flushContext(context);

        observer.assertExactlyOneTerminalThenOneCompletion();
    }

    /**
     * W1: proves the successful-write path publishes the terminal at {@code beginWrite} — before the
     * byte write — and the completion only at {@code finishWrite} — after it. The snapshot is taken
     * inside one context task: immediately after {@code beginWrite} returns, exactly one terminal and
     * zero completions are observable; only after {@code finishWrite} does the completion appear. If
     * the terminal were emitted post-write (the W1 defect) the after-begin terminal count would be
     * zero.
     *
     * @throws Exception if the context task does not complete within its bound
     */
    @DisplayName("W1: terminal is published at beginWrite (pre-write), completion at finishWrite (post-write)")
    @Test
    void shouldPublishTerminalAtBeginWriteBeforeCompletionAtFinishWrite() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        RecordingObserver observer = new RecordingObserver();
        McpCompletionCoordinator coordinator = coordinator(context, clock, observer);

        CompletableFuture<WritePhaseSnapshot> snapshot = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                boolean began = coordinator.beginWrite(successTerminal());
                int terminalsAfterBegin = observer.terminalCount();
                int completionsAfterBegin = observer.completionCount();
                coordinator.finishWrite(McpTransportOutcome.WRITTEN, true, COMPLETED_AT);
                int completionsAfterFinish = observer.completionCount();
                snapshot.complete(new WritePhaseSnapshot(
                        began, terminalsAfterBegin, completionsAfterBegin, completionsAfterFinish));
            } catch (Throwable failure) {
                snapshot.completeExceptionally(failure);
            }
        });
        WritePhaseSnapshot phases = snapshot.get(SETTLEMENT_WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(phases.began()).as("beginWrite must win settlement").isTrue();
        assertThat(phases.terminalsAfterBegin())
                .as("the terminal must be observable at beginWrite, before the byte write")
                .isOne();
        assertThat(phases.completionsAfterBegin())
                .as("no completion may be published before finishWrite (before the byte write)")
                .isZero();
        assertThat(phases.completionsAfterFinish())
                .as("the completion must be published at finishWrite, after the byte write")
                .isOne();
    }

    /**
     * W3: proves the completion records the response's actual commit state passed to {@code
     * finishWrite}, not a success flag. A write whose {@code end()} future failed after the head was
     * already committed is driven as {@code finishWrite(WRITE_FAILED, true, ...)}; the completed event
     * must carry {@code responseCommitted=true}. Before the fix the dispatcher recorded the success
     * boolean ({@code false} on a failed write), so this asserted-{@code true} would have been false.
     *
     * @throws Exception if the context-marshalled write calls do not complete within their bound
     */
    @DisplayName("W3: a failed write with committed head records responseCommitted=true")
    @Test
    void shouldRecordActualCommitStateOnWriteFailure() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        RecordingObserver observer = new RecordingObserver();
        McpCompletionCoordinator coordinator = coordinator(context, clock, observer);

        assertThat(beginWriteOnContext(context, coordinator, successTerminal())).isTrue();
        finishWriteOnContext(context, coordinator, McpTransportOutcome.WRITE_FAILED, true);
        assertThat(observer.awaitCallbacks())
                .as("the write path must publish its terminal and completion")
                .isTrue();

        McpRequestCompletedEvent completed = observer.lastCompleted();
        assertThat(completed).isNotNull();
        assertThat(completed.transportOutcome())
                .as("a failed end() records the WRITE_FAILED outcome")
                .isEqualTo(McpTransportOutcome.WRITE_FAILED);
        assertThat(completed.responseCommitted())
                .as("the completion records the actual committed head state, not the end() success flag")
                .isTrue();
    }

    /**
     * P03 item 2: proves a stalled write — one whose {@code end()} future never resolves, e.g. a
     * client with a stopped TCP receive window — is still settled exactly once when the connection
     * closes, and that a genuinely resolved {@code end()} arriving afterward publishes nothing more.
     * Before the fix, {@code completeOnContext} found {@code settled} already {@code true} (set by
     * {@code beginWrite}) and returned immediately, dropping the disconnect signal and stranding the
     * observation forever.
     *
     * @throws Exception if the context-marshalled write calls do not complete within their bound
     */
    @DisplayName("stalled write: a disconnect after beginWrite wins still settles exactly one completion")
    @Test
    void shouldSettleAStalledWriteOnDisconnect() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        RecordingObserver observer = new RecordingObserver();
        McpCompletionCoordinator coordinator = coordinator(context, clock, observer);

        // beginWrite wins settlement and publishes the terminal synchronously; its end() is modeled as
        // never resolving, so finishWrite is never reached from the write path itself.
        assertThat(beginWriteOnContext(context, coordinator, successTerminal()))
                .as("beginWrite must win settlement")
                .isTrue();
        assertThat(observer.terminalCount())
                .as("the terminal must already be published before the stalled write settles")
                .isOne();
        assertThat(observer.completionCount())
                .as("no completion may be published while the write is stalled")
                .isZero();

        // The client goes away while end() is still pending; this is the only signal that can ever
        // complete this request.
        coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), true);

        assertThat(observer.awaitCallbacks())
                .as("the disconnect must drive the stalled write's completion instead of being dropped")
                .isTrue();
        observer.assertExactlyOneTerminalThenOneCompletion();
        assertThat(observer.lastCompleted().transportOutcome())
                .as("the completion records the disconnect's outcome, not a fabricated WRITTEN outcome")
                .isEqualTo(McpTransportOutcome.DISCONNECTED);

        // A late, genuine end() resolution must not publish a second completion.
        finishWriteOnContext(context, coordinator, McpTransportOutcome.WRITTEN, true);
        observer.assertExactlyOneTerminalThenOneCompletion();
    }

    // --- R06 (issue #435): McpCompletionScope bracketing ---

    /**
     * R06: proves the coordinator opens every retained session's {@link McpCompletionScope} before
     * <em>any</em> observation's or listener's {@code onCompleted} runs, and closes every opened scope
     * — in exact reverse open order — only after <em>all</em> of them have returned. Derived from the
     * shared event log's own recorded indices, not a hardcoded observer order, so it holds regardless
     * of {@link Set} iteration order. If the bracketing were removed (reverting to the pre-R06
     * dispatch loop), no {@code :open}/{@code :close} entries would ever be logged and the index
     * derivation below would fail outright.
     */
    @Test
    @DisplayName("R06: completion scopes open before any onCompleted and close, in reverse order, after all of them")
    void shouldOpenCompletionScopesBeforeDispatchAndCloseInReverseOrderAfter() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        List<String> log = new CopyOnWriteArrayList<>();
        ScopeOrderObserver scopeA = new ScopeOrderObserver(log, "A", false, false);
        ScopeOrderObserver scopeB = new ScopeOrderObserver(log, "B", false, false);
        PlainOrderObserver plain = new PlainOrderObserver(log, "P");

        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context,
                new LinkedHashSet<>(List.of(scopeA, scopeB, plain)),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT,
                clock);

        coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);
        flushContext(context);

        List<String> opens = eventsEndingWith(log, ":open");
        List<String> completions = eventsEndingWith(log, ":completed");
        List<String> closes = eventsEndingWith(log, ":close");

        assertThat(opens)
                .as("both completion-scope sessions must have opened")
                .containsExactlyInAnyOrder("A:open", "B:open");
        assertThat(completions)
                .as("every retained observation must have been notified of completion")
                .containsExactlyInAnyOrder("A:completed", "B:completed", "P:completed");
        assertThat(closes)
                .as("both completion-scope sessions must have closed")
                .containsExactlyInAnyOrder("A:close", "B:close");

        // DECISIVE: every open precedes every completion, and every completion precedes every close —
        // the bracket spans the whole dispatch loop, not an individual callback.
        int lastOpenIndex = log.indexOf(opens.get(opens.size() - 1));
        int firstCompletionIndex = log.indexOf(completions.get(0));
        int lastCompletionIndex = log.indexOf(completions.get(completions.size() - 1));
        int firstCloseIndex = log.indexOf(closes.get(0));
        assertThat(lastOpenIndex)
                .as("DECISIVE: the last scope open must precede the first onCompleted")
                .isLessThan(firstCompletionIndex);
        assertThat(lastCompletionIndex)
                .as("DECISIVE: the last onCompleted must precede the first scope close")
                .isLessThan(firstCloseIndex);

        // DECISIVE: scopes close in the exact reverse of their observed open order.
        String openOrderFirst = opens.get(0).split(":")[0];
        String openOrderSecond = opens.get(1).split(":")[0];
        assertThat(closes)
                .as("DECISIVE: scopes close in exact reverse of their open order")
                .containsExactly(openOrderSecond + ":close", openOrderFirst + ":close");
    }

    /**
     * R06: a session whose {@code openCompletionScope()} throws must not prevent any other session's
     * scope from opening, any observation's {@code onCompleted} from running, or that other session's
     * own scope from closing. A {@link LinkedHashSet} fixes the iteration order so the failing session
     * runs first — the harder ordering for the isolation guarantee to hold under.
     */
    @Test
    @DisplayName("R06: a scope-open failure is isolated and does not suppress other sessions' completion or scope")
    void shouldIsolateAScopeOpenFailureFromOtherSessions() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        List<String> log = new CopyOnWriteArrayList<>();
        ScopeOrderObserver failingOpen = new ScopeOrderObserver(log, "A", true, false);
        ScopeOrderObserver healthy = new ScopeOrderObserver(log, "B", false, false);
        PlainOrderObserver plain = new PlainOrderObserver(log, "P");

        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context,
                new LinkedHashSet<>(List.of(failingOpen, healthy, plain)),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT,
                clock);

        coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);
        flushContext(context);

        assertThat(log)
                .as("DECISIVE: the failing session's own open attempt is recorded but never closed, while "
                        + "the healthy session's scope and every observation's completion still run")
                .contains("A:open", "B:open", "B:close", "A:completed", "B:completed", "P:completed")
                .doesNotContain("A:close");
    }

    /**
     * R06: a session whose scope's {@code close()} throws must not prevent another session's scope
     * from closing. Both sessions are given a failing close so ordering cannot mask a skipped close —
     * if the loop aborted after the first failure, only one {@code :close} entry would ever appear.
     */
    @Test
    @DisplayName("R06: a scope-close failure is isolated and does not suppress another scope's close")
    void shouldIsolateAScopeCloseFailureFromOtherScopes() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        List<String> log = new CopyOnWriteArrayList<>();
        ScopeOrderObserver scopeA = new ScopeOrderObserver(log, "A", false, true);
        ScopeOrderObserver scopeB = new ScopeOrderObserver(log, "B", false, true);

        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context,
                new LinkedHashSet<>(List.of(scopeA, scopeB)),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT,
                clock);

        coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);
        flushContext(context);

        assertThat(eventsEndingWith(log, ":close"))
                .as("DECISIVE: every opened scope's close must run even though both throw")
                .containsExactlyInAnyOrder("A:close", "B:close");
    }

    // --- R07 item 6 (security review): an Error must not abandon an already-opened scope ---

    /**
     * R07 item 6: before this fix, {@code openCompletionScopes()} caught only {@link RuntimeException}
     * and was called <em>before</em> the try/finally even began, so an {@link Error} escaping one
     * session's {@code openCompletionScope()} propagated out of the whole call — abandoning the
     * already-opened earlier session's scope with no finally ever reached to close it. This is the
     * decisive proof an ordinary {@code RuntimeException} row (see {@link
     * #shouldIsolateAScopeOpenFailureFromOtherSessions()} above) cannot give: {@code RuntimeException}
     * was already caught per-session before this fix, so that row would pass identically whether or
     * not this fix exists. Only an {@link Error} distinguishes the two: the healthy session opens
     * <em>first</em>, so its scope is the one an unfixed implementation would lose.
     */
    @Test
    @DisplayName("R07 item 6: an Error from one session's scope open does not abandon an earlier-opened scope")
    void shouldNotAbandonAnEarlierOpenedScopeWhenALaterSessionsOpenThrowsAnError() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        List<String> log = new CopyOnWriteArrayList<>();
        ScopeOrderObserver healthy = ScopeOrderObserver.healthy(log, "B");
        ScopeOrderObserver failingWithError = ScopeOrderObserver.failingOpenWithError(log, "A");

        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context,
                new LinkedHashSet<>(List.of(healthy, failingWithError)),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT,
                clock);

        coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);
        flushContext(context);

        assertThat(log)
                .as("DECISIVE: the earlier-opened healthy scope must still close, and every session's "
                        + "completion must still run, even though a later session's open threw an Error")
                .contains("B:open", "A:open", "B:close", "A:completed", "B:completed")
                .doesNotContain("A:close");
    }

    /**
     * R07 item 6: mirrors {@link #shouldIsolateAScopeCloseFailureFromOtherScopes()} but with an {@link
     * Error} instead of a {@link RuntimeException} — the pre-fix {@code closeCompletionScopes} caught
     * only {@code Exception}, so an {@code Error} closing one scope would have skipped every scope
     * still queued to close after it.
     */
    @Test
    @DisplayName("R07 item 6: an Error from one scope's close does not prevent another scope's close")
    void shouldIsolateAScopeCloseErrorFromOtherScopes() throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);
        List<String> log = new CopyOnWriteArrayList<>();
        ScopeOrderObserver scopeA = ScopeOrderObserver.failingCloseWithError(log, "A");
        ScopeOrderObserver scopeB = ScopeOrderObserver.failingCloseWithError(log, "B");

        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context,
                new LinkedHashSet<>(List.of(scopeA, scopeB)),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT,
                clock);

        coordinator.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false);
        flushContext(context);

        assertThat(eventsEndingWith(log, ":close"))
                .as("DECISIVE: every opened scope's close must run even though both throw an Error")
                .containsExactlyInAnyOrder("A:close", "B:close");
    }

    private static List<String> eventsEndingWith(List<String> log, String suffix) {
        return log.stream().filter(entry -> entry.endsWith(suffix)).toList();
    }

    private static Stream<AbortSettlement> abortSettlements() {
        return Stream.of(
                new AbortSettlement(
                        "disconnect", c -> c.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), false)),
                new AbortSettlement("reset", c -> c.settleReset(cancelledTerminal(McpErrorType.TRANSPORT), false)));
    }

    private boolean beginWriteOnContext(
            Context context, McpCompletionCoordinator coordinator, McpRequestTerminalEvent terminal) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        context.runOnContext(ignored -> result.complete(coordinator.beginWrite(terminal)));
        return result.get(SETTLEMENT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private void finishWriteOnContext(
            Context context, McpCompletionCoordinator coordinator, McpTransportOutcome outcome, boolean committed)
            throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            coordinator.finishWrite(outcome, committed, COMPLETED_AT);
            done.complete(null);
        });
        done.get(SETTLEMENT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Posts and awaits a no-op task on {@code context}, guaranteeing every task queued before it —
     * such as a redispatched settlement that must be suppressed — has already run.
     */
    private void flushContext(Context context) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(ignored -> done.complete(null));
        done.get(SETTLEMENT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static McpCompletionCoordinator coordinator(
            Context context, InstantSource clock, RecordingObserver observer) {
        return new McpCompletionCoordinator(
                context,
                Set.<McpRequestLifecycleObserver>of(observer),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT,
                clock);
    }

    private static McpRequestTerminalEvent successTerminal() {
        return McpRequestTerminalEvent.success(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                null,
                null,
                null);
    }

    private static McpRequestTerminalEvent cancelledTerminal(McpErrorType errorType) {
        return McpRequestTerminalEvent.cancelled(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                errorType,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    /** A named abort settlement path for the reverse-order suppression matrix. */
    private record AbortSettlement(String name, Settlement action) {
        void settle(McpCompletionCoordinator coordinator) {
            action.settle(coordinator);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** One coordinator settlement invocation. */
    @FunctionalInterface
    private interface Settlement {
        void settle(McpCompletionCoordinator coordinator);
    }

    /** Snapshot of observer callback counts across the two write phases, taken inside one context task. */
    private record WritePhaseSnapshot(
            boolean began, int terminalsAfterBegin, int completionsAfterBegin, int completionsAfterFinish) {}

    /** Records terminal and completion callbacks, their arrival order, and their Vert.x context. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicReference<Context> callbackContext = new AtomicReference<>();
        private volatile int terminalCount;
        private volatile int completionCount;
        private volatile McpRequestCompletedEvent lastCompleted;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
            order.add("terminal");
            callbackContext.compareAndSet(null, Vertx.currentContext());
            callbacks.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount++;
            lastCompleted = event;
            order.add("completed");
            callbackContext.compareAndSet(null, Vertx.currentContext());
            callbacks.countDown();
        }

        boolean awaitCallbacks() throws InterruptedException {
            return callbacks.await(SETTLEMENT_WAIT_SECONDS, TimeUnit.SECONDS);
        }

        Context callbackContext() {
            return callbackContext.get();
        }

        int terminalCount() {
            return terminalCount;
        }

        int completionCount() {
            return completionCount;
        }

        McpRequestCompletedEvent lastCompleted() {
            return lastCompleted;
        }

        void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount)
                    .as("exactly one terminal event must be published")
                    .isOne();
            assertThat(completionCount)
                    .as("exactly one completion event must be published")
                    .isOne();
            assertThat(order)
                    .as("the terminal event must precede the completion event")
                    .containsExactly("terminal", "completed");
        }
    }

    /**
     * A fake session implementing {@link McpCompletionScope}: {@code name + ":open"} is logged when
     * {@code openCompletionScope()} is called (optionally throwing, per {@code failOpen}), and
     * {@code name + ":close"} when the returned scope is closed (optionally throwing, per
     * {@code failClose}); {@code name + ":completed"} is logged from {@code onCompleted}.
     */
    private static final class ScopeOrderObserver implements McpRequestLifecycleObserver, McpCompletionScope {
        private final List<String> log;
        private final String name;
        private final boolean failOpen;
        private final boolean failClose;
        private final boolean useError;

        ScopeOrderObserver(List<String> log, String name, boolean failOpen, boolean failClose) {
            this(log, name, failOpen, failClose, false);
        }

        private ScopeOrderObserver(
                List<String> log, String name, boolean failOpen, boolean failClose, boolean useError) {
            this.log = log;
            this.name = name;
            this.failOpen = failOpen;
            this.failClose = failClose;
            this.useError = useError;
        }

        /** A session whose scope opens and closes without failing. */
        static ScopeOrderObserver healthy(List<String> log, String name) {
            return new ScopeOrderObserver(log, name, false, false, false);
        }

        /** A session whose {@code openCompletionScope()} throws an {@link Error} (R07 item 6). */
        static ScopeOrderObserver failingOpenWithError(List<String> log, String name) {
            return new ScopeOrderObserver(log, name, true, false, true);
        }

        /** A session whose scope's {@code close()} throws an {@link Error} (R07 item 6). */
        static ScopeOrderObserver failingCloseWithError(List<String> log, String name) {
            return new ScopeOrderObserver(log, name, false, true, true);
        }

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public AutoCloseable openCompletionScope() {
            log.add(name + ":open");
            if (failOpen) {
                if (useError) {
                    throw new Error("scope-open-failure:" + name);
                }
                throw new RuntimeException("scope-open-failure:" + name);
            }
            return () -> {
                log.add(name + ":close");
                if (failClose) {
                    if (useError) {
                        throw new Error("scope-close-failure:" + name);
                    }
                    throw new RuntimeException("scope-close-failure:" + name);
                }
            };
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            log.add(name + ":completed");
        }
    }

    /** A fake session that only records {@code name + ":completed"} — never a {@link McpCompletionScope}. */
    private static final class PlainOrderObserver implements McpRequestLifecycleObserver {
        private final List<String> log;
        private final String name;

        PlainOrderObserver(List<String> log, String name) {
            this.log = log;
            this.name = name;
        }

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return new McpRequestObservation() {
                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    log.add(name + ":completed");
                }
            };
        }
    }

    /** A test-controlled {@link InstantSource} that returns a fixed instant until advanced. */
    private static final class ManualClock implements InstantSource {
        private volatile Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T013 TP-002 — the determinism claim for the write-phase settlement race.
 *
 * <p>Drives {@link McpRequestDispatcher#write(RoutingContext, int, byte[], McpRequestTerminalEvent)}
 * (made package-private for exactly this seam) against a mocked {@link RoutingContext}/{@link
 * HttpServerResponse} whose {@code end(Buffer)} returns a {@link Promise}-backed future this test
 * owns and completes explicitly — no socket, no buffer sizing, and no timing dependency: the write
 * future simply never completes until a row says so. Every row also drives {@link
 * McpCompletionCoordinator#settleReset}/{@link McpCompletionCoordinator#settleDisconnected} directly,
 * using the T004 manual clock the coordinator already accepts, so the whole race is reproduced
 * deterministically on one Vert.x context with no reliance on real transport timing.
 *
 * <p>Per the caution in {@code docs/specs/mcp-001-server-support/tasks/T013-*.md}: {@code
 * HttpServerResponse#ended()} is never stubbed here — the write path this class exercises never reads
 * it. {@code headWritten()} is instead backed by a live {@link AtomicBoolean} that this fixture flips
 * only when the mocked {@code end(Buffer)} is actually invoked, so every {@code responseCommitted}
 * assertion reads a value that genuinely changes with the mock's own call history, never a literal the
 * test hard-codes into the assertion.
 */
class McpWritePhaseSettlementTest {

    private static final String RESET_WHILE_PENDING_ROW = "shouldSettleOnceWhenResetArrivesWhileTheWriteIsPending";
    private static final String WRITE_FAILS_AFTER_RESET_ROW = "shouldSettleOnceWhenTheWriteFutureFailsAfterReset";
    private static final String LATE_WRITE_SUCCESS_ROW = "shouldSuppressALateWriteSuccessAfterSettlement";
    private static final String COMMIT_STATE_ROW = "shouldRecordTheActualCommitStateOnEachPath";

    private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(10);
    private static final Instant COMPLETED_AT = STARTED_AT.plusMillis(20);
    private static final byte[] BODY = "{\"jsonrpc\":\"2.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Bounded wait for every context-marshalled step; a red settlement path exhausts it and fails. */
    private static final long WAIT_SECONDS = 2;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    private static Stream<String> writePhaseSettlementRows() {
        return Stream.of(
                RESET_WHILE_PENDING_ROW, WRITE_FAILS_AFTER_RESET_ROW, LATE_WRITE_SUCCESS_ROW, COMMIT_STATE_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writePhaseSettlementRows")
    @DisplayName("T013 write-phase determinism: exactly-once settlement when the write future never "
            + "completes on its own")
    void shouldSettleOnceWhenTheWriteFutureNeverCompletes(String row) throws Exception {
        Context context = vertx.getOrCreateContext();
        ManualClock clock = new ManualClock(COMPLETED_AT);

        switch (row) {
            case RESET_WHILE_PENDING_ROW -> {
                // Given: the terminal write is started and its future is left pending (never resolves
                // on its own — the determinism seam, not a socket or a timer).
                RecordingObserver observer = new RecordingObserver();
                McpCompletionCoordinator coordinator = coordinator(context, clock, observer);
                WriteSeamFixture fixture = new WriteSeamFixture(coordinator);
                runOnContext(
                        context, () -> McpRequestDispatcher.write(fixture.context(), 200, BODY, successTerminal()));
                assertThat(observer.terminalCount())
                        .as("beginWrite must publish the terminal before the pending byte write")
                        .isOne();
                assertThat(observer.completionCount())
                        .as("no completion may be published while the write future is pending")
                        .isZero();

                // When: exactly one competing signal — a reset — arrives while the write is pending.
                coordinator.settleReset(cancelledTerminal(), fixture.headWritten());

                // Then: the stalled write settles exactly once, as RESET, through the recovery path.
                assertThat(observer.awaitCallbacks())
                        .as("the reset must drive the stalled write's completion instead of being dropped")
                        .isTrue();
                observer.assertExactlyOneTerminalThenOneCompletion();
                assertThat(observer.lastCompleted().transportOutcome()).isEqualTo(McpTransportOutcome.RESET);

                // Then: completing the write future in the declared order — after the reset already
                // won — must be suppressed: no second terminal, no second completion.
                runOnContext(context, fixture::completeWriteSuccessfully);
                observer.assertExactlyOneTerminalThenOneCompletion();
                assertThat(observer.lastCompleted().transportOutcome())
                        .as("a late write success must not overwrite the reset's recorded outcome")
                        .isEqualTo(McpTransportOutcome.RESET);
            }
            case WRITE_FAILS_AFTER_RESET_ROW -> {
                // Given: same pending-write setup, reset settles first exactly as above.
                RecordingObserver observer = new RecordingObserver();
                McpCompletionCoordinator coordinator = coordinator(context, clock, observer);
                WriteSeamFixture fixture = new WriteSeamFixture(coordinator);
                runOnContext(
                        context, () -> McpRequestDispatcher.write(fixture.context(), 200, BODY, successTerminal()));
                coordinator.settleReset(cancelledTerminal(), fixture.headWritten());
                assertThat(observer.awaitCallbacks()).isTrue();
                observer.assertExactlyOneTerminalThenOneCompletion();

                // When: the write future subsequently FAILS (not succeeds) after the reset already won.
                runOnContext(context, () -> fixture.failWrite(new RuntimeException("late write failure")));

                // Then: the failure is suppressed exactly like a late success would be — still exactly
                // one terminal, one completion, and the RESET outcome is not overwritten by WRITE_FAILED.
                observer.assertExactlyOneTerminalThenOneCompletion();
                assertThat(observer.lastCompleted().transportOutcome())
                        .as("a late write failure must not overwrite the reset's recorded outcome")
                        .isEqualTo(McpTransportOutcome.RESET);
            }
            case LATE_WRITE_SUCCESS_ROW -> {
                // Given: this row isolates a different boundary — a disconnect (not a reset) settles
                // first while the write is pending.
                RecordingObserver observer = new RecordingObserver();
                McpCompletionCoordinator coordinator = coordinator(context, clock, observer);
                WriteSeamFixture fixture = new WriteSeamFixture(coordinator);
                runOnContext(
                        context, () -> McpRequestDispatcher.write(fixture.context(), 200, BODY, successTerminal()));
                coordinator.settleDisconnected(cancelledTerminal(), fixture.headWritten());
                assertThat(observer.awaitCallbacks())
                        .as("the disconnect must drive the stalled write's completion instead of being dropped")
                        .isTrue();
                observer.assertExactlyOneTerminalThenOneCompletion();
                assertThat(observer.lastCompleted().transportOutcome()).isEqualTo(McpTransportOutcome.DISCONNECTED);

                // When: the write future later completes successfully.
                runOnContext(context, fixture::completeWriteSuccessfully);

                // Then (DECISIVE — this is the case an unfixed completionEmitted guard would double-fire
                // on): still exactly one terminal and one completion, still DISCONNECTED, never WRITTEN.
                observer.assertExactlyOneTerminalThenOneCompletion();
                assertThat(observer.lastCompleted().transportOutcome())
                        .as("a late write success must never overwrite an already-settled disconnect")
                        .isEqualTo(McpTransportOutcome.DISCONNECTED);
            }
            case COMMIT_STATE_ROW -> {
                // (a) A reset before any write has ever started records responseCommitted=false — read
                // from the mock's live headWritten() state, never a literal this test supplies.
                RecordingObserver beforeWriteObserver = new RecordingObserver();
                McpCompletionCoordinator beforeWriteCoordinator = coordinator(context, clock, beforeWriteObserver);
                WriteSeamFixture beforeWriteFixture = new WriteSeamFixture(beforeWriteCoordinator);
                assertThat(beforeWriteFixture.headWritten())
                        .as("no end(Buffer) call has happened yet")
                        .isFalse();
                beforeWriteCoordinator.settleReset(cancelledTerminal(), beforeWriteFixture.headWritten());
                assertThat(beforeWriteObserver.awaitCallbacks()).isTrue();
                assertThat(beforeWriteObserver.lastCompleted().responseCommitted())
                        .as("a reset before any write must record the real (uncommitted) response state")
                        .isFalse();

                // (b) A reset while a write is pending records responseCommitted=true — the mock's
                // headWritten() flips true only because end(Buffer) was actually invoked by write().
                RecordingObserver pendingObserver = new RecordingObserver();
                McpCompletionCoordinator pendingCoordinator = coordinator(context, clock, pendingObserver);
                WriteSeamFixture pendingFixture = new WriteSeamFixture(pendingCoordinator);
                runOnContext(
                        context,
                        () -> McpRequestDispatcher.write(pendingFixture.context(), 200, BODY, successTerminal()));
                assertThat(pendingFixture.headWritten())
                        .as("end(Buffer) must have flipped the mock's live head-written state")
                        .isTrue();
                pendingCoordinator.settleReset(cancelledTerminal(), pendingFixture.headWritten());
                assertThat(pendingObserver.awaitCallbacks()).isTrue();
                assertThat(pendingObserver.lastCompleted().responseCommitted())
                        .as("a reset recovering a stalled write must record the real (committed) response state")
                        .isTrue();

                // (c) The write's own future failing (not a reset) records responseCommitted=true too,
                // read by write()'s own onEnd handler from the same live mock state — not by this test.
                RecordingObserver failureObserver = new RecordingObserver();
                McpCompletionCoordinator failureCoordinator = coordinator(context, clock, failureObserver);
                WriteSeamFixture failureFixture = new WriteSeamFixture(failureCoordinator);
                runOnContext(
                        context,
                        () -> McpRequestDispatcher.write(failureFixture.context(), 200, BODY, successTerminal()));
                runOnContext(context, () -> failureFixture.failWrite(new RuntimeException("write failed")));
                assertThat(failureObserver.awaitCallbacks()).isTrue();
                McpRequestCompletedEvent failed = failureObserver.lastCompleted();
                assertThat(failed.transportOutcome()).isEqualTo(McpTransportOutcome.WRITE_FAILED);
                assertThat(failed.responseCommitted())
                        .as("a write failure with a committed head must record responseCommitted=true, read "
                                + "from the response's real state by write()'s own onEnd handler")
                        .isTrue();
            }
            default -> fail("unknown T013 write-phase settlement row: " + row);
        }
    }

    /**
     * Posts {@code action} onto {@code context} and awaits its completion, guaranteeing every prior
     * queued task (a redispatched settlement, a completed write promise) has already run.
     */
    private void runOnContext(Context context, Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                action.run();
            } finally {
                done.complete(null);
            }
        });
        done.get(WAIT_SECONDS, TimeUnit.SECONDS);
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
                STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, "call.stalledTool", 200, null, null, null, null);
    }

    private static McpRequestTerminalEvent cancelledTerminal() {
        return McpRequestTerminalEvent.cancelled(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.TRANSPORT,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * The T013 TP-002 write seam: a mocked {@link RoutingContext}/{@link HttpServerResponse} whose
     * {@code end(Buffer)} returns a {@link Promise}-backed future this fixture hands to the caller to
     * complete or fail explicitly, and whose {@code headWritten()} reflects only whether {@code
     * end(Buffer)} was actually invoked — a live value, not a stub the test hard-codes.
     */
    private static final class WriteSeamFixture {
        private final RoutingContext context = mock(RoutingContext.class);
        private final HttpServerResponse response = mock(HttpServerResponse.class);
        private final AtomicBoolean headWritten = new AtomicBoolean(false);
        private Promise<Void> writePromise;

        @SuppressWarnings("unchecked")
        WriteSeamFixture(McpCompletionCoordinator coordinator) {
            when(context.response()).thenReturn(response);
            when(context.get(anyString())).thenReturn(coordinator);
            when(response.headWritten()).thenAnswer(invocation -> headWritten.get());
            when(response.end(any(Buffer.class))).thenAnswer(invocation -> {
                headWritten.set(true);
                writePromise = Promise.promise();
                return writePromise.future();
            });
        }

        RoutingContext context() {
            return context;
        }

        boolean headWritten() {
            return headWritten.get();
        }

        void completeWriteSuccessfully() {
            writePromise.complete();
        }

        void failWrite(Throwable cause) {
            writePromise.fail(cause);
        }
    }

    /** Records terminal and completion callbacks, their arrival order, and the last completion seen. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
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
            callbacks.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount++;
            lastCompleted = event;
            order.add("completed");
            callbacks.countDown();
        }

        boolean awaitCallbacks() throws InterruptedException {
            return callbacks.await(WAIT_SECONDS, TimeUnit.SECONDS);
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

    /** A test-controlled {@link InstantSource} that returns a fixed instant. */
    private static final class ManualClock implements InstantSource {
        private final Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

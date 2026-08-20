// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T004 exactly-once settlement contract of {@link McpCompletionCoordinator}.
 *
 * <p>The coordinator must publish exactly one terminal event before exactly one completion event on
 * every settlement path — successful write, disconnect, reset, timeout, and a late handler
 * completion that loses the race — and must suppress every signal that arrives after the first
 * settlement wins. Every completion instant is drawn from a manual {@link InstantSource} so the
 * races are deterministic and free of wall-clock sleeps.
 *
 * <p>This is a red-slice proof. The disconnect, reset, and timeout settlement entries the T004
 * dispatcher will call are present on the coordinator but NON-FUNCTIONAL until the T004 green slice
 * implements them, so the {@code shouldSettleOnceOnWriteDisconnectResetTimeoutAndLateCompletion} row
 * lands red on the decisive assertion that a disconnect before any write settles exactly one
 * terminal and one completion. The redispatch/ordering row exercises the already-implemented write
 * path and is expected green.
 *
 * <p>Sensitivity (for the eventual green run): injecting exactly one duplicate terminal/completion
 * signal must move the exactly-once count assertion from 1 to 2 while the event order is unchanged.
 */
class McpCompletionCoordinatorTest {

    private static final String SETTLE_ONCE_ROW = "shouldSettleOnceOnWriteDisconnectResetTimeoutAndLateCompletion";
    private static final String REDISPATCH_ORDER_ROW = "shouldRedispatchOffContextAndNotifyTerminalBeforeCompletion";

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
                // Given: a write settlement wins first, so a later duplicate write and a later
                // disconnect must both be suppressed — exactly one terminal, one completion.
                RecordingObserver writeThenLateWrite = new RecordingObserver();
                McpCompletionCoordinator writeWinner = coordinator(context, clock, writeThenLateWrite);
                writeWinner.complete(successTerminal(), McpTransportOutcome.WRITTEN, true);
                assertThat(writeThenLateWrite.awaitCallbacks())
                        .as("the winning write settlement must deliver its terminal and completion")
                        .isTrue();
                writeWinner.complete(successTerminal(), McpTransportOutcome.WRITTEN, true);
                writeThenLateWrite.assertExactlyOneTerminalThenOneCompletion();

                RecordingObserver writeThenLateDisconnect = new RecordingObserver();
                McpCompletionCoordinator writeThenDisconnect = coordinator(context, clock, writeThenLateDisconnect);
                writeThenDisconnect.complete(successTerminal(), McpTransportOutcome.WRITTEN, true);
                assertThat(writeThenLateDisconnect.awaitCallbacks())
                        .as("the winning write settlement must deliver before any late disconnect")
                        .isTrue();
                writeThenDisconnect.settleDisconnected(cancelledTerminal(McpErrorType.TRANSPORT), true);
                writeThenLateDisconnect.assertExactlyOneTerminalThenOneCompletion();

                // DECISIVE: a client disconnect before any response write must settle the
                // coordinator — the frozen lifecycle contract promises exactly one terminal and one
                // completion on the disconnect path. The red-slice settlement entry is
                // NON-FUNCTIONAL, so no callback is delivered and this assertion fails.
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

                RecordingObserver timeoutAlone = new RecordingObserver();
                McpCompletionCoordinator timeoutWinner = coordinator(context, clock, timeoutAlone);
                timeoutWinner.settleTimeout(cancelledTerminal(McpErrorType.TIMEOUT));
                assertThat(timeoutAlone.awaitCallbacks())
                        .as("a whole-request timeout must settle exactly one terminal and one completion")
                        .isTrue();
                timeoutAlone.assertExactlyOneTerminalThenOneCompletion();
            }
            case REDISPATCH_ORDER_ROW -> {
                // Given: a settlement raised from a non-Vert.x thread must be redispatched onto the
                // request-owning context, notifying the terminal before the completion.
                RecordingObserver observer = new RecordingObserver();
                McpCompletionCoordinator coordinator = coordinator(context, clock, observer);

                coordinator.complete(successTerminal(), McpTransportOutcome.WRITTEN, true);

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
                null);
    }

    /** Records terminal and completion callbacks, their arrival order, and their Vert.x context. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicReference<Context> callbackContext = new AtomicReference<>();
        private int terminalCount;
        private int completionCount;

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

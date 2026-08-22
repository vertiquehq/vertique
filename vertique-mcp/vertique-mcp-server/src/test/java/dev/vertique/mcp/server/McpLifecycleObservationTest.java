// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T004 neutral lifecycle observation contract of {@link McpCompletionCoordinator}.
 *
 * <p>Each contributed {@link McpRequestLifecycleObserver} is opened exactly once per request, and
 * every retained {@link McpRequestObservation} receives exactly one terminal callback followed by
 * exactly one completion callback — on every settlement path, including the disconnect path the T004
 * seam introduces. An observer that throws from {@code open}, returns a null session,
 * or throws from a callback is isolated to itself and never suppresses a healthy observer.
 *
 * <p>Observers are opened in the coordinator constructor, and the disconnect settlement path then
 * delivers the terminal and completion callbacks to every opened session. Both rows drive that path
 * and assert that a healthy observer receives its terminal and completion in order. Value
 * observation ({@code onToolInput}/{@code onToolOutput}) is owned by T009 and is deliberately not
 * exercised here.
 *
 * <p>Sensitivity: injecting exactly one duplicate terminal/completion signal must move the
 * exactly-once count assertion from 1 to 2 while the callback order is unchanged.
 */
class McpLifecycleObservationTest {

    private static final String CALLBACK_ORDER_ROW = "shouldCreateOneSessionPerObserverAndRequestInFixedCallbackOrder";
    private static final String ISOLATION_ROW = "shouldIsolateThrowNullAndRetentionFailurePerObserver";

    private static final Instant STARTED_AT = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(10);

    /** Bounded wait for the two observation callbacks; a red settlement path exhausts it and fails. */
    private static final long SETTLEMENT_WAIT_SECONDS = 2;

    private final Vertx vertx = Vertx.vertx();

    /** Closes the owned {@link Vertx}, waiting for its teardown to settle. */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    private static Stream<String> observationRows() {
        return Stream.of(CALLBACK_ORDER_ROW, ISOLATION_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("observationRows")
    @DisplayName("T004 observation matrix: one session per observer, fixed order, per-observer isolation")
    void shouldEnforceT004ContractMatrix(String row) throws Exception {
        Context context = vertx.getOrCreateContext();

        switch (row) {
            case CALLBACK_ORDER_ROW -> {
                // Given: zero, one, and three neutral observers each contributed once.
                for (int observerCount : new int[] {0, 1, 3}) {
                    List<RecordingObserver> observers = IntStream.range(0, observerCount)
                            .mapToObj(index -> new RecordingObserver())
                            .toList();
                    McpCompletionCoordinator coordinator = coordinator(context, observers);

                    observers.forEach(observer -> assertThat(observer.openCount())
                            .as("each contributed observer's open must be called exactly once at scope creation")
                            .isOne());

                    // DECISIVE: settlement on the disconnect path delivers the terminal and completion
                    // callbacks to every opened session, so every opened observer sees both callbacks.
                    coordinator.settleDisconnected(disconnectTerminal(), false);
                    for (RecordingObserver observer : observers) {
                        assertThat(observer.awaitCallbacks())
                                .as("an opened observation must receive its terminal and completion on settlement")
                                .isTrue();
                        observer.assertOpenThenTerminalThenCompleted();
                    }
                }
            }
            case ISOLATION_ROW -> {
                // Given: a throwing-open observer, a null-returning observer, a throwing-callback
                // observer, and one healthy observer, all contributed to the same request.
                RecordingObserver healthy = new RecordingObserver();
                Set<McpRequestLifecycleObserver> mixed = new LinkedHashSet<>();
                mixed.add(new ThrowingOpenObserver());
                mixed.add(new NullSessionObserver());
                mixed.add(new ThrowingCallbackObserver());
                mixed.add(healthy);

                McpCompletionCoordinator coordinator = assertConstructsWithoutPropagatingFailures(context, mixed);

                // DECISIVE: the disconnect settlement must reach the healthy observer regardless of a
                // sibling that threw from open, returned null, or throws from its own callback. The
                // red-slice settlement entry delivers nothing, so the healthy observer is starved.
                coordinator.settleDisconnected(disconnectTerminal(), false);
                assertThat(healthy.awaitCallbacks())
                        .as("a healthy observer must receive its terminal and completion despite failing siblings")
                        .isTrue();
                healthy.assertOpenThenTerminalThenCompleted();
            }
            default -> fail("unknown T004 observation row: " + row);
        }
    }

    private McpCompletionCoordinator coordinator(Context context, List<RecordingObserver> observers) {
        return new McpCompletionCoordinator(
                context,
                new LinkedHashSet<McpRequestLifecycleObserver>(observers),
                Set.<McpRequestCompletedListener>of(),
                STARTED_AT);
    }

    private McpCompletionCoordinator assertConstructsWithoutPropagatingFailures(
            Context context, Set<McpRequestLifecycleObserver> observers) {
        CompletableFuture<McpCompletionCoordinator> built = new CompletableFuture<>();
        assertThatCode(() -> built.complete(new McpCompletionCoordinator(
                        context, observers, Set.<McpRequestCompletedListener>of(), STARTED_AT)))
                .as("a throwing or null observer must be isolated at scope creation")
                .doesNotThrowAnyException();
        return built.join();
    }

    private static McpRequestTerminalEvent disconnectTerminal() {
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
                null);
    }

    /** Records open, terminal, and completion callbacks and their arrival order. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private int openCount;
        private int terminalCount;
        private int completionCount;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            openCount++;
            order.add("open");
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
            order.add("completed");
            callbacks.countDown();
        }

        int openCount() {
            return openCount;
        }

        boolean awaitCallbacks() throws InterruptedException {
            return callbacks.await(SETTLEMENT_WAIT_SECONDS, TimeUnit.SECONDS);
        }

        void assertOpenThenTerminalThenCompleted() {
            assertThat(terminalCount).as("exactly one terminal callback").isOne();
            assertThat(completionCount).as("exactly one completion callback").isOne();
            assertThat(order)
                    .as("the callback order must be open then terminal then completed")
                    .containsExactly("open", "terminal", "completed");
        }
    }

    /** An observer that throws when opened; its failure must be isolated. */
    private static final class ThrowingOpenObserver implements McpRequestLifecycleObserver {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            throw new IllegalStateException("synthetic open failure");
        }
    }

    /** An observer that opens no session; the null must be isolated. */
    private static final class NullSessionObserver implements McpRequestLifecycleObserver {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            return null;
        }
    }

    /** An observer whose session throws from its terminal callback; the throw must be isolated. */
    private static final class ThrowingCallbackObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            throw new IllegalStateException("synthetic terminal-callback failure");
        }
    }
}

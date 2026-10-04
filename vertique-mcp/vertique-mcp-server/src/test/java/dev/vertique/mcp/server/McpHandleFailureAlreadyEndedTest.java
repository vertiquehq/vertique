// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link McpRequestDispatcher#handleFailure(RoutingContext)}'s already-ended path: no second
 * write on a response Vert.x has already ended, and — when an owned completion coordinator is present
 * — exactly one terminal then one completion (T004 S-b / #407). Without a coordinator the early
 * return remains a pure no-op.
 *
 * <p>Per the T007 review caution: {@code HttpServerResponse#ended()} is set synchronously by {@code
 * end()} and is never mocked {@code false} after an {@code end()} call here — this test mocks {@code
 * ended()} as unconditionally {@code true} for the entire call, modeling a response that was already
 * ended by an earlier point on the request path, not a race with an in-flight {@code end()}.
 */
class McpHandleFailureAlreadyEndedTest {

    private static final long WAIT_SECONDS = 2;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("handleFailure on an already-ended response with no coordinator performs no write")
    void shouldPerformNoWriteWhenResponseAlreadyEndedWithoutCoordinator() {
        HttpServerResponse response = mock(HttpServerResponse.class);
        RoutingContext context = mock(RoutingContext.class);
        when(response.ended()).thenReturn(true);
        when(context.response()).thenReturn(response);

        McpRequestDispatcher dispatcher = dispatcher(Set.of());

        dispatcher.handleFailure(context);

        // DECISIVE: without a coordinator the early path short-circuits before any response mutation
        // and before status is read. Against a regressed dispatcher that fell through to the ordinary
        // rejection write path, at least one of these would be invoked.
        verify(response, never()).setStatusCode(anyInt());
        verify(response, never()).end();
        verify(response, never()).end(any(Buffer.class));
        verify(context, never()).statusCode();
    }

    @Test
    @DisplayName("handleFailure on an already-ended response settles the coordinator exactly once")
    void shouldSettleCoordinatorWhenResponseAlreadyEnded() throws Exception {
        Context owningContext = vertx.getOrCreateContext();
        RecordingObserver observer = new RecordingObserver();
        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                owningContext,
                Set.<McpRequestLifecycleObserver>of(observer),
                Set.<McpRequestCompletedListener>of(),
                Instant.parse("2026-08-21T00:00:00Z"));

        HttpServerResponse response = mock(HttpServerResponse.class);
        RoutingContext context = mock(RoutingContext.class);
        when(response.ended()).thenReturn(true);
        when(response.headWritten()).thenReturn(true);
        when(context.response()).thenReturn(response);
        when(context.statusCode()).thenReturn(500);
        when(context.get(eq(McpRequestDispatcher.COMPLETION_COORDINATOR_KEY))).thenReturn(coordinator);

        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        when(securityRuntime.current()).thenReturn(null);

        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(observer),
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));

        dispatcher.handleFailure(context);

        // DECISIVE: no second write on an already-ended response.
        verify(response, never()).setStatusCode(anyInt());
        verify(response, never()).end();
        verify(response, never()).end(any(Buffer.class));

        assertThat(observer.awaitCallbacks())
                .as("the ends-then-fails path must publish terminal and completion")
                .isTrue();
        observer.assertExactlyOneTerminalThenOneCompletion();
        assertThat(observer.lastCompleted().transportOutcome())
                .as("the already-ended response was committed on the wire before failure")
                .isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(observer.lastCompleted().responseCommitted()).isTrue();
        assertThat(observer.lastTerminal().event().outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(observer.lastTerminal().event().errorType()).isEqualTo(McpErrorType.INTERNAL);
    }

    private static McpRequestDispatcher dispatcher(Set<McpRequestLifecycleObserver> observers) {
        return new McpRequestDispatcher(
                McpServerConfig.defaults(),
                mock(SecurityRuntime.class),
                observers,
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding (R09). */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /** Records terminal and completion callbacks, their arrival order, and the last events seen. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private volatile int terminalCount;
        private volatile int completionCount;
        private volatile McpRequestTerminalObservation lastTerminal;
        private volatile McpRequestCompletedEvent lastCompleted;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
            lastTerminal = observation;
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

        McpRequestTerminalObservation lastTerminal() {
            return lastTerminal;
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
}

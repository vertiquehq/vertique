// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpProgressReporter;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpProgressReporterTest {

    @Test
    @DisplayName("a missing progress token makes invalid progress input a successful no-op")
    void shouldNoOpSuccessfullyWithoutAClientProgressToken() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Context context = vertx.getOrCreateContext();
            RoutingContext routing = mock(RoutingContext.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(routing.response()).thenReturn(response);
            when(response.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
            McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                    context, Set.of(), Set.of(), Instant.now(), InstantSource.system(), routing);
            Promise<Void> finished = Promise.promise();

            context.runOnContext(ignored -> coordinator
                    .cancellation()
                    .progressReporter()
                    .report(Double.NaN, null, "ignored")
                    .onComplete(finished));

            finished.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            org.mockito.Mockito.verifyNoInteractions(response);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("standard progress preserves order and suppresses non-increasing values")
    void shouldEmitStandardProgressOnTheRequestStreamAndSuppressNonIncreasingValues() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Context context = vertx.getOrCreateContext();
            RoutingContext routing = mock(RoutingContext.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(routing.response()).thenReturn(response);
            when(response.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
            McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                    context, Set.of(), Set.of(), Instant.now(), InstantSource.system(), routing);
            Promise<Void> finished = Promise.promise();

            context.runOnContext(ignored -> {
                coordinator.bindProgressToken(new ObjectMapper().valueToTree("weather-2"));
                McpProgressReporter reporter = coordinator.cancellation().progressReporter();
                reporter.report(1, 2.0, "starting")
                        .compose(v -> reporter.report(1, 2.0, "duplicate"))
                        .compose(v -> reporter.report(2, 2.0, "done"))
                        .onComplete(finished);
            });

            finished.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var frame = org.mockito.ArgumentCaptor.forClass(Buffer.class);
            verify(response, org.mockito.Mockito.times(2)).write(frame.capture());
            assertThat(frame.getAllValues().get(0).toString())
                    .contains("notifications/progress", "weather-2", "starting");
            assertThat(frame.getAllValues().get(1).toString()).contains("\"progress\":2", "done");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("opaque integer progress tokens outside long are echoed without numeric corruption")
    void shouldPreserveLargeIntegerProgressToken() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Context context = vertx.getOrCreateContext();
            RoutingContext routing = mock(RoutingContext.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(routing.response()).thenReturn(response);
            when(response.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
            McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                    context, Set.of(), Set.of(), Instant.now(), InstantSource.system(), routing);
            Promise<Void> finished = Promise.promise();
            JsonNode token = McpRequestDispatcher.progressTokenOf(
                    new ObjectMapper().readTree("{\"params\":{\"_meta\":{\"progressToken\":9223372036854775808}}}"));

            context.runOnContext(ignored -> {
                coordinator.bindProgressToken(token);
                coordinator.progressReporter().report(1, null, null).onComplete(finished);
            });

            finished.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var frame = org.mockito.ArgumentCaptor.forClass(Buffer.class);
            verify(response).write(frame.capture());
            assertThat(frame.getValue().toString()).contains("\"progressToken\":9223372036854775808");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("progress frames stop before the shared response budget is exceeded")
    void shouldAccountProgressFramesAgainstSharedResponseBudget() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Context context = vertx.getOrCreateContext();
            RoutingContext routing = mock(RoutingContext.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(routing.response()).thenReturn(response);
            when(response.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
            McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                    context, Set.of(), Set.of(), Instant.now(), InstantSource.system(), routing, 64, null);
            Promise<Void> finished = Promise.promise();

            context.runOnContext(ignored -> {
                coordinator.bindProgressToken(new ObjectMapper().valueToTree("token"));
                coordinator
                        .progressReporter()
                        .report(1, 1.0, "this frame does not fit")
                        .onComplete(finished);
            });

            finished.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            org.mockito.Mockito.verifyNoInteractions(response);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a failed progress write enters request settlement exactly once")
    void shouldSettleTheRequestWhenProgressWriteFails() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Context context = vertx.getOrCreateContext();
            RoutingContext routing = mock(RoutingContext.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(routing.response()).thenReturn(response);
            when(response.write(any(Buffer.class))).thenReturn(Future.failedFuture("write failed"));
            AtomicReference<McpRequestCompletedEvent> completed = new AtomicReference<>();
            McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                    context,
                    Set.of(),
                    Set.of(completed::set),
                    Instant.now(),
                    InstantSource.system(),
                    routing,
                    4096,
                    () -> McpRequestTerminalEvent.cancelled(
                            Instant.now(),
                            Instant.now(),
                            McpMethod.TOOLS_CALL,
                            "weather.current",
                            McpErrorType.TRANSPORT,
                            0,
                            null,
                            null,
                            null,
                            null,
                            null));
            CompletableFuture<Throwable> failed = new CompletableFuture<>();

            context.runOnContext(ignored -> {
                coordinator.bindProgressToken(new ObjectMapper().valueToTree("token"));
                coordinator.progressReporter().report(1, null, null).onComplete(ar -> {
                    if (ar.failed()) {
                        failed.complete(ar.cause());
                    }
                });
            });

            failed.get(2, TimeUnit.SECONDS);
            assertThat(coordinator.cancellation().isCancelled()).isTrue();
            assertThat(completed.get()).isNotNull();
            assertThat(completed.get().transportOutcome()).isEqualTo(McpTransportOutcome.WRITE_FAILED);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
}

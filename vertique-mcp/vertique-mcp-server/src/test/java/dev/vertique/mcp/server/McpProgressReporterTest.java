// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpProgressReporterTest {

    @Test
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
                coordinator.bindProgressToken("weather-2");
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
}

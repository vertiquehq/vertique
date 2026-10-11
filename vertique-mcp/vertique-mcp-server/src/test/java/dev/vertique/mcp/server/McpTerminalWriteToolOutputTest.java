// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * What a completion listener sees of a tool's armed output when the terminal writer meets the
 * response byte budget.
 *
 * <p>Driven through {@link McpRequestDispatcher#write(RoutingContext, int, byte[],
 * McpRequestTerminalEvent)} against a mocked {@link RoutingContext} and {@link HttpServerResponse}, with
 * a real coordinator whose response budget the test sets. A listener reads the output through the
 * completion view, so each assertion is on what the writer bound, never on a value the test set.
 */
class McpTerminalWriteToolOutputTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:00Z");
    private static final byte[] FALLBACK =
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final Map<String, Object> OUTPUT = Map.of("marker", "armed-output-marker");
    private static final long WAIT_SECONDS = 5;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a result that fits the response budget is reported to a listener with the bytes written")
    void shouldReportTheArmedOutputWhenTheResultItselfIsWritten() throws Exception {
        // Given: an armed output and a body that fits the budget exactly.
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        byte[] body = ascii(FALLBACK.length);
        McpCompletionCoordinator coordinator = coordinator(listener, FALLBACK.length);
        coordinator.armToolOutput(OUTPUT);
        WriteSeam seam = new WriteSeam(coordinator, FALLBACK);

        // When: the terminal write runs.
        boolean written = McpRequestDispatcher.write(seam.context, 200, body, terminal());
        List<Completion> completions = listener.await(1);

        // Then: the control: the result's own bytes were written and its output is visible, so the
        // substitution row below fails only because the substitution happened.
        assertThat(written).isTrue();
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).structuredOutput())
                .as("the output of the result that was written")
                .contains(OUTPUT);
        assertThat(completions.get(0).responseBody()).isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("when the response budget forces the bounded fallback onto the wire, a listener sees no "
            + "tool output and the fallback bytes as the response body")
    void shouldDropTheArmedOutputWhenTheBoundedFallbackReplacesTheResult() throws Exception {
        // Given: an armed output, and a result one byte too large for the budget that still holds the
        // bounded fallback.
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        McpCompletionCoordinator coordinator = coordinator(listener, FALLBACK.length);
        coordinator.armToolOutput(OUTPUT);
        WriteSeam seam = new WriteSeam(coordinator, FALLBACK);

        // When: the terminal write runs with the oversized result.
        boolean written = McpRequestDispatcher.write(seam.context, 200, ascii(FALLBACK.length + 1), terminal());
        List<Completion> completions = listener.await(1);

        // Then: the fallback, not the result, went to the wire.
        assertThat(written).isTrue();
        ArgumentCaptor<Buffer> end = ArgumentCaptor.forClass(Buffer.class);
        verify(seam.response).end(end.capture());
        assertThat(end.getValue().getBytes())
                .as("precondition: the substitution happened")
                .isEqualTo(FALLBACK);
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).event().terminal().errorType())
                .as("precondition: the terminal is the budget failure's")
                .isEqualTo(McpErrorType.SERIALIZATION);

        // Then: the listener sees the fallback as the body, and none of the result's output.
        assertThat(completions.get(0).toolOutput())
                .as("DECISIVE: an output the fallback replaced is not reported")
                .isEmpty();
        assertThat(completions.get(0).responseBody())
                .as("the response body is what was written")
                .isEqualTo(new String(FALLBACK, StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "fallbackBytes={0}")
    @ValueSource(ints = {0, 40})
    @DisplayName("when no body fits the response budget the request settles as a failed write with no body "
            + "and no tool output")
    void shouldFailTheWriteWhenNoBodyFitsTheResponseBudget(int fallbackBytes) throws Exception {
        // Given: an armed output, a budget of 16 bytes, and a result larger than it; the fallback is
        // either absent or larger than the budget too.
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        McpCompletionCoordinator coordinator = coordinator(listener, 16);
        coordinator.armToolOutput(OUTPUT);
        WriteSeam seam = new WriteSeam(coordinator, fallbackBytes == 0 ? null : ascii(fallbackBytes));

        // When: the terminal write runs.
        boolean written = McpRequestDispatcher.write(seam.context, 200, ascii(300), terminal());
        List<Completion> completions = listener.await(1);

        // Then: nothing was written, and the one completion is a failed write.
        assertThat(written).as("the write did not proceed").isFalse();
        verify(seam.response, times(1).description("the response is ended without a body"))
                .end();
        verify(seam.response, never().description("no body bytes reach the wire"))
                .end(any(Buffer.class));
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).event().transportOutcome())
                .as("DECISIVE: the request settled as a failed write")
                .isEqualTo(McpTransportOutcome.WRITE_FAILED);
        assertThat(completions.get(0).toolOutput()).as("no tool output").isEmpty();
        assertThat(completions.get(0).hasResponseBody()).as("no response body").isFalse();
        assertThat(listener.arrivesWithin(2, Duration.ofMillis(200)))
                .as("the completion is published once")
                .isFalse();
    }

    // --- Fixture ---

    private McpCompletionCoordinator coordinator(McpRequestCompletedListener listener, int responseMaxBytes) {
        return new McpCompletionCoordinator(
                vertx.getOrCreateContext(),
                Set.<McpRequestLifecycleObserver>of(),
                Set.of(listener),
                STARTED_AT,
                InstantSource.system(),
                null,
                responseMaxBytes,
                null);
    }

    private static byte[] ascii(int length) {
        return "x".repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    private static McpRequestTerminalEvent terminal() {
        return McpRequestTerminalEvent.success(
                STARTED_AT,
                STARTED_AT.plusMillis(10),
                McpMethod.TOOLS_CALL,
                "budget.fixtureTool",
                200,
                null,
                null,
                null,
                null,
                RequestOrigin.unknown());
    }

    /**
     * A mocked routing context that holds the coordinator and, when {@code fallback} is given, selects
     * SSE and offers it as the bounded terminal fallback. The response ends successfully.
     */
    private static final class WriteSeam {
        private final RoutingContext context = mock(RoutingContext.class);
        private final HttpServerResponse response = mock(HttpServerResponse.class);

        WriteSeam(McpCompletionCoordinator coordinator, byte[] fallback) {
            when(context.response()).thenReturn(response);
            when(context.get(McpRequestDispatcher.COMPLETION_COORDINATOR_KEY)).thenReturn(coordinator);
            if (fallback != null) {
                when(context.get(McpRequestDispatcher.SSE_SELECTED_KEY)).thenReturn(Boolean.TRUE);
                when(context.get(McpRequestDispatcher.TERMINAL_FALLBACK_BODY_KEY))
                        .thenReturn(fallback);
            }
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
            when(response.end()).thenReturn(Future.succeededFuture());
        }
    }
}

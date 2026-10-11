// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The terminal writer takes its response header snapshot only when a completion listener can read it,
 * and still decides the armed tool output either way.
 *
 * <p>Driven as {@code McpWritePhaseSettlementTest} drives it: {@link
 * McpRequestDispatcher#write(RoutingContext, int, byte[], McpRequestTerminalEvent)} against a mocked
 * {@link RoutingContext} and {@link HttpServerResponse}. The coordinator is a spy over a real one
 * (unbound, so it belongs to every request), which records which calls the writer made on it.
 */
class McpTerminalWriteSnapshotTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:00Z");
    private static final byte[] BODY = "{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8);
    private static final long WAIT_SECONDS = 5;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("without a completion listener the writer neither reads the response headers nor binds "
            + "the response, and still decides the armed tool output")
    void shouldNotTakeTheResponseHeaderSnapshotWhenNoListenerIsRegistered() {
        // Given: a coordinator with no completion listener and an armed tool output.
        McpCompletionCoordinator coordinator = coordinator(Set.of());
        coordinator.armToolOutput(java.util.Map.of("v", 1));
        WriteSeam seam = new WriteSeam(coordinator);

        // When: the terminal write runs.
        McpRequestDispatcher.write(seam.context, 200, BODY, terminal());

        // Then: the response was written, its headers were never read, and the output was decided.
        verify(seam.response).end(any(Buffer.class));
        verify(seam.response, never().description("DECISIVE: no listener, so no header snapshot"))
                .headers();
        verify(coordinator, never().description("DECISIVE: no listener, so nothing to bind"))
                .bindResponse(any(), any());
        verify(coordinator, atLeastOnce().description("the armed output is decided on every write"))
                .promoteToolOutput(true);
    }

    @Test
    @DisplayName("with a completion listener the writer reads the response headers and binds them with "
            + "the body, so the listener's view reports both")
    void shouldTakeTheResponseHeaderSnapshotWhenAListenerIsRegistered() throws Exception {
        // Given: a coordinator with one completion listener and a response carrying a header.
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(listener));
        WriteSeam seam = new WriteSeam(coordinator);
        seam.headers.set("X-Snapshot-Marker", "present");

        // When: the terminal write runs.
        McpRequestDispatcher.write(seam.context, 200, BODY, terminal());
        List<Completion> completions = listener.await(1);

        // Then: the headers were read, and the view reports them with the written body.
        verify(seam.response, atLeastOnce().description("a listener can read the snapshot"))
                .headers();
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).responseHeader("x-snapshot-marker")).contains("present");
        assertThat(completions.get(0).responseBody()).isEqualTo(new String(BODY, StandardCharsets.UTF_8));
    }

    // --- Fixture ---

    private McpCompletionCoordinator coordinator(Set<McpRequestCompletedListener> listeners) {
        return spy(new McpCompletionCoordinator(
                vertx.getOrCreateContext(),
                Set.<McpRequestLifecycleObserver>of(),
                listeners,
                STARTED_AT,
                InstantSource.system()));
    }

    private static McpRequestTerminalEvent terminal() {
        return McpRequestTerminalEvent.success(
                STARTED_AT,
                STARTED_AT.plusMillis(10),
                McpMethod.TOOLS_CALL,
                "snapshot.fixtureTool",
                200,
                null,
                null,
                null,
                null,
                RequestOrigin.unknown());
    }

    /** A mocked routing context whose response ends successfully and whose headers the test controls. */
    private static final class WriteSeam {
        private final RoutingContext context = mock(RoutingContext.class);
        private final HttpServerResponse response = mock(HttpServerResponse.class);
        private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        WriteSeam(McpCompletionCoordinator coordinator) {
            when(context.response()).thenReturn(response);
            when(context.get(anyString())).thenReturn(coordinator);
            when(response.headers()).thenReturn(headers);
            when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        }
    }
}

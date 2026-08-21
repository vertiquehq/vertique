// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves finding W-a: the whole-request timeout now bounds the transport write phase. {@link
 * McpRequestDispatcher#begin(RoutingContext)} arms a {@code mcp.request.timeoutMs} timer, and the
 * write path must leave that timer armed across {@code beginWrite} and cancel it only once
 * {@code end()} resolves — so a write whose transport stalls past the timeout is reset, and that
 * reset drives the response's pending {@code end()} future to fail, yielding exactly one
 * {@link McpTransportOutcome#WRITE_FAILED} completion rather than a terminal with no completion.
 *
 * <p>The seam is a focused deterministic drive of the real {@code begin}/{@code dispatch} path on a
 * live Vert.x context with a mock {@link HttpServerResponse} whose {@code end(Buffer)} returns a
 * promise that never resolves on its own — modelling a peer that withholds delivery so neither the
 * close nor the exception handler fires — and whose {@code reset()} fails that promise, as a real
 * transport reset does. A true socket-level stall is not cleanly authorable at T004 (the discovery
 * response is far smaller than any socket send buffer, so a real {@code end()} resolves at once
 * regardless of client reads), so this proves the decisive fact — the timer lifecycle and the
 * timeout-driven {@code WRITE_FAILED} completion — at the dispatcher seam instead.
 *
 * <p>Against pre-fix production, {@code write()} cancelled the timer as its first statement, so the
 * timer could never fire during the stalled write: no reset, no {@code end()} failure, no
 * completion — the completion wait below exhausts and fails red.
 */
class McpWriteTimerLifecycleTest {

    /** A short whole-request timeout so the stalled-write timeout fires promptly in the test. */
    private static final long REQUEST_TIMEOUT_MS = 150;

    /** Bounded wait for the WRITE_FAILED completion; a red timer lifecycle exhausts it and fails. */
    private static final long COMPLETION_WAIT_SECONDS = 5;

    private final Vertx vertx = Vertx.vertx();

    /** Closes the owned {@link Vertx}, waiting for its teardown to settle. */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("W-a: a write stalled past the request timeout is reset and completes WRITE_FAILED once")
    void shouldBoundStalledWriteWithTheRequestTimeout() throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .requestTimeoutMs(REQUEST_TIMEOUT_MS)
                .build();

        AtomicInteger completions = new AtomicInteger();
        CompletableFuture<McpRequestCompletedEvent> completed = new CompletableFuture<>();
        McpRequestCompletedListener listener = event -> {
            completions.incrementAndGet();
            completed.complete(event);
        };

        McpRequestDispatcher dispatcher =
                new McpRequestDispatcher(config, mock(SecurityRuntime.class), Set.of(), Set.of(listener));

        // The stalled write: end(body) returns a promise that never resolves on its own; only reset()
        // — as a real transport reset does — fails it, which is the timeout path the fix relies on.
        Promise<Void> endPromise = Promise.promise();
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.ended()).thenReturn(false);
        when(response.headWritten()).thenReturn(false);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.closeHandler(any())).thenReturn(response);
        when(response.exceptionHandler(any())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(endPromise.future());
        when(response.reset()).thenAnswer(ignored -> {
            endPromise.tryFail("transport reset by whole-request timeout");
            return true;
        });

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.getHeader(anyString())).thenReturn(null);

        RequestBody requestBody = mock(RequestBody.class);
        when(requestBody.buffer()).thenReturn(discoverFrame());

        Map<String, Object> store = new ConcurrentHashMap<>();
        RoutingContext context = mock(RoutingContext.class);
        when(context.vertx()).thenReturn(vertx);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(context.put(anyString(), any())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return context;
        });
        when(context.<Object>get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        when(context.remove(anyString())).thenAnswer(invocation -> store.remove(invocation.getArgument(0)));

        // Run begin + dispatch on one Vert.x context so the armed timer, the coordinator's context, and
        // the stalled write all share the request-owning event loop, exactly as production does.
        Context requestContext = vertx.getOrCreateContext();
        requestContext.runOnContext(ignored -> {
            dispatcher.begin(context);
            dispatcher.dispatch(context);
        });

        // DECISIVE: the completion only arrives if the timer stayed armed across the write — it fires,
        // reset()s the stalled response, fails the pending end(), and drives finishWrite(WRITE_FAILED).
        // Pre-fix the timer was cancelled at write start, so nothing ever completes and this wait fails.
        McpRequestCompletedEvent event = completed.get(COMPLETION_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(event.transportOutcome())
                .as("a write reset by the whole-request timeout records the WRITE_FAILED transport outcome")
                .isEqualTo(McpTransportOutcome.WRITE_FAILED);
        assertThat(completions.get())
                .as("the timeout-bounded stalled write publishes exactly one completion")
                .isEqualTo(1);
    }

    /**
     * Builds a schema-valid {@code server/discover} frame whose {@code params._meta} carries the
     * candidate protocol version and an empty client-capabilities object, so the strict codec decodes
     * it to the discovery path that reaches the two-phase write.
     */
    private static Buffer discoverFrame() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject frame = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", meta));
        return frame.toBuffer();
    }
}

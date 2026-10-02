// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestDispatcher.COMPLETION_COORDINATOR_KEY;
import static dev.vertique.mcp.server.McpRequestDispatcher.TERMINAL_FALLBACK_BODY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@link McpRequestDispatcher#begin} treats the value already under {@link
 * McpRequestDispatcher#COMPLETION_COORDINATOR_KEY} when it runs.
 *
 * <p>A reroute back into the MCP mount reaches {@code begin} a second time with the same {@code
 * data()} map and the same {@link HttpServerRequest}. The coordinator the first call built belongs to
 * that request, so the second call must reuse it: it opens no second lifecycle observation, replaces
 * neither the coordinator nor the terminal fallback body, registers no second settlement end handler,
 * and still continues the route exactly once. A value of any other type under the key is not a
 * coordinator, so {@code begin} ignores it and runs its ordinary body.
 *
 * <p>The dispatcher is built as {@code McpLifecycleFactsTest} builds it, with a {@link
 * CountingObserver} as its one lifecycle observer. The mocked {@link RoutingContext} is a copy of
 * {@code McpLifecycleFactsTest}'s helper: its {@code put} and {@code get} share one map, which stands
 * for the request's {@code data()}, and its {@code request()} returns the same mocked request on every
 * call, as a real request's contexts do across a reroute.
 */
class McpBeginReentryTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String KNOWN_TOOL = "greet";

    /** Bounded wait for the owned {@link Vertx} to close in {@link #tearDown()}. */
    private static final long CLOSE_WAIT_SECONDS = 5;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A re-entered begin reuses the request's coordinator and registers no second settlement hook")
    void reenteredBeginReusesTheCoordinatorAndRegistersNoSecondHook() {
        // Given: the request passed RequestContextLifecycle and one begin, as on its first routing pass.
        CountingObserver observer = new CountingObserver();
        McpRequestDispatcher dispatcher = dispatcher(observer);
        RoutingContext context = mockRoutingContext(vertx.getOrCreateContext(), toolsCallBody());
        new RequestContextLifecycle().handle(context);
        dispatcher.begin(context);
        Object firstCoordinator = context.get(COMPLETION_COORDINATOR_KEY);
        Object firstFallbackBody = context.get(TERMINAL_FALLBACK_BODY_KEY);
        assertThat(firstCoordinator)
                .as("precondition: the first begin stored a coordinator for this request")
                .isInstanceOf(McpCompletionCoordinator.class);
        assertThat(firstFallbackBody)
                .as("precondition: the first begin stored the terminal fallback body")
                .isInstanceOf(byte[].class);

        // When: a reroute back into the mount reaches begin again, with the same data().
        clearInvocations(context);
        dispatcher.begin(context);

        // Then
        assertAll(
                () -> assertThat(observer.opens())
                        .as("the observer opened exactly one lifecycle observation for the request")
                        .isEqualTo(1),
                () -> assertThat(context.<Object>get(COMPLETION_COORDINATOR_KEY))
                        .as("the coordinator slot still holds the first begin's coordinator")
                        .isSameAs(firstCoordinator),
                () -> assertThat(context.<Object>get(TERMINAL_FALLBACK_BODY_KEY))
                        .as("the terminal fallback slot still holds the first begin's body")
                        .isSameAs(firstFallbackBody),
                () -> verify(context, never().description("the second begin put nothing under the coordinator key"))
                        .put(eq(COMPLETION_COORDINATOR_KEY), any()),
                () -> verify(
                                context,
                                never().description("the second begin put nothing under the terminal fallback key"))
                        .put(eq(TERMINAL_FALLBACK_BODY_KEY), any()),
                () -> verify(context, never().description("the second begin registered no end handler"))
                        .addEndHandler(any()),
                () -> verify(context, times(1).description("the second begin continued the route exactly once"))
                        .next());
    }

    @Test
    @DisplayName("A String under the coordinator key is ignored: begin builds and registers its own coordinator")
    void valueOfAnotherTypeUnderTheCoordinatorKeyIsIgnored() {
        // Given: the request passed RequestContextLifecycle, and its coordinator slot holds a String.
        CountingObserver observer = new CountingObserver();
        McpRequestDispatcher dispatcher = dispatcher(observer);
        RoutingContext context = mockRoutingContext(vertx.getOrCreateContext(), toolsCallBody());
        new RequestContextLifecycle().handle(context);
        context.put(COMPLETION_COORDINATOR_KEY, "not a coordinator");
        clearInvocations(context);

        // When
        var beginOutcome = assertThatCode(() -> dispatcher.begin(context));

        // Then
        assertAll(
                () -> beginOutcome
                        .as("begin ignores the String and throws nothing")
                        .doesNotThrowAnyException(),
                () -> assertThat(observer.opens())
                        .as("begin opened exactly one lifecycle observation")
                        .isEqualTo(1),
                () -> assertThat(context.<Object>get(COMPLETION_COORDINATOR_KEY))
                        .as("the coordinator slot now holds the coordinator begin built")
                        .isInstanceOf(McpCompletionCoordinator.class),
                () -> assertThat(context.<Object>get(TERMINAL_FALLBACK_BODY_KEY))
                        .as("the terminal fallback slot holds the body begin built")
                        .isInstanceOf(byte[].class),
                () -> verify(context, times(1).description("begin registered exactly one end handler"))
                        .addEndHandler(any()),
                () -> verify(context, times(1).description("begin continued the route exactly once"))
                        .next());
    }

    // --- Fixture construction ---

    /** Builds the dispatcher as {@code McpLifecycleFactsTest} does, with {@code observer} as its one observer. */
    private static McpRequestDispatcher dispatcher(CountingObserver observer) {
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);

        return new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(observer),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
    }

    /** A lifecycle observer that counts the observations it opens; each opened observation ignores its events. */
    private static final class CountingObserver implements McpRequestLifecycleObserver {
        private final AtomicInteger opens = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            opens.incrementAndGet();
            return new McpRequestObservation() {};
        }

        int opens() {
            return opens.get();
        }
    }

    /** Copied from {@code McpLifecycleFactsTest}, which stays unmodified. */
    private static RoutingContext mockRoutingContext(Context vertxContext, JsonObject body) {
        RoutingContext context = mock(RoutingContext.class);
        io.vertx.core.Vertx contextVertx = mock(io.vertx.core.Vertx.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody requestBody = mock(RequestBody.class);
        Map<Object, Object> attributes = new HashMap<>();

        when(context.vertx()).thenReturn(contextVertx);
        when(contextVertx.getOrCreateContext()).thenReturn(vertxContext);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer()).thenReturn(Buffer.buffer(body.toBuffer().getBytes()));
        when(request.headers()).thenReturn(headersFor(body));
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(response.end()).thenReturn(Future.succeededFuture());
        when(response.closeHandler(any())).thenReturn(response);
        when(response.exceptionHandler(any())).thenReturn(response);
        when(context.statusCode()).thenReturn(401);
        when(context.put(anyString(), any())).thenAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return context;
        });
        when(context.get(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return context;
    }

    private static MultiMap headersFor(JsonObject body) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.set("Mcp-Method", body.getString("method"));
        JsonObject params = body.getJsonObject("params");
        String name = params.getString("name");
        headers.set("Mcp-Name", name != null ? name : body.getString("method"));
        return headers;
    }

    private static JsonObject toolsCallBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put(
                        "params",
                        new JsonObject()
                                .put(
                                        "_meta",
                                        new JsonObject()
                                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()))
                                .put("name", KNOWN_TOOL)
                                .put("arguments", new JsonObject()));
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
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
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * Repair task R33 defect 2: a {@link McpPreparedToolCall#invoke()} that returns {@code null} (rather
 * than throwing, or returning a failed future) is never guarded.
 *
 * <p>{@code McpRequestDispatcher#invokeAndRespond} only wraps the synchronous call {@code
 * prepared.invoke()} itself in a {@code catch (RuntimeException | StackOverflowError)}; the very next
 * statement, {@code anchoredOnContext(result, owningContext)}, calls {@code result.isComplete()}
 * without a null check. When {@code result} is {@code null}, that throws a {@link
 * NullPointerException} — but only once the surrounding continuation actually runs. This fixture
 * forces that continuation to run genuinely asynchronously, exactly the way an application-supplied
 * {@code SecurityPolicyEnforcer}/{@code Authorizer} would in production: {@link
 * McpPolicyEnforcer#decide} is stubbed to return a pending {@link Promise}'s future, completed only
 * from a plain, non-Vert.x thread <em>after</em> {@link McpRequestDispatcher#dispatch} has already
 * returned to its caller. At that point the resulting {@code writeToolsCall}/{@code
 * invokeAndRespond} continuation runs entirely outside {@code dispatch()}'s own {@code
 * dispatchByMethod} try/catch (which only ever wrapped the synchronous portion of the call), so the
 * NPE this defect produces is caught nowhere: no response is written, and no terminal or completion
 * event is ever published. This mirrors {@code McpOffContextCompletionIT}'s established "release from
 * a plain thread after the caller has already returned" technique for proving an async gap.
 *
 * <p>RED today: the bounded await below times out because nothing ever settles. Once repaired, {@code
 * prepared.invoke()} returning {@code null} must settle the same bounded internal-error fallback
 * {@code invokeFailure}/{@code stage5Failure} already produce, publishing exactly one terminal event
 * and one completion event, well inside the bounded await.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpNullInvocationFutureIT {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String KNOWN_TOOL = "r33-null-invocation-fixture-tool";
    private static final long BOUNDED_AWAIT_SECONDS = 5;
    private static final int INTERNAL_ERROR_CODE = -32603;

    private Vertx vertx;

    @AfterEach
    void tearDown() throws Exception {
        if (vertx == null) {
            return;
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(result -> {
            if (result.failed()) {
                closed.completeExceptionally(result.cause());
            } else {
                closed.complete(null);
            }
        });
        closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("R33 defect 2: an invoke() returning a null future must still settle the bounded "
            + "internal-error fallback with exactly one terminal and one completion event")
    void shouldSettleTheBoundedInternalErrorFallbackWhenInvokeReturnsANullFuture() throws Exception {
        vertx = Vertx.vertx();
        Context vertxContext = vertx.getOrCreateContext();

        // Stands in for an application-supplied SecurityPolicyEnforcer/Authorizer resolving
        // genuinely asynchronously — see the class javadoc for why this is what actually exposes the
        // defect rather than merely relocating a synchronous NPE into an already-guarded catch.
        Promise<AuthorizationDecision> decisionPromise = Promise.promise();
        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        when(policyEnforcer.decide(any(), any())).thenReturn(decisionPromise.future());

        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        when(securityRuntime.current()).thenReturn(SecurityContexts.unauthenticated(SecurityIdentity.anonymous()));

        RecordingObserver observer = new RecordingObserver();
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(observer),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of(new NullInvocationToolInvoker())),
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));

        RoutingContext context = mockRoutingContext(vertxContext);
        // R09: begin() registers the correlation bind scope with RequestContextLifecycle's per-request
        // handle; this synthetic RoutingContext has no real ROOT-scoped middleware chain, so the test
        // installs the lifecycle handle itself, exactly as production's HttpVerticle does.
        new RequestContextLifecycle().handle(context);
        dispatcher.begin(context);

        dispatcher.dispatch(context);

        // Released only from a plain, non-Vert.x thread, and only after dispatch() has already
        // returned to this test — the dispatcher's own single-threaded, non-yielding sequence
        // (register the completion handler, then return) already guarantees the handler was attached
        // before this thread starts.
        Thread releaser = new Thread(
                () -> decisionPromise.tryComplete(AuthorizationDecision.permit("PERMITTED")),
                "r33-null-invocation-release");
        releaser.start();
        releaser.join(TimeUnit.SECONDS.toMillis(BOUNDED_AWAIT_SECONDS));

        boolean settled = observer.awaitSettlement(BOUNDED_AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(settled)
                .as("RED today (R33 defect 2): prepared.invoke() returning null NPEs inside a "
                        + "continuation dispatch()'s own try/catch never covers, so neither a response "
                        + "nor a terminal/completion event ever arrives within the bounded await")
                .isTrue();
        observer.assertExactlyOneTerminalThenOneCompletion();

        int status = statusOf(context);
        assertThat(status)
                .as("a null invocation future must settle the bounded internal-error fallback")
                .isEqualTo(500);
        Integer errorCode = errorCodeOf(context);
        assertThat(errorCode).isEqualTo(INTERNAL_ERROR_CODE);
    }

    private static int statusOf(RoutingContext context) {
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(context.response()).setStatusCode(statusCaptor.capture());
        return statusCaptor.getValue();
    }

    private static Integer errorCodeOf(RoutingContext context) {
        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(context.response()).end(bodyCaptor.capture());
        // A known, authorized tools/call has already selected SSE before invocation is ever attempted
        // (contract: "never falls back to JSON after SSE is selected"), so the bounded internal-error
        // fallback this defect settles through is SSE-framed too, exactly like every other authorized
        // tools/call response — never a bare JSON body. Mirrors
        // McpToolInterceptorPipelineTest#decodeSseJson, the established direct-dispatcher decode for
        // this same frame shape.
        JsonObject decoded = decodeSseJson(bodyCaptor.getValue().getBytes());
        JsonObject error = decoded.getJsonObject("error");
        return error == null ? null : error.getInteger("code");
    }

    /** Strips the single {@code event: message}/{@code data:} SSE frame and decodes its JSON body. */
    private static JsonObject decodeSseJson(byte[] framed) {
        String text = new String(framed, StandardCharsets.UTF_8);
        String prefix = "event: message\ndata: ";
        String suffix = "\n\n";
        return new JsonObject(text.substring(prefix.length(), text.length() - suffix.length()));
    }

    // --- Fixture: the defect-triggering invoker ---

    /** A {@link McpToolInvoker} whose prepared call's {@code invoke()} returns {@code null} (R33 defect 2). */
    private static final class NullInvocationToolInvoker implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                KNOWN_TOOL,
                null,
                "R33 defect 2 fixture: invoke() returns a null future.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        @Override
        public McpToolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    // R33 defect 2: a null return, never a thrown exception or a failed future.
                    return null;
                }
            };
        }
    }

    // --- Real recording observer/session (mirrors McpOffContextCompletionIT's RecordingObserver) ---

    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completionCount = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount.incrementAndGet();
            order.add("terminal");
            settlement.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount.incrementAndGet();
            order.add("completed");
            settlement.countDown();
        }

        private boolean awaitSettlement(long timeout, TimeUnit unit) throws InterruptedException {
            return settlement.await(timeout, unit);
        }

        private void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount.get()).isOne();
            assertThat(completionCount.get()).isOne();
            assertThat(order).containsExactly("terminal", "completed");
        }
    }

    // --- Wiring: a mocked RoutingContext backed by a real attribute map, mirroring McpLifecycleFactsTest ---

    private static RoutingContext mockRoutingContext(Context vertxContext) {
        RoutingContext context = mock(RoutingContext.class);
        Vertx contextVertx = mock(Vertx.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody requestBody = mock(RequestBody.class);
        Map<Object, Object> attributes = new HashMap<>();

        when(context.vertx()).thenReturn(contextVertx);
        when(contextVertx.getOrCreateContext()).thenReturn(vertxContext);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer())
                .thenReturn(Buffer.buffer(toolsCallBody().toBuffer().getBytes()));
        when(request.headers()).thenReturn(headers());
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(response.end()).thenReturn(Future.succeededFuture());
        when(response.closeHandler(any())).thenReturn(response);
        when(response.exceptionHandler(any())).thenReturn(response);
        when(context.put(anyString(), any())).thenAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return context;
        });
        when(context.get(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return context;
    }

    private static io.vertx.core.MultiMap headers() {
        io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.set("Mcp-Method", "tools/call");
        headers.set("Mcp-Name", KNOWN_TOOL);
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
}

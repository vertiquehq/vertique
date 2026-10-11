// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestDispatcher.COMPLETION_COORDINATOR_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
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
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Pins how the dispatcher treats the value in a request's coordinator slot once the request is past
 * admission, and which method a failure through its failure handler reports.
 *
 * <p>The dispatcher is driven directly with a mocked {@link RoutingContext} whose {@code put} and
 * {@code get} share one map, as {@code McpBeginReentryTest} does. A coordinator found in the slot that
 * does not belong to the request must receive nothing from the call, while a coordinator that does
 * belong to it receives the same calls, so the first assertion is the one that can fail.
 */
class McpDispatcherCoordinatorOwnershipTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String TOOL = "ownership.fixture.tool";
    private static final String RESULT_MARKER = "ownership-result-marker";
    private static final long WAIT_SECONDS = 5;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // --- Ownership of the coordinator slot on the tool-call path ---

    @Test
    @DisplayName("a coordinator in the slot that belongs to another request receives none of a tool call's "
            + "progress token, input or output")
    void shouldWriteNothingIntoAForeignCoordinatorDuringAToolCall() {
        // Given: the slot holds a coordinator built for another request's routing context.
        Fixture fixture = new Fixture(List.of());
        McpCompletionCoordinator foreign = spy(new McpCompletionCoordinator(
                vertx.getOrCreateContext(),
                Set.<McpRequestLifecycleObserver>of(),
                Set.<McpRequestCompletedListener>of(),
                Instant.parse("2026-08-21T00:00:00Z"),
                InstantSource.system(),
                otherRequestsRoutingContext()));
        fixture.context.put(COMPLETION_COORDINATOR_KEY, foreign);

        // When: a tools/call carrying a progress token is dispatched on this request.
        fixture.dispatch();

        // Then: the call ran and was answered directly, and the foreign coordinator was not written to.
        assertThat(fixture.responseText())
                .as("the tool ran and its result was written")
                .contains(RESULT_MARKER);
        assertAll(
                () -> verify(foreign, never().description("the progress token")).bindProgressToken(any()),
                () -> verify(foreign, never().description("the prepared call's input"))
                        .bindToolInput(any(), any()),
                () -> verify(foreign, never().description("the tool's output")).armToolOutput(any()),
                () -> verify(foreign, never().description("a write of this request"))
                        .beginWrite(any()));
    }

    @Test
    @DisplayName("a coordinator in the slot that belongs to the request receives the tool call's progress "
            + "token, input and output")
    void shouldWriteTheCallIntoTheRequestsOwnCoordinator() {
        // Given: the slot holds a coordinator built for this request's routing context.
        Fixture fixture = new Fixture(List.of());
        McpCompletionCoordinator owned = spy(new McpCompletionCoordinator(
                vertx.getOrCreateContext(),
                Set.<McpRequestLifecycleObserver>of(),
                Set.<McpRequestCompletedListener>of(),
                Instant.parse("2026-08-21T00:00:00Z"),
                InstantSource.system(),
                fixture.context));
        fixture.context.put(COMPLETION_COORDINATOR_KEY, owned);

        // When: the same tools/call is dispatched.
        fixture.dispatch();

        // Then: the control: the request's own coordinator receives all three, so the foreign row's
        // absence is a consequence of ownership and not of the call never reaching these binds.
        assertThat(fixture.responseText()).contains(RESULT_MARKER);
        verify(owned, times(1)).bindProgressToken(any());
        verify(owned, times(1)).bindToolInput(any(), any());
        verify(owned, times(1)).armToolOutput(any());
        verify(owned, times(1)).disarmToolOutput();
    }

    @Test
    @DisplayName(
            "a coordinator in the slot that belongs to another request is not given the request's " + "linked trace")
    void shouldBindNoLinkedTraceIntoAForeignCoordinator() {
        // Given: the slot holds a coordinator built for another request's routing context.
        Fixture fixture = new Fixture(List.of());
        McpCompletionCoordinator foreign = foreignCoordinator();
        fixture.context.put(COMPLETION_COORDINATOR_KEY, foreign);

        // When: a tools/call is dispatched on this request.
        fixture.dispatch();

        // Then: the call ran, and the foreign coordinator was not given a linked trace.
        assertThat(fixture.responseText()).contains(RESULT_MARKER);
        verify(foreign, never().description("DECISIVE: the linked trace of this request"))
                .bindLinkedTrace(any());
    }

    @Test
    @DisplayName("a coordinator in the slot that belongs to another request and was cancelled does not "
            + "make this request report settled")
    void shouldNotTreatAForeignCancelledCoordinatorAsThisRequestSettled() throws Exception {
        // Given: the slot holds another request's coordinator, whose cancellation has fired.
        Fixture fixture = new Fixture(List.of());
        McpCompletionCoordinator foreign = foreignCoordinator();
        fireCancellation(foreign);
        fixture.context.put(COMPLETION_COORDINATOR_KEY, foreign);

        // When: a tools/call is dispatched on this request.
        fixture.dispatch();

        // Then: the request proceeded and was answered.
        assertThat(fixture.responseText())
                .as("DECISIVE: the settlement guards did not stop this request")
                .contains(RESULT_MARKER);
    }

    @Test
    @DisplayName("a cancelled coordinator that belongs to the request stops the call before any "
            + "authorization, input work or tool invocation, and before any write")
    void shouldStopTheCallWhenTheRequestsOwnCoordinatorWasCancelled() throws Exception {
        // Given: the slot holds this request's own coordinator, whose cancellation has fired.
        FixtureTool tool = new FixtureTool();
        Fixture fixture = new Fixture(List.of(), tool);
        McpCompletionCoordinator owned = new McpCompletionCoordinator(
                vertx.getOrCreateContext(),
                Set.<McpRequestLifecycleObserver>of(),
                Set.<McpRequestCompletedListener>of(),
                Instant.parse("2026-08-21T00:00:00Z"),
                InstantSource.system(),
                fixture.context);
        fireCancellation(owned);
        fixture.context.put(COMPLETION_COORDINATOR_KEY, owned);

        // When: a tools/call is dispatched.
        fixture.dispatch();

        // Then: the control: the guard stopped the call, so no authorization decision was made, the
        // tool's input was not prepared, the tool never ran and nothing was written. The write is
        // suppressed by its own settlement claim whether or not the guard fired, and the invocation
        // itself is also refused by the cancellation signal, so the decision and the preparation are
        // what show that the guard did.
        verify(
                        fixture.policyEnforcer,
                        never().description("DECISIVE: no authorization decision for a " + "settled request"))
                .decide(any(), any());
        assertAll(
                () -> assertThat(tool.preparations())
                        .as("DECISIVE: the settled request's input was not prepared")
                        .isZero(),
                () -> assertThat(tool.invocations())
                        .as("the settled request never invoked the tool")
                        .isZero());
        verify(fixture.response, never().description("the settled request is not answered a second time"))
                .end(any(Buffer.class));
    }

    // --- The method a failure through the failure handler reports ---

    @ParameterizedTest(name = "status={0}, dispatched={1}, responseEnded={2} -> {3}")
    @CsvSource({
        // A server failure reports the method dispatch had classified, or OTHER when it had none.
        "500, true,  false, TOOLS_CALL",
        "500, true,  true,  TOOLS_CALL",
        "500, false, false, OTHER",
        "500, false, true,  OTHER",
        // An authentication rejection reports OTHER even when dispatch had classified tools/call: the
        // ended-response rows are the ones that move; the open-response rows are the controls.
        "401, true,  true,  OTHER",
        "403, true,  true,  OTHER",
        "401, true,  false, OTHER",
        "403, true,  false, OTHER",
    })
    @DisplayName("a failure handled after the request was admitted reports the method dispatch had "
            + "classified, or OTHER when it had classified none or the failure is an authentication rejection")
    void shouldReportTheClassifiedMethodOfAFailedRequest(
            int status, boolean dispatched, boolean responseEnded, McpMethod expectedMethod) throws Exception {
        // Given: an admitted request with a listener, whose tool call is in flight when dispatched.
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        HeldTool tool = new HeldTool();
        Fixture fixture = new Fixture(List.of(listener), tool);
        fixture.begin();
        if (dispatched) {
            fixture.dispatch();
            assertThat(tool.invoked.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("precondition: the tool is in flight, so dispatch classified the method")
                    .isTrue();
        }
        when(fixture.context.statusCode()).thenReturn(status);
        if (responseEnded) {
            when(fixture.response.ended()).thenReturn(true);
            when(fixture.response.headWritten()).thenReturn(true);
        }

        // When: the request fails through the failure handler.
        fixture.dispatcher.handleFailure(fixture.context);

        // Then: exactly one completion, whose terminal carries the method known at that point.
        List<Completion> completions = listener.await(1);
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).event().terminal().method())
                .as("DECISIVE: the failure's terminal reports the method known when it failed")
                .isEqualTo(expectedMethod);
        assertThat(listener.arrivesWithin(2, Duration.ofMillis(200)))
                .as("no second completion follows")
                .isFalse();
    }

    @ParameterizedTest(name = "responseEnded={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a value of another type in the classified-method slot counts as no classified method and "
            + "does not fail the failure handler")
    void shouldReportOtherWhenTheClassifiedMethodSlotHoldsAnotherType(boolean responseEnded) throws Exception {
        // Given: a dispatched request whose classified-method slot, a predictable key in shared
        // routing-context data, was overwritten with a String.
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        HeldTool tool = new HeldTool();
        Fixture fixture = new Fixture(List.of(listener), tool);
        fixture.begin();
        fixture.dispatch();
        assertThat(tool.invoked.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("precondition: the tool is in flight, so dispatch classified the method")
                .isTrue();
        String methodKey = McpRequestDispatcher.class.getName() + ".method";
        assertThat((Object) fixture.context.get(methodKey))
                .as("precondition: the slot held the classified method")
                .isEqualTo(McpMethod.TOOLS_CALL);
        fixture.context.put(methodKey, "tools/call");
        when(fixture.context.statusCode()).thenReturn(500);
        if (responseEnded) {
            when(fixture.response.ended()).thenReturn(true);
            when(fixture.response.headWritten()).thenReturn(true);
        }

        // When: the request fails through the failure handler.
        // Then: the handler does not throw.
        assertThatCode(() -> fixture.dispatcher.handleFailure(fixture.context))
                .as("DECISIVE: a foreign value in the slot does not fail the handler")
                .doesNotThrowAnyException();

        // Then: the request is completed once, with OTHER as its method.
        List<Completion> completions = listener.await(1);
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).event().terminal().method()).isEqualTo(McpMethod.OTHER);
        assertThat(listener.arrivesWithin(2, Duration.ofMillis(200)))
                .as("no second completion follows")
                .isFalse();
    }

    // --- Fixture ---

    private McpCompletionCoordinator foreignCoordinator() {
        return spy(new McpCompletionCoordinator(
                vertx.getOrCreateContext(),
                Set.<McpRequestLifecycleObserver>of(),
                Set.<McpRequestCompletedListener>of(),
                Instant.parse("2026-08-21T00:00:00Z"),
                InstantSource.system(),
                otherRequestsRoutingContext()));
    }

    /** Settles the coordinator as a reset, which fires its cancellation signal, and awaits the signal. */
    private static void fireCancellation(McpCompletionCoordinator coordinator) throws Exception {
        Instant startedAt = Instant.parse("2026-08-21T00:00:00Z");
        coordinator.settleReset(
                McpRequestTerminalEvent.cancelled(
                        startedAt,
                        startedAt.plusMillis(10),
                        McpMethod.OTHER,
                        McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                        McpErrorType.TRANSPORT,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        RequestOrigin.unknown()),
                false);
        coordinator
                .cancellation()
                .cancelled()
                .toCompletionStage()
                .toCompletableFuture()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private RoutingContext otherRequestsRoutingContext() {
        RoutingContext other = mock(RoutingContext.class);
        when(other.request()).thenReturn(mock(HttpServerRequest.class));
        return other;
    }

    /** A tool that records its invocation and then never completes. */
    private static final class HeldTool extends FixtureTool {
        private final CountDownLatch invoked = new CountDownLatch(1);

        @Override
        Future<McpToolResult<?>> result() {
            invoked.countDown();
            return Promise.<McpToolResult<?>>promise().future();
        }
    }

    /** A tool that answers immediately with a structured result. */
    private static class FixtureTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL,
                null,
                "Coordinator ownership fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        Future<McpToolResult<?>> result() {
            return Future.succeededFuture(McpToolResult.structured(Map.of("marker", RESULT_MARKER)));
        }

        private final AtomicInteger preparations = new AtomicInteger();
        private final AtomicInteger invocations = new AtomicInteger();

        /** Returns how many times this tool's input was prepared. */
        int preparations() {
            return preparations.get();
        }

        /** Returns how many times a prepared call of this tool was invoked. */
        int invocations() {
            return invocations.get();
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            preparations.incrementAndGet();
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocations.incrementAndGet();
                    return result();
                }
            };
        }
    }

    /** One dispatcher with a permitting policy enforcer, and one request's map-backed routing context. */
    private final class Fixture {
        private final McpRequestDispatcher dispatcher;
        private final McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        private final RoutingContext context = mock(RoutingContext.class);
        private final HttpServerResponse response = mock(HttpServerResponse.class);

        Fixture(List<McpRequestCompletedListener> listeners) {
            this(listeners, new FixtureTool());
        }

        Fixture(List<McpRequestCompletedListener> listeners, FixtureTool tool) {
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
            when(securityRuntime.current()).thenReturn(anonymous);
            when(policyEnforcer.decide(any(), any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit("PERMITTED")));
            this.dispatcher = new McpRequestDispatcher(
                    McpServerConfig.defaults(),
                    securityRuntime,
                    Set.<McpRequestLifecycleObserver>of(),
                    Set.copyOf(listeners),
                    Set.of(),
                    Set.of(),
                    HttpConfig.builder().build(),
                    McpToolRegistry.build(Set.of(tool)),
                    policyEnforcer,
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
            wire();
        }

        private void wire() {
            io.vertx.core.Vertx contextVertx = mock(io.vertx.core.Vertx.class);
            Context vertxContext = vertx.getOrCreateContext();
            HttpServerRequest request = mock(HttpServerRequest.class);
            RequestBody requestBody = mock(RequestBody.class);
            Map<Object, Object> attributes = Collections.synchronizedMap(new HashMap<>());
            when(context.vertx()).thenReturn(contextVertx);
            when(contextVertx.getOrCreateContext()).thenReturn(vertxContext);
            when(context.request()).thenReturn(request);
            when(context.response()).thenReturn(response);
            when(context.body()).thenReturn(requestBody);
            when(requestBody.buffer())
                    .thenReturn(Buffer.buffer(toolsCallBody().toBuffer().getBytes()));
            when(request.headers()).thenReturn(negotiationHeaders());
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
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
            new RequestContextLifecycle().handle(context);
        }

        /** Runs {@code begin}, which builds the request's own coordinator. */
        void begin() {
            dispatcher.begin(context);
        }

        void dispatch() {
            dispatcher.dispatch(context);
        }

        /** The text of the one buffer the terminal writer ended the response with. */
        String responseText() {
            ArgumentCaptor<Buffer> written = ArgumentCaptor.forClass(Buffer.class);
            verify(response).end(written.capture());
            return written.getValue().toString();
        }
    }

    private static MultiMap negotiationHeaders() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.set("Mcp-Method", "tools/call");
        headers.set("Mcp-Name", TOOL);
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
                                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject())
                                                .put("progressToken", "ownership-progress"))
                                .put("name", TOOL)
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

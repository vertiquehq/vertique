// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * Proves that handler-path failures are classified at the MCP boundary without leaking application
 * exception text, and that the shared SSE fallback keeps its existing serialization behavior.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpHandlerPathRateLimitClassificationTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String KNOWN_TOOL = "handler-path-classification-fixture";
    private static final int RATE_LIMIT_CODE = -32010;
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
    @DisplayName("maps TooManyRequestsException on both synchronous and asynchronous handler paths")
    void shouldClassifyTooManyRequestsExceptionAsRateLimitRejectionWhileStatusIsWritable() throws Exception {
        TooManyRequestsException failure =
                new TooManyRequestsException("secret handler detail", Duration.ofMillis(2500));

        assertFailure(
                failure,
                InvocationMode.SYNCHRONOUS,
                429,
                RATE_LIMIT_CODE,
                "Rate limit exceeded",
                McpErrorType.RATE_LIMIT,
                McpOutcome.REJECTED,
                true,
                "3");
        assertFailure(
                failure,
                InvocationMode.ASYNCHRONOUS,
                429,
                RATE_LIMIT_CODE,
                "Rate limit exceeded",
                McpErrorType.RATE_LIMIT,
                McpOutcome.REJECTED,
                true,
                "3");
    }

    @Test
    @DisplayName("maps rate-limit backend unavailability without treating it as a generic handler failure")
    void shouldClassifyRateLimitUnavailableExceptionAsRateLimitingUnavailableRejection() throws Exception {
        RateLimitUnavailableException failure = new RateLimitUnavailableException(rateLimitDecision());

        assertFailure(
                failure,
                InvocationMode.SYNCHRONOUS,
                503,
                RATE_LIMIT_CODE,
                "Rate limiting unavailable",
                McpErrorType.RATE_LIMIT,
                McpOutcome.REJECTED,
                true,
                null);
        assertFailure(
                failure,
                InvocationMode.ASYNCHRONOUS,
                503,
                RATE_LIMIT_CODE,
                "Rate limiting unavailable",
                McpErrorType.RATE_LIMIT,
                McpOutcome.REJECTED,
                true,
                null);
    }

    @Test
    @DisplayName("maps resilience timeout and unavailable failures to fixed MCP transport errors")
    void shouldMapResilienceTimeoutToBoundedMcpFailure() throws Exception {
        String key = "test:" + "0".repeat(64);
        ResilienceTimeoutException timeout = new ResilienceTimeoutException(key, 100);
        CircuitOpenException unavailable = new CircuitOpenException(key, key);

        assertFailure(
                timeout,
                InvocationMode.SYNCHRONOUS,
                504,
                INTERNAL_ERROR_CODE,
                "Request timed out",
                McpErrorType.TIMEOUT,
                McpOutcome.FAILED,
                true,
                null);
        assertFailure(
                unavailable,
                InvocationMode.ASYNCHRONOUS,
                503,
                INTERNAL_ERROR_CODE,
                "Service unavailable",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                true,
                null);
    }

    @Test
    @DisplayName("maps resilience unavailability to the bounded service-unavailable failure")
    void shouldMapResilienceUnavailableToBoundedMcpFailure() throws Exception {
        String key = "test:" + "0".repeat(64);
        assertFailure(
                new CircuitOpenException(key, key),
                InvocationMode.ASYNCHRONOUS,
                503,
                INTERNAL_ERROR_CODE,
                "Service unavailable",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                true,
                null);
    }

    @Test
    @DisplayName("recognizes a bounded cause chain, but terminates safely for cycles and deeper chains")
    void shouldTerminateOnACyclicCauseChainWithoutMisclassifying() throws Exception {
        RuntimeException wrapped =
                new RuntimeException("outer", new RateLimitUnavailableException(rateLimitDecision()));
        assertFailure(
                wrapped,
                InvocationMode.SYNCHRONOUS,
                503,
                RATE_LIMIT_CODE,
                "Rate limiting unavailable",
                McpErrorType.RATE_LIMIT,
                McpOutcome.REJECTED,
                true,
                null);

        RuntimeException cycleA = new RuntimeException("a");
        RuntimeException cycleB = new RuntimeException("b");
        cycleA.initCause(cycleB);
        cycleB.initCause(cycleA);
        assertFailure(
                cycleA,
                InvocationMode.SYNCHRONOUS,
                500,
                INTERNAL_ERROR_CODE,
                "Internal error",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                false,
                null);

        Throwable deep = new RateLimitUnavailableException(rateLimitDecision());
        for (int i = 0; i < 9; i++) {
            deep = new RuntimeException("wrapper-" + i, deep);
        }
        assertFailure(
                deep,
                InvocationMode.SYNCHRONOUS,
                500,
                INTERNAL_ERROR_CODE,
                "Internal error",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                false,
                null);
    }

    @Test
    @DisplayName("preserves committed response status and headers while retaining classified lifecycle facts")
    void shouldCarryOnlyTheJsonRpcCodeOnceTheResponseIsAlreadyCommitted() throws Exception {
        Fixture fixture = new Fixture(
                new TooManyRequestsException("hidden", Duration.ofSeconds(2)), InvocationMode.SYNCHRONOUS, true);
        fixture.dispatch();

        assertThat(fixture.observer.awaitSettlement()).isTrue();
        assertThat(fixture.observer.terminal.get().event().httpStatus()).isEqualTo(429);
        assertThat(errorBody(fixture.response).getJsonObject("error").getInteger("code"))
                .isEqualTo(RATE_LIMIT_CODE);
        verify(fixture.response, never()).setStatusCode(anyInt());
        verify(fixture.response, never()).putHeader(eq("Retry-After"), anyString());
        verify(fixture.response, never()).putHeader(eq("Cache-Control"), anyString());
    }

    @Test
    @DisplayName("chooses the nearest recognized root in the original cause order")
    void shouldChooseTheNearestRecognizedHandlerFailureRoot() throws Exception {
        String key = "test:" + "0".repeat(64);
        ResilienceTimeoutException timeoutOuter = new ResilienceTimeoutException(key, 100);
        timeoutOuter.initCause(new TooManyRequestsException("inner", Duration.ofSeconds(1)));
        TooManyRequestsException rateLimitOuter = new TooManyRequestsException("outer", Duration.ofSeconds(1));
        rateLimitOuter.initCause(new ResilienceTimeoutException(key, 100));
        ResiliencePolicyException policyOuter =
                new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION);
        policyOuter.initCause(new CircuitOpenException(key, key));

        assertFailure(
                timeoutOuter,
                InvocationMode.SYNCHRONOUS,
                504,
                INTERNAL_ERROR_CODE,
                "Request timed out",
                McpErrorType.TIMEOUT,
                McpOutcome.FAILED,
                true,
                null);
        assertFailure(
                rateLimitOuter,
                InvocationMode.SYNCHRONOUS,
                429,
                RATE_LIMIT_CODE,
                "Rate limit exceeded",
                McpErrorType.RATE_LIMIT,
                McpOutcome.REJECTED,
                true,
                "1");
        assertFailure(
                policyOuter,
                InvocationMode.SYNCHRONOUS,
                503,
                INTERNAL_ERROR_CODE,
                "Service unavailable",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                true,
                null);
    }

    @Test
    @DisplayName("preserves committed status and headers for resilience failures")
    void shouldPreserveCommittedStatusAndHeadersForResilienceFailures() throws Exception {
        String key = "test:" + "0".repeat(64);
        Fixture timeout = new Fixture(new ResilienceTimeoutException(key, 100), InvocationMode.SYNCHRONOUS, true);
        timeout.dispatch();
        assertThat(timeout.observer.terminal.get().event().httpStatus()).isEqualTo(504);
        assertThat(errorBody(timeout.response).getJsonObject("error").getString("message"))
                .isEqualTo("Request timed out");
        verify(timeout.response, never()).setStatusCode(anyInt());
        verify(timeout.response, never()).putHeader(eq("Cache-Control"), anyString());

        Fixture unavailable = new Fixture(new CircuitOpenException(key, key), InvocationMode.ASYNCHRONOUS, true);
        unavailable.dispatch();
        assertThat(unavailable.observer.terminal.get().event().httpStatus()).isEqualTo(503);
        assertThat(errorBody(unavailable.response).getJsonObject("error").getString("message"))
                .isEqualTo("Service unavailable");
        verify(unavailable.response, never()).setStatusCode(anyInt());
        verify(unavailable.response, never()).putHeader(eq("Cache-Control"), anyString());
    }

    @Test
    @DisplayName("keeps generic and unrecognized resilience failures on the existing internal fallback")
    void shouldRetainInternalFallbackForUnrecognizedResilienceFailures() throws Exception {
        assertFailure(
                new RuntimeException("secret", new UnavailableException("also secret")),
                InvocationMode.SYNCHRONOUS,
                500,
                INTERNAL_ERROR_CODE,
                "Internal error",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                false,
                null);
        assertFailure(
                new UnavailableException("backend detail must stay private"),
                InvocationMode.SYNCHRONOUS,
                500,
                INTERNAL_ERROR_CODE,
                "Internal error",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                false,
                null);
        assertFailure(
                new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION),
                InvocationMode.ASYNCHRONOUS,
                500,
                INTERNAL_ERROR_CODE,
                "Internal error",
                McpErrorType.INTERNAL,
                McpOutcome.FAILED,
                false,
                null);
    }

    @Test
    @DisplayName("keeps structured-content normalization failures on the serialization fallback")
    void shouldLeaveStructuredContentSerializationFailureUnchanged() throws Exception {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        Fixture fixture =
                new Fixture(McpServerConfig.defaults(), new ResultInvoker(McpToolResult.structured(cyclic)), false);
        fixture.dispatch();

        assertPreservedSerializationFallback(fixture);
    }

    @Test
    @DisplayName("keeps output-cap failures on the serialization fallback")
    void shouldLeaveOutputCapSerializationFailureUnchanged() throws Exception {
        Fixture fixture = new Fixture(
                McpServerConfig.builder().outputMaxBytes(1_024).build(),
                new ResultInvoker(McpToolResult.text("x".repeat(5_000))),
                false);
        fixture.dispatch();

        assertPreservedSerializationFallback(fixture);
    }

    private void assertFailure(
            Throwable failure,
            InvocationMode mode,
            int expectedStatus,
            int expectedCode,
            String expectedMessage,
            McpErrorType expectedErrorType,
            McpOutcome expectedOutcome,
            boolean noStore,
            String expectedRetryAfter)
            throws Exception {
        Fixture fixture = new Fixture(failure, mode, false);
        fixture.dispatch();

        assertThat(fixture.observer.awaitSettlement()).isTrue();
        McpRequestTerminalEvent terminal = fixture.observer.terminal.get().event();
        assertThat(terminal.httpStatus()).isEqualTo(expectedStatus);
        assertThat(terminal.protocolErrorCode()).isEqualTo(expectedCode);
        assertThat(terminal.errorType()).isEqualTo(expectedErrorType);
        assertThat(terminal.outcome()).isEqualTo(expectedOutcome);
        assertThat(errorBody(fixture.response).getJsonObject("error").getInteger("code"))
                .isEqualTo(expectedCode);
        assertThat(errorBody(fixture.response).getJsonObject("error").getString("message"))
                .isEqualTo(expectedMessage);
        if (noStore) {
            verify(fixture.response).putHeader("Cache-Control", "no-store");
        } else {
            verify(fixture.response, never()).putHeader(eq("Cache-Control"), anyString());
        }
        if (expectedRetryAfter != null) {
            verify(fixture.response).putHeader("Retry-After", expectedRetryAfter);
        } else {
            verify(fixture.response, never()).putHeader(eq("Retry-After"), anyString());
        }
    }

    private static JsonObject errorBody(HttpServerResponse response) {
        ArgumentCaptor<Buffer> body = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(body.capture());
        byte[] framed = body.getValue().getBytes();
        String text = new String(framed, StandardCharsets.UTF_8);
        String prefix = "event: message\ndata: ";
        String suffix = "\n\n";
        return new JsonObject(text.substring(prefix.length(), text.length() - suffix.length()));
    }

    private static void assertPreservedSerializationFallback(Fixture fixture) throws Exception {
        McpRequestTerminalEvent terminal = fixture.observer.terminal.get().event();
        assertThat(terminal.httpStatus()).isEqualTo(500);
        assertThat(terminal.protocolErrorCode()).isEqualTo(INTERNAL_ERROR_CODE);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.SERIALIZATION);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.FAILED);
        JsonObject error = errorBody(fixture.response).getJsonObject("error");
        assertThat(error.getInteger("code")).isEqualTo(INTERNAL_ERROR_CODE);
        assertThat(error.getString("message")).isEqualTo("Internal error");
        verify(fixture.response, never()).putHeader(eq("Retry-After"), anyString());
        verify(fixture.response, never()).putHeader(eq("Cache-Control"), anyString());
    }

    private static RateLimitDecision rateLimitDecision() {
        return new RateLimitDecision(
                "fixture",
                RateLimitOutcome.BACKEND_FAILURE_CLOSED,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                1,
                OptionalLong.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(RateLimitFailureCode.UNAVAILABLE));
    }

    private enum InvocationMode {
        SYNCHRONOUS,
        ASYNCHRONOUS
    }

    private final class Fixture {
        private final RecordingObserver observer = new RecordingObserver();
        private final Promise<AuthorizationDecision> decision = Promise.promise();
        private final HttpServerResponse response = mock(HttpServerResponse.class);
        private final RoutingContext context;
        private final McpRequestDispatcher dispatcher;

        private Fixture(Throwable failure, InvocationMode mode, boolean committed) {
            this(McpServerConfig.defaults(), new FailureInvoker(failure, mode), committed);
        }

        private Fixture(McpServerConfig config, McpToolInvoker invoker, boolean committed) {
            vertx = Vertx.vertx();
            Context owningContext = vertx.getOrCreateContext();
            McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
            when(policyEnforcer.decide(any(), any())).thenReturn(decision.future());
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            when(securityRuntime.current()).thenReturn(SecurityContexts.unauthenticated(SecurityIdentity.anonymous()));
            dispatcher = new McpRequestDispatcher(
                    config,
                    securityRuntime,
                    Set.of(observer),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    HttpConfig.builder().build(),
                    McpToolRegistry.build(Set.of(invoker)),
                    policyEnforcer,
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
            context = mockRoutingContext(owningContext, response, committed);
        }

        private void dispatch() throws Exception {
            new RequestContextLifecycle().handle(context);
            dispatcher.begin(context);
            dispatcher.dispatch(context);
            decision.tryComplete(AuthorizationDecision.permit("PERMITTED"));
            assertThat(observer.awaitSettlement()).isTrue();
        }
    }

    private static final class FailureInvoker implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                KNOWN_TOOL,
                null,
                "handler failure classification fixture",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        private final Throwable failure;
        private final InvocationMode mode;

        private FailureInvoker(Throwable failure, InvocationMode mode) {
            this.failure = failure;
            this.mode = mode;
        }

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
                    if (mode == InvocationMode.SYNCHRONOUS) {
                        throwUnchecked(failure);
                    }
                    return Future.failedFuture(failure);
                }
            };
        }

        private static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(failure);
        }
    }

    private static final class ResultInvoker implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                KNOWN_TOOL,
                null,
                "handler result serialization fixture",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        private final McpToolResult<?> result;

        private ResultInvoker(McpToolResult<?> result) {
            this.result = result;
        }

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
                    return Future.succeededFuture(result);
                }
            };
        }
    }

    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final AtomicReference<McpRequestTerminalObservation> terminal = new AtomicReference<>();
        private final List<String> order = new CopyOnWriteArrayList<>();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminal.set(observation);
            order.add("terminal");
            settlement.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            order.add("completed");
            settlement.countDown();
        }

        private boolean awaitSettlement() throws InterruptedException {
            return settlement.await(5, TimeUnit.SECONDS);
        }
    }

    private static RoutingContext mockRoutingContext(
            Context owningContext, HttpServerResponse response, boolean committed) {
        RoutingContext context = mock(RoutingContext.class);
        Vertx contextVertx = mock(Vertx.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        RequestBody requestBody = mock(RequestBody.class);
        Map<Object, Object> attributes = new HashMap<>();

        when(context.vertx()).thenReturn(contextVertx);
        when(contextVertx.getOrCreateContext()).thenReturn(owningContext);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer())
                .thenReturn(Buffer.buffer(toolsCallBody().encode().getBytes(StandardCharsets.UTF_8)));
        when(request.headers()).thenReturn(headers());
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(response.end()).thenReturn(Future.succeededFuture());
        when(response.closeHandler(any())).thenReturn(response);
        when(response.exceptionHandler(any())).thenReturn(response);
        when(response.headWritten()).thenReturn(committed);
        when(response.getStatusCode()).thenReturn(committed ? 200 : 0);
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

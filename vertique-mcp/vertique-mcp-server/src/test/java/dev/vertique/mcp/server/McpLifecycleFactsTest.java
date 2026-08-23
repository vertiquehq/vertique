// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.mcp.lifecycle.McpAuthorizationSummary;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
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
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R05 TP-002 — {@code shouldCarryNegotiatedVersionCorrelationAndAuthorizationToEveryTerminal}
 * (issue #431).
 *
 * <p>Drives {@link McpRequestDispatcher#begin}, then either {@link McpRequestDispatcher#dispatch} or
 * {@link McpRequestDispatcher#completeAuthenticationRejection}, against a mocked {@link
 * RoutingContext} wired to a real {@link McpCompletionCoordinator} (so terminal delivery is genuine,
 * not simulated) and a real recording {@link McpRequestLifecycleObserver} session. Four rows enumerate
 * the named terminal paths: a successful {@code tools/call}, a policy-denied {@code tools/call}, an
 * authentication rejection (terminates before negotiation ever runs), and a handler failure inside an
 * otherwise-authorized {@code tools/call} (a "lifecycle-observed failure" — the terminal event a
 * genuine handler throw produces, observed through the same lifecycle session every other row uses).
 * Every row asserts the terminal event's {@code protocolVersion}, {@code correlation}, and {@code
 * authorization} facts (or their documented absence) rather than only the wire response, because the
 * frozen record's own fields are exactly what {@code vertique-audit-mcp} and the OpenTelemetry adapter
 * consume — a proof that only inspected the HTTP response could not tell them apart from the
 * pre-repair {@code null} facts.
 *
 * <p>Enterprise-side consumption ({@code McpAuditProjector} reading {@link
 * McpRequestTerminalEvent#correlation()} instead of {@code AuditCorrelation.empty()}) is outside this
 * module's worktree and is not re-proven here; {@code McpAuditProjector} already branches on a
 * non-{@code null} correlation (see its source), so once this slice makes production ever populate one,
 * that branch starts exercising for real without an enterprise-side code change.
 */
class McpLifecycleFactsTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String KNOWN_TOOL = "greet";

    private static final String SUCCESS_ROW = "shouldCarryFactsOnASuccessfulToolCall";
    private static final String DENIAL_ROW = "shouldCarryFactsOnAPolicyDeniedToolCall";
    private static final String AUTHENTICATION_FAILURE_ROW = "shouldCarryFactsOnAnAuthenticationRejection";
    private static final String LIFECYCLE_FAILURE_ROW = "shouldCarryFactsOnAHandlerFailureInsideAnAuthorizedCall";

    private final List<Vertx> openedVertx = new ArrayList<>();

    @AfterEach
    void tearDown() {
        openedVertx.forEach(Vertx::close);
        openedVertx.clear();
    }

    private static Stream<String> r05Tp002Rows() {
        return Stream.of(SUCCESS_ROW, DENIAL_ROW, AUTHENTICATION_FAILURE_ROW, LIFECYCLE_FAILURE_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("r05Tp002Rows")
    @DisplayName("R05 TP-002: every terminal path carries the negotiated version, correlation, and authorization")
    void shouldCarryNegotiatedVersionCorrelationAndAuthorizationToEveryTerminal(String row) {
        switch (row) {
            case SUCCESS_ROW -> shouldCarryFactsOnASuccessfulToolCall();
            case DENIAL_ROW -> shouldCarryFactsOnAPolicyDeniedToolCall();
            case AUTHENTICATION_FAILURE_ROW -> shouldCarryFactsOnAnAuthenticationRejection();
            case LIFECYCLE_FAILURE_ROW -> shouldCarryFactsOnAHandlerFailureInsideAnAuthorizedCall();
            default -> throw new IllegalArgumentException("unknown R05 TP-002 row: " + row);
        }
    }

    // --- Success ---

    private void shouldCarryFactsOnASuccessfulToolCall() {
        Fixture fixture =
                build(AuthorizationDecision.permit("PERMITTED"), FakeInvoker.succeeding(McpToolResult.text("ok")));

        fixture.dispatcher.dispatch(fixture.context);

        McpRequestTerminalEvent terminal = onlyTerminal(fixture);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.SUCCESS);
        assertProtocolVersionPresent(terminal);
        assertCorrelationPresent(terminal);
        assertThat(terminal.authorization())
                .as("DECISIVE: the permit decision's own summary reaches the success terminal")
                .isEqualTo(new McpAuthorizationSummary(true, "permitted", null, null));
    }

    // --- Denial ---

    private void shouldCarryFactsOnAPolicyDeniedToolCall() {
        Fixture fixture = build(
                AuthorizationDecision.deny("ROLE_MISSING"), FakeInvoker.succeeding(McpToolResult.text("unreachable")));

        fixture.dispatcher.dispatch(fixture.context);

        McpRequestTerminalEvent terminal = onlyTerminal(fixture);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.AUTHORIZATION);
        assertProtocolVersionPresent(terminal);
        assertCorrelationPresent(terminal);
        assertThat(terminal.authorization())
                .as("DECISIVE: the deny decision's own summary reaches the rejection terminal — this is the "
                        + "row the R05 task names as the sensitivity target for dropping the summary")
                .isEqualTo(new McpAuthorizationSummary(false, "role_missing", null, null));
    }

    // --- Authentication failure (terminates before negotiation ever runs) ---

    private void shouldCarryFactsOnAnAuthenticationRejection() {
        Fixture fixture = build(AuthorizationDecision.permit("PERMITTED"), FakeInvoker.succeeding(null));

        McpRequestDispatcher.completeAuthenticationRejection(fixture.context);

        McpRequestTerminalEvent terminal = onlyTerminal(fixture);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.AUTHENTICATION);
        assertThat(terminal.protocolVersion())
                .as("DECISIVE: a request rejected before negotiation ever ran carries no negotiated "
                        + "version — 'or that it negotiated nothing' (contract §4.7)")
                .isNull();
        assertCorrelationPresent(terminal);
        assertThat(terminal.authorization())
                .as("no policy evaluation ever occurred for an authentication rejection")
                .isNull();
        assertThat(terminal.security())
                .as("the frozen invariant: an authentication rejection must not carry security facts")
                .isNull();
    }

    // --- Lifecycle-observed failure: a handler throw inside an otherwise-authorized call ---

    private void shouldCarryFactsOnAHandlerFailureInsideAnAuthorizedCall() {
        Fixture fixture = build(AuthorizationDecision.permit("PERMITTED"), FakeInvoker.throwingOnPrepare());

        fixture.dispatcher.dispatch(fixture.context);

        McpRequestTerminalEvent terminal = onlyTerminal(fixture);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.FAILED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERNAL);
        assertProtocolVersionPresent(terminal);
        assertCorrelationPresent(terminal);
        assertThat(terminal.authorization())
                .as("DECISIVE: the policy decision made before the handler ran is still carried on a "
                        + "terminal event the handler's own failure produced")
                .isEqualTo(new McpAuthorizationSummary(true, "permitted", null, null));
    }

    // --- Shared assertions ---

    private static void assertProtocolVersionPresent(McpRequestTerminalEvent terminal) {
        assertThat(terminal.protocolVersion())
                .as("DECISIVE: the negotiated protocol version — read from this request's own body, never "
                        + "a hardcoded constant — reaches the terminal event")
                .isEqualTo(PROTOCOL_VERSION);
    }

    private static void assertCorrelationPresent(McpRequestTerminalEvent terminal) {
        CorrelationContextSnapshot correlation = terminal.correlation();
        assertThat(correlation)
                .as("DECISIVE: correlation must never be null on this path")
                .isNotNull();
        assertThat(correlation.requestId().value())
                .as("a non-empty, minted correlation identifier — not a blank or synthetic placeholder")
                .isNotBlank();
        assertThat(correlation.correlationId().value()).isNotBlank();
    }

    private static McpRequestTerminalEvent onlyTerminal(Fixture fixture) {
        assertThat(fixture.observer.terminals)
                .as("exactly one terminal event must be published per request")
                .hasSize(1);
        return fixture.observer.terminals.get(0).event();
    }

    // --- Fixture construction ---

    private Fixture build(AuthorizationDecision decision, FakeInvoker invoker) {
        Vertx vertx = Vertx.vertx();
        openedVertx.add(vertx);
        Context vertxContext = vertx.getOrCreateContext();

        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        when(policyEnforcer.decide(any(), any())).thenReturn(Future.succeededFuture(decision));

        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);

        RecordingObserver observer = new RecordingObserver();
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(observer),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of(invoker)),
                policyEnforcer);

        RoutingContext context = mockRoutingContext(vertxContext, toolsCallBody());
        dispatcher.begin(context);

        return new Fixture(dispatcher, context, observer);
    }

    /** One built scenario: the dispatcher, its driven request context, and the observer that recorded it. */
    private record Fixture(McpRequestDispatcher dispatcher, RoutingContext context, RecordingObserver observer) {}

    // --- Real recording observer/session ---

    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final List<McpRequestTerminalObservation> terminals = new ArrayList<>();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.add(observation);
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            // not needed for this proof
        }
    }

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

    /** A minimal real {@link McpToolInvoker} test double for {@value #KNOWN_TOOL}. */
    private static final class FakeInvoker implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                KNOWN_TOOL,
                null,
                "A fixture tool for R05 TP-002.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        private final McpToolResult<?> result;
        private final boolean throwOnPrepare;

        private FakeInvoker(McpToolResult<?> result, boolean throwOnPrepare) {
            this.result = result;
            this.throwOnPrepare = throwOnPrepare;
        }

        static FakeInvoker succeeding(McpToolResult<?> result) {
            return new FakeInvoker(result, false);
        }

        static FakeInvoker throwingOnPrepare() {
            return new FakeInvoker(null, true);
        }

        @Override
        public McpToolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            if (throwOnPrepare) {
                throw new IllegalStateException("fixture handler failure");
            }
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
}

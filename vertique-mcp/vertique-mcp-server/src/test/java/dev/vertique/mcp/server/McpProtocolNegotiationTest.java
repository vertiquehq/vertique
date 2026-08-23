// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * R05 TP-001 — {@code shouldValidateHeadersAndMethodSchemasBeforeAnyDispatch} (issue #429).
 *
 * <p>Every row drives {@link McpRequestDispatcher#dispatch} directly against a mocked {@link
 * RoutingContext} — mirroring {@code McpRequestInterceptorPipelineTest}'s driving style — carrying a
 * negotiation violation: a missing required header, a mismatched required header, a schema-invalid
 * {@code params} for each supported method, or a {@code tools/call} reserved-field violation. Each
 * must yield HTTP 400 / {@code -32020} <strong>and</strong> leave a permitting request interceptor and
 * the policy enforcer completely uninvoked — the decisive proof that negotiation runs strictly before
 * the request-interceptor stage, tool lookup, and authorization, not merely that the response happens
 * to be 400. A row that only checked the response would pass identically whether negotiation ran first
 * or last, since every one of these bodies would also fail *some* later stage; only the invocation
 * counts distinguish "rejected before" from "rejected coincidentally."
 *
 * <p>One control row ({@link #BASELINE_ROW}) sends a fully valid negotiation and asserts the
 * interceptor <em>does</em> run — proving the fixture's baseline body/header shape is not itself
 * accidentally triggering {@code -32020}, so a negative row's rejection is attributable to the one
 * mutated fact it names.
 */
class McpProtocolNegotiationTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String KNOWN_TOOL = "greet";

    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    private static final String BASELINE_ROW = "shouldPermitAFullyNegotiatedRequestAsTheControl";
    private static final String MISSING_HEADER_ROW = "shouldRejectAMissingRequiredHeaderBeforeDispatch";
    private static final String MISMATCHED_HEADER_ROW = "shouldRejectAMismatchedRequiredHeaderBeforeDispatch";
    private static final String SCHEMA_INVALID_DISCOVER_ROW =
            "shouldRejectSchemaInvalidMetaForServerDiscoverBeforeDispatch";
    private static final String SCHEMA_INVALID_TOOLS_LIST_ROW =
            "shouldRejectSchemaInvalidMetaForToolsListBeforeDispatch";
    private static final String SCHEMA_INVALID_TOOLS_CALL_ROW =
            "shouldRejectSchemaInvalidMetaForToolsCallBeforeDispatch";
    private static final String RESERVED_FIELD_ROW = "shouldRejectAToolsCallReservedFieldBeforeDispatch";

    private static Stream<String> r05Tp001Rows() {
        return Stream.of(
                BASELINE_ROW,
                MISSING_HEADER_ROW,
                MISMATCHED_HEADER_ROW,
                SCHEMA_INVALID_DISCOVER_ROW,
                SCHEMA_INVALID_TOOLS_LIST_ROW,
                SCHEMA_INVALID_TOOLS_CALL_ROW,
                RESERVED_FIELD_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("r05Tp001Rows")
    @DisplayName("R05 TP-001: protocol negotiation runs before interceptors, lookup, or authorization")
    void shouldValidateHeadersAndMethodSchemasBeforeAnyDispatch(String row) {
        switch (row) {
            case BASELINE_ROW -> shouldPermitAFullyNegotiatedRequestAsTheControl();
            case MISSING_HEADER_ROW -> shouldRejectAMissingRequiredHeaderBeforeDispatch();
            case MISMATCHED_HEADER_ROW -> shouldRejectAMismatchedRequiredHeaderBeforeDispatch();
            case SCHEMA_INVALID_DISCOVER_ROW -> shouldRejectSchemaInvalidMetaForServerDiscoverBeforeDispatch();
            case SCHEMA_INVALID_TOOLS_LIST_ROW -> shouldRejectSchemaInvalidMetaForToolsListBeforeDispatch();
            case SCHEMA_INVALID_TOOLS_CALL_ROW -> shouldRejectSchemaInvalidMetaForToolsCallBeforeDispatch();
            case RESERVED_FIELD_ROW -> shouldRejectAToolsCallReservedFieldBeforeDispatch();
            default -> throw new IllegalArgumentException("unknown R05 TP-001 row: " + row);
        }
    }

    // --- Control: a fully negotiated request reaches the interceptor stage ---

    private void shouldPermitAFullyNegotiatedRequestAsTheControl() {
        Outcome outcome = drive(discoverBody(), validHeaders("server/discover", null));

        assertThat(outcome.status())
                .as("a fully negotiated discover request must not be rejected at negotiation")
                .isEqualTo(200);
        assertThat(outcome.interceptorInvocations())
                .as("CONTROL: the baseline body/header shape must reach the interceptor stage, so a "
                        + "negative row's rejection is attributable to its one named mutation")
                .isEqualTo(1);
        verifyNoInteractions(outcome.policyEnforcer());
    }

    // --- Missing header ---

    private void shouldRejectAMissingRequiredHeaderBeforeDispatch() {
        MultiMap headers = validHeaders("server/discover", null);
        headers.remove(HEADER_PROTOCOL_VERSION);

        Outcome outcome = drive(discoverBody(), headers);

        assertRejectedBeforeDispatch(outcome, "a missing MCP-Protocol-Version header");
    }

    // --- Mismatched header ---

    private void shouldRejectAMismatchedRequiredHeaderBeforeDispatch() {
        MultiMap headers = validHeaders("server/discover", null);
        headers.set(HEADER_PROTOCOL_VERSION, "1999-01-01");

        Outcome outcome = drive(discoverBody(), headers);

        assertRejectedBeforeDispatch(outcome, "a header value that disagrees with the negotiated body value");
    }

    // --- Schema-invalid params, one row per supported method ---

    private void shouldRejectSchemaInvalidMetaForServerDiscoverBeforeDispatch() {
        JsonObject body = discoverBody();
        body.getJsonObject("params").getJsonObject("_meta").remove("io.modelcontextprotocol/protocolVersion");

        Outcome outcome = drive(body, validHeaders("server/discover", null));

        assertRejectedBeforeDispatch(outcome, "server/discover missing the schema-required protocolVersion");
    }

    private void shouldRejectSchemaInvalidMetaForToolsListBeforeDispatch() {
        JsonObject body = toolsListBody();
        body.getJsonObject("params").getJsonObject("_meta").remove("io.modelcontextprotocol/clientCapabilities");

        Outcome outcome = drive(body, validHeaders("tools/list", null));

        assertRejectedBeforeDispatch(outcome, "tools/list missing the schema-required clientCapabilities");
    }

    private void shouldRejectSchemaInvalidMetaForToolsCallBeforeDispatch() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").getJsonObject("_meta").put("io.modelcontextprotocol/protocolVersion", "   ");

        Outcome outcome = drive(body, validHeaders("tools/call", KNOWN_TOOL));

        assertRejectedBeforeDispatch(outcome, "tools/call with a blank protocolVersion");
    }

    // --- tools/call reserved MRTR field ---

    private void shouldRejectAToolsCallReservedFieldBeforeDispatch() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").put("inputResponses", new JsonArray());

        Outcome outcome = drive(body, validHeaders("tools/call", KNOWN_TOOL));

        assertRejectedBeforeDispatch(outcome, "a tools/call params carrying the reserved inputResponses field");
    }

    // --- Shared assertion ---

    private static void assertRejectedBeforeDispatch(Outcome outcome, String caseLabel) {
        assertThat(outcome.status())
                .as(caseLabel + " must be rejected HTTP 400")
                .isEqualTo(400);
        assertThat(outcome.errorCode()).as(caseLabel + " must carry -32020").isEqualTo(-32020);
        assertThat(outcome.interceptorInvocations())
                .as("DECISIVE (" + caseLabel + "): the request-interceptor stage must never run — a proof that "
                        + "only checked the response status would pass identically whether negotiation ran "
                        + "before or after this stage")
                .isZero();
        verifyNoInteractions(outcome.policyEnforcer());
    }

    // --- Fixture: dispatcher construction and driving ---

    private Outcome drive(JsonObject body, MultiMap headers) {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(interceptor),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                policyEnforcer);

        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody requestBody = mock(RequestBody.class);

        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer()).thenReturn(Buffer.buffer(body.toBuffer().getBytes()));
        when(request.headers()).thenReturn(headers);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

        dispatcher.dispatch(context);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatusCode(statusCaptor.capture());
        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        int status = statusCaptor.getValue();
        JsonObject decoded = new JsonObject(bodyCaptor.getValue());
        JsonObject error = decoded.getJsonObject("error");
        Integer errorCode = error == null ? null : error.getInteger("code");
        return new Outcome(status, errorCode, interceptor.invocations(), policyEnforcer);
    }

    /** One drive's observed outcome. */
    private record Outcome(
            int status, Integer errorCode, int interceptorInvocations, McpPolicyEnforcer policyEnforcer) {}

    // --- Body/header builders ---

    private static JsonObject metaObject() {
        return new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
    }

    private static JsonObject discoverBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", metaObject()));
    }

    private static JsonObject toolsListBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", new JsonObject().put("_meta", metaObject()));
    }

    private static JsonObject toolsCallBody(String toolName) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put(
                        "params",
                        new JsonObject()
                                .put("_meta", metaObject())
                                .put("name", toolName)
                                .put("arguments", new JsonObject()));
    }

    /**
     * Builds the three required headers, self-consistent with a baseline body built by {@link
     * #discoverBody()}/{@link #toolsListBody()}/{@link #toolsCallBody(String)}: {@code MCP-Protocol-Version}
     * mirrors {@link #PROTOCOL_VERSION}, {@code Mcp-Method} mirrors {@code method}, and {@code Mcp-Name}
     * mirrors {@code toolName} for {@code tools/call} or the method string otherwise (this test's own
     * bounded design choice for the two methods with no schema-level "name" — see {@code
     * McpProtocolCodec#validateNegotiation}'s javadoc).
     */
    private static MultiMap validHeaders(String method, String toolName) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
        headers.set(HEADER_METHOD, method);
        headers.set(HEADER_NAME, toolName != null ? toolName : method);
        return headers;
    }

    /** Records {@link McpRequestInterceptor#beforeRequest} invocation count; always permits. */
    private static final class RecordingInterceptor implements McpRequestInterceptor {
        private int invocations;

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            invocations++;
            return Future.succeededFuture();
        }

        int invocations() {
            return invocations;
        }
    }
}

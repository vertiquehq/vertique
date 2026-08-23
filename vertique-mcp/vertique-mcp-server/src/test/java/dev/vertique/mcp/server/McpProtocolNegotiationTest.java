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
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * R05 TP-001 — {@code shouldValidateHeadersAndMethodSchemasBeforeAnyDispatch} (issues #429/#438).
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
 *
 * <p><strong>R08 (issue #438) rows.</strong> The {@code SCHEMA_INVALID_..._ROW} rows above mutate only
 * {@code _meta} — exactly why R08 found this suite could pass while {@link McpProtocolCodec} validated
 * nothing else against the pinned official schema. The {@code NON_META_...} rows below each mutate a
 * schema-typed field <em>outside</em> {@code _meta} — {@code cursor} ({@code tools/list}), {@code name}
 * ({@code tools/call}, two ways: absent and non-textual) — proving the new {@link
 * McpProtocolSchemaValidator} gate itself, not the pre-existing {@code _meta} checks, is what rejects
 * them. There is no equivalent non-{@code _meta} row for {@code server/discover}: the pinned schema's
 * {@code RequestParams} — {@code server/discover}'s own {@code params} shape — declares exactly one
 * property, {@code _meta}; every other request-envelope fact ({@code id}, {@code jsonrpc}, {@code
 * method}) is already validated earlier, by {@link McpProtocolCodec#decodeEnvelope}. Fabricating an
 * unsupported extra property on {@code server/discover} would not exercise the schema gate either — the
 * pinned schema permits unknown properties there (contract §4.7 — "Unknown fields permitted by the
 * official schema... are accepted"), so such a request would still be schema-valid.
 *
 * <p><strong>{@code arguments} is deliberately not one of these rows.</strong> {@link
 * #shouldNotRejectNonObjectArgumentsAtNegotiation()} below proves, directly against {@link
 * McpProtocolCodec}, that a present non-object {@code arguments} value still passes negotiation: it is
 * already rejected downstream, as a bounded SSE tool-error, by the pre-existing, decisively tested
 * {@code McpToolCallMalformedArgumentsIT} contract (review-finding round-14 remediation) — reached only
 * after registry lookup and the policy enforcer have already run. Folding it into this gate too would
 * reject the identical condition earlier with a different wire response, a consumer-visible behavior
 * change outside this repair-only slice's authority (see {@link McpProtocolSchemaValidator}'s own
 * javadoc for the full reasoning).
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

    /**
     * R08 (issue #438): a {@code cursor} value the pinned schema types as a string, mutated to a
     * number — a schema violation entirely outside {@code _meta}.
     */
    private static final String NON_META_INVALID_CURSOR_ROW = "shouldRejectANonStringCursorBeforeDispatch";

    /**
     * R08 (issue #438): a {@code tools/call} request whose {@code name} — schema-required and typed as
     * a string — is absent. The pinned schema's {@code name} type carries no {@code minLength}, so a
     * present-but-blank {@code name} stays schema-valid and is deliberately left to the downstream
     * {@code -32602} unknown-or-unauthorized path (not this row's concern); an <em>absent</em> {@code
     * name} is a genuine schema violation this row proves is now caught before the interceptor stage.
     */
    private static final String NON_META_MISSING_NAME_ROW = "shouldRejectAMissingToolNameBeforeDispatch";

    /**
     * R08 (issue #438): a {@code tools/call} request whose {@code name} is present but non-textual (a
     * number) — the pinned schema's {@code name} type is {@code string}, so this is also a genuine
     * schema violation, distinct from the missing-name row above.
     */
    private static final String NON_META_NON_TEXTUAL_NAME_ROW = "shouldRejectANonTextualToolNameBeforeDispatch";

    /**
     * R07 item 1 (security review): HTAB (0x09) survives Netty's own non-first-byte header validation
     * ({@code c < 32 && c != 9}), so a header/body-mirrored {@code protocolVersion} carrying one is
     * neither textually blank nor over-length — the two checks {@code validateNegotiation} previously
     * enforced — yet still satisfies {@link Character#isISOControl}, the exact bound {@code
     * McpRequestTerminalEvent}'s own compact constructor throws on. This row proves the mirrored bound
     * added to {@code validateNegotiation} rejects it before any terminal-event construction site can
     * ever see it.
     */
    private static final String CONTROL_CHARACTER_ROW = "shouldRejectAControlCharacterInProtocolVersionBeforeDispatch";

    /**
     * R07 item 2 (security review): a well-formed, non-blank, ≤64-char, control-character-free version
     * the header and body still agree on — so neither pre-existing check catches it — but that is not
     * {@code McpCursorCodec#PROTOCOL_VERSION}, the single version this server actually supports.
     */
    private static final String UNSUPPORTED_VERSION_ROW = "shouldRejectAnUnsupportedProtocolVersionBeforeDispatch";

    private static Stream<String> r05Tp001Rows() {
        return Stream.of(
                BASELINE_ROW,
                MISSING_HEADER_ROW,
                MISMATCHED_HEADER_ROW,
                SCHEMA_INVALID_DISCOVER_ROW,
                SCHEMA_INVALID_TOOLS_LIST_ROW,
                SCHEMA_INVALID_TOOLS_CALL_ROW,
                RESERVED_FIELD_ROW,
                CONTROL_CHARACTER_ROW,
                UNSUPPORTED_VERSION_ROW,
                NON_META_INVALID_CURSOR_ROW,
                NON_META_MISSING_NAME_ROW,
                NON_META_NON_TEXTUAL_NAME_ROW);
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
            case CONTROL_CHARACTER_ROW -> shouldRejectAControlCharacterInProtocolVersionBeforeDispatch();
            case UNSUPPORTED_VERSION_ROW -> shouldRejectAnUnsupportedProtocolVersionBeforeDispatch();
            case NON_META_INVALID_CURSOR_ROW -> shouldRejectANonStringCursorBeforeDispatch();
            case NON_META_MISSING_NAME_ROW -> shouldRejectAMissingToolNameBeforeDispatch();
            case NON_META_NON_TEXTUAL_NAME_ROW -> shouldRejectANonTextualToolNameBeforeDispatch();
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

    // --- R08 (issue #438): schema violations outside _meta, one row per field the pinned schema types ---

    private void shouldRejectANonStringCursorBeforeDispatch() {
        JsonObject body = toolsListBody();
        body.getJsonObject("params").put("cursor", 12345);

        Outcome outcome = drive(body, validHeaders("tools/list", null));

        assertRejectedBeforeDispatch(outcome, "tools/list with a non-string cursor");
    }

    private void shouldRejectAMissingToolNameBeforeDispatch() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").remove("name");

        Outcome outcome = drive(body, validHeaders("tools/call", KNOWN_TOOL));

        assertRejectedBeforeDispatch(outcome, "tools/call with an absent (schema-required) name");
    }

    private void shouldRejectANonTextualToolNameBeforeDispatch() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").put("name", 42);

        Outcome outcome = drive(body, validHeaders("tools/call", KNOWN_TOOL));

        assertRejectedBeforeDispatch(outcome, "tools/call with a non-textual name");
    }

    /**
     * R08 (issue #438), decisive boundary proof: a present non-object {@code arguments} value must
     * still pass negotiation — it is deliberately excluded from {@link McpProtocolSchemaValidator}'s
     * compiled {@code tools/call} schema (see that class's javadoc) because {@code
     * McpToolCallMalformedArgumentsIT} already, decisively, pins a different wire shape for the exact
     * same condition (a bounded SSE tool-error reached only after registry lookup and the policy
     * enforcer already ran). Driven directly against {@link McpProtocolCodec} rather than through
     * {@link #drive}: {@link #drive}'s shared fixture uses an empty tool registry and an unstubbed
     * mocked {@link McpPolicyEnforcer}, so it cannot safely exercise the real downstream tool-call
     * pipeline this boundary is about — this test only needs to prove negotiation itself does not
     * reject the request, which {@link McpProtocolCodec#validateNegotiation} alone already answers.
     * If a future change folded {@code arguments} back into the negotiation schema gate, this test
     * would go red instead of {@code McpToolCallMalformedArgumentsIT} silently changing its own wire
     * shape.
     */
    @Test
    @DisplayName("R08: arguments' type-validity stays out of the negotiation-stage schema gate")
    void shouldNotRejectNonObjectArgumentsAtNegotiation() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").put("arguments", "not-an-object");
        MultiMap headers = validHeaders("tools/call", KNOWN_TOOL);

        McpProtocolCodec codec = new McpProtocolCodec(HttpConfig.builder().build());
        McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(body.toBuffer().getBytes());
        assertThat(decoded.isError())
                .as("the envelope itself must decode cleanly")
                .isFalse();

        McpProtocolCodec.NegotiationResult negotiation = codec.validateNegotiation(decoded.envelope(), headers);

        assertThat(negotiation.isError())
                .as("DECISIVE: non-object arguments must not fail negotiation — that condition is "
                        + "already, deliberately, rejected downstream by McpToolCallMalformedArgumentsIT")
                .isFalse();
    }

    // --- R07 item 1: a control character in protocolVersion ---

    private void shouldRejectAControlCharacterInProtocolVersionBeforeDispatch() {
        // HTAB (0x09) specifically: Netty's own non-first-byte header-value validation
        // (`c < 32 && c != 9`) admits it, so this is not merely a synthetic test value — it is the one
        // control character that can genuinely reach this codec over the wire. Mirrored identically
        // into the header and the body's _meta field so the header/body-mismatch check cannot be what
        // catches this row.
        String poisoned = PROTOCOL_VERSION + "\t";
        JsonObject body = discoverBody();
        body.getJsonObject("params").getJsonObject("_meta").put("io.modelcontextprotocol/protocolVersion", poisoned);
        MultiMap headers = validHeaders("server/discover", null);
        headers.set(HEADER_PROTOCOL_VERSION, poisoned);

        Outcome outcome = drive(body, headers);

        assertRejectedBeforeDispatch(outcome, "a protocolVersion carrying an embedded HTAB control character");
    }

    // --- R07 item 2: a well-formed but unsupported protocolVersion ---

    private void shouldRejectAnUnsupportedProtocolVersionBeforeDispatch() {
        // Well-formed, non-blank, under the length cap, no control characters — the header and body
        // still fully agree — so only the new supported-version-set check can reject this row.
        String unsupported = "1999-01-01";
        JsonObject body = discoverBody();
        body.getJsonObject("params").getJsonObject("_meta").put("io.modelcontextprotocol/protocolVersion", unsupported);
        MultiMap headers = validHeaders("server/discover", null);
        headers.set(HEADER_PROTOCOL_VERSION, unsupported);

        Outcome outcome = drive(body, headers);

        assertRejectedBeforeDispatch(
                outcome, "a well-formed protocolVersion the header and body agree on but the server does not support");
    }

    // --- R07 item 1 (decisive): the control-character row settles bounded, not with a crash, and ---
    // --- still emits a terminal event ---

    /**
     * R07 item 1's fully decisive proof: {@link #shouldRejectAControlCharacterInProtocolVersionBeforeDispatch()}
     * above (part of the shared negotiation matrix) proves the response is rejected {@code -32020} and
     * that the interceptor stage never runs, but the shared {@link #drive} fixture never calls {@link
     * McpRequestDispatcher#begin}, so it cannot observe whether a terminal event is actually published
     * through the lifecycle-observation pipeline — the exact question this defect turns on: before the
     * fix, the same poisoned value reached {@code McpRequestTerminalEvent}'s compact constructor twice
     * (once on the direct construction path, once from inside the {@code catch (RuntimeException |
     * StackOverflowError)} recovery that reconstructs the identical record with the identical poisoned
     * value), so the recovery itself threw and no terminal was ever published — a request that settles
     * with nothing: no bounded response actually reaching the wire in that failure mode, and a total
     * audit blackout for that request class. This test drives the full {@code begin()} → {@code
     * dispatch()} pipeline against a real {@link McpCompletionCoordinator} and a real recording {@link
     * McpRequestLifecycleObserver} session (mirroring {@code McpLifecycleFactsTest}'s harness), and
     * asserts both that {@code dispatch()} returns normally (no crash escapes past it) and that exactly
     * one terminal event was published.
     */
    @Test
    @DisplayName("R07 item 1 (decisive): a control character settles bounded and still emits a terminal event")
    void shouldSettleBoundedAndStillEmitATerminalForAControlCharacter() {
        String poisoned = PROTOCOL_VERSION + "\t";
        JsonObject body = discoverBody();
        body.getJsonObject("params").getJsonObject("_meta").put("io.modelcontextprotocol/protocolVersion", poisoned);
        MultiMap headers = validHeaders("server/discover", null);
        headers.set(HEADER_PROTOCOL_VERSION, poisoned);

        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);
        RecordingTerminalObserver observer = new RecordingTerminalObserver();
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(observer),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                policyEnforcer);

        RoutingContext context = mockStatefulRoutingContext(body, headers);

        // DECISIVE: dispatch() must return normally — a crash here (e.g. an IllegalArgumentException
        // escaping from a poisoned McpRequestTerminalEvent construction, and then again from its own
        // recovery path) is exactly the pre-fix failure mode this proof exists to catch.
        dispatcher.begin(context);
        dispatcher.dispatch(context);

        int status = statusOf(context);
        assertThat(status)
                .as("a control-character protocolVersion must settle bounded HTTP 400")
                .isEqualTo(400);
        assertThat(observer.terminals)
                .as("DECISIVE: exactly one terminal event must still be published for this request — the "
                        + "pre-fix double-construction failure mode published none at all")
                .hasSize(1);
        assertThat(observer.terminals.get(0).event().protocolErrorCode())
                .as("the published terminal must carry the -32020 negotiation-mismatch code")
                .isEqualTo(-32020);
    }

    private static int statusOf(RoutingContext context) {
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(context.response()).setStatusCode(statusCaptor.capture());
        return statusCaptor.getValue();
    }

    /** A {@link RoutingContext} mock whose {@code put}/{@code get} are backed by a real attribute map. */
    private static RoutingContext mockStatefulRoutingContext(JsonObject body, MultiMap headers) {
        RoutingContext context = mock(RoutingContext.class);
        io.vertx.core.Vertx contextVertx = mock(io.vertx.core.Vertx.class);
        io.vertx.core.Context vertxContext = mock(io.vertx.core.Context.class);
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
        when(request.headers()).thenReturn(headers);
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

    /** Records every published terminal observation. */
    private static final class RecordingTerminalObserver implements McpRequestLifecycleObserver, McpRequestObservation {
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

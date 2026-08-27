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

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * R05 TP-001 — {@code shouldValidateHeadersAndMethodSchemasBeforeAnyDispatch} (issues #429/#438).
 *
 * <p>Every row drives {@link McpRequestDispatcher#dispatch} directly against a mocked {@link
 * RoutingContext} — mirroring {@code McpRequestInterceptorPipelineTest}'s driving style — carrying a
 * negotiation violation: a missing required header, a mismatched required header, or a {@code tools/call}
 * reserved-field violation. Each
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
 * <p>R15 separately proves the official per-method schema boundary. Those failures are JSON-RPC
 * {@code -32602}, not protocol-version negotiation failures, so they deliberately do not share this
 * matrix's {@code -32020} assertion.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpProtocolNegotiationTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String KNOWN_TOOL = "greet";

    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    private static final String BASELINE_ROW = "shouldPermitAFullyNegotiatedRequestAsTheControl";
    private static final String MISSING_HEADER_ROW = "shouldRejectAMissingRequiredHeaderBeforeDispatch";
    private static final String MISMATCHED_HEADER_ROW = "shouldRejectAMismatchedRequiredHeaderBeforeDispatch";
    private static final String BLANK_CALL_PROTOCOL_VERSION_ROW =
            "shouldRejectABlankToolsCallProtocolVersionBeforeDispatch";
    private static final String RESERVED_FIELD_ROW = "shouldRejectAToolsCallReservedFieldBeforeDispatch";

    private static final String MISSING_DISCOVER_META_MEMBER_ROW = "server/discover missing _meta protocolVersion";
    private static final String NON_STRING_CURSOR_ROW = "tools/list with a non-string cursor";
    private static final String NON_OBJECT_ARGUMENTS_ROW = "tools/call with non-object arguments";
    private static final String NULL_ARGUMENTS_ROW = "tools/call with explicit null arguments";

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

    /**
     * Repair task R33 defect 1: {@code headerMatches} (~{@code McpProtocolCodec} line 291) reads only
     * {@link MultiMap#get(String)}, which silently returns the <em>first</em> value of a duplicated
     * header and never notices the duplication itself. This row duplicates {@code Mcp-Method} with an
     * identical repeated value — a value that agrees with the negotiated body on both occurrences — so
     * the only fact that can make this row fail is the duplication itself, never a value mismatch. RED
     * today: the first value matches, so negotiation wrongly succeeds and this fully valid-looking
     * {@code server/discover} request reaches the interceptor stage exactly like {@link #BASELINE_ROW}.
     */
    private static final String DUPLICATE_METHOD_HEADER_ROW = "shouldRejectADuplicatedMcpMethodHeaderBeforeDispatch";

    /** Repair task R33 defect 1, the {@code MCP-Protocol-Version} sibling of {@link #DUPLICATE_METHOD_HEADER_ROW}. */
    private static final String DUPLICATE_PROTOCOL_VERSION_HEADER_ROW =
            "shouldRejectADuplicatedProtocolVersionHeaderBeforeDispatch";

    /**
     * Repair task R33 defect 1, the {@code tools/call} {@code Mcp-Name} sibling of {@link
     * #DUPLICATE_METHOD_HEADER_ROW}. {@code Mcp-Name} is the one required header this codec only checks
     * for {@code tools/call}, so this row is the only one of the three that can observe the defect on
     * that header at all.
     */
    private static final String DUPLICATE_NAME_HEADER_ROW = "shouldRejectADuplicatedToolsCallNameHeaderBeforeDispatch";

    private static Stream<String> r05Tp001Rows() {
        return Stream.of(
                BASELINE_ROW,
                MISSING_HEADER_ROW,
                MISMATCHED_HEADER_ROW,
                BLANK_CALL_PROTOCOL_VERSION_ROW,
                RESERVED_FIELD_ROW,
                CONTROL_CHARACTER_ROW,
                UNSUPPORTED_VERSION_ROW,
                DUPLICATE_METHOD_HEADER_ROW,
                DUPLICATE_PROTOCOL_VERSION_HEADER_ROW,
                DUPLICATE_NAME_HEADER_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("r05Tp001Rows")
    @DisplayName("R05 TP-001: protocol negotiation runs before interceptors, lookup, or authorization")
    void shouldValidateHeadersAndMethodSchemasBeforeAnyDispatch(String row) {
        switch (row) {
            case BASELINE_ROW -> shouldPermitAFullyNegotiatedRequestAsTheControl();
            case MISSING_HEADER_ROW -> shouldRejectAMissingRequiredHeaderBeforeDispatch();
            case MISMATCHED_HEADER_ROW -> shouldRejectAMismatchedRequiredHeaderBeforeDispatch();
            case BLANK_CALL_PROTOCOL_VERSION_ROW -> shouldRejectABlankToolsCallProtocolVersionBeforeDispatch();
            case RESERVED_FIELD_ROW -> shouldRejectAToolsCallReservedFieldBeforeDispatch();
            case CONTROL_CHARACTER_ROW -> shouldRejectAControlCharacterInProtocolVersionBeforeDispatch();
            case UNSUPPORTED_VERSION_ROW -> shouldRejectAnUnsupportedProtocolVersionBeforeDispatch();
            case DUPLICATE_METHOD_HEADER_ROW -> shouldRejectADuplicatedMcpMethodHeaderBeforeDispatch();
            case DUPLICATE_PROTOCOL_VERSION_HEADER_ROW -> shouldRejectADuplicatedProtocolVersionHeaderBeforeDispatch();
            case DUPLICATE_NAME_HEADER_ROW -> shouldRejectADuplicatedToolsCallNameHeaderBeforeDispatch();
            default -> throw new IllegalArgumentException("unknown R05 TP-001 row: " + row);
        }
    }

    private static Stream<StandardHeaderCase> applicableStandardHeaderCases() {
        JsonObject schemaInvalidName = toolsCallBody(KNOWN_TOOL);
        schemaInvalidName.getJsonObject("params").put("name", 42);

        return Stream.of(
                StandardHeaderCase.negotiated(
                        "server/discover without Mcp-Name", discoverBody(), standardHeaders("server/discover")),
                StandardHeaderCase.negotiated(
                        "tools/list without Mcp-Name", toolsListBody(), standardHeaders("tools/list")),
                StandardHeaderCase.negotiated(
                        "server/discover with unsolicited Mcp-Name",
                        discoverBody(),
                        validHeaders("server/discover", "unsolicited")),
                StandardHeaderCase.negotiated(
                        "tools/list with unsolicited Mcp-Name",
                        toolsListBody(),
                        validHeaders("tools/list", "unsolicited")),
                StandardHeaderCase.negotiated(
                        "tools/call with matching Mcp-Name",
                        toolsCallBody(KNOWN_TOOL),
                        validHeaders("tools/call", KNOWN_TOOL)),
                StandardHeaderCase.negotiationFailure(
                        "tools/call without Mcp-Name", toolsCallBody(KNOWN_TOOL), standardHeaders("tools/call")),
                StandardHeaderCase.negotiationFailure(
                        "tools/call with mismatched Mcp-Name",
                        toolsCallBody(KNOWN_TOOL),
                        validHeaders("tools/call", "different-tool")),
                StandardHeaderCase.officialParamsFailure(
                        "tools/call with a non-textual name remains an official-params failure",
                        schemaInvalidName,
                        validHeaders("tools/call", KNOWN_TOOL)),
                StandardHeaderCase.negotiated(
                        "tools/call with matching blank name", toolsCallBody(""), validHeaders("tools/call", "")),
                StandardHeaderCase.negotiationFailure(
                        "tools/call with blank body name and non-blank Mcp-Name",
                        toolsCallBody(""),
                        validHeaders("tools/call", "different-tool")));
    }

    /** D010: each standard routing header is required only when its official body mirror applies. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("applicableStandardHeaderCases")
    @DisplayName("R21: standard request headers are required only when applicable")
    void shouldRequireOnlyApplicableStandardHeaders(StandardHeaderCase standardHeaderCase) {
        McpProtocolCodec codec = new McpProtocolCodec(
                HttpConfig.builder().build(), McpServerConfig.defaults().ingressMaxTokens());
        McpProtocolCodec.Decoded decoded =
                codec.decodeEnvelope(standardHeaderCase.body().toBuffer().getBytes());
        assertThat(decoded.isError())
                .as(standardHeaderCase.description() + " must first decode as a valid envelope")
                .isFalse();

        McpProtocolCodec.ParamsValidationResult params = codec.validateOfficialParams(decoded.envelope());
        Integer paramsError = params.error() == null ? null : params.error().code();
        assertThat(paramsError)
                .as(standardHeaderCase.description() + " official-params result")
                .isEqualTo(standardHeaderCase.expectedOfficialParamsError());
        if (params.isError()) {
            return;
        }

        McpProtocolCodec.NegotiationResult negotiation =
                codec.validateNegotiation(decoded.envelope(), standardHeaderCase.headers());
        Integer negotiationError =
                negotiation.error() == null ? null : negotiation.error().code();
        assertThat(negotiationError)
                .as(standardHeaderCase.description() + " negotiation result")
                .isEqualTo(standardHeaderCase.expectedNegotiationError());
        if (standardHeaderCase.expectedNegotiationError() != null) {
            Outcome outcome = drive(standardHeaderCase.body(), standardHeaderCase.headers());
            assertThat(outcome.errorCode()).isEqualTo(-32020);
            assertThat(outcome.interceptorInvocations())
                    .as(standardHeaderCase.description() + " must fail before application interceptors")
                    .isZero();
            verifyNoInteractions(outcome.toolRegistry(), outcome.policyEnforcer());
        }
        if (!negotiation.isError()) {
            assertThat(negotiation.protocolVersion()).isEqualTo(PROTOCOL_VERSION);
        }
    }

    private static Stream<OfficialParamsViolation> officialParamsViolations() {
        JsonObject missingDiscoverMetaMember = discoverBody();
        missingDiscoverMetaMember
                .getJsonObject("params")
                .getJsonObject("_meta")
                .remove("io.modelcontextprotocol/protocolVersion");

        JsonObject nonStringCursor = toolsListBody();
        nonStringCursor.getJsonObject("params").put("cursor", 12345);

        JsonObject nonObjectArguments = toolsCallBody(KNOWN_TOOL);
        nonObjectArguments.getJsonObject("params").put("arguments", "not-an-object");

        JsonObject nullArguments = toolsCallBody(KNOWN_TOOL);
        nullArguments.getJsonObject("params").put("arguments", (Object) null);

        JsonObject missingListCapabilities = toolsListBody();
        missingListCapabilities
                .getJsonObject("params")
                .getJsonObject("_meta")
                .remove("io.modelcontextprotocol/clientCapabilities");

        JsonObject missingToolName = toolsCallBody(KNOWN_TOOL);
        missingToolName.getJsonObject("params").remove("name");

        JsonObject nonTextualToolName = toolsCallBody(KNOWN_TOOL);
        nonTextualToolName.getJsonObject("params").put("name", 42);

        return Stream.of(
                new OfficialParamsViolation(
                        MISSING_DISCOVER_META_MEMBER_ROW,
                        missingDiscoverMetaMember,
                        standardHeaders("server/discover")),
                new OfficialParamsViolation(NON_STRING_CURSOR_ROW, nonStringCursor, standardHeaders("tools/list")),
                new OfficialParamsViolation(
                        NON_OBJECT_ARGUMENTS_ROW, nonObjectArguments, validHeaders("tools/call", KNOWN_TOOL)),
                new OfficialParamsViolation(NULL_ARGUMENTS_ROW, nullArguments, validHeaders("tools/call", KNOWN_TOOL)),
                new OfficialParamsViolation(
                        "tools/list missing _meta clientCapabilities",
                        missingListCapabilities,
                        standardHeaders("tools/list")),
                new OfficialParamsViolation(
                        "tools/call missing the schema-required name",
                        missingToolName,
                        validHeaders("tools/call", KNOWN_TOOL)),
                new OfficialParamsViolation(
                        "tools/call with a non-textual name",
                        nonTextualToolName,
                        validHeaders("tools/call", KNOWN_TOOL)));
    }

    /**
     * R15 TP-001: the complete pinned official params shape is a protocol boundary, before all
     * application-policy work. Each row changes exactly one official-schema fact and proves the
     * resulting {@code -32602} response is not a later authorization or invocation failure that happens
     * to share HTTP 400.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("officialParamsViolations")
    @DisplayName("R15: official params violations are rejected before application policy")
    void shouldRejectOfficialParamsViolationsAsInvalidParamsBeforeApplicationPolicy(OfficialParamsViolation violation) {
        Outcome outcome = drive(violation.body(), violation.headers());

        assertThat(outcome.status())
                .as(violation.description() + " must be rejected HTTP 400")
                .isEqualTo(400);
        assertThat(outcome.errorCode())
                .as(violation.description() + " must carry JSON-RPC Invalid params")
                .isEqualTo(-32602);
        assertThat(outcome.interceptorInvocations())
                .as("DECISIVE (" + violation.description() + "): interceptors must not see invalid official params")
                .isZero();
        verifyNoInteractions(outcome.toolRegistry(), outcome.policyEnforcer());
    }

    /**
     * The pinned schema deliberately leaves unknown extension properties open. This control prevents the
     * negative rows from being implemented by adding an invented {@code additionalProperties: false}
     * restriction at the protocol boundary.
     */
    @Test
    @DisplayName("R15: an unknown official-schema extension remains accepted")
    void shouldAcceptUnknownExtensionPropertyPermittedByTheOfficialSchema() {
        JsonObject body = discoverBody();
        body.getJsonObject("params").put("io.vertique.test/extension", new JsonObject().put("enabled", true));

        Outcome outcome = drive(body, standardHeaders("server/discover"));

        assertThat(outcome.status())
                .as("the official schema permits unknown extension properties")
                .isEqualTo(200);
        assertThat(outcome.interceptorInvocations())
                .as("the accepted extension must reach the next application stage")
                .isEqualTo(1);
        verifyNoInteractions(outcome.toolRegistry(), outcome.policyEnforcer());
    }

    // --- Control: a fully negotiated request reaches the interceptor stage ---

    private void shouldPermitAFullyNegotiatedRequestAsTheControl() {
        Outcome outcome = drive(discoverBody(), standardHeaders("server/discover"));

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
        MultiMap headers = standardHeaders("server/discover");
        headers.remove(HEADER_PROTOCOL_VERSION);

        Outcome outcome = drive(discoverBody(), headers);

        assertRejectedBeforeDispatch(outcome, "a missing MCP-Protocol-Version header");
    }

    // --- Mismatched header ---

    private void shouldRejectAMismatchedRequiredHeaderBeforeDispatch() {
        MultiMap headers = standardHeaders("server/discover");
        headers.set(HEADER_PROTOCOL_VERSION, "1999-01-01");

        Outcome outcome = drive(discoverBody(), headers);

        assertRejectedBeforeDispatch(outcome, "a header value that disagrees with the negotiated body value");
    }

    // --- Repair task R33 defect 1: duplicated routing headers ---

    private void shouldRejectADuplicatedMcpMethodHeaderBeforeDispatch() {
        MultiMap headers = standardHeaders("server/discover");
        headers.add(HEADER_METHOD, "server/discover");

        Outcome outcome = drive(discoverBody(), headers);

        assertRejectedBeforeDispatch(outcome, "a duplicated Mcp-Method header, even with an identical repeated value");
    }

    private void shouldRejectADuplicatedProtocolVersionHeaderBeforeDispatch() {
        MultiMap headers = standardHeaders("server/discover");
        headers.add(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);

        Outcome outcome = drive(discoverBody(), headers);

        assertRejectedBeforeDispatch(
                outcome, "a duplicated MCP-Protocol-Version header, even with an identical repeated value");
    }

    private void shouldRejectADuplicatedToolsCallNameHeaderBeforeDispatch() {
        MultiMap headers = validHeaders("tools/call", KNOWN_TOOL);
        headers.add(HEADER_NAME, KNOWN_TOOL);

        Outcome outcome = drive(toolsCallBody(KNOWN_TOOL), headers);

        assertRejectedBeforeDispatch(
                outcome, "a duplicated Mcp-Name header on tools/call, even with an identical repeated value");
    }

    private void shouldRejectABlankToolsCallProtocolVersionBeforeDispatch() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").getJsonObject("_meta").put("io.modelcontextprotocol/protocolVersion", "   ");

        Outcome outcome = drive(body, validHeaders("tools/call", KNOWN_TOOL));

        assertRejectedBeforeDispatch(outcome, "tools/call with a blank protocolVersion");
    }

    // --- tools/call reserved MRTR field ---

    private void shouldRejectAToolsCallReservedFieldBeforeDispatch() {
        JsonObject body = toolsCallBody(KNOWN_TOOL);
        body.getJsonObject("params").put("inputResponses", new JsonObject());

        Outcome outcome = drive(body, validHeaders("tools/call", KNOWN_TOOL));

        assertRejectedBeforeDispatch(outcome, "a tools/call params carrying the reserved inputResponses field");
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
        MultiMap headers = standardHeaders("server/discover");
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
        MultiMap headers = standardHeaders("server/discover");
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
        MultiMap headers = standardHeaders("server/discover");
        headers.set(HEADER_PROTOCOL_VERSION, poisoned);

        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        McpToolRegistry toolRegistry = mock(McpToolRegistry.class);
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
                toolRegistry,
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));

        RoutingContext context = mockStatefulRoutingContext(body, headers);
        // R09: begin() registers the correlation bind scope with RequestContextLifecycle's per-request
        // handle; this synthetic RoutingContext has no real ROOT-scoped middleware chain, so the test
        // installs the lifecycle handle itself, exactly as production's HttpVerticle does.
        new RequestContextLifecycle().handle(context);

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
        McpToolRegistry toolRegistry = mock(McpToolRegistry.class);
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
                toolRegistry,
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));

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
        return new Outcome(status, errorCode, interceptor.invocations(), toolRegistry, policyEnforcer);
    }

    /** One drive's observed outcome. */
    private record Outcome(
            int status,
            Integer errorCode,
            int interceptorInvocations,
            McpToolRegistry toolRegistry,
            McpPolicyEnforcer policyEnforcer) {}

    private record OfficialParamsViolation(String description, JsonObject body, MultiMap headers) {
        @Override
        public String toString() {
            return description;
        }
    }

    private record StandardHeaderCase(
            String description,
            JsonObject body,
            MultiMap headers,
            Integer expectedOfficialParamsError,
            Integer expectedNegotiationError) {

        private static StandardHeaderCase negotiated(String description, JsonObject body, MultiMap headers) {
            return new StandardHeaderCase(description, body, headers, null, null);
        }

        private static StandardHeaderCase negotiationFailure(String description, JsonObject body, MultiMap headers) {
            return new StandardHeaderCase(description, body, headers, null, -32020);
        }

        private static StandardHeaderCase officialParamsFailure(String description, JsonObject body, MultiMap headers) {
            return new StandardHeaderCase(description, body, headers, -32602, null);
        }

        @Override
        public String toString() {
            return description;
        }
    }

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

    /** Builds the universally required protocol-version and method headers. */
    private static MultiMap standardHeaders(String method) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
        headers.set(HEADER_METHOD, method);
        return headers;
    }

    /**
     * Builds standard headers with an explicit {@code Mcp-Name}. The value is the required body mirror
     * for {@code tools/call} and a tolerated unsolicited value for methods with no name-shaped field.
     */
    private static MultiMap validHeaders(String method, String toolName) {
        MultiMap headers = standardHeaders(method);
        headers.set(HEADER_NAME, toolName);
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

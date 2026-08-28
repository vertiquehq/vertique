// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.rest.core.config.HttpConfig;
import io.vertx.core.MultiMap;
import jakarta.annotation.Nullable;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Strict, bounded final-2026 JSON-RPC codec for the MCP wire layer.
 *
 * <p>The codec canonically encodes a JSON value to compact, insertion-order-preserving UTF-8 bytes,
 * strictly decodes and validates an incoming JSON-RPC request envelope, and produces bounded
 * JSON-RPC error responses. It never leaks internal exception text: an internal codec failure
 * settles through a pre-encoded internal-error response written exactly once. Protocol failures use
 * the final-spec JSON-RPC codes — {@code -32700} (malformed JSON or a strict-reader rejection such
 * as a duplicate key or trailing token), {@code -32600} (invalid envelope/request), {@code -32601}
 * (unknown method, classified against the bounded supported-method set), and {@code -32603}
 * (internal error). Official per-method {@code params} violations are {@code -32602} (invalid
 * params). Error messages are the standard JSON-RPC strings and no {@code data} member is emitted.
 *
 * <p>Envelope validation trusts only the framework-owned {@link McpEnvelopeJsonCodec}; the supported
 * request methods are the bounded set {@code server/discover}, {@code tools/list}, and
 * {@code tools/call}. Tool-level authorization ({@code -32602}) belongs to a later HTTP slice and is
 * deliberately absent here. Official per-method {@code params} validation is {@link
 * #validateOfficialParams}; it runs immediately after a successful {@link #decodeEnvelope}. Protocol
 * negotiation — required header/body agreement, supported-version policy, and rejected {@code
 * tools/call} MRTR fields — is {@link #validateNegotiation}; it runs only after official params
 * validation. Neither step depends on interceptors, tool lookup, or authorization.
 */
@Slf4j
final class McpProtocolCodec {

    /** The bounded set of final-2026 request methods this server envelope-validates. */
    private static final Set<String> SUPPORTED_METHODS = Set.of("server/discover", "tools/list", "tools/call");

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    /**
     * The implementation-defined JSON-RPC server-error-range code (contract §4.7 — "Header/body
     * mismatch is HTTP 400 with -32020") this class's {@link #validateNegotiation} settles as. It is
     * reserved for HTTP header/body disagreement and Phase-1 negotiation policy, never official
     * per-method schema violations.
     */
    private static final int NEGOTIATION_MISMATCH = -32020;

    private static final String MSG_PARSE_ERROR = "Parse error";
    private static final String MSG_INVALID_REQUEST = "Invalid Request";
    private static final String MSG_METHOD_NOT_FOUND = "Method not found";
    private static final String MSG_INVALID_PARAMS = "Invalid params";
    private static final String MSG_INTERNAL_ERROR = "Internal error";

    /**
     * The standard, non-leaking message paired with {@link #NEGOTIATION_MISMATCH}: never echoes the
     * offending header name, header value, or body field, matching {@link
     * McpPolicyEnforcer#UNKNOWN_OR_UNAUTHORIZED_MESSAGE}'s established non-leaking convention for a
     * different stage.
     */
    private static final String MSG_NEGOTIATION_MISMATCH = "Header/body mismatch";

    // --- Protocol negotiation (contract §4.7, issue #429) ---

    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    private static final String META_FIELD = "_meta";
    private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /**
     * The plain, un-prefixed {@code _meta} keys repair task R39's body trace-context extraction
     * reads (MCP 2026-07-28 §_meta, OpenTelemetry trace context) — deliberately distinct from the
     * {@code io.modelcontextprotocol/}-prefixed negotiation keys above: W3C trace propagation is a
     * Vertique-owned extension of the same {@code _meta} object, not an official MCP protocol field.
     * {@code baggage} is reserved upstream too but has no consumer here and is never read.
     */
    private static final String META_TRACEPARENT = "traceparent";

    private static final String META_TRACESTATE = "tracestate";

    /**
     * The bounded W3C {@code traceparent} wire format {@link #extractBodyTraceContext} accepts:
     * {@code 00-<32 lowercase hex trace id>-<16 lowercase hex span id>-<2 hex flags>}. Any other
     * shape — including an unsupported version field — is malformed syntax and yields no trace
     * context.
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    /**
     * The {@link TraceReference#source()} label stamped on every reference {@link
     * #extractBodyTraceContext} produces (R51; the framework's single trace-reference type, formerly
     * the MCP-local {@code McpTraceContext}).
     */
    private static final String TRACE_REFERENCE_SOURCE = "mcp._meta";

    /**
     * The two {@code tools/call} {@code CallToolRequestParams} fields the official schema permits for
     * multi-round-trip tool execution (MRTR) that Phase 1 deliberately does not implement (contract
     * §4.7 — "Phase 1 rejects {@code inputResponses}/{@code requestState} rather than pretending to
     * support MRTR"). Present only on {@code CallToolRequestParams} (and {@code
     * ReadResourceRequestParams}, which Phase 1 never exposes) in the vendored schema — never on
     * {@code RequestParams} ({@code server/discover}) or {@code PaginatedRequestParams} ({@code
     * tools/list}) — so this check applies only to {@code tools/call}.
     */
    private static final Set<String> RESERVED_TOOLS_CALL_PARAM_FIELDS = Set.of("inputResponses", "requestState");

    /**
     * The maximum accepted length of a candidate {@code io.modelcontextprotocol/protocolVersion}
     * value, mirroring {@link dev.vertique.mcp.lifecycle.McpRequestTerminalEvent}'s own bound on the
     * same fact so a negotiated value can never reach that record's compact constructor already
     * knowing it would be rejected there.
     */
    private static final int MAX_PROTOCOL_VERSION_CHARS = 64;

    /**
     * The bounded set of {@code io.modelcontextprotocol/protocolVersion} values this server actually
     * supports — the single final-2026 version {@code server/discover} itself advertises ({@link
     * McpCursorCodec#PROTOCOL_VERSION}). Security-review finding (post-R05): {@link #validateNegotiation}
     * previously compared the header and body values only against each other, never against this set,
     * so any non-blank, ≤{@value #MAX_PROTOCOL_VERSION_CHARS}-char string negotiated successfully and
     * then flowed verbatim into every terminal event and the {@code mcp.protocol.version} span
     * attribute. A negotiation stage that never rejects an unsupported version is not a negotiation
     * stage.
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(McpCursorCodec.PROTOCOL_VERSION);

    private static final String JSONRPC_VERSION = "2.0";

    /** Compact, insertion-order-preserving encoder; writes big decimals in plain (non-scientific) form. */
    private static final ObjectMapper ENCODER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final McpEnvelopeJsonCodec envelopeCodec;
    private final McpProtocolSchemaValidator schemaValidator;

    /**
     * Creates a codec with independent ingress byte and parser-token bounds.
     *
     * @param httpConfig the shared HTTP configuration whose {@link HttpConfig#maxBodySize()} bounds
     *     the envelope codec's maximum decodable document length
     * @param ingressMaxTokens the configured parser-token budget for an ingress envelope
     */
    McpProtocolCodec(HttpConfig httpConfig, int ingressMaxTokens) {
        this.envelopeCodec = new McpEnvelopeJsonCodec(httpConfig, ingressMaxTokens);
        this.schemaValidator = new McpProtocolSchemaValidator();
    }

    /**
     * Canonically encodes a JSON value to compact, insertion-order-preserving UTF-8 bytes.
     *
     * @param value the JSON value to encode
     * @return the canonical UTF-8 byte encoding
     */
    byte[] encode(JsonNode value) {
        try {
            return ENCODER.writeValueAsBytes(value);
        } catch (JacksonException encodeFailure) {
            // An in-memory JsonNode tree cannot fail to serialize; a failure here is a programming
            // error, not a wire condition, so it is surfaced rather than silently swallowed.
            // JacksonException extends IOException, so it is a valid UncheckedIOException cause.
            throw new UncheckedIOException(encodeFailure);
        }
    }

    /**
     * Strictly decodes and validates one JSON-RPC request envelope.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return a validated envelope on success, or a bounded classified error carrying the original
     *     usable request id (or {@code null}), so a failing caller never needs to re-analyze {@code
     *     utf8} a second time just to recover the id to echo (see {@link #errorResponseFor})
     */
    Decoded decodeEnvelope(byte[] utf8) {
        Analysis analysis = analyze(utf8);
        return analysis.error() != null
                ? Decoded.failed(analysis.id(), analysis.error())
                : Decoded.ok(analysis.envelope());
    }

    /**
     * Validates the complete pinned official {@code params} schema for an already envelope-validated
     * request.
     *
     * <p>This protocol-boundary check is deliberately independent of HTTP negotiation and runs before
     * headers, request interceptors, tool lookup, authorization, application input validation, SSE
     * selection, or invocation. A violation is JSON-RPC {@code -32602} with the bounded generic
     * {@code Invalid params} message.
     *
     * @param envelope a successfully decoded envelope, as {@link Decoded#envelope()} carries it
     * @return a successful result when {@code params} satisfies the pinned schema, otherwise the
     *     bounded {@code -32602} error
     */
    ParamsValidationResult validateOfficialParams(JsonNode envelope) {
        String method = envelope.get("method").asText();
        JsonNode params = envelope.get("params");
        return schemaValidator.isValid(method, params)
                ? ParamsValidationResult.ok()
                : ParamsValidationResult.failed(invalidParamsError());
    }

    /**
     * Validates protocol negotiation for one already envelope-validated request (contract §4.7,
     * issues #429/#438): the universally required {@code MCP-Protocol-Version} / {@code Mcp-Method}
     * headers and the method-applicable {@code Mcp-Name} header against their body-mirrored values,
     * supported protocol-version policy, and — for {@code tools/call} only — the rejected reserved
     * MRTR fields.
     *
     * <p><strong>Ordering is the caller's obligation, not this method's.</strong> This method reads
     * only {@code envelope} and {@code headers}; it has no dependency on interceptors, the tool
     * registry, or authorization. The caller invokes {@link #validateOfficialParams} first, then this
     * method, and both before application policy.
     *
     * <p><strong>Header comparison.</strong> {@code MCP-Protocol-Version} must equal {@code
     * params._meta["io.modelcontextprotocol/protocolVersion"]} — the vendored schema's own {@code
     * RequestMetaObject} description: "For the HTTP transport, this value MUST match the {@code
     * MCP-Protocol-Version} header; otherwise the server MUST return a 400 Bad Request." {@code
     * Mcp-Method} must equal {@code envelope.method}. {@code Mcp-Name} is required and must equal
     * {@code params.name} only for {@code tools/call}; {@code server/discover} and {@code tools/list}
     * carry no schema-level name-shaped identifier to mirror, so an absent or unsolicited {@code
     * Mcp-Name} is accepted for those methods. Official params validation has already established that
     * {@code tools/call.params.name} is a string before this method runs. Applicable header
     * <em>values</em> are compared case-sensitively; header <em>name</em> lookup is case-insensitive
     * ({@link MultiMap#get(String)}'s own contract). This method does not implement
     * the contract's "Base64 sentinel values are decoded before comparison" clause: no concrete
     * sentinel syntax is specified anywhere in this feature's governance corpus, and the three values
     * compared here (the fixed protocol-version literal, the fixed method-string enum, and a tool name
     * already bounded to {@code [A-Za-z0-9_.-]{1,128}} once resolved) never need one — see the R05
     * evidence for the full reasoning. The vendored schema's {@code x-mcp-header}/{@code Mcp-Param-*}
     * argument-mirroring mechanism (§4.7 — "Phase 1 emits no {@code x-mcp-header}") is the more
     * plausible owner of that clause, and Phase 1 does not implement it either.
     *
     * <p><strong>{@code _meta} shape.</strong> {@code params._meta} must be an object; {@code
     * io.modelcontextprotocol/protocolVersion} must be a non-blank string of at most {@value
     * #MAX_PROTOCOL_VERSION_CHARS} characters containing no ISO control character (mirroring {@code
     * McpRequestTerminalEvent}'s own bound and control-character rejection on the same fact, so a
     * negotiated value can never reach that record's compact constructor already knowing it would be
     * rejected there — Netty's non-first-byte header validation admits HTAB (0x09) and 0x80-0x9F, both
     * of which {@link Character#isISOControl} still classifies as control characters, so this check
     * cannot be skipped in favor of trusting the header alone) and must be a member of {@link
     * #SUPPORTED_PROTOCOL_VERSIONS} — the single final-2026 version this server actually advertises;
     * {@code io.modelcontextprotocol/clientCapabilities} must be an object. Both are schema-required on
     * every supported method's {@code RequestMetaObject}. The bounds (length, control characters, and
     * supported-version set) are Phase-1 negotiation policy layered on the official schema.
     *
     * <p><strong>Reserved fields.</strong> A {@code tools/call} {@code params} containing {@code
     * inputResponses} or {@code requestState} — schema-permitted MRTR fields Phase 1 does not implement
     * — fails negotiation (contract §4.7). The pinned schema itself permits both fields, so the
     * official-schema check above does not catch this either; it is Phase 1's own policy, not a schema
     * violation.
     *
     * @param envelope a successfully decoded envelope, as {@link Decoded#envelope()} carries it
     * @param headers the request's HTTP headers
     * @return the negotiated protocol version when every check passes, or a bounded classified error
     *     when any check fails; never both
     */
    NegotiationResult validateNegotiation(JsonNode envelope, MultiMap headers) {
        String method = envelope.get("method").asText();
        JsonNode params = envelope.get("params");
        JsonNode meta = params.get(META_FIELD);
        if (meta == null || !meta.isObject()) {
            return NegotiationResult.failed(negotiationError());
        }
        JsonNode protocolVersionNode = meta.get(META_PROTOCOL_VERSION);
        if (protocolVersionNode == null
                || !protocolVersionNode.isTextual()
                || protocolVersionNode.asText().isBlank()
                || protocolVersionNode.asText().length() > MAX_PROTOCOL_VERSION_CHARS
                || protocolVersionNode.asText().chars().anyMatch(Character::isISOControl)
                || !SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersionNode.asText())) {
            return NegotiationResult.failed(negotiationError());
        }
        JsonNode clientCapabilities = meta.get(META_CLIENT_CAPABILITIES);
        if (clientCapabilities == null || !clientCapabilities.isObject()) {
            return NegotiationResult.failed(negotiationError());
        }
        if ("tools/call".equals(method)) {
            for (String reserved : RESERVED_TOOLS_CALL_PARAM_FIELDS) {
                if (params.has(reserved)) {
                    return NegotiationResult.failed(negotiationError());
                }
            }
        }
        String protocolVersion = protocolVersionNode.asText();
        if (!headerMatches(headers, HEADER_PROTOCOL_VERSION, protocolVersion)) {
            return NegotiationResult.failed(negotiationError());
        }
        if (!headerMatches(headers, HEADER_METHOD, method)) {
            return NegotiationResult.failed(negotiationError());
        }
        if ("tools/call".equals(method)
                && !headerMatches(headers, HEADER_NAME, params.get("name").asText())) {
            return NegotiationResult.failed(negotiationError());
        }
        return NegotiationResult.ok(protocolVersion);
    }

    /**
     * Extracts this request's optional body trace reference from {@code params._meta.traceparent} /
     * {@code params._meta.tracestate} (repair task R39). MCP 2026-07-28 §_meta, OpenTelemetry trace
     * context reserves these exact un-prefixed keys for W3C trace-context propagation — see {@link
     * #META_TRACEPARENT}'s own note on why they are never namespaced under {@link
     * #META_PROTOCOL_VERSION}'s {@code io.modelcontextprotocol/} prefix.
     *
     * <p>Returns {@code null} — and never fails the request — for every anomaly, each logged once as
     * a bounded, non-leaking DEBUG diagnostic (never the raw {@code traceparent}/{@code tracestate}
     * value): an absent or non-string {@code traceparent}; syntax that does not match {@link
     * #TRACEPARENT_PATTERN}'s bounded W3C wire format; an all-zero trace or span id (rejected here,
     * since {@link TraceReference} itself is a generic, protocol-agnostic type with no W3C hex-format
     * opinion of its own); or a {@code tracestate} rejected by {@link TraceReference}'s own compact
     * constructor bounds (blank, over its character cap, or carrying a non-printable-ASCII
     * character). A present but non-string {@code tracestate} is silently treated as absent rather
     * than as an anomaly, since {@code tracestate} alone is optional by the W3C spec. DEBUG, not WARN,
     * because every anomaly here is client-triggerable at will by an anonymous, unauthenticated caller
     * (repair task R47) — WARN stays reserved for a framework or application contract violation.
     *
     * <p><strong>Repair task R51.</strong> Returns a core {@link TraceReference} — the framework's
     * single trace-reference type, replacing the deleted MCP-local {@code McpTraceContext} — stamped
     * with source label {@value #TRACE_REFERENCE_SOURCE}. The caller invokes this method at most once
     * per request, from {@link McpRequestDispatcher#dispatch}, and only when {@code
     * McpServerConfig#bodyTracePolicy()} is {@code McpBodyTracePolicy.LINK}: under the default {@code
     * IGNORE} policy this method is never called at all, so no parsing, validation, or diagnostic
     * logging ever runs for the body trace fields. Extraction never affects admission; it runs after
     * negotiation ({@link #validateOfficialParams}/{@link #validateNegotiation}) has already
     * succeeded, so a malformed or absent body trace reference can never itself reject a request.
     *
     * @param envelope a successfully decoded envelope, as {@link Decoded#envelope()} carries it
     * @return the normalized W3C trace reference, or {@code null} when none is present or valid
     */
    @Nullable
    TraceReference extractBodyTraceContext(JsonNode envelope) {
        JsonNode params = envelope.get("params");
        if (params == null || !params.isObject()) {
            return null;
        }
        JsonNode meta = params.get(META_FIELD);
        if (meta == null || !meta.isObject()) {
            return null;
        }
        JsonNode traceparentNode = meta.get(META_TRACEPARENT);
        if (traceparentNode == null || !traceparentNode.isTextual()) {
            return null;
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparentNode.asText());
        if (!matcher.matches()) {
            log.debug("Ignoring malformed body _meta.traceparent syntax");
            return null;
        }
        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        if (isAllZero(traceId) || isAllZero(spanId)) {
            log.debug("Ignoring body _meta.traceparent with an all-zero trace or span id");
            return null;
        }
        boolean sampled = (Integer.parseInt(matcher.group(3), 16) & 0x1) == 1;
        JsonNode traceStateNode = meta.get(META_TRACESTATE);
        String traceState = traceStateNode != null && traceStateNode.isTextual() ? traceStateNode.asText() : null;
        try {
            return new TraceReference(traceId, spanId, TRACE_REFERENCE_SOURCE, sampled, traceState);
        } catch (IllegalArgumentException rejected) {
            // Never logs the rejection's own message or the offending value: only its occurrence
            // matters, matching McpCompletionCoordinator's established non-leaking log convention.
            log.debug("Ignoring invalid body _meta trace context");
            return null;
        }
    }

    /** Reports whether {@code hex} is composed entirely of the character {@code '0'}. */
    private static boolean isAllZero(String hex) {
        return hex.chars().allMatch(character -> character == '0');
    }

    /**
     * Reports whether {@code headers} carries {@code headerName} exactly once with a value equal
     * (case-sensitively) to {@code expected}. The header name lookup is case-insensitive per {@link
     * MultiMap#get(String)}'s own contract; an absent header never matches. A header sent with more
     * than one value never matches either, even when every occurrence is identical to {@code
     * expected}: duplicates are rejected to prevent intermediary/backend header-desync, where a
     * proxy or gateway forwards a different one of the duplicated values than the one this codec
     * observed.
     */
    private static boolean headerMatches(MultiMap headers, String headerName, String expected) {
        List<String> values = headers.getAll(headerName);
        return values.size() == 1 && values.get(0).equals(expected);
    }

    private static CodecError negotiationError() {
        return new CodecError(NEGOTIATION_MISMATCH, MSG_NEGOTIATION_MISMATCH, null);
    }

    private static CodecError invalidParamsError() {
        return new CodecError(INVALID_PARAMS, MSG_INVALID_PARAMS, null);
    }

    /**
     * The outcome of {@link #validateOfficialParams}: either success or a bounded invalid-params error.
     *
     * @param error the bounded {@code -32602} error, or {@code null} when validation succeeded
     */
    record ParamsValidationResult(@Nullable CodecError error) {

        /**
         * Reports whether official parameter validation failed.
         *
         * @return {@code true} when an invalid-params error was produced
         */
        boolean isError() {
            return error != null;
        }

        static ParamsValidationResult ok() {
            return new ParamsValidationResult(null);
        }

        static ParamsValidationResult failed(CodecError error) {
            return new ParamsValidationResult(error);
        }
    }

    /**
     * The outcome of {@link #validateNegotiation}: either the negotiated protocol version or a bounded
     * classified error, never both.
     *
     * @param protocolVersion the negotiated {@code io.modelcontextprotocol/protocolVersion} value, or
     *     {@code null} on failure
     * @param error the bounded classified error, or {@code null} on success
     */
    record NegotiationResult(
            @Nullable String protocolVersion, @Nullable CodecError error) {

        /** Reports whether negotiation failed. */
        boolean isError() {
            return error != null;
        }

        static NegotiationResult ok(String protocolVersion) {
            return new NegotiationResult(protocolVersion, null);
        }

        static NegotiationResult failed(CodecError error) {
            return new NegotiationResult(null, error);
        }
    }

    // --- Unbounded wire-format helpers: test-only, unreachable from any write path (R14 item 4) ---
    //
    // Every one of the three methods below serializes through an unrestricted writeValueAsBytes: none
    // of them observes mcp.output.maxBytes. R12 (merge blocker 5) rerouted every dispatcher write path
    // off them and onto McpRequestDispatcher#boundedErrorResponse, which streams through the capped
    // stream instead. What remains here exists solely so McpGoldenWireTest and McpCodecFailureTest can
    // pin this codec's own canonical error bytes without a dispatcher, a routing context, or a cap in
    // the way.
    //
    // R14 item 4 deleted the fourth, errorResponseFor(JsonNode, NegotiationResult): after R12 it had
    // zero callers in main OR test source, and deleting it makes R12's own documented mutation —
    // putting codec.errorResponseFor(...) back into writePreDispatchProtocolRejection — fail to COMPILE. That
    // is the regression protection R12's evidence reported as impossible to obtain: no test can
    // distinguish the defective and fixed byte output, but a method that no longer exists cannot be
    // called back into a write path at all.
    //
    // The three survivors are held off every write path by McpBoundedWritePathArchitectureTest, an
    // ArchUnit rule over the compiled production bytecode: no production class other than this one may
    // call them. That rule — not a javadoc note — is what keeps this comment true.

    /**
     * Produces the external JSON-RPC error response for a failing request frame, stamping the
     * original usable request id or a null id, and never leaking internal exception text.
     *
     * <p><strong>Test-only (R14 item 4).</strong> Bounded in <em>content</em> — the message text is a
     * fixed constant and never carries internal detail — but not in <em>bytes</em>: it has no {@code
     * mcp.output.maxBytes} check. No production caller exists, and none may be added; see this
     * section's banner comment.
     *
     * <p>Re-analyzes {@code utf8} from scratch: a full strict-UTF-8 validation and Jackson parse of up
     * to the body limit, exactly like {@link #decodeEnvelope} did. Prefer {@link #errorResponseFor}
     * when a {@link Decoded} for the same bytes was already produced by {@link #decodeEnvelope} — this
     * method exists for a caller (or test) that has only the raw bytes and no prior {@link Decoded}.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return the complete, bounded JSON-RPC error response bytes
     */
    byte[] errorResponse(byte[] utf8) {
        Analysis analysis = analyze(utf8);
        CodecError error =
                analysis.error() != null ? analysis.error() : new CodecError(INTERNAL_ERROR, MSG_INTERNAL_ERROR, null);
        return encodeError(analysis.id(), error.code(), error.message());
    }

    /**
     * Produces the same external JSON-RPC error response {@link #errorResponse} does, from an
     * already-computed {@link Decoded} failure rather than re-decoding the original bytes — the body
     * is analyzed exactly once per request on the {@link #decodeEnvelope} call that produced {@code
     * decoded}.
     *
     * <p><strong>Test-only (R14 item 4).</strong> Byte-unbounded, exactly like {@link #errorResponse};
     * no production caller exists and none may be added — see this section's banner comment.
     *
     * @param decoded a failed decode this codec already produced for the same request
     * @return the complete, bounded JSON-RPC error response bytes
     * @throws IllegalArgumentException if {@code decoded} is not an error ({@link Decoded#isError()}
     *     is {@code false})
     */
    byte[] errorResponseFor(Decoded decoded) {
        if (!decoded.isError()) {
            throw new IllegalArgumentException("errorResponseFor requires a failed Decoded");
        }
        return encodeError(decoded.id(), decoded.error().code(), decoded.error().message());
    }

    /**
     * Settles an internal codec failure through the pre-encoded internal-error response, written
     * exactly once and never carrying the cause's text.
     *
     * <p><strong>Test-only (R14 item 4).</strong> Byte-unbounded, exactly like {@link #errorResponse};
     * no production caller exists and none may be added — see this section's banner comment.
     *
     * @param id the original usable request id, or {@code null} when none is available
     * @param cause the internal failure whose text must never reach the client
     * @return the complete, bounded internal-error response bytes
     */
    byte[] internalFallback(@Nullable JsonNode id, Throwable cause) {
        // The cause is deliberately never read: only its occurrence matters, never its text.
        return encodeError(id, INTERNAL_ERROR, MSG_INTERNAL_ERROR);
    }

    // --- Envelope analysis ---

    /**
     * Strictly decodes and classifies one request frame against the final-2026 envelope rules.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return the parsed envelope with its usable id, or a bounded classified error
     */
    private Analysis analyze(byte[] utf8) {
        McpEnvelopeJsonCodec.Result parsed = envelopeCodec.decode(utf8);
        if (parsed.isRejected()) {
            return new Analysis(null, null, new CodecError(PARSE_ERROR, MSG_PARSE_ERROR, null));
        }
        JsonNode node = parsed.value();
        if (node == null || !node.isObject()) {
            return new Analysis(node, null, new CodecError(INVALID_REQUEST, MSG_INVALID_REQUEST, null));
        }
        JsonNode rawId = node.get("id");
        JsonNode id = usableId(rawId);
        JsonNode version = node.get("jsonrpc");
        if (version == null || !version.isTextual() || !JSONRPC_VERSION.equals(version.asText())) {
            return new Analysis(node, id, new CodecError(INVALID_REQUEST, MSG_INVALID_REQUEST, null));
        }
        // The final-2026 request schema requires a string or integer id on every supported method —
        // all three are requests, none a notification — so an absent, null, fractional, or structured
        // id is an invalid request. The usable id is null because no trustworthy value can be echoed.
        if (rawId == null || !(rawId.isTextual() || rawId.isIntegralNumber())) {
            return new Analysis(node, null, new CodecError(INVALID_REQUEST, MSG_INVALID_REQUEST, null));
        }
        JsonNode method = node.get("method");
        if (method == null || !method.isTextual()) {
            return new Analysis(node, id, new CodecError(INVALID_REQUEST, MSG_INVALID_REQUEST, null));
        }
        if (!SUPPORTED_METHODS.contains(method.asText())) {
            return new Analysis(node, id, new CodecError(METHOD_NOT_FOUND, MSG_METHOD_NOT_FOUND, null));
        }
        // Every supported final-2026 request method (CallToolRequest, DiscoverRequest, ListToolsRequest)
        // lists params in its schema-required set: the mandatory request _meta — protocol version and
        // client capabilities — lives inside params, so an absent or non-object params is a structurally
        // invalid request. Only structural presence and shape are validated here; params content stays a
        // later slice's concern. This check runs after the supported-method check so an unknown method
        // with an absent params still classifies as -32601, not -32600.
        JsonNode params = node.get("params");
        if (params == null || !params.isObject()) {
            return new Analysis(node, id, new CodecError(INVALID_REQUEST, MSG_INVALID_REQUEST, null));
        }
        return new Analysis(node, id, null);
    }

    /**
     * Returns the id node when it is a trustworthy echo (integral number or string), else {@code null}.
     *
     * @param id the raw id node, or {@code null} when absent
     * @return the usable id node, or {@code null}
     */
    @Nullable
    private static JsonNode usableId(@Nullable JsonNode id) {
        if (id != null && (id.isTextual() || id.isIntegralNumber())) {
            return id;
        }
        return null;
    }

    /**
     * Builds and encodes the pinned bounded JSON-RPC error response.
     *
     * @param id the usable id to stamp, or {@code null} for a null id
     * @param code the final-spec JSON-RPC error code
     * @param message the standard JSON-RPC error message
     * @return the complete, bounded response bytes
     */
    private byte[] encodeError(@Nullable JsonNode id, int code, String message) {
        ObjectNode response = ENCODER.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        response.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode error = ENCODER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return encode(response);
    }

    /**
     * The result of analyzing a request frame: the parsed envelope, its usable id, and any error.
     *
     * @param envelope the parsed JSON envelope, or {@code null} when the frame did not parse
     * @param id the original usable request id, or {@code null} when none is trustworthy
     * @param error the bounded classified error, or {@code null} when the envelope is valid
     */
    private record Analysis(
            @Nullable JsonNode envelope,
            @Nullable JsonNode id,
            @Nullable CodecError error) {}

    /**
     * The outcome of an envelope decode: either a validated {@code envelope} or a bounded
     * {@code error} carrying the original usable request id, never both.
     *
     * @param envelope the validated JSON-RPC envelope, or {@code null} on failure
     * @param id the original usable request id an error response should echo, or {@code null} when
     *     none is trustworthy; always {@code null} on a successful decode (the envelope itself carries
     *     the id there)
     * @param error the bounded classified error, or {@code null} on success
     */
    record Decoded(
            @Nullable JsonNode envelope,
            @Nullable JsonNode id,
            @Nullable CodecError error) {

        /**
         * Reports whether the decode failed.
         *
         * @return {@code true} when a bounded error was produced
         */
        boolean isError() {
            return error != null;
        }

        /**
         * Wraps a validated envelope.
         *
         * @param envelope the validated JSON-RPC envelope
         * @return a successful decode
         */
        static Decoded ok(JsonNode envelope) {
            return new Decoded(envelope, null, null);
        }

        /**
         * Wraps a bounded classified error together with the original usable request id it should
         * echo.
         *
         * @param id the original usable request id, or {@code null} when none is trustworthy
         * @param error the bounded error
         * @return a failed decode carrying no envelope
         */
        static Decoded failed(@Nullable JsonNode id, CodecError error) {
            return new Decoded(null, id, error);
        }
    }

    /**
     * A bounded JSON-RPC error: the final-spec {@code code}, a safe {@code message}, and optional
     * non-leaking {@code data}.
     *
     * @param code the final-spec JSON-RPC error code
     * @param message a short, non-leaking description
     * @param data optional bounded, non-leaking error data, or {@code null}
     */
    record CodecError(int code, String message, @Nullable JsonNode data) {}
}

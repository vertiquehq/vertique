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
import jakarta.annotation.Nullable;
import java.io.UncheckedIOException;
import java.util.Set;

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
 * (internal error). Error messages are the standard JSON-RPC strings and no {@code data} member is
 * emitted.
 *
 * <p>Envelope validation trusts only the framework-owned {@link McpStrictJsonReader}; the supported
 * request methods are the bounded set {@code server/discover}, {@code tools/list}, and
 * {@code tools/call}. Header/body-mismatch classification ({@code -32020}) and tool-level
 * authorization ({@code -32602}) belong to a later HTTP slice and are deliberately absent here.
 */
final class McpProtocolCodec {

    /** The bounded set of final-2026 request methods this server envelope-validates. */
    private static final Set<String> SUPPORTED_METHODS = Set.of("server/discover", "tools/list", "tools/call");

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;

    private static final String MSG_PARSE_ERROR = "Parse error";
    private static final String MSG_INVALID_REQUEST = "Invalid Request";
    private static final String MSG_METHOD_NOT_FOUND = "Method not found";
    private static final String MSG_INTERNAL_ERROR = "Internal error";

    private static final String JSONRPC_VERSION = "2.0";

    /** Compact, insertion-order-preserving encoder; writes big decimals in plain (non-scientific) form. */
    private static final ObjectMapper ENCODER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final McpStrictJsonReader reader;

    /**
     * Creates a codec bound to the supplied configuration's JSON limits.
     *
     * @param config the MCP server configuration whose limits bound every decode and encode
     */
    McpProtocolCodec(McpServerConfig config) {
        this.reader = new McpStrictJsonReader(config);
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
     * @return a validated envelope on success, or a bounded classified error
     */
    Decoded decodeEnvelope(byte[] utf8) {
        Analysis analysis = analyze(utf8);
        return analysis.error() != null ? Decoded.failed(analysis.error()) : Decoded.ok(analysis.envelope());
    }

    /**
     * Produces the bounded external JSON-RPC error response for a failing request frame, stamping the
     * original usable request id or a null id, and never leaking internal exception text.
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
     * Settles an internal codec failure through the bounded pre-encoded internal-error response,
     * written exactly once and never carrying the cause's text.
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
        McpStrictJsonReader.Result parsed = reader.read(utf8);
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
        // params is optional (absent is normalized elsewhere), but when present it must be an object —
        // a structured params of any other shape is a structurally invalid request.
        JsonNode params = node.get("params");
        if (params != null && !params.isObject()) {
            return new Analysis(node, id, new CodecError(INVALID_REQUEST, MSG_INVALID_REQUEST, null));
        }
        if (!SUPPORTED_METHODS.contains(method.asText())) {
            return new Analysis(node, id, new CodecError(METHOD_NOT_FOUND, MSG_METHOD_NOT_FOUND, null));
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
     * {@code error}, never both.
     *
     * @param envelope the validated JSON-RPC envelope, or {@code null} on failure
     * @param error the bounded classified error, or {@code null} on success
     */
    record Decoded(@Nullable JsonNode envelope, @Nullable CodecError error) {

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
            return new Decoded(envelope, null);
        }

        /**
         * Wraps a bounded classified error.
         *
         * @param error the bounded error
         * @return a failed decode carrying no envelope
         */
        static Decoded failed(CodecError error) {
            return new Decoded(null, error);
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

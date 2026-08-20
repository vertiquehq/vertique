// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * Strict, bounded final-2026 JSON-RPC codec for the MCP wire layer.
 *
 * <p>The codec canonically encodes a JSON value to UTF-8 bytes, strictly decodes and validates an
 * incoming JSON-RPC envelope, and produces bounded JSON-RPC error responses. It never leaks internal
 * exception text: an internal codec failure settles through a pre-encoded internal-error response
 * written exactly once. Protocol failures use the final-spec JSON-RPC codes — {@code -32700}
 * (malformed JSON), {@code -32600} (invalid envelope/request), {@code -32601} (unknown method),
 * {@code -32602} (unknown/unauthorized tool), and {@code -32603} (internal error).
 *
 * <p>This class is a non-functional skeleton: the T003 implementation slice replaces every method
 * body with the codec logic. The current bodies exist only so the wire layer compiles and the T003
 * proofs fail on their decisive assertions rather than on setup errors.
 */
final class McpProtocolCodec {

    /**
     * A bounded, non-leaking placeholder response the skeleton returns so failure proofs can decode a
     * complete JSON-RPC frame and land on their decisive assertions. It carries no internal text.
     */
    private static final byte[] STUB_ERROR_RESPONSE =
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":0,\"message\":\"unimplemented\"}}"
                    .getBytes(StandardCharsets.UTF_8);

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
     * Canonically encodes a JSON value to UTF-8 bytes.
     *
     * @param value the JSON value to encode
     * @return the canonical UTF-8 byte encoding
     */
    byte[] encode(JsonNode value) {
        // Skeleton: canonical encoding is the T003 implementation slice's responsibility.
        return new byte[0];
    }

    /**
     * Strictly decodes and validates one JSON-RPC envelope.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return a validated envelope on success, or a bounded classified error
     */
    Decoded decodeEnvelope(byte[] utf8) {
        // Skeleton: envelope validation lands in the implementation slice. The stub routes through
        // the reader and reports success so the failure proofs fail on their decisive assertions.
        McpStrictJsonReader.Result parsed = reader.read(utf8);
        return Decoded.ok(parsed.value());
    }

    /**
     * Produces the bounded external JSON-RPC error response for a failing request frame, stamping the
     * original usable request id or a null id, and never leaking internal exception text.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return the complete, bounded JSON-RPC error response bytes
     */
    byte[] errorResponse(byte[] utf8) {
        // Skeleton: the implementation slice classifies and encodes the pinned error. The stub
        // returns a complete, non-leaking frame so proofs decode it and fail on the pinned code.
        return STUB_ERROR_RESPONSE.clone();
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
        // Skeleton: the implementation slice emits the pinned -32603 fallback. The stub deliberately
        // ignores the cause so no internal text can leak, and returns one complete response.
        return STUB_ERROR_RESPONSE.clone();
    }

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

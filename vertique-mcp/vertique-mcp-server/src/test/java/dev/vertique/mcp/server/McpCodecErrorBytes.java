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

/**
 * Test-side canonical encoder and expected JSON-RPC error bytes for the codec tests.
 *
 * <p>The production codec has no serializer: the dispatcher's capped stream is the only error
 * serializer. The golden-wire and failure tests still pin the canonical bytes, so they build them here
 * with their own compact, insertion-order-preserving mapper that writes big decimals in plain form. The error bytes are built from the
 * {@link McpProtocolCodec.Decoded} that {@link McpProtocolCodec#decodeEnvelope} produces.
 */
final class McpCodecErrorBytes {

    private static final ObjectMapper ENCODER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private static final String JSONRPC_VERSION = "2.0";
    private static final String MSG_INTERNAL_ERROR = "Internal error";

    private McpCodecErrorBytes() {}

    /**
     * Canonically encodes a JSON value to compact, insertion-order-preserving UTF-8 bytes.
     *
     * @param value the JSON value to encode
     * @return the canonical UTF-8 byte encoding
     */
    static byte[] encode(JsonNode value) {
        try {
            return ENCODER.writeValueAsBytes(value);
        } catch (JacksonException encodeFailure) {
            throw new UncheckedIOException(encodeFailure);
        }
    }

    /**
     * Builds the expected external JSON-RPC error response for a failed decode.
     *
     * @param decoded a failed decode the codec produced
     * @return the complete JSON-RPC error response bytes
     * @throws IllegalArgumentException if {@code decoded} is not an error
     */
    static byte[] errorResponseFor(McpProtocolCodec.Decoded decoded) {
        if (!decoded.isError()) {
            throw new IllegalArgumentException("errorResponseFor requires a failed Decoded");
        }
        return encodeError(decoded.id(), decoded.error().code(), decoded.error().message());
    }

    /**
     * Builds the expected external JSON-RPC error response for a raw frame: the decoded failure, or the
     * internal-error response when the frame decodes successfully.
     *
     * @param codec the codec that decodes the frame
     * @param utf8 the raw UTF-8 request bytes
     * @return the complete JSON-RPC error response bytes
     */
    static byte[] errorResponse(McpProtocolCodec codec, byte[] utf8) {
        McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(utf8);
        if (decoded.isError()) {
            return errorResponseFor(decoded);
        }
        JsonNode id = decoded.envelope().get("id");
        boolean usable = id != null && (id.isTextual() || id.isIntegralNumber());
        return internalFallback(usable ? id : null);
    }

    /**
     * Builds the expected internal-error response, which never carries any cause text.
     *
     * @param id the original usable request id, or {@code null} when none is available
     * @return the complete internal-error response bytes
     */
    private static byte[] internalFallback(@Nullable JsonNode id) {
        return encodeError(id, McpProtocolCodec.INTERNAL_ERROR, MSG_INTERNAL_ERROR);
    }

    private static byte[] encodeError(@Nullable JsonNode id, int code, String message) {
        ObjectNode response = ENCODER.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        response.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode error = ENCODER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return encode(response);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import dev.vertique.rest.core.config.HttpConfig;
import jakarta.annotation.Nullable;
import java.io.IOException;

/**
 * Bounded Jackson JSON-RPC envelope codec for the MCP wire layer (T007).
 *
 * <p>Decodes exactly one complete JSON value and rejects — with a bounded, classified {@link Result}
 * and no partial value — any input that carries duplicate object keys, trailing tokens after a
 * complete value, an over-deep document, an over-long numeric token, an over-long string or property
 * name, an over-large document, or invalid UTF-8. Every bound is enforced by Jackson's own {@link
 * StreamReadConstraints} rather than a handcrafted reader: this class replaces the T003/T004
 * handcrafted strict JSON reader and the four generic JSON-limit configuration properties it
 * enforced. None of the constraints below is a consumer-visible configuration key — they are frozen
 * by the T007 contract amendment, not chosen at implementation time, so the codec cannot silently
 * inherit a changed upstream Jackson default.
 *
 * <ul>
 *   <li>{@code maxNestingDepth} 1000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxNumberLength} 1000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxStringLength} 20,000,000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxNameLength} 50,000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxDocumentLength} — the one Jackson default that is unbounded ({@code -1}); set
 *       explicitly to the effective {@link HttpConfig#maxBodySize()} in bytes so this codec introduces
 *       no separate MCP ingress bound.
 *   <li>{@code maxTokenCount} — stays unlimited ({@code -1}); a finite document length already bounds
 *       it, so a separate token budget would be a second unshared policy.
 * </ul>
 *
 * <p>{@link StreamReadFeature#STRICT_DUPLICATE_DETECTION} and {@link
 * DeserializationFeature#FAIL_ON_TRAILING_TOKENS} are enabled. The T003 {@link java.math.BigDecimal}
 * scale-magnitude hardening cap is retained as a fixed internal constant, applied after decode: it is
 * not expressible through {@link StreamReadConstraints}, but guards the same out-of-memory
 * plain-form-encode vector the encoder's {@code WRITE_BIGDECIMAL_AS_PLAIN} guard rejects.
 *
 * <p>Tool argument and result values keep using the existing {@code JsonMapperProfile}/
 * {@code JsonMapperProfileRegistry} contract; this codec owns only the JSON-RPC envelope and adds no
 * public parser API.
 */
final class McpEnvelopeJsonCodec {

    private static final int MAX_NESTING_DEPTH = 1_000;
    private static final int MAX_NUMBER_LENGTH = 1_000;
    private static final int MAX_STRING_LENGTH = 20_000_000;
    private static final int MAX_NAME_LENGTH = 50_000;
    private static final long UNLIMITED_TOKEN_COUNT = -1L;

    /**
     * Fixed internal cap on the magnitude of a decimal's scale, aligned exactly with the encoder's
     * {@code WRITE_BIGDECIMAL_AS_PLAIN} plain-form guard (retained unchanged from the T003 hardening).
     * Jackson's {@code GeneratorBase} rejects a plain-form encode when {@code scale < -9999 ||
     * scale > 9999}, so the codec's admitted set is the encoder's safe set precisely when it rejects a
     * scale magnitude greater than this bound. A short token such as {@code 1e999999999} passes the
     * frozen {@code maxNumberLength} bound yet would otherwise decode to a {@link java.math.BigDecimal}
     * whose plain-form encode exhausts memory; bounding the scale magnitude rejects it before that.
     */
    private static final int MAX_DECIMAL_SCALE = 9_999;

    private final ObjectMapper mapper;

    /**
     * Creates a codec whose {@code maxDocumentLength} is bound to the effective ingress cap.
     *
     * @param httpConfig the shared HTTP configuration whose {@link HttpConfig#maxBodySize()} becomes
     *     this codec's {@code maxDocumentLength}
     */
    McpEnvelopeJsonCodec(HttpConfig httpConfig) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxDocumentLength(httpConfig.maxBodySize())
                .maxTokenCount(UNLIMITED_TOKEN_COUNT)
                .build();
        JsonFactory factory =
                JsonFactory.builder().streamReadConstraints(constraints).build();
        this.mapper = JsonMapper.builder(factory)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // Preserve exact BigDecimal lexical precision for a floating-point token (no lossy
                // double rounding) and keep its exact scale (no trailing-zero normalization), matching
                // the T003 hardening's lossless round-trip requirement. Replaces the deprecated
                // JsonNodeFactory.withExactBigDecimals(true).
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
    }

    /**
     * Strictly decodes one complete JSON value from UTF-8 bytes.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return a bounded value on success, or a classified rejection carrying no partial value
     */
    Result decode(@Nullable byte[] utf8) {
        if (utf8 == null) {
            return Result.rejected();
        }
        JsonNode value;
        try {
            value = mapper.readValue(utf8, JsonNode.class);
        } catch (IOException bounded) {
            // Malformed JSON, a duplicate key, a trailing token, an over-deep or over-large document,
            // an over-long number literal, and invalid UTF-8 all surface as an IOException from
            // Jackson's own bounded read constraints; every one collapses to the same bounded
            // rejection, matching the frozen protocol contract's single -32700 classification.
            return Result.rejected();
        }
        if (value == null || exceedsDecimalScaleBound(value)) {
            return Result.rejected();
        }
        return Result.ok(value);
    }

    /**
     * Walks the decoded tree for a decimal whose scale magnitude exceeds the fixed internal hardening
     * bound. The walk cannot itself run away: nesting is already bounded at {@link #MAX_NESTING_DEPTH}
     * by the decode that produced this tree.
     *
     * @param node the node (or subtree) to check
     * @return {@code true} when any decimal in the tree exceeds the scale-magnitude bound
     */
    private static boolean exceedsDecimalScaleBound(JsonNode node) {
        if (node instanceof DecimalNode decimal) {
            return Math.abs((long) decimal.decimalValue().scale()) > MAX_DECIMAL_SCALE;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (exceedsDecimalScaleBound(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The outcome of a strict decode: either a bounded {@code value} or a classified rejection, never
     * both and never a partial value.
     *
     * @param value the decoded JSON value, or {@code null} when the decode was rejected
     * @param wasRejected whether the decode was rejected
     */
    record Result(@Nullable JsonNode value, boolean wasRejected) {

        /**
         * Reports whether the decode was rejected.
         *
         * @return {@code true} when a bounded rejection was produced
         */
        boolean isRejected() {
            return wasRejected;
        }

        /**
         * Wraps a successfully decoded value.
         *
         * @param value the decoded JSON value
         * @return a successful result
         */
        static Result ok(JsonNode value) {
            return new Result(value, false);
        }

        /**
         * Wraps a bounded rejection.
         *
         * @return a rejected result carrying no value
         */
        static Result rejected() {
            return new Result(null, true);
        }
    }
}

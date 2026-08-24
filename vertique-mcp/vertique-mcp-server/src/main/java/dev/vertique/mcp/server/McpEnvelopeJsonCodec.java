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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Bounded Jackson JSON-RPC envelope codec for the MCP wire layer (T007).
 *
 * <p>Decodes exactly one complete JSON value and rejects — with a bounded, classified {@link Result}
 * and no partial value — any input that carries duplicate object keys, trailing tokens after a
 * complete value, an over-deep document, an over-long numeric token, an over-long string or property
 * name, an over-large document, or invalid UTF-8 (including a well-formed-looking but non-shortest
 * "overlong" encoding, which {@link #decode} rejects with a strict pre-parse gate before Jackson's own
 * more permissive UTF-8 decoding ever runs — see {@link #rejectsInvalidUtf8}). Every other bound is
 * enforced by Jackson's own {@link StreamReadConstraints} rather than a handcrafted reader: this class
 * replaces the T003/T004 handcrafted strict JSON reader and the four generic JSON-limit configuration
 * properties it enforced. The generic shape constraints remain frozen by the T007 contract amendment
 * so the codec cannot silently inherit changed Jackson defaults; R18 makes only {@code maxTokenCount}
 * consumer-configurable through {@code mcp.ingressMaxTokens}.
 *
 * <ul>
 *   <li>{@code maxNestingDepth} 1000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxNumberLength} 1000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxStringLength} 20,000,000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxNameLength} 50,000 — matches Jackson's own default, restated explicitly.
 *   <li>{@code maxDocumentLength} — the one Jackson default that is unbounded ({@code -1}); set
 *       explicitly to the effective {@link HttpConfig#maxBodySize()} in bytes so this codec introduces
 *       no second byte setting; this cap independently bounds the encoded document.
 *   <li>{@code maxTokenCount} — set to the configured MCP ingress token budget independently of
 *       {@code maxDocumentLength}.
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
     * Creates a codec with independent byte and parser-token bounds for one ingress document.
     *
     * @param httpConfig the shared HTTP configuration whose {@link HttpConfig#maxBodySize()} becomes
     *     this codec's {@code maxDocumentLength}
     * @param ingressMaxTokens the configured {@code maxTokenCount} for this ingress document
     */
    McpEnvelopeJsonCodec(HttpConfig httpConfig, int ingressMaxTokens) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxDocumentLength(httpConfig.maxBodySize())
                .maxTokenCount(ingressMaxTokens)
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
        if (rejectsInvalidUtf8(utf8)) {
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
        } catch (RuntimeException unbounded) {
            // NOT redundant with the IOException catch above: Jackson throws an UNCHECKED
            // NumberFormatException (not an IOException) when a numeric token's decimal exponent
            // overflows int range while still under the frozen maxNumberLength bound (e.g.
            // "1E2147483649", including nested inside an otherwise well-formed envelope). Left
            // uncaught, that exception would escape this codec's boundary entirely, surface as a bare
            // Vert.x route failure, and return an HTTP 500 with no body instead of the pinned -32700
            // envelope every other malformed-input path produces. Collapsing it to the same bounded
            // rejection keeps this decode boundary total over every RuntimeException Jackson may throw
            // while walking untrusted bytes, not only the checked ones its own Javadoc documents.
            return Result.rejected();
        }
        if (value == null || exceedsDecimalScaleBound(value)) {
            return Result.rejected();
        }
        return Result.ok(value);
    }

    /**
     * Strictly validates {@code utf8} as well-formed, shortest-form UTF-8 before Jackson ever parses
     * it, using the JDK's own strict decoder rather than relying on Jackson's parser-level UTF-8
     * handling.
     *
     * <p>Jackson's UTF-8 decoding rejects malformed byte sequences but admits non-shortest ("overlong")
     * encodings — e.g. the two-byte sequence {@code C1 81} decodes to the ASCII letter {@code A}, and
     * {@code C0 80} decodes to NUL — which is a canonicalization bypass at the outermost trust boundary:
     * a string that visually or semantically differs from its shortest-form encoding can smuggle
     * characters past later name/authorization checks that operate on the decoded {@link String}. The
     * JDK {@link java.nio.charset.CharsetDecoder} with {@link CodingErrorAction#REPORT} on both
     * malformed and unmappable input rejects every overlong and otherwise non-canonical sequence, so
     * gating on it here closes that bypass before Jackson's own (more permissive) UTF-8 handling runs.
     *
     * @param utf8 the raw request bytes to validate
     * @return {@code true} when {@code utf8} is not strictly well-formed, shortest-form UTF-8
     */
    private static boolean rejectsInvalidUtf8(byte[] utf8) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8));
            return false;
        } catch (CharacterCodingException invalid) {
            return true;
        }
    }

    /**
     * Walks the decoded tree for a decimal whose scale magnitude exceeds the fixed internal hardening
     * bound, using an explicit stack rather than native call recursion so the walk cannot grow the JVM
     * call stack on the Vert.x event-loop thread it runs on. The walk cannot itself run away regardless:
     * nesting is already bounded at {@link #MAX_NESTING_DEPTH} by the decode that produced this tree.
     *
     * @param root the decoded tree to check
     * @return {@code true} when any decimal in the tree exceeds the scale-magnitude bound
     */
    private static boolean exceedsDecimalScaleBound(JsonNode root) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node instanceof DecimalNode decimal) {
                if (Math.abs((long) decimal.decimalValue().scale()) > MAX_DECIMAL_SCALE) {
                    return true;
                }
            } else if (node.isContainerNode()) {
                for (Iterator<JsonNode> children = node.elements(); children.hasNext(); ) {
                    pending.push(children.next());
                }
            }
        }
        return false;
    }

    /**
     * Exposes this codec's mapper for structural assertions on its frozen {@link StreamReadConstraints}
     * (test-only seam; no production caller).
     *
     * @return this codec's mapper
     */
    ObjectMapper mapper() {
        return mapper;
    }

    /**
     * The outcome of a strict decode: either a bounded {@code value} or a classified rejection, never
     * both and never a partial value.
     *
     * @param value the decoded JSON value, or {@code null} when the decode was rejected
     * @param isRejected whether the decode was rejected
     */
    record Result(@Nullable JsonNode value, boolean isRejected) {

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

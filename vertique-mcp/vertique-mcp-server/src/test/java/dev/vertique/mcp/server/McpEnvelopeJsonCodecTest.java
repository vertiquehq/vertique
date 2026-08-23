// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.rest.core.config.HttpConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 — pins the T007 {@link McpEnvelopeJsonCodec} contract matrix: a well-formed envelope decodes,
 * and every named boundary violation — duplicate keys, trailing tokens, over-deep nesting, an
 * over-long number literal, invalid UTF-8 (both malformed and overlong), an overflowing decimal
 * exponent, and an over-scaled decimal — maps to the same bounded rejection. The depth and
 * number-length rows assert the frozen boundary from both sides in the same row: exactly 1000 is
 * accepted, exactly 1001 is rejected. No row consults an MCP configuration key because none exists —
 * every bound is Jackson's own frozen {@code StreamReadConstraints} inside the codec (proved
 * structurally by {@link #STRUCTURAL_CONSTRAINTS_ROW}), except {@code maxDocumentLength}, which is
 * proved separately in {@link #shouldRejectOnlyPastTheConfiguredMaxDocumentLength()} because it is
 * derived from a per-codec {@link HttpConfig} rather than being a fixed frozen value.
 */
class McpEnvelopeJsonCodecTest {

    private static final String WELL_FORMED_ROW = "shouldDecodeAWellFormedEnvelope";
    private static final String DUPLICATE_KEY_ROW = "shouldRejectDuplicateObjectKeys";
    private static final String TRAILING_TOKENS_ROW = "shouldRejectTrailingTokens";
    private static final String OVER_DEEP_ROW = "shouldRejectOverDeepDocumentsAtTheFrozenNestingDepth";
    private static final String OVER_LONG_NUMBER_ROW = "shouldRejectOverLongNumberLiteralsAtTheFrozenNumberLength";
    private static final String INVALID_UTF8_ROW = "shouldRejectInvalidUtf8";
    private static final String BARE_EXPONENT_OVERFLOW_ROW = "shouldRejectABareNumberWithAnOverflowingDecimalExponent";
    private static final String NESTED_EXPONENT_OVERFLOW_ROW =
            "shouldRejectAnOverflowingDecimalExponentNestedInAnEnvelope";
    private static final String OVERLONG_UTF8_ROW = "shouldRejectOverlongUtf8Encodings";
    private static final String LONE_SURROGATE_UTF8_ROW = "shouldRejectALoneUtf8EncodedSurrogate";
    private static final String DECIMAL_SCALE_BOUNDARY_ROW = "shouldEnforceTheDecimalScaleMagnitudeBoundary";
    private static final String NESTED_OVER_SCALE_ROW = "shouldRejectAnOverScaledDecimalNestedInAContainer";
    private static final String STRUCTURAL_CONSTRAINTS_ROW = "shouldFreezeAllSixStreamReadConstraints";

    /** The frozen {@code maxNestingDepth} and {@code maxNumberLength} boundary (T007 contract). */
    private static final int FROZEN_BOUNDARY = 1_000;

    /** The fixed internal decimal scale-magnitude hardening bound (see {@code MAX_DECIMAL_SCALE}). */
    private static final int MAX_DECIMAL_SCALE = 9_999;

    private final McpEnvelopeJsonCodec codec = McpEnvelopeJsonCodecTestFixture.newCodec();

    private static Stream<String> contractRows() {
        return Stream.of(
                WELL_FORMED_ROW,
                DUPLICATE_KEY_ROW,
                TRAILING_TOKENS_ROW,
                OVER_DEEP_ROW,
                OVER_LONG_NUMBER_ROW,
                INVALID_UTF8_ROW,
                BARE_EXPONENT_OVERFLOW_ROW,
                NESTED_EXPONENT_OVERFLOW_ROW,
                OVERLONG_UTF8_ROW,
                LONE_SURROGATE_UTF8_ROW,
                DECIMAL_SCALE_BOUNDARY_ROW,
                NESTED_OVER_SCALE_ROW,
                STRUCTURAL_CONSTRAINTS_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractRows")
    @DisplayName("T007 contract matrix: a well-formed envelope decodes and every boundary violation is rejected")
    void shouldEnforceT007ContractMatrix(String row) {
        switch (row) {
            case WELL_FORMED_ROW -> {
                // Given: a well-formed final-2026 discover request envelope.
                byte[] frame = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{\"_meta\":"
                                + "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
                        .getBytes(StandardCharsets.UTF_8);

                McpEnvelopeJsonCodec.Result result = codec.decode(frame);

                // DECISIVE: a well-formed envelope decodes without rejection to its exact structure.
                assertThat(result.isRejected())
                        .as("a well-formed envelope must not be rejected")
                        .isFalse();
                JsonNode value = result.value();
                assertThat(value)
                        .as("a decoded well-formed envelope carries its value")
                        .isNotNull();
                assertThat(value.get("method").asText())
                        .as("the decoded value preserves the envelope's method field")
                        .isEqualTo("server/discover");
            }
            case DUPLICATE_KEY_ROW -> {
                // Given: an envelope whose top-level object declares "id" twice.
                byte[] frame = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"server/discover\"}")
                        .getBytes(StandardCharsets.UTF_8);

                McpEnvelopeJsonCodec.Result result = codec.decode(frame);

                // DECISIVE: STRICT_DUPLICATE_DETECTION rejects the duplicate "id" member.
                assertThat(result.isRejected())
                        .as("a duplicate object key must be rejected")
                        .isTrue();
                assertThat(result.value())
                        .as("a rejected decode carries no partial value")
                        .isNull();
            }
            case TRAILING_TOKENS_ROW -> {
                // Given: a complete envelope followed by a second, individually well-formed JSON token
                // ("1"). Using a real token (rather than a bare word that Jackson would already reject
                // as an unrecognized literal) isolates FAIL_ON_TRAILING_TOKENS as the mechanism under
                // test: without it, Jackson's readValue silently ignores trailing content.
                byte[] frame = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"} 1")
                        .getBytes(StandardCharsets.UTF_8);

                McpEnvelopeJsonCodec.Result result = codec.decode(frame);

                // DECISIVE: FAIL_ON_TRAILING_TOKENS rejects the token after the complete value.
                assertThat(result.isRejected())
                        .as("a trailing token after one complete value must be rejected")
                        .isTrue();
                assertThat(result.value())
                        .as("a rejected decode carries no partial value")
                        .isNull();
            }
            case OVER_DEEP_ROW -> {
                // Given: a document nested exactly at the frozen maxNestingDepth, and one nested one
                // level deeper.
                McpEnvelopeJsonCodec.Result atBoundary =
                        codec.decode(McpEnvelopeJsonCodecTestFixture.nestedArrayDocument(FROZEN_BOUNDARY));
                McpEnvelopeJsonCodec.Result pastBoundary =
                        codec.decode(McpEnvelopeJsonCodecTestFixture.nestedArrayDocument(FROZEN_BOUNDARY + 1));

                // DECISIVE: the boundary is exact on both sides — 1000 accepted, 1001 rejected.
                assertThat(atBoundary.isRejected())
                        .as("nesting exactly at the frozen maxNestingDepth (1000) must be accepted")
                        .isFalse();
                assertThat(pastBoundary.isRejected())
                        .as("nesting one level past the frozen maxNestingDepth (1001) must be rejected")
                        .isTrue();
            }
            case OVER_LONG_NUMBER_ROW -> {
                // Given: a bare numeric document exactly at the frozen maxNumberLength, and one
                // character longer.
                McpEnvelopeJsonCodec.Result atBoundary =
                        codec.decode(McpEnvelopeJsonCodecTestFixture.numberLiteralDocument(FROZEN_BOUNDARY));
                McpEnvelopeJsonCodec.Result pastBoundary =
                        codec.decode(McpEnvelopeJsonCodecTestFixture.numberLiteralDocument(FROZEN_BOUNDARY + 1));

                // DECISIVE: the boundary is exact on both sides — 1000 accepted, 1001 rejected.
                assertThat(atBoundary.isRejected())
                        .as("a number literal exactly at the frozen maxNumberLength (1000 chars) must be accepted")
                        .isFalse();
                assertThat(pastBoundary.isRejected())
                        .as("a number literal one character past the frozen maxNumberLength (1001 chars) must be "
                                + "rejected")
                        .isTrue();
            }
            case INVALID_UTF8_ROW -> {
                // Given: a frame whose string value contains an invalid UTF-8 byte (0xFF is never valid
                // in any UTF-8 byte position).
                byte[] frame = McpEnvelopeJsonCodecTestFixture.invalidUtf8Document();

                McpEnvelopeJsonCodec.Result result = codec.decode(frame);

                // DECISIVE: Jackson's own UTF-8 validation rejects the invalid byte.
                assertThat(result.isRejected())
                        .as("invalid UTF-8 must be rejected")
                        .isTrue();
                assertThat(result.value())
                        .as("a rejected decode carries no partial value")
                        .isNull();
            }
            case BARE_EXPONENT_OVERFLOW_ROW -> {
                // Given: a bare numeric token whose decimal exponent overflows int range in each
                // direction, while staying well under the frozen maxNumberLength bound. Jackson throws
                // an UNCHECKED NumberFormatException for these, not an IOException.
                //
                // DECISIVE: before the fix, decode() let the NumberFormatException escape uncaught,
                // failing this test with an error (not merely a wrong assertion) instead of returning a
                // classified rejection.
                assertThat(codec.decode("1E2147483649".getBytes(StandardCharsets.UTF_8))
                                .isRejected())
                        .as("a positive-direction exponent overflow must be rejected, not thrown")
                        .isTrue();
                assertThat(codec.decode("1E-2147483649".getBytes(StandardCharsets.UTF_8))
                                .isRejected())
                        .as("a negative-direction exponent overflow must be rejected, not thrown")
                        .isTrue();
            }
            case NESTED_EXPONENT_OVERFLOW_ROW -> {
                // Given: an otherwise well-formed envelope whose params carry an overflowing exponent.
                byte[] frame = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\","
                                + "\"params\":{\"x\":1E2147483649}}")
                        .getBytes(StandardCharsets.UTF_8);

                // DECISIVE: the same uncaught-NumberFormatException hazard reachable through a nested
                // position inside a real envelope shape, not only a bare top-level token.
                assertThat(codec.decode(frame).isRejected())
                        .as("an overflowing exponent nested inside an envelope must be rejected, not thrown")
                        .isTrue();
            }
            case OVERLONG_UTF8_ROW -> {
                // Given: two non-shortest ("overlong") UTF-8 encodings that Jackson's own parser-level
                // UTF-8 handling admits: C1 81 decodes to the ASCII letter 'A', and C0 80 decodes to
                // NUL. Both are valid-looking two-byte sequences that a byte-for-byte scan would miss.
                McpEnvelopeJsonCodec.Result overlongA = codec.decode(
                        McpEnvelopeJsonCodecTestFixture.embeddedBytesDocument(new byte[] {(byte) 0xC1, (byte) 0x81}));
                McpEnvelopeJsonCodec.Result overlongNul = codec.decode(
                        McpEnvelopeJsonCodecTestFixture.embeddedBytesDocument(new byte[] {(byte) 0xC0, (byte) 0x80}));

                // DECISIVE: the strict pre-parse UTF-8 gate rejects both overlong forms even though
                // Jackson's own decoding would otherwise silently canonicalize them.
                assertThat(overlongA.isRejected())
                        .as("the overlong two-byte encoding of 'A' (C1 81) must be rejected")
                        .isTrue();
                assertThat(overlongNul.isRejected())
                        .as("the overlong two-byte encoding of NUL (C0 80) must be rejected")
                        .isTrue();
            }
            case LONE_SURROGATE_UTF8_ROW -> {
                // Given: a UTF-8 encoding of a lone (unpaired) high surrogate (ED A0 80, U+D800), which
                // is invalid UTF-8 independent of the overlong gate — asserting it stays rejected guards
                // against a regression that would only catch overlong forms and miss this class.
                byte[] frame = McpEnvelopeJsonCodecTestFixture.embeddedBytesDocument(
                        new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80});

                assertThat(codec.decode(frame).isRejected())
                        .as("a lone UTF-8-encoded surrogate must remain rejected")
                        .isTrue();
            }
            case DECIMAL_SCALE_BOUNDARY_ROW -> {
                // Given: a decimal scale magnitude exactly at the frozen bound, and one past it.
                McpEnvelopeJsonCodec.Result atBoundary = codec.decode("1e-9999".getBytes(StandardCharsets.UTF_8));
                McpEnvelopeJsonCodec.Result pastBoundary = codec.decode("1e-10000".getBytes(StandardCharsets.UTF_8));

                // DECISIVE: the boundary is exact — scale 9999 accepted and safely re-encodable in
                // plain form, scale 10000 rejected.
                assertThat(atBoundary.isRejected())
                        .as("a decimal scale exactly at the frozen bound (9999) must be accepted")
                        .isFalse();
                BigDecimal atBoundaryValue = atBoundary.value().decimalValue();
                assertThat(Math.abs(atBoundaryValue.scale()))
                        .as("the accepted value's scale must be exactly the frozen bound")
                        .isEqualTo(MAX_DECIMAL_SCALE);
                assertThatCode(() -> McpEnvelopeJsonCodecTestFixture.plainDecimalEncoder()
                                .writeValueAsBytes(atBoundary.value()))
                        .as("a decimal at the frozen scale bound must re-encode in plain form without throwing")
                        .doesNotThrowAnyException();
                assertThat(pastBoundary.isRejected())
                        .as("a decimal scale one past the frozen bound (10000) must be rejected")
                        .isTrue();
            }
            case NESTED_OVER_SCALE_ROW -> {
                // Given: an over-scaled decimal nested one level deep inside an object/array container,
                // exercising the tree walk itself rather than only a bare top-level decimal.
                byte[] frame = "{\"a\":[1e-10000]}".getBytes(StandardCharsets.UTF_8);

                assertThat(codec.decode(frame).isRejected())
                        .as("an over-scaled decimal nested inside a container must be rejected")
                        .isTrue();
            }
            case STRUCTURAL_CONSTRAINTS_ROW -> {
                // Given: the codec's own mapper, built from the default HttpConfig (matching production
                // wiring for every constraint except maxDocumentLength, proved separately).
                StreamReadConstraints constraints = codec.mapper().getFactory().streamReadConstraints();

                // DECISIVE: reads every one of the six frozen constraints back off the live parser
                // configuration, so a change to any of them — including the three (maxStringLength,
                // maxNameLength, maxTokenCount) that no other row exercises — fails this assertion.
                assertThat(constraints.getMaxNestingDepth())
                        .as("maxNestingDepth must stay frozen at 1000")
                        .isEqualTo(1_000);
                assertThat(constraints.getMaxNumberLength())
                        .as("maxNumberLength must stay frozen at 1000")
                        .isEqualTo(1_000);
                assertThat(constraints.getMaxStringLength())
                        .as("maxStringLength must stay frozen at 20,000,000")
                        .isEqualTo(20_000_000);
                assertThat(constraints.getMaxNameLength())
                        .as("maxNameLength must stay frozen at 50,000")
                        .isEqualTo(50_000);
                assertThat(constraints.getMaxDocumentLength())
                        .as("maxDocumentLength must equal the codec's configured HttpConfig#maxBodySize")
                        .isEqualTo(HttpConfig.builder().build().maxBodySize());
                assertThat(constraints.getMaxTokenCount())
                        .as("maxTokenCount must be derived as maxBodySize / 4 (issue #423), not left "
                                + "unlimited — see McpEnvelopeTokenBudgetTest for the full derivation and "
                                + "rejection-ordering proof")
                        .isEqualTo(HttpConfig.builder().build().maxBodySize() / 4);
            }
            default -> fail("unknown T007 contract row: " + row);
        }
    }

    /**
     * P03 item 5: proves {@code maxDocumentLength} is actually wired to the codec's configured {@link
     * HttpConfig#maxBodySize()} rather than being hard-coded or omitted. Every other test in this class
     * uses the default {@code HttpConfig}, whose 2 MB body limit no realistic document exercises; a
     * codec that ignored {@code maxDocumentLength} entirely would pass every other row.
     */
    @DisplayName("maxDocumentLength: a document at the configured cap decodes, one byte past it is rejected")
    @Test
    void shouldRejectOnlyPastTheConfiguredMaxDocumentLength() {
        long smallCap = 10;
        McpEnvelopeJsonCodec smallCapCodec = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(smallCap).build());

        // A 10-byte JSON string literal: two quote characters plus 8 filler bytes.
        McpEnvelopeJsonCodec.Result atCap = smallCapCodec.decode("\"aaaaaaaa\"".getBytes(StandardCharsets.UTF_8));
        // An 11-byte JSON string literal: one byte past the configured cap.
        McpEnvelopeJsonCodec.Result pastCap = smallCapCodec.decode("\"aaaaaaaaa\"".getBytes(StandardCharsets.UTF_8));

        assertThat(atCap.isRejected())
                .as("a document exactly at the configured maxDocumentLength (10 bytes) must be accepted")
                .isFalse();
        assertThat(pastCap.isRejected())
                .as("a document one byte past the configured maxDocumentLength (11 bytes) must be rejected")
                .isTrue();
    }

    /** Framework wiring for the T007 contract matrix: codec construction and boundary fixture bytes. */
    private static final class McpEnvelopeJsonCodecTestFixture {

        private McpEnvelopeJsonCodecTestFixture() {}

        /** A codec built from the default {@link HttpConfig}, matching production wiring. */
        static McpEnvelopeJsonCodec newCodec() {
            return new McpEnvelopeJsonCodec(HttpConfig.builder().build());
        }

        /**
         * A mapper mirroring the dispatcher's {@code OUTPUT_ENCODER} plain-form BigDecimal encoding, used
         * only to prove a decoded decimal at the scale boundary is safely re-encodable.
         */
        static ObjectMapper plainDecimalEncoder() {
            return JsonMapper.builder()
                    .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .build();
        }

        /**
         * Builds a JSON document nested {@code depth} arrays deep: {@code depth} opening brackets, one
         * scalar, then {@code depth} closing brackets.
         *
         * @param depth the exact nesting depth to build
         * @return the raw UTF-8 document bytes
         */
        static byte[] nestedArrayDocument(int depth) {
            return ("[".repeat(depth) + "1" + "]".repeat(depth)).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Builds a bare top-level numeric document with exactly {@code digitCount} digit characters.
         *
         * @param digitCount the exact lexical length of the number literal to build
         * @return the raw UTF-8 document bytes
         */
        static byte[] numberLiteralDocument(int digitCount) {
            return "9".repeat(digitCount).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Builds a well-formed-looking JSON object frame whose string value carries one invalid UTF-8
         * byte ({@code 0xFF}, never valid in any UTF-8 byte position).
         *
         * @return the raw bytes, not valid UTF-8
         */
        static byte[] invalidUtf8Document() {
            byte[] prefix = "{\"a\":\"".getBytes(StandardCharsets.UTF_8);
            byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[prefix.length + 1 + suffix.length];
            System.arraycopy(prefix, 0, frame, 0, prefix.length);
            frame[prefix.length] = (byte) 0xFF;
            System.arraycopy(suffix, 0, frame, prefix.length + 1, suffix.length);
            return frame;
        }

        /**
         * Builds a bare JSON string literal whose content is exactly {@code rawBytes}, unescaped —
         * i.e. {@code "} + {@code rawBytes} + {@code "}. Used to embed a specific raw byte sequence (an
         * overlong UTF-8 encoding or a lone surrogate) directly into a string token's content.
         *
         * @param rawBytes the raw bytes to embed between the quotes
         * @return the raw frame bytes
         */
        static byte[] embeddedBytesDocument(byte[] rawBytes) {
            byte[] quote = "\"".getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[quote.length + rawBytes.length + quote.length];
            System.arraycopy(quote, 0, frame, 0, quote.length);
            System.arraycopy(rawBytes, 0, frame, quote.length, rawBytes.length);
            System.arraycopy(quote, 0, frame, quote.length + rawBytes.length, quote.length);
            return frame;
        }
    }
}

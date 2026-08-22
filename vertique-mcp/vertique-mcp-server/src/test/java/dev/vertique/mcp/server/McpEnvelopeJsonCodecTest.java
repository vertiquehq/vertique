// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.config.HttpConfig;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 — pins the T007 {@link McpEnvelopeJsonCodec} contract matrix: a well-formed envelope decodes,
 * and every named boundary violation — duplicate keys, trailing tokens, over-deep nesting, an
 * over-long number literal, and invalid UTF-8 — maps to the same bounded rejection. The depth and
 * number-length rows assert the frozen boundary from both sides in the same row: exactly 1000 is
 * accepted, exactly 1001 is rejected. No row consults an MCP configuration key because none exists —
 * every bound is Jackson's own frozen {@code StreamReadConstraints} inside the codec.
 */
class McpEnvelopeJsonCodecTest {

    private static final String WELL_FORMED_ROW = "shouldDecodeAWellFormedEnvelope";
    private static final String DUPLICATE_KEY_ROW = "shouldRejectDuplicateObjectKeys";
    private static final String TRAILING_TOKENS_ROW = "shouldRejectTrailingTokens";
    private static final String OVER_DEEP_ROW = "shouldRejectOverDeepDocumentsAtTheFrozenNestingDepth";
    private static final String OVER_LONG_NUMBER_ROW = "shouldRejectOverLongNumberLiteralsAtTheFrozenNumberLength";
    private static final String INVALID_UTF8_ROW = "shouldRejectInvalidUtf8";

    /** The frozen {@code maxNestingDepth} and {@code maxNumberLength} boundary (T007 contract). */
    private static final int FROZEN_BOUNDARY = 1_000;

    private final McpEnvelopeJsonCodec codec = McpEnvelopeJsonCodecTestFixture.newCodec();

    private static Stream<String> contractRows() {
        return Stream.of(
                WELL_FORMED_ROW,
                DUPLICATE_KEY_ROW,
                TRAILING_TOKENS_ROW,
                OVER_DEEP_ROW,
                OVER_LONG_NUMBER_ROW,
                INVALID_UTF8_ROW);
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
                // Given: a complete envelope followed by a trailing token.
                byte[] frame = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"} trailing")
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
            default -> fail("unknown T007 contract row: " + row);
        }
    }

    /** Framework wiring for the T007 contract matrix: codec construction and boundary fixture bytes. */
    private static final class McpEnvelopeJsonCodecTestFixture {

        private McpEnvelopeJsonCodecTestFixture() {}

        /** A codec built from the default {@link HttpConfig}, matching production wiring. */
        static McpEnvelopeJsonCodec newCodec() {
            return new McpEnvelopeJsonCodec(HttpConfig.builder().build());
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
    }
}

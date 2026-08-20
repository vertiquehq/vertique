// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-002 — pins the strict reader's contract matrix.
 *
 * <p>One valid request parses to a value; each adjacent invalid frame — duplicate keys, trailing
 * tokens, over-limit depth, invalid UTF-8 bytes, over-limit string, over-limit array, and over-limit
 * property count — returns its specific bounded {@link McpStrictJsonReader.Rejection} and no partial
 * value; and the exact-precision integer and decimal survive the round-trip so downstream schema
 * validation sees the value the client sent.
 */
class McpStrictJsonReaderTest {

    /** The exact 64-bit-overflowing integer that a {@code double} would corrupt. */
    private static final BigInteger EXACT_INTEGER = new BigInteger("9007199254740993");

    /** The exact decimal that a {@code double} would corrupt to {@code 0.1}. */
    private static final BigDecimal EXACT_DECIMAL = new BigDecimal("0.10000000000000001");

    @Test
    @DisplayName("the strict reader enforces every configured limit and preserves exact numeric precision")
    void shouldEnforceT003ContractMatrix() {
        McpStrictJsonReader reader = McpStrictJsonReaderTestFixture.boundedReader();

        for (McpStrictJsonReaderTestFixture.Row row : McpStrictJsonReaderTestFixture.contractRows()) {
            McpStrictJsonReader.Result result = reader.read(row.frame());
            assertThat(result.rejection())
                    .as("row %s returns its specific bounded rejection", row.name())
                    .isEqualTo(row.expected());
            if (row.expected() != null) {
                assertThat(result.value())
                        .as("rejected row %s carries no partial value", row.name())
                        .isNull();
            }
        }

        assertExactPrecision(reader);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.vertique.mcp.server.McpStrictJsonReaderTest#contractRowArguments")
    @DisplayName("reject duplicate keys, trailing tokens, and every configured limit")
    void shouldRejectDuplicateKeysTrailingTokensAndEveryConfiguredLimit(
            String name, byte[] frame, McpStrictJsonReader.Rejection expected) {
        McpStrictJsonReader.Result result =
                McpStrictJsonReaderTestFixture.boundedReader().read(frame);
        assertThat(result.rejection())
                .as("row %s returns its specific bounded rejection", name)
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.vertique.mcp.server.McpStrictJsonReaderTest#precisionArguments")
    @DisplayName("preserve integer and decimal precision for schema validation")
    void shouldPreserveIntegerAndDecimalPrecisionForSchemaValidation(String name, String frame, Number expected) {
        McpStrictJsonReader.Result result =
                McpStrictJsonReaderTestFixture.boundedReader().read(frame.getBytes(StandardCharsets.UTF_8));
        assertThat(result.isRejected())
                .as("row %s parses without rejection", name)
                .isFalse();
        JsonNode value = result.value();
        if (expected instanceof BigInteger) {
            assertThat(value.bigIntegerValue())
                    .as("row %s preserves the exact integer", name)
                    .isEqualTo(expected);
        } else {
            assertThat(value.decimalValue())
                    .as("row %s preserves the exact decimal", name)
                    .isEqualByComparingTo((BigDecimal) expected);
        }
    }

    private static void assertExactPrecision(McpStrictJsonReader reader) {
        McpStrictJsonReader.Result integer = reader.read("9007199254740993".getBytes(StandardCharsets.UTF_8));
        assertThat(integer.isRejected())
                .as("the exact integer parses without rejection")
                .isFalse();
        assertThat(integer.value().bigIntegerValue())
                .as("the exact integer survives the round-trip")
                .isEqualTo(EXACT_INTEGER);

        McpStrictJsonReader.Result decimal = reader.read("0.10000000000000001".getBytes(StandardCharsets.UTF_8));
        assertThat(decimal.isRejected())
                .as("the exact decimal parses without rejection")
                .isFalse();
        assertThat(decimal.value().decimalValue())
                .as("the exact decimal survives the round-trip")
                .isEqualByComparingTo(EXACT_DECIMAL);
    }

    static Stream<Arguments> contractRowArguments() {
        return McpStrictJsonReaderTestFixture.contractRows().stream()
                .map(row -> Arguments.of(row.name(), row.frame(), row.expected()));
    }

    static Stream<Arguments> precisionArguments() {
        return Stream.of(
                Arguments.of("integer9007199254740993", "9007199254740993", EXACT_INTEGER),
                Arguments.of("decimal0_10000000000000001", "0.10000000000000001", EXACT_DECIMAL));
    }

    /** Framework wiring for the matrix proof: the bounded reader and the literal contract rows. */
    private static final class McpStrictJsonReaderTestFixture {

        private McpStrictJsonReaderTestFixture() {}

        /** A reader whose limits are small enough that each boundary frame stays a short literal. */
        static McpStrictJsonReader boundedReader() {
            McpServerConfig config = McpServerConfig.builder()
                    .jsonMaxDepth(8)
                    .jsonMaxPropertiesPerObject(3)
                    .jsonMaxItemsPerArray(3)
                    .jsonMaxStringChars(5)
                    .build();
            return new McpStrictJsonReader(config);
        }

        static List<Row> contractRows() {
            return List.of(
                    new Row("validRequest", utf8("{\"a\":1}"), null),
                    new Row("duplicateKeys", utf8("{\"a\":1,\"a\":2}"), McpStrictJsonReader.Rejection.DUPLICATE_KEY),
                    new Row("trailingTokens", utf8("{\"a\":1} x"), McpStrictJsonReader.Rejection.TRAILING_TOKENS),
                    new Row("depthOverLimit", utf8("[[[[[[[[[1]]]]]]]]]"), McpStrictJsonReader.Rejection.MAX_DEPTH),
                    new Row(
                            "propertiesOverLimit",
                            utf8("{\"a\":1,\"b\":2,\"c\":3,\"d\":4}"),
                            McpStrictJsonReader.Rejection.MAX_PROPERTIES),
                    new Row("itemsOverLimit", utf8("[1,2,3,4]"), McpStrictJsonReader.Rejection.MAX_ITEMS),
                    new Row("stringOverLimit", utf8("\"abcdef\""), McpStrictJsonReader.Rejection.MAX_STRING_CHARS),
                    new Row(
                            "invalidUtf8Byte",
                            new byte[] {
                                (byte) '{', (byte) '"', (byte) 'a', (byte) '"', (byte) ':', (byte) 0xFF, (byte) '}'
                            },
                            McpStrictJsonReader.Rejection.INVALID_UTF8));
        }

        private static byte[] utf8(String literal) {
            return literal.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * One contract-matrix row.
         *
         * @param name the row name
         * @param frame the literal UTF-8 frame bytes
         * @param expected the expected bounded rejection, or {@code null} for the valid anchor
         */
        record Row(String name, byte[] frame, McpStrictJsonReader.Rejection expected) {}
    }
}

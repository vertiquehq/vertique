// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins D008's unsigned cursor structure: exactly protocol version, registry digest, and a
 * syntactically valid lexicographic anchor. The codec validates syntax and canonical structure,
 * never registry membership.
 */
class McpCursorCodecTest {

    private static final String ROUND_TRIP_ROW = "shouldRoundTripTheCanonicalThreeFieldCursor";
    private static final String WRONG_VERSION_ROW = "shouldRejectAWrongProtocolVersion";
    private static final String STALE_DIGEST_ROW = "shouldRejectAStaleRegistryDigest";
    private static final String NONMEMBER_ANCHOR_ROW = "shouldAcceptASyntacticallyValidNonmemberAnchor";
    private static final String MALFORMED_ANCHOR_ROW = "shouldRejectAMalformedAnchor";
    private static final String NONCANONICAL_STRUCTURE_ROW = "shouldRejectANoncanonicalFieldSet";
    private static final String BOUND_BEFORE_PARSE_ROW = "shouldBoundDecodedBytesBeforeJsonParsing";

    private static final String REGISTRY_DIGEST = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2";
    private static final String OTHER_DIGEST = "0".repeat(64);
    private static final String ANCHOR = "example.tool";
    private static final String NONMEMBER_ANCHOR = "example.position-hint";

    private static Stream<String> d008ContractRows() {
        return Stream.of(
                ROUND_TRIP_ROW,
                WRONG_VERSION_ROW,
                STALE_DIGEST_ROW,
                NONMEMBER_ANCHOR_ROW,
                MALFORMED_ANCHOR_ROW,
                NONCANONICAL_STRUCTURE_ROW,
                BOUND_BEFORE_PARSE_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("d008ContractRows")
    @DisplayName("D008 cursor matrix: canonical structure, bounded decoding, and membership-free anchors")
    void shouldEnforceD008CursorContract(String row) {
        switch (row) {
            case ROUND_TRIP_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                String token = buildToken(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, ANCHOR);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST);
                assertThat(decoded.isInvalid()).isFalse();
                assertThat(decoded.anchor()).isEqualTo(ANCHOR);
                assertNoSignatureExpiryOrRetiredStateMember(token);
            }
            case WRONG_VERSION_ROW -> assertInvalid(buildToken("2020-01-01", REGISTRY_DIGEST, ANCHOR));
            case STALE_DIGEST_ROW -> assertInvalid(buildToken(McpCursorCodec.PROTOCOL_VERSION, OTHER_DIGEST, ANCHOR));
            case NONMEMBER_ANCHOR_ROW -> {
                McpCursorCodec.Decoded decoded = new McpCursorCodec()
                        .decode(
                                buildToken(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, NONMEMBER_ANCHOR),
                                REGISTRY_DIGEST);

                assertThat(decoded.isInvalid())
                        .as("a valid forged anchor is a position hint, not a registry-membership oracle")
                        .isFalse();
                assertThat(decoded.anchor()).isEqualTo(NONMEMBER_ANCHOR);
            }
            case MALFORMED_ANCHOR_ROW ->
                assertInvalid(buildToken(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, "not valid"));
            case NONCANONICAL_STRUCTURE_ROW ->
                assertInvalid(buildRawToken(
                        "{\"protocolVersion\":\"" + McpCursorCodec.PROTOCOL_VERSION + "\",\"registryDigest\":\""
                                + REGISTRY_DIGEST + "\",\"lastScannedToolName\":\"" + ANCHOR + "\",\"attempts\":0}"));
            case BOUND_BEFORE_PARSE_ROW -> assertByteBoundBeforeParsing();
            default -> fail("unknown D008 cursor matrix row: " + row);
        }
    }

    private static void assertInvalid(String token) {
        McpCursorCodec.Decoded decoded = new McpCursorCodec().decode(token, REGISTRY_DIGEST);
        assertThat(decoded.isInvalid()).isTrue();
        assertThat(decoded.anchor()).isNull();
    }

    private static void assertByteBoundBeforeParsing() {
        assertEncodedBoundary(63, 84);
        assertEncodedBoundary(64, 86);
        assertEncodedBoundary(65, 87);
        assertEncodedBoundary(2_048, 2_731);

        CountingObjectMapper counting = new CountingObjectMapper();
        McpCursorCodec withinBound = new McpCursorCodec(2_048, counting);
        assertThat(withinBound
                        .decode(buildToken(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, ANCHOR), REGISTRY_DIGEST)
                        .isInvalid())
                .isFalse();
        assertThat(counting.readTreeCount()).isEqualTo(1);
    }

    private static void assertEncodedBoundary(int maxDecodedBytes, int expectedMaxEncodedChars) {
        String atEncodedBound = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[maxDecodedBytes]);
        String overLongToken = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[maxDecodedBytes + 1]);
        assertThat(atEncodedBound).hasSize(expectedMaxEncodedChars);
        assertThat(overLongToken.length()).isGreaterThan(expectedMaxEncodedChars);

        CountingObjectMapper counting = new CountingObjectMapper();
        AtomicInteger decodeCount = new AtomicInteger();
        McpCursorCodec bounded = new McpCursorCodec(maxDecodedBytes, counting, token -> {
            decodeCount.incrementAndGet();
            return Base64.getUrlDecoder().decode(token);
        });

        assertThat(bounded.decode(overLongToken, REGISTRY_DIGEST).isInvalid()).isTrue();
        assertThat(decodeCount)
                .as("an encoded token beyond the exact unpadded-base64 maximum must not be decoded")
                .hasValue(0);
        assertThat(counting.readTreeCount())
                .as("an over-long cursor must not reach the JSON parser")
                .isZero();

        assertThat(bounded.decode(atEncodedBound, REGISTRY_DIGEST).isInvalid()).isTrue();
        assertThat(decodeCount)
                .as("the exact encoded boundary remains eligible for decoding")
                .hasValue(1);
        assertThat(counting.readTreeCount()).isEqualTo(1);
    }

    private static void assertNoSignatureExpiryOrRetiredStateMember(String token) {
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token));
        } catch (IOException failure) {
            throw new AssertionError("encoded cursor must be well-formed JSON", failure);
        }
        assertThat(node.isObject()).isTrue();
        java.util.List<String> fieldNames = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames)
                .as("a D008 cursor carries exactly protocolVersion, registryDigest, and lastScannedToolName")
                .containsExactly("protocolVersion", "registryDigest", "lastScannedToolName");
    }

    private static String buildToken(String protocolVersion, String registryDigest, String lastScannedToolName) {
        return buildRawToken("{\"protocolVersion\":\"" + protocolVersion + "\",\"registryDigest\":\"" + registryDigest
                + "\",\"lastScannedToolName\":\"" + lastScannedToolName + "\"}");
    }

    private static String buildRawToken(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Counts {@link #readTree(byte[])} invocations to prove the byte cap precedes JSON parsing. */
    private static final class CountingObjectMapper extends ObjectMapper {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public JsonNode readTree(byte[] content) throws IOException {
            count.incrementAndGet();
            return super.readTree(content);
        }

        int readTreeCount() {
            return count.get();
        }
    }
}

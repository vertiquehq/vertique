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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T011 unsigned, non-expiring {@code tools/list} cursor contract owned by {@link
 * McpCursorCodec} (§4.7): protocol version, registry digest, and anchor validation, the
 * before-parsing decoded-byte bound, and the R14 item 2 forward-progress counter's own range.
 *
 * <p>Every row encodes or hand-builds one cursor over the same literal fixture — protocol version
 * {@code 2026-07-28}, {@link #REGISTRY_DIGEST}, and anchor {@link #ANCHOR} — and decodes it exactly
 * once, isolating one boundary. Every invalid row collapses to the same {@link
 * McpCursorCodec.Decoded#isInvalid()} outcome carrying no anchor, so a caller mapping it to the wire
 * can never distinguish which validation failed (T005's carried {@code -32602} indistinguishability
 * obligation).
 */
class McpCursorCodecTest {

    private static final String ROUND_TRIP_ROW = "shouldRoundTripAValidCursor";
    private static final String WRONG_VERSION_ROW = "shouldRejectAWrongProtocolVersion";
    private static final String STALE_DIGEST_ROW = "shouldRejectAStaleRegistryDigest";
    private static final String OUT_OF_RANGE_ANCHOR_ROW = "shouldRejectAnOutOfRangeAnchor";
    private static final String BOUND_BEFORE_PARSE_ROW = "shouldBoundDecodedBytesBeforeJsonParsing";
    private static final String MISSING_ATTEMPTS_ROW = "shouldRejectACursorWithNoAttemptsCounter";
    private static final String OUT_OF_RANGE_ATTEMPTS_ROW = "shouldRejectAnOutOfRangeAttemptsCounter";
    private static final String NEGATIVE_ATTEMPTS_ROW = "shouldRejectANegativeAttemptsCounter";

    private static final String REGISTRY_DIGEST = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2";
    private static final String OTHER_DIGEST = "0".repeat(64);
    private static final String ANCHOR = "example.tool";
    private static final Set<String> VALID_ANCHORS = Set.of(ANCHOR, "other.tool");

    private static Stream<String> t011ContractRows() {
        return Stream.of(
                ROUND_TRIP_ROW,
                WRONG_VERSION_ROW,
                STALE_DIGEST_ROW,
                OUT_OF_RANGE_ANCHOR_ROW,
                BOUND_BEFORE_PARSE_ROW,
                MISSING_ATTEMPTS_ROW,
                OUT_OF_RANGE_ATTEMPTS_ROW,
                NEGATIVE_ATTEMPTS_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t011ContractRows")
    @DisplayName("T011 cursor matrix: unsigned, non-expiring validation and the before-parse byte bound")
    void shouldEnforceT011ContractMatrix(String row) {
        switch (row) {
            case ROUND_TRIP_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                String token = codec.encode(ANCHOR, REGISTRY_DIGEST, 1);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(decoded.isInvalid())
                        .as("a freshly encoded cursor must round-trip")
                        .isFalse();
                assertThat(decoded.anchor()).isEqualTo(ANCHOR);
                assertThat(decoded.attempts())
                        .as("R14 item 2: the non-progressing-page count must survive the round trip — a "
                                + "counter that reset itself on every hop would never reach its bound")
                        .isEqualTo(1);

                // DECISIVE: unsigned and non-expiring is proven by inspecting the encoded shape itself,
                // not merely a successful round trip — a signed or expiring implementation would still
                // pass a plain round-trip assertion.
                assertNoSignatureOrExpiryMember(token);
            }
            case WRONG_VERSION_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                String token = buildToken("2020-01-01", REGISTRY_DIGEST, ANCHOR);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(decoded.isInvalid())
                        .as("a wrong protocol version must be rejected")
                        .isTrue();
                assertThat(decoded.anchor())
                        .as("an invalid decode must carry no anchor")
                        .isNull();
            }
            case STALE_DIGEST_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                // SENSITIVITY PROOF (TP-002): the digest literal on the next line is the mutation
                // point. Replacing only it with REGISTRY_DIGEST must flip isInvalid() true→false and
                // the decoded anchor null→ANCHOR. Restore before recording green evidence.
                String staleDigest = OTHER_DIGEST;
                String token = buildToken(McpCursorCodec.PROTOCOL_VERSION, staleDigest, ANCHOR);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(decoded.isInvalid())
                        .as("a stale registry digest must be rejected")
                        .isTrue();
                assertThat(decoded.anchor()).isNull();
            }
            case OUT_OF_RANGE_ANCHOR_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                String token = buildToken(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, "not-in-registry.tool");

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(decoded.isInvalid())
                        .as("an anchor absent from the current registry must be rejected")
                        .isTrue();
                assertThat(decoded.anchor()).isNull();
            }
            case BOUND_BEFORE_PARSE_ROW -> {
                int smallBound = 64;
                String overLongPayload = "{\"padding\":\"" + "a".repeat(500) + "\"}"; // decodes far past smallBound
                String overLongToken = Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(overLongPayload.getBytes(StandardCharsets.UTF_8));

                // Same real production bound must reject it too, not just the instrumented instance.
                McpCursorCodec production = new McpCursorCodec();
                assertThat(production
                                .decode(overLongToken, REGISTRY_DIGEST, VALID_ANCHORS)
                                .isInvalid())
                        .as("the production codec must reject an over-long cursor")
                        .isTrue();

                CountingObjectMapper counting = new CountingObjectMapper();
                McpCursorCodec bounded = new McpCursorCodec(smallBound, counting);
                McpCursorCodec.Decoded overLongResult = bounded.decode(overLongToken, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(overLongResult.isInvalid()).isTrue();
                // DECISIVE: proves ordering, not merely rejection — the parser must never be invoked
                // for a payload that exceeds the byte bound.
                assertThat(counting.readTreeCount())
                        .as("an over-long cursor must never reach the JSON parser")
                        .isZero();

                // The counting harness is not vacuously always-zero: a normal, real-size cursor DOES
                // reach the parser through the same instrumented mapper once it is within bound (a
                // real digest alone already exceeds smallBound, so a realistically sized bound is used
                // here — only the byte-length cap differs, the mapper instance is the same one that
                // just proved zero invocations above).
                McpCursorCodec realisticallyBounded = new McpCursorCodec(2_048, counting);
                String validToken = realisticallyBounded.encode(ANCHOR, REGISTRY_DIGEST, 0);
                McpCursorCodec.Decoded validResult =
                        realisticallyBounded.decode(validToken, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(validResult.isInvalid()).isFalse();
                assertThat(counting.readTreeCount())
                        .as("a within-bound cursor must reach the parser exactly once")
                        .isEqualTo(1);
            }
            case MISSING_ATTEMPTS_ROW -> {
                // R14 item 2: a cursor with no attempts counter cannot be counted, so following it
                // forever would be indistinguishable from progress. Rejected like every other malformed
                // cursor — the client simply restarts pagination from the beginning.
                McpCursorCodec codec = new McpCursorCodec();
                String token = buildTokenWithoutAttempts(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, ANCHOR);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(decoded.isInvalid())
                        .as("a cursor carrying no attempts counter must be rejected")
                        .isTrue();
                assertThat(decoded.anchor()).isNull();
            }
            case OUT_OF_RANGE_ATTEMPTS_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                String token = buildToken(
                        McpCursorCodec.PROTOCOL_VERSION,
                        REGISTRY_DIGEST,
                        ANCHOR,
                        McpCursorCodec.MAX_NON_PROGRESSING_PAGES);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                assertThat(decoded.isInvalid())
                        .as("this codec never issues a counter at or past the bound, so one that arrives "
                                + "is forged and must be rejected")
                        .isTrue();
                assertThat(decoded.anchor()).isNull();
            }
            case NEGATIVE_ATTEMPTS_ROW -> {
                McpCursorCodec codec = new McpCursorCodec();
                String token = buildToken(McpCursorCodec.PROTOCOL_VERSION, REGISTRY_DIGEST, ANCHOR, Integer.MIN_VALUE);

                McpCursorCodec.Decoded decoded = codec.decode(token, REGISTRY_DIGEST, VALID_ANCHORS);
                // DECISIVE: this is the range check that actually matters. A tampered negative counter
                // increments forever without ever reaching MAX_NON_PROGRESSING_PAGES, which would
                // reinstate exactly the unbounded client loop R14 item 2 exists to close — accepting it
                // would leave the bound present in the code and absent in effect.
                assertThat(decoded.isInvalid())
                        .as("DECISIVE: a negative attempts counter would defeat the forward-progress "
                                + "bound and must be rejected")
                        .isTrue();
                assertThat(decoded.anchor()).isNull();
            }
            default -> fail("unknown T011 cursor matrix row: " + row);
        }
    }

    /**
     * Independently base64url-decodes and parses {@code token}, asserting it carries exactly the
     * three documented fields — no signature, HMAC, or expiry member — proving unsigned and
     * non-expiring by shape rather than by round-trip behavior alone.
     */
    private static void assertNoSignatureOrExpiryMember(String token) {
        byte[] decoded = Base64.getUrlDecoder().decode(token);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node;
        try {
            node = mapper.readTree(decoded);
        } catch (IOException failure) {
            throw new AssertionError("encoded cursor must be well-formed JSON", failure);
        }
        assertThat(node.isObject()).isTrue();
        java.util.List<String> fieldNames = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames)
                .as("an unsigned, non-expiring cursor carries exactly protocolVersion, digest, anchor, and "
                        + "the R14 item 2 attempts counter — no signature, HMAC, or expiry member")
                .containsExactlyInAnyOrder("protocolVersion", "digest", "anchor", "attempts");
    }

    /** Hand-builds a base64url cursor token from arbitrary field values, bypassing production encode(). */
    private static String buildToken(String protocolVersion, String digest, String anchor) {
        return buildToken(protocolVersion, digest, anchor, 0);
    }

    /** As {@link #buildToken(String, String, String)}, with an arbitrary — possibly forged — attempts counter. */
    private static String buildToken(String protocolVersion, String digest, String anchor, int attempts) {
        String json = "{\"protocolVersion\":\"" + protocolVersion + "\",\"digest\":\"" + digest + "\",\"anchor\":\""
                + anchor + "\",\"attempts\":" + attempts + "}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Hand-builds a pre-R14 cursor token: the three original fields and no attempts counter. */
    private static String buildTokenWithoutAttempts(String protocolVersion, String digest, String anchor) {
        String json = "{\"protocolVersion\":\"" + protocolVersion + "\",\"digest\":\"" + digest + "\",\"anchor\":\""
                + anchor + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Counts every {@link #readTree(byte[])} invocation, to prove the before-parse byte bound's ordering. */
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

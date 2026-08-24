// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.mcp.tool.McpToolDescriptor;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Encodes and decodes the unsigned, non-expiring {@code tools/list} pagination cursor (§4.7).
 *
 * <p>A cursor is canonical compact JSON with exactly {@code protocolVersion}, {@code registryDigest},
 * and {@code lastScannedToolName}, in that order, base64url-encoded without padding. It carries no
 * signature, HMAC, expiry, membership proof, or retry state: every resumed candidate is reauthorized,
 * and the immutable registry digest invalidates a cursor across deployments. Pagination is not an
 * authority boundary, so a cursor secret is disproportionate.
 *
 * <p>{@link #decode} bounds the encoded token before Base64 decoding and decoded bytes before JSON
 * parsing. It accepts an anchor only when it is a syntactically valid tool name. It deliberately does
 * not require that name to be in the current registry: the anchor is only a lexicographic position
 * hint, so membership validation would expose a registry-name oracle. Every rejection collapses to
 * {@link Decoded#isInvalid()} with no detail, so callers emit the same indistinguishable {@code
 * -32602} response.
 *
 * <p>Not part of the application-facing public surface: package-private per the frozen artifact
 * inventory, matching {@link McpToolRegistry} and {@link McpSchemaRegistry}.
 */
final class McpCursorCodec {

    /** The one frozen final-2026 protocol version every valid cursor must carry. */
    static final String PROTOCOL_VERSION = "2026-07-28";

    /** The upper bound on decoded cursor bytes, checked before JSON parsing. */
    private static final int MAX_DECODED_BYTES = 2_048;

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    private static final String FIELD_REGISTRY_DIGEST = "registryDigest";
    private static final String FIELD_LAST_SCANNED_TOOL_NAME = "lastScannedToolName";
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private final int maxDecodedBytes;
    private final long maxEncodedChars;
    private final ObjectMapper mapper;
    private final Base64UrlDecoder decoder;

    /** Creates the production codec with the frozen byte bound and a plain Jackson mapper. */
    McpCursorCodec() {
        this(MAX_DECODED_BYTES, DEFAULT_MAPPER);
    }

    /**
     * Test-only seam: lets a proof substitute a smaller bound and/or an instrumented mapper to prove
     * the byte-length bound is enforced before JSON parsing.
     *
     * @param maxDecodedBytes the decoded-byte cap to enforce before parsing
     * @param mapper the mapper used for canonical encoding and bounded-tree parsing
     */
    McpCursorCodec(int maxDecodedBytes, ObjectMapper mapper) {
        this(maxDecodedBytes, mapper, Base64.getUrlDecoder()::decode);
    }

    /** Test-only seam: permits proving that the encoded-length guard runs before Base64 decoding. */
    McpCursorCodec(int maxDecodedBytes, ObjectMapper mapper, Base64UrlDecoder decoder) {
        if (maxDecodedBytes < 1) {
            throw new IllegalArgumentException("maxDecodedBytes must be positive");
        }
        this.maxDecodedBytes = maxDecodedBytes;
        this.maxEncodedChars = maxUnpaddedBase64UrlChars(maxDecodedBytes);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Encodes an unsigned, non-expiring cursor naming the last examined candidate.
     *
     * @param lastScannedToolName the last candidate examined; must be a syntactically valid tool name
     * @param registryDigest the current immutable SHA-256 registry digest
     * @return the opaque, canonical, base64url-encoded cursor token without padding
     * @throws IllegalArgumentException if either value is outside the cursor grammar
     */
    String encode(String lastScannedToolName, String registryDigest) {
        requireValidAnchor(lastScannedToolName);
        requireValidDigest(registryDigest);
        return serializeCanonicalNode(canonicalNode(lastScannedToolName, registryDigest), "cursor encoding failed");
    }

    /**
     * Strictly decodes and validates one cursor token without checking anchor membership.
     *
     * @param cursor the opaque cursor token to decode; must not be {@code null}
     * @param registryDigest the current immutable registry digest a valid cursor must match
     * @return the decoded last scanned tool name, or an invalid result carrying no name
     */
    Decoded decode(String cursor, String registryDigest) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(registryDigest, "registryDigest");
        if (cursor.length() > maxEncodedChars || !isCanonicalBase64Url(cursor)) {
            return Decoded.invalid();
        }
        byte[] decodedBytes;
        try {
            decodedBytes = decoder.decode(cursor);
        } catch (IllegalArgumentException invalidBase64) {
            return Decoded.invalid();
        }
        if (decodedBytes.length > maxDecodedBytes) {
            return Decoded.invalid();
        }
        JsonNode node;
        try {
            node = mapper.readTree(decodedBytes);
        } catch (IOException malformed) {
            return Decoded.invalid();
        }
        if (node == null || !node.isObject() || node.size() != 3) {
            return Decoded.invalid();
        }
        String version = textField(node, FIELD_PROTOCOL_VERSION);
        String digest = textField(node, FIELD_REGISTRY_DIGEST);
        String lastScannedToolName = textField(node, FIELD_LAST_SCANNED_TOOL_NAME);
        if (!PROTOCOL_VERSION.equals(version)
                || !isValidDigest(digest)
                || !registryDigest.equals(digest)
                || !McpToolDescriptor.isValidName(lastScannedToolName)
                || !cursor.equals(canonicalToken(lastScannedToolName, digest))) {
            return Decoded.invalid();
        }
        return Decoded.ok(lastScannedToolName);
    }

    /** Builds the exactly ordered canonical JSON node. */
    private ObjectNode canonicalNode(String lastScannedToolName, String registryDigest) {
        ObjectNode node = mapper.createObjectNode();
        node.put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION);
        node.put(FIELD_REGISTRY_DIGEST, registryDigest);
        node.put(FIELD_LAST_SCANNED_TOOL_NAME, lastScannedToolName);
        return node;
    }

    /** Re-encodes a validated node to compare its original wire representation with the canonical one. */
    private String canonicalToken(String lastScannedToolName, String registryDigest) {
        return serializeCanonicalNode(
                canonicalNode(lastScannedToolName, registryDigest), "cursor canonicalization failed");
    }

    /** Serializes one canonical node as the cursor's unpadded base64url token. */
    private String serializeCanonicalNode(ObjectNode node, String failureMessage) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(node));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(failureMessage, impossible);
        }
    }

    /** Reports whether {@code cursor} uses the one unpadded base64url spelling. */
    private static boolean isCanonicalBase64Url(String cursor) {
        return !cursor.isEmpty()
                && cursor.length() % 4 != 1
                && cursor.chars()
                        .allMatch(character ->
                                Character.isLetterOrDigit(character) || character == '-' || character == '_');
    }

    /** Returns the exact maximum unpadded-base64 length for a decoded byte budget. */
    private static long maxUnpaddedBase64UrlChars(int maxDecodedBytes) {
        long completeQuanta = maxDecodedBytes / 3L;
        int remainder = maxDecodedBytes % 3;
        return completeQuanta * 4L
                + switch (remainder) {
                    case 0 -> 0L;
                    case 1 -> 2L;
                    case 2 -> 3L;
                    default -> throw new AssertionError("unreachable remainder");
                };
    }

    /** Reports whether {@code digest} has the lower-case hexadecimal SHA-256 grammar. */
    private static boolean isValidDigest(@Nullable String digest) {
        return digest != null && SHA_256_HEX.matcher(digest).matches();
    }

    /** Rejects an invalid digest supplied to the encoder. */
    private static void requireValidDigest(String digest) {
        if (!isValidDigest(digest)) {
            throw new IllegalArgumentException("registryDigest must be a lower-case SHA-256 hex digest");
        }
    }

    /** Rejects an invalid anchor supplied to the encoder. */
    private static void requireValidAnchor(String lastScannedToolName) {
        if (!McpToolDescriptor.isValidName(lastScannedToolName)) {
            throw new IllegalArgumentException("lastScannedToolName must be a valid tool name");
        }
    }

    @Nullable
    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * The outcome of a cursor decode: either a valid last scanned tool name or an invalid result with
     * no distinguishing rejection detail.
     *
     * @param anchor the decoded last scanned tool name, or {@code null} when invalid
     * @param isInvalid whether the cursor failed validation
     */
    record Decoded(@Nullable String anchor, boolean isInvalid) {

        /**
         * Wraps a successfully decoded last scanned tool name.
         *
         * @param anchor the decoded last scanned tool name
         * @return a valid decode
         */
        static Decoded ok(String anchor) {
            return new Decoded(anchor, false);
        }

        /**
         * Wraps an invalid cursor, carrying no anchor or rejection detail.
         *
         * @return an invalid decode
         */
        static Decoded invalid() {
            return new Decoded(null, true);
        }
    }

    /** Decodes one unpadded base64url token after its encoded length and alphabet are accepted. */
    @FunctionalInterface
    interface Base64UrlDecoder {
        byte[] decode(String cursor);
    }
}

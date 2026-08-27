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
 * <p><strong>Two-form anchor grammar (R40/C5).</strong> The {@code lastScannedToolName} field carries
 * exactly one of two mutually exclusive, syntactically disjoint forms — disjoint because the tool-name
 * grammar {@code [A-Za-z0-9_.-]{1,128}} never contains {@code '#'}:
 *
 * <ul>
 *   <li><strong>Name form</strong> — a syntactically valid tool name ({@link
 *       McpToolDescriptor#isValidName}), used when a page fills by reaching {@code pageSize} or the
 *       registry is exhausted. It is only a lexicographic position hint: the next page resumes at the
 *       first registry name strictly greater than it, and it need not be a current registry member.
 *   <li><strong>Position form</strong> — {@code "#"} followed by a bounded non-negative decimal index
 *       (no leading zero unless the value is exactly {@code "0"}, at most 10 digits, in {@code int}
 *       range), used only when a page's examination budget exhausts before the page fills. It encodes
 *       the index of the <em>next unexamined</em> candidate in the digest-pinned registry order — never
 *       the last examined candidate — so a denied candidate examined right at the budget boundary is
 *       never named in the cursor. The registry digest binding guarantees the index refers to the same
 *       registry order on resume.
 * </ul>
 *
 * <p>{@link #decode} bounds the encoded token before Base64 decoding and decoded bytes before JSON
 * parsing. It accepts an anchor only when it satisfies one of the two forms above. For the name form it
 * deliberately does not require that name to be in the current registry: membership validation would
 * expose a registry-name oracle. Every rejection collapses to {@link Decoded#isInvalid()} with no
 * detail, so callers emit the same indistinguishable {@code -32602} response.
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

    /** Prefix marking the opaque scan-position anchor form (R40/C5); never a valid tool-name character. */
    private static final String POSITION_ANCHOR_PREFIX = "#";

    /** Bounded decimal grammar for the scan-position anchor form: no leading zero, at most 10 digits. */
    private static final Pattern POSITION_ANCHOR = Pattern.compile("#(0|[1-9][0-9]{0,9})");

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
     * Encodes an unsigned, non-expiring cursor naming the last examined candidate — the page-full and
     * registry-exhausted anchor form.
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
     * Encodes an unsigned, non-expiring cursor carrying an opaque scan-position anchor (R40/C5) — the
     * budget-exhaustion anchor form, emitted instead of the last examined candidate's name so a
     * denied-but-examined candidate is never disclosed.
     *
     * @param nextUnexaminedIndex the zero-based index, in the digest-pinned registry order, of the next
     *     unexamined candidate; must not be negative
     * @param registryDigest the current immutable SHA-256 registry digest
     * @return the opaque, canonical, base64url-encoded cursor token without padding
     * @throws IllegalArgumentException if {@code nextUnexaminedIndex} is negative or the digest is
     *     outside the cursor grammar
     */
    String encodePosition(int nextUnexaminedIndex, String registryDigest) {
        if (nextUnexaminedIndex < 0) {
            throw new IllegalArgumentException("nextUnexaminedIndex must not be negative");
        }
        requireValidDigest(registryDigest);
        String anchor = POSITION_ANCHOR_PREFIX + nextUnexaminedIndex;
        return serializeCanonicalNode(canonicalNode(anchor, registryDigest), "cursor encoding failed");
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
                || !isValidAnchor(lastScannedToolName)
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

    /** Rejects an invalid name-form anchor supplied to {@link #encode}. */
    private static void requireValidAnchor(String lastScannedToolName) {
        if (!McpToolDescriptor.isValidName(lastScannedToolName)) {
            throw new IllegalArgumentException("lastScannedToolName must be a valid tool name");
        }
    }

    /** Reports whether {@code anchor} satisfies either the name form or the position form (R40/C5). */
    private static boolean isValidAnchor(@Nullable String anchor) {
        return McpToolDescriptor.isValidName(anchor) || isValidPositionAnchor(anchor);
    }

    /**
     * Reports whether {@code anchor} is the bounded {@code "#<index>"} scan-position form, including
     * that the parsed index fits in {@code int} range.
     */
    private static boolean isValidPositionAnchor(@Nullable String anchor) {
        if (anchor == null || !POSITION_ANCHOR.matcher(anchor).matches()) {
            return false;
        }
        try {
            Integer.parseInt(anchor.substring(POSITION_ANCHOR_PREFIX.length()));
            return true;
        } catch (NumberFormatException outOfIntRange) {
            return false;
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

        /**
         * Reports whether this valid decode's anchor is the opaque scan-position form (R40/C5) rather
         * than a tool-name lexicographic position hint.
         *
         * @return {@code true} when {@link #anchor()} is the {@code "#<index>"} position form
         */
        boolean isPositional() {
            return anchor != null && anchor.startsWith(POSITION_ANCHOR_PREFIX);
        }

        /**
         * Parses this decode's scan-position index.
         *
         * @return the zero-based next-unexamined-candidate index this anchor carries
         * @throws IllegalStateException if this decode is not {@link #isPositional()}
         */
        int positionIndex() {
            if (!isPositional()) {
                throw new IllegalStateException("anchor is not a position-form anchor");
            }
            return Integer.parseInt(anchor.substring(POSITION_ANCHOR_PREFIX.length()));
        }
    }

    /** Decodes one unpadded base64url token after its encoded length and alphabet are accepted. */
    @FunctionalInterface
    interface Base64UrlDecoder {
        byte[] decode(String cursor);
    }
}

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
import java.util.Set;

/**
 * Encodes and decodes the unsigned, non-expiring {@code tools/list} pagination cursor (§4.7).
 *
 * <p>A cursor is canonical, compact JSON — {@code protocolVersion}, the registry {@code digest}, and
 * the last global-name candidate {@code anchor} examined, in that field order — base64url-encoded
 * without padding. It carries no signature, HMAC, or expiry member: tampering cannot bypass
 * authorization because every candidate reachable from a resumed cursor is reauthorized exactly like
 * every other candidate, and the immutable registry digest — not a client-enforceable expiry —
 * invalidates a cursor across deployments. Pagination is not an authority boundary, so a cursor secret
 * is disproportionate.
 *
 * <p>{@link #decode} bounds the base64url-decoded byte length <em>before</em> the bytes are ever
 * handed to a JSON parser, so an over-long or maliciously expensive-to-parse payload is rejected
 * without incurring a parse attempt. Every rejection — a wrong protocol version, a stale registry
 * digest, an anchor absent from the current registry, an over-long payload, or a malformed one —
 * collapses to the same {@link Decoded#isInvalid()} outcome carrying no distinguishing detail, so a
 * caller mapping it to the wire can only ever produce the one indistinguishable {@code -32602}
 * response (T005's carried obligation).
 *
 * <p>Not part of the application-facing public surface: package-private per the frozen artifact
 * inventory, matching {@link McpToolRegistry} and {@link McpSchemaRegistry}.
 *
 * <p><b>R13 item 2 (retracting R07 item 7's "unavoidable" residual).</b> {@link #BEFORE_FIRST_ANCHOR}
 * is a reserved, non-membership-checked anchor value meaning "resume scanning from the very
 * beginning of the registry" — distinct from an ordinary anchor, which always names a genuinely
 * examined candidate and is therefore excluded (the grammar's anchor is exclusive). It is the empty
 * string, which {@link McpToolDescriptor#isValidName} can never accept as a real tool name ({@code
 * [A-Za-z0-9_.-]{1,128}} requires at least one character), so it can never collide with a genuine
 * anchor and needs no dedicated wire field. R07 claimed no such value was expressible because the
 * cursor's anchor was assumed to always mean "this candidate was examined and is excluded"; that
 * assumption, not the cursor's opacity, was the limitation — the grammar already had room for one
 * more reserved value.
 */
final class McpCursorCodec {

    /** The one frozen final-2026 protocol version every valid cursor must carry. */
    static final String PROTOCOL_VERSION = "2026-07-28";

    /**
     * The reserved anchor meaning "resume scanning from the very beginning of the registry" — used
     * when a scan must stop before any candidate was ever genuinely examined (a gate timeout, an
     * exhausted deadline, or a cancellation observed before the very first candidate's decision even
     * started). Never a real tool name ({@link McpToolDescriptor#isValidName} requires at least one
     * character), so {@link #decode} recognizes it without checking registry membership, and a
     * resumed scan seeded with it starts again at index 0 rather than silently excluding the
     * candidate an ordinary (membership-checked, exclusive) anchor would.
     */
    static final String BEFORE_FIRST_ANCHOR = "";

    /**
     * The upper bound on base64url-decoded cursor bytes, checked before any JSON parsing is attempted.
     * A valid cursor (a short protocol version, a 64-character hex digest, and a tool name at most 128
     * characters long) is at most a few hundred bytes; this bound is deliberately far smaller than the
     * envelope codec's own document bound, since a cursor is never a general-purpose payload.
     */
    private static final int MAX_DECODED_BYTES = 2_048;

    private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    private static final String FIELD_DIGEST = "digest";
    private static final String FIELD_ANCHOR = "anchor";

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private final int maxDecodedBytes;
    private final ObjectMapper mapper;

    /** Creates the production codec with the frozen byte bound and a plain Jackson mapper. */
    McpCursorCodec() {
        this(MAX_DECODED_BYTES, DEFAULT_MAPPER);
    }

    /**
     * Test-only seam: lets a proof substitute a smaller bound and/or an instrumented mapper (e.g. one
     * that counts invocations) to prove the byte-length bound is enforced strictly before any JSON
     * parsing is attempted.
     *
     * @param maxDecodedBytes the decoded-byte cap to enforce before parsing
     * @param mapper the mapper used for both canonical encoding and bounded-tree parsing
     */
    McpCursorCodec(int maxDecodedBytes, ObjectMapper mapper) {
        this.maxDecodedBytes = maxDecodedBytes;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Encodes an unsigned, non-expiring cursor naming the last examined global-name candidate, or
     * {@link #BEFORE_FIRST_ANCHOR} to encode "resume from the very beginning" instead.
     *
     * @param anchor the last examined candidate's global tool name, or {@link #BEFORE_FIRST_ANCHOR};
     *     must not be {@code null}
     * @param registryDigest the current immutable registry digest; must not be {@code null}
     * @return the opaque, base64url-encoded (no padding) cursor token
     */
    String encode(String anchor, String registryDigest) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(registryDigest, "registryDigest");
        ObjectNode node = mapper.createObjectNode();
        node.put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION);
        node.put(FIELD_DIGEST, registryDigest);
        node.put(FIELD_ANCHOR, anchor);
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException impossible) {
            // Serializing a fully in-memory node tree of three string fields cannot fail; a failure
            // here would be a programming error, not a wire condition.
            throw new IllegalStateException("cursor encoding failed", impossible);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Strictly decodes and validates one cursor token.
     *
     * <p>Validation order is: base64url decode, then the decoded-byte-length bound (before any JSON
     * parsing), then JSON parsing and structural shape, then the protocol version, then the registry
     * digest, then anchor membership in the current registry — skipped for {@link
     * #BEFORE_FIRST_ANCHOR}, which never needs to name a real candidate. Every failure — regardless of
     * which check rejected it — collapses to the same {@link Decoded#isInvalid()} result with no
     * distinguishing detail.
     *
     * @param cursor the opaque cursor token to decode; must not be {@code null}
     * @param registryDigest the current immutable registry digest a valid cursor must match
     * @param validAnchors the current registry's global tool names; a valid cursor's anchor must be
     *     one of these, or {@link #BEFORE_FIRST_ANCHOR}
     * @return the decoded anchor on success, or an invalid result carrying no anchor
     */
    Decoded decode(String cursor, String registryDigest, Set<String> validAnchors) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(registryDigest, "registryDigest");
        Objects.requireNonNull(validAnchors, "validAnchors");
        byte[] decodedBytes;
        try {
            decodedBytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException invalidBase64) {
            return Decoded.invalid();
        }
        // Bounded BEFORE any JSON parsing is attempted: an over-long payload never reaches the parser.
        if (decodedBytes.length > maxDecodedBytes) {
            return Decoded.invalid();
        }
        JsonNode node;
        try {
            node = mapper.readTree(decodedBytes);
        } catch (IOException malformed) {
            return Decoded.invalid();
        }
        if (node == null || !node.isObject()) {
            return Decoded.invalid();
        }
        String version = textField(node, FIELD_PROTOCOL_VERSION);
        if (version == null || !PROTOCOL_VERSION.equals(version)) {
            return Decoded.invalid();
        }
        String digest = textField(node, FIELD_DIGEST);
        if (digest == null || !registryDigest.equals(digest)) {
            return Decoded.invalid();
        }
        String anchor = textField(node, FIELD_ANCHOR);
        if (anchor == null) {
            return Decoded.invalid();
        }
        // R13 item 2: BEFORE_FIRST_ANCHOR is a reserved sentinel, never a real tool name, so it is
        // recognized by exact equality and never checked against registry membership.
        boolean beforeFirst = BEFORE_FIRST_ANCHOR.equals(anchor);
        if (!beforeFirst && (anchor.isBlank() || !validAnchors.contains(anchor))) {
            return Decoded.invalid();
        }
        return Decoded.ok(anchor);
    }

    @Nullable
    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * The outcome of a cursor decode: either a valid {@code anchor} or an invalid result carrying no
     * distinguishing detail about why validation failed.
     *
     * @param anchor the decoded last-examined global tool name, {@link #BEFORE_FIRST_ANCHOR}, or
     *     {@code null} when invalid
     * @param isInvalid whether the cursor failed validation
     */
    record Decoded(@Nullable String anchor, boolean isInvalid) {

        /**
         * Wraps a successfully decoded anchor.
         *
         * @param anchor the decoded last-examined global tool name
         * @return a valid decode
         */
        static Decoded ok(String anchor) {
            return new Decoded(anchor, false);
        }

        /**
         * Wraps an invalid cursor, carrying no anchor and no reason.
         *
         * @return an invalid decode
         */
        static Decoded invalid() {
            return new Decoded(null, true);
        }
    }
}

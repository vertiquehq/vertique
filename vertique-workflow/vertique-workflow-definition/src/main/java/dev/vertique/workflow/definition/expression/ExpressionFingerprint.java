// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for computing stable SHA-256 fingerprints of {@link CompiledExpression} values.
 *
 * <p>The fingerprint is derived from {@link CompiledExpression#canonicalForm()} so that:
 * <ul>
 *   <li>Whitespace-only differences do not change the fingerprint (because the profile's unparser
 *       produces a normalised canonical form before storage in the record).</li>
 *   <li>Semantic changes (different operands, operators, or structure) always produce a different
 *       fingerprint because the canonical form changes.</li>
 * </ul>
 *
 * <p>The output is a 64-character lower-case hexadecimal string. It is stable across JVM restarts
 * and independent of the {@link CompiledExpression#handle()} implementation type.
 *
 * <p>Used by {@code DecisionRouteCompiler} (Slice F) to fold route fingerprints into
 * {@code CallbackId.value()} so that route changes propagate automatically to the plan hash.
 */
public final class ExpressionFingerprint {

    private ExpressionFingerprint() {
        // Utility class — no instances.
    }

    /**
     * Computes the SHA-256 hex fingerprint of the given compiled expression's canonical form.
     *
     * @param expr the compiled expression whose canonical form is hashed; non-null
     * @return a 64-character lower-case hex string; never {@code null}
     * @throws IllegalArgumentException if {@code expr} is {@code null}
     */
    public static String of(CompiledExpression expr) {
        if (expr == null) {
            throw new IllegalArgumentException("expr must be non-null");
        }
        return ofString(expr.canonicalForm(), 32);
    }

    /**
     * Computes the SHA-256 hex digest of the given UTF-8 string and returns a prefix of the result.
     *
     * <p>Used by {@code DecisionRouteCompiler} to fold a compact route-table fingerprint into a
     * {@code CallbackId} value: a shorter prefix keeps {@code CallbackId.value()} readable in logs
     * and history payloads while still providing enough entropy to detect any semantic change to a
     * route table within a single definition version (16 hex chars = 64 bits).
     *
     * @param input the input string; non-null
     * @param hexBytes the number of digest bytes to emit (each byte becomes 2 hex characters); must
     *     be in {@code [1, 32]} since SHA-256 produces 32 bytes
     * @return a {@code hexBytes * 2}-character lower-case hex string; never null
     * @throws IllegalArgumentException if {@code input} is null or {@code hexBytes} is out of range
     */
    public static String ofString(String input, int hexBytes) {
        if (input == null) {
            throw new IllegalArgumentException("input must be non-null");
        }
        if (hexBytes < 1 || hexBytes > 32) {
            throw new IllegalArgumentException("hexBytes must be in [1, 32]; got " + hexBytes);
        }
        byte[] hash = sha256Digest().digest(input.getBytes(StandardCharsets.UTF_8));
        return toHex(hash, hexBytes);
    }

    // --- Private helpers ---

    /**
     * Returns a fresh {@link MessageDigest} for SHA-256.
     *
     * @return a new SHA-256 digest instance
     */
    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JVM spec (java.security.MessageDigest guarantees).
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Encodes the first {@code hexBytes} bytes of the given array as a lower-case hexadecimal
     * string. The output length is {@code hexBytes * 2}.
     *
     * @param bytes the digest bytes; must contain at least {@code hexBytes} entries
     * @param hexBytes the number of bytes to encode
     * @return the hex prefix
     */
    private static String toHex(byte[] bytes, int hexBytes) {
        StringBuilder sb = new StringBuilder(hexBytes * 2);
        for (int i = 0; i < hexBytes; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}

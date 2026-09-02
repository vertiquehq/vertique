// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-function storage-identity derivation ({@code contracts/rate-limit-runtime.md},
 * "Storage identity"). Package-private to {@code vertique-rate-limit-core} in v1 ({@code
 * spec.md} §14 defers a shared extraction).
 *
 * <p>{@link #canonicalInput} frames the canonical storage input shared by both modes.
 * {@link #local} returns it directly, with no HMAC and no secret. {@link #clustered} derives the
 * secret-scoped physical key ready for a Redis backend to use ({@code T009}); this class never
 * wires to an actual backend or validates the secret's minimum length — that is {@code T003}'s
 * startup-validation responsibility. No method here logs, throws with, or otherwise exposes a
 * resolved secret, HMAC output, or raw key (NFR-CONF-002).
 */
final class RateLimitStorageIdentity {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int FINGERPRINT_HEX_LENGTH = 8;

    private RateLimitStorageIdentity() {}

    /**
     * Canonical storage input = {@code policyName + ':' + policyRevision + ':' +
     * canonicalKeyEncoding}.
     *
     * @param policyName the owning policy's name
     * @param policyRevision the owning policy's {@code revision}
     * @param key the caller-supplied key
     * @return the canonical input string shared by both {@link #local} and {@link #clustered}
     */
    static String canonicalInput(String policyName, String policyRevision, RateLimitKey key) {
        Objects.requireNonNull(policyName, "policyName");
        Objects.requireNonNull(policyRevision, "policyRevision");
        Objects.requireNonNull(key, "key");
        return policyName + ':' + policyRevision + ':' + key.canonicalEncoding();
    }

    /**
     * {@code LOCAL} storage identity: the canonical input used directly as the in-memory map key.
     * No HMAC is computed and no secret is required.
     *
     * @param canonicalInput the canonical storage input from {@link #canonicalInput}
     * @return {@code canonicalInput}, unchanged
     */
    static String local(String canonicalInput) {
        return Objects.requireNonNull(canonicalInput, "canonicalInput");
    }

    /**
     * {@code CLUSTERED} physical key: {@code <namespace>:v1:<keyFingerprint>:<hmac>}, where
     * {@code keyFingerprint} is the first 8 hex characters of {@code SHA-256(secret)} and {@code
     * hmac} is the full 64-character lowercase-hex {@code HMAC-SHA-256(secret, canonicalInput)}
     * output, never truncated.
     *
     * @param namespace {@code rateLimit.redis.namespace}
     * @param secret the resolved key-derivation secret; this method neither validates nor
     *     enforces any minimum length on it
     * @param canonicalInput the canonical storage input from {@link #canonicalInput}
     * @return the physical Redis key, ready for a {@code CLUSTERED} backend to use
     */
    static String clustered(String namespace, String secret, String canonicalInput) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(canonicalInput, "canonicalInput");
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        String fingerprint = toLowerHex(sha256(secretBytes)).substring(0, FINGERPRINT_HEX_LENGTH);
        String hmac = toLowerHex(hmacSha256(secretBytes, canonicalInput));
        return namespace + ":v1:" + fingerprint + ':' + hmac;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] secretBytes, String canonicalInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(canonicalInput.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", e);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}

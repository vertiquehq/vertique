// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-function {@code CLUSTERED} physical-key derivation (contracts/rate-limit-runtime.md,
 * "Storage identity", "Redis integration contract" — §6.1): {@code
 * <namespace>:v1:<keyFingerprint>:HMAC-SHA-256(secret, canonicalInput)}.
 *
 * <p>{@code request.storageKey()} (from {@code RateLimitBackendRequest}) is already the shared
 * canonical input {@code policyName + ':' + policyRevision + ':' + canonicalKeyEncoding} that
 * {@code RateLimiter} computes once for both modes (contracts/rate-limit-runtime.md, "Storage
 * identity" — LOCAL uses it directly; CLUSTERED derives this physical key from it). T002's
 * equivalent derivation ({@code RateLimitStorageIdentity}) is package-private to {@code
 * vertique-rate-limit-core} and not reachable from this module, so this class re-derives the same
 * algorithm independently from JDK primitives — proven identical by shared golden vectors
 * (contracts/rate-limit-runtime.md, "Storage identity"). No method here logs, throws with, or
 * otherwise exposes a resolved secret, HMAC output, or raw key (NFR-CONF-002).
 */
final class RedisPhysicalKey {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int FINGERPRINT_HEX_LENGTH = 8;

    private RedisPhysicalKey() {}

    /**
     * @param namespace {@code rateLimit.redis.namespace}
     * @param secret the resolved {@code rateLimit.keyDerivation.secret}
     * @param canonicalInput the canonical storage input ({@code request.storageKey()})
     * @return the physical Redis key {@code <namespace>:v1:<keyFingerprint>:<hmac>}
     */
    static String physicalKey(String namespace, String secret, String canonicalInput) {
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

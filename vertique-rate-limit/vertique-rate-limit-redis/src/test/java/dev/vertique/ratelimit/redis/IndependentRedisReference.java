// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Independent JDK-primitive reference computations for this module's decisive assertions, written
 * separately from {@link Bucket4jRedisTranslation}/{@link RedisPhysicalKey} so the tests that use
 * this helper do not check themselves (TP-003, TP-007's "Test readability" notes).
 */
final class IndependentRedisReference {

    private IndependentRedisReference() {}

    /** Independently recomputes the {@code CLUSTERED} physical key from JDK primitives (§6.1). */
    static String physicalKey(String namespace, String secret, String canonicalInput) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        String fingerprint = toLowerHex(sha256(secretBytes)).substring(0, 8);
        String hmac = toLowerHex(hmacSha256(secretBytes, canonicalInput));
        return namespace + ":v1:" + fingerprint + ':' + hmac;
    }

    /** Independently recomputes the greedy-refill worst-case time-to-full, in milliseconds. */
    static long worstCaseTimeToFullMs(long capacity, long tokensPerPeriod, long periodMs) {
        long totalMillis = Math.multiplyExact(capacity, periodMs);
        return (totalMillis + tokensPerPeriod - 1) / tokensPerPeriod;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmacSha256(byte[] secretBytes, String canonicalInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return mac.doFinal(canonicalInput.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}

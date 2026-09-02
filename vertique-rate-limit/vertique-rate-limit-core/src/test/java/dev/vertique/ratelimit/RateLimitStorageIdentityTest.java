// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * TP-003, TP-004: {@link RateLimitStorageIdentity}'s {@code LOCAL} and {@code CLUSTERED} storage
 * identity derivation ({@code contracts/rate-limit-runtime.md}, "Storage identity"; D015).
 */
class RateLimitStorageIdentityTest {

    /**
     * Golden vectors (D015 compatibility-contract requirement, matching D008's own requirement):
     * canonical input {@code "policy-a:r1:Scaller-1"} (policy {@code "policy-a"}, revision
     * {@code "r1"}, one-component key {@code "caller-1"}).
     *
     * <ul>
     *   <li>secret {@code "rl-test-secret"} -&gt; fingerprint {@code 7791113a}, hmac {@code
     *       c091a0c4e6b73e6ae1432257d3988428772aaac411575d1e83f83b73a4eacbe6}
     *   <li>secret {@code "rl-test-secret-2"} -&gt; fingerprint {@code 4f97e019}, hmac {@code
     *       b4bcca6ec96d29e9ba2ad1bc264650217a3d53d20d108240b5d6bba05a365bea}
     * </ul>
     */
    private static final String CANONICAL_INPUT =
            RateLimitStorageIdentity.canonicalInput("policy-a", "r1", RateLimitKey.of("caller-1"));

    @Test
    void shouldUseCanonicalEncodingDirectlyForLocalModeWithNoHmac() {
        assertThat(CANONICAL_INPUT).isEqualTo("policy-a:r1:Scaller-1");

        String local = RateLimitStorageIdentity.local(CANONICAL_INPUT);

        assertThat(local).isEqualTo(CANONICAL_INPUT);
    }

    @Test
    void shouldDeriveKeyFingerprintAndHmacFromSecretForClusteredMode() {
        String namespace = "rl";
        String secretOne = "rl-test-secret";
        String secretTwo = "rl-test-secret-2";

        String physicalKeyOne = RateLimitStorageIdentity.clustered(namespace, secretOne, CANONICAL_INPUT);
        String physicalKeyTwo = RateLimitStorageIdentity.clustered(namespace, secretTwo, CANONICAL_INPUT);

        assertThat(physicalKeyOne)
                .isEqualTo(namespace + ":v1:" + referenceFingerprint(secretOne) + ':'
                        + referenceHmac(secretOne, CANONICAL_INPUT));
        assertThat(physicalKeyTwo)
                .isEqualTo(namespace + ":v1:" + referenceFingerprint(secretTwo) + ':'
                        + referenceHmac(secretTwo, CANONICAL_INPUT));

        // Golden vectors: pinned literals, not merely re-derived through the same JDK primitives.
        assertThat(physicalKeyOne)
                .isEqualTo("rl:v1:7791113a:c091a0c4e6b73e6ae1432257d3988428772aaac411575d1e83f83b73a4eacbe6");
        assertThat(physicalKeyTwo)
                .isEqualTo("rl:v1:4f97e019:b4bcca6ec96d29e9ba2ad1bc264650217a3d53d20d108240b5d6bba05a365bea");

        assertThat(physicalKeyOne).isNotEqualTo(physicalKeyTwo);
    }

    /** Independent JDK {@link MessageDigest} reference computation so the test does not check itself. */
    private static String referenceFingerprint(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return toLowerHex(hash).substring(0, 8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Independent JDK {@link Mac} reference computation so the test does not check itself. */
    private static String referenceHmac(String secret, String canonicalInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toLowerHex(mac.doFinal(canonicalInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}

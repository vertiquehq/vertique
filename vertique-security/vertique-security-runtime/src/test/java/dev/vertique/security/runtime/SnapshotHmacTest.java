// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Red (failing-by-design) tests for {@link SnapshotHmac}.
 *
 * <p>{@code SnapshotHmac} does not exist yet — this class only compiles once the runtime HMAC
 * signer/verifier over a keyset (active signing key + previous verification keys, PRD-ID-002
 * §14.3) is implemented. Until then the module fails to compile with {@code cannot find symbol},
 * which is the expected red state for slice P1.S1.
 */
class SnapshotHmacTest {

    private static final byte[] PAYLOAD = "canonical-snapshot-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("the constructor rejects a secret shorter than 32 UTF-8 bytes, naming only the keyId")
    void constructorRejectsShortSecret() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> new SnapshotHmac(Map.of("k", "short"), "k"));

        assertTrue(ex.getMessage().contains("k"), "the message must name the offending keyId");
        assertFalse(ex.getMessage().contains("short"), "the message must never leak the secret material");
    }

    @Test
    @DisplayName("the constructor accepts a secret that is at least 32 UTF-8 bytes")
    void constructorAcceptsSecretAtMinimumLength() {
        assertDoesNotThrow(() -> new SnapshotHmac(Map.of("k", "a".repeat(32)), "k"));
    }

    @Test
    @DisplayName("a tampered payload fails verification")
    void tamperedPayloadFailsVerify() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");

        String tag = hmac.sign(PAYLOAD, "key-1", "HmacSHA256");

        byte[] tampered = PAYLOAD.clone();
        tampered[0] ^= 0x01;

        assertFalse(hmac.verify(tampered, "key-1", "HmacSHA256", tag));
    }

    @Test
    @DisplayName("verifying with no key configured fails closed")
    void missingKeyFailsClosed() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of(), "key-1");

        assertThrows(SnapshotHmacException.class, () -> hmac.verify(PAYLOAD, "key-1", "HmacSHA256", "any-tag"));
    }

    @Test
    @DisplayName("a signing key demoted to previous still verifies (rotation grace window)")
    void previousKeyStillVerifies() {
        SnapshotHmac signer = new SnapshotHmac(Map.of("key-old", "super-secret-signing-key-material"), "key-old");
        String tag = signer.sign(PAYLOAD, "key-old", "HmacSHA256");

        // key-old is no longer the active signing key, but is retained as a previous
        // verification key for the rotation grace window.
        SnapshotHmac verifier = new SnapshotHmac(
                Map.of(
                        "key-new",
                        "another-secret-signing-key-material",
                        "key-old",
                        "super-secret-signing-key-material"),
                "key-new");

        assertTrue(verifier.verify(PAYLOAD, "key-old", "HmacSHA256", tag));
    }

    @Test
    @DisplayName("verifying a tag whose keyId is unknown to the keyset fails closed")
    void unknownKeyIdFailsClosed() {
        SnapshotHmac signer = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        String tag = signer.sign(PAYLOAD, "key-1", "HmacSHA256");

        SnapshotHmac verifier = new SnapshotHmac(Map.of("key-2", "a-completely-different-key-material"), "key-2");

        assertThrows(SnapshotHmacException.class, () -> verifier.verify(PAYLOAD, "key-1", "HmacSHA256", tag));
    }

    @Test
    @DisplayName("verifying under an algorithm outside the server allowlist fails closed (no Mac.getInstance)")
    void disallowedAlgorithmFailsClosed() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        String tag = hmac.sign(PAYLOAD, "key-1", "HmacSHA256");

        // HmacMD5 is a valid JCA algorithm, so without a server-side allowlist the message would get
        // to pick its own (weaker) verify primitive and verification would merely return false. The
        // allowlist must reject it before Mac.getInstance, failing closed with a typed exception.
        assertThrows(SnapshotHmacException.class, () -> hmac.verify(PAYLOAD, "key-1", "HmacMD5", tag));
    }
}

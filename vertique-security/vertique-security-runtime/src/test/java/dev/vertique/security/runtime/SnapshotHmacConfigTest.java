// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link SnapshotHmacConfig} keyset config and its {@link SnapshotHmacKeyConfig}
 * entries, covering the fail-fast, secret-free validation contract (PRD-ID-002 §14.3 "Durable
 * carriage", ADR-0162).
 *
 * <p>Two security-critical validation guarantees are proven here:
 * <ul>
 *   <li>a {@code keyId} reused across two {@code previous} keys is rejected at construction with a
 *       {@link ConfigurationException} that names only the offending {@code keyId} — never a secret
 *       — so a duplicate key can never leak raw secret material into the startup log via the
 *       {@code Collectors.toUnmodifiableMap} duplicate-key {@link IllegalStateException};</li>
 *   <li>a {@code secretRef} shorter than the HMAC-SHA256 output size (32 bytes UTF-8) is rejected,
 *       again with a secret-free message, so a short/weak key cannot defeat the sole forgery
 *       defense over the app-writable durable store.</li>
 * </ul>
 */
class SnapshotHmacConfigTest {

    private static final String STRONG_SECRET_A = "aaaaaaaa-previous-secret-material";
    private static final String STRONG_SECRET_B = "bbbbbbbb-previous-secret-material";
    private static final String ACTIVE_SECRET = "super-secret-signing-key-material";

    @Nested
    @DisplayName("SnapshotHmacConfig previous-key uniqueness (F4)")
    class PreviousKeyUniqueness {

        @Test
        @DisplayName("two previous keys sharing a keyId are rejected with a secret-free message")
        void duplicatePreviousKeyIdRejected() {
            SnapshotHmacKeyConfig active = new SnapshotHmacKeyConfig("active", ACTIVE_SECRET);
            SnapshotHmacKeyConfig prevA = new SnapshotHmacKeyConfig("dup", STRONG_SECRET_A);
            SnapshotHmacKeyConfig prevB = new SnapshotHmacKeyConfig("dup", STRONG_SECRET_B);

            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> new SnapshotHmacConfig(active, List.of(prevA, prevB)));

            assertTrue(ex.getMessage().contains("dup"), "the message must name the offending keyId");
            assertFalse(
                    ex.getMessage().contains(STRONG_SECRET_A),
                    "the message must never contain a secret value (first previous key)");
            assertFalse(
                    ex.getMessage().contains(STRONG_SECRET_B),
                    "the message must never contain a secret value (second previous key)");
        }

        @Test
        @DisplayName("distinct previous keyIds are accepted")
        void distinctPreviousKeyIdsAccepted() {
            SnapshotHmacKeyConfig active = new SnapshotHmacKeyConfig("active", ACTIVE_SECRET);
            SnapshotHmacKeyConfig prevA = new SnapshotHmacKeyConfig("prev-1", STRONG_SECRET_A);
            SnapshotHmacKeyConfig prevB = new SnapshotHmacKeyConfig("prev-2", STRONG_SECRET_B);

            assertDoesNotThrow(() -> new SnapshotHmacConfig(active, List.of(prevA, prevB)));
        }
    }

    @Nested
    @DisplayName("SnapshotHmacKeyConfig minimum key strength (F6)")
    class MinimumKeyStrength {

        @Test
        @DisplayName("a secretRef shorter than 32 bytes is rejected with a secret-free message")
        void shortSecretRefRejected() {
            String shortSecret = "0123456789"; // 10 bytes UTF-8, below the 32-byte HMAC-SHA256 floor

            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> new SnapshotHmacKeyConfig("weak-key", shortSecret));

            assertTrue(ex.getMessage().contains("weak-key"), "the message must name the offending keyId");
            assertFalse(ex.getMessage().contains(shortSecret), "the message must never contain the secret value");
        }

        @Test
        @DisplayName("a secretRef of exactly 32 bytes is accepted")
        void thirtyTwoByteSecretRefAccepted() {
            String boundarySecret = "01234567890123456789012345678901"; // exactly 32 bytes UTF-8

            assertDoesNotThrow(() -> new SnapshotHmacKeyConfig("ok-key", boundarySecret));
        }
    }
}

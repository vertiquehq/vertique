// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HmacSecretResolverVerificationSource} validation.
 *
 * <p>Verifies that both {@code resolverId} and {@code algorithm} are non-null and non-blank.
 */
class HmacSecretResolverVerificationSourceTest {

    @Test
    @DisplayName("rejects null resolverId")
    void rejectsNullResolverId() {
        assertThrows(NullPointerException.class, () -> new HmacSecretResolverVerificationSource(null, "HmacSHA256"));
    }

    @Test
    @DisplayName("rejects blank resolverId")
    void rejectsBlankResolverId() {
        assertThrows(
                IllegalArgumentException.class, () -> new HmacSecretResolverVerificationSource("  ", "HmacSHA256"));
    }

    @Test
    @DisplayName("rejects null algorithm")
    void rejectsNullAlgorithm() {
        assertThrows(
                NullPointerException.class, () -> new HmacSecretResolverVerificationSource("webhook-resolver", null));
    }

    @Test
    @DisplayName("rejects blank algorithm")
    void rejectsBlankAlgorithm() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HmacSecretResolverVerificationSource("webhook-resolver", "  "));
    }
}

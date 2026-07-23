// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BasicCredentialVerifierVerificationSource} validation.
 *
 * <p>Verifies that {@code verifierId} is non-null and non-blank.
 */
class BasicCredentialVerifierVerificationSourceTest {

    @Test
    @DisplayName("rejects null verifierId")
    void rejectsNullVerifierId() {
        assertThrows(NullPointerException.class, () -> new BasicCredentialVerifierVerificationSource(null));
    }

    @Test
    @DisplayName("rejects blank verifierId")
    void rejectsBlankVerifierId() {
        assertThrows(IllegalArgumentException.class, () -> new BasicCredentialVerifierVerificationSource("   "));
    }
}

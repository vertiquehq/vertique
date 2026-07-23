// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwksVerificationSource} null rejection.
 *
 * <p>All four Optional fields must be non-null references (though their values may be empty).
 */
class JwksVerificationSourceTest {

    @Test
    @DisplayName("rejects null issuer Optional")
    void rejectsNullIssuer() {
        assertThrows(
                NullPointerException.class,
                () -> new JwksVerificationSource(null, Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("rejects null jwksUri Optional")
    void rejectsNullJwksUri() {
        assertThrows(
                NullPointerException.class,
                () -> new JwksVerificationSource(Optional.empty(), null, Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("rejects null kid Optional")
    void rejectsNullKid() {
        assertThrows(
                NullPointerException.class,
                () -> new JwksVerificationSource(Optional.empty(), Optional.empty(), null, Optional.empty()));
    }

    @Test
    @DisplayName("rejects null alg Optional")
    void rejectsNullAlg() {
        assertThrows(
                NullPointerException.class,
                () -> new JwksVerificationSource(Optional.empty(), Optional.empty(), Optional.empty(), null));
    }
}

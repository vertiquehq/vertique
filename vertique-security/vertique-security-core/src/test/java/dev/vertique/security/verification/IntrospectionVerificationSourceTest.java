// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntrospectionVerificationSource} validation.
 *
 * <p>Verifies that {@code endpoint} and {@code responseShape} are non-null,
 * and that a blank {@code endpoint} is rejected.
 */
class IntrospectionVerificationSourceTest {

    @Test
    @DisplayName("rejects null endpoint")
    void rejectsNullEndpoint() {
        assertThrows(
                NullPointerException.class,
                () -> new IntrospectionVerificationSource(null, IntrospectionResponseShape.RFC7662_JSON));
    }

    @Test
    @DisplayName("rejects blank endpoint")
    void rejectsBlankEndpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntrospectionVerificationSource("  ", IntrospectionResponseShape.RFC7662_JSON));
    }

    @Test
    @DisplayName("rejects null responseShape")
    void rejectsNullResponseShape() {
        assertThrows(
                NullPointerException.class,
                () -> new IntrospectionVerificationSource("https://idp.example.com/introspect", null));
    }
}

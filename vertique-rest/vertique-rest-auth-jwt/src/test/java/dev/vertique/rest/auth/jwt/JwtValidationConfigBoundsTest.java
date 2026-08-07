// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the bounds {@link JwtValidationConfig} enforces on {@link
 * JwtValidationConfig#clockSkewSeconds()}.
 *
 * <p>The value is passed straight to Vert.x as {@code JWTOptions} leeway, where a negative value is
 * meaningless and a very large one silently widens the {@code exp}/{@code nbf}/{@code iat} window
 * far past what RFC 7519 §4.1.4 contemplates ("some small leeway, usually no more than a few
 * minutes"). Both are rejected at construction — including through Jackson, which deserializes via
 * the builder and therefore through the same constructor.
 */
class JwtValidationConfigBoundsTest {

    @Test
    @DisplayName("A negative clock skew is rejected")
    void shouldRejectNegativeClockSkew() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> JwtValidationConfig.builder().clockSkewSeconds(-1).build());

        assertTrue(
                failure.getMessage().contains("jwt.validation.clockSkewSeconds"),
                () -> "message must name the config path, was: " + failure.getMessage());
    }

    @Test
    @DisplayName("A clock skew above the maximum is rejected")
    void shouldRejectClockSkewAboveMaximum() {
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> JwtValidationConfig.builder()
                .clockSkewSeconds(JwtValidationConfig.MAX_CLOCK_SKEW_SECONDS + 1)
                .build());

        assertTrue(
                failure.getMessage().contains("jwt.validation.clockSkewSeconds"),
                () -> "message must name the config path, was: " + failure.getMessage());
    }

    @Test
    @DisplayName("A clock skew exactly at the maximum is accepted")
    void shouldAcceptClockSkewAtMaximum() {
        JwtValidationConfig config = JwtValidationConfig.builder()
                .clockSkewSeconds(JwtValidationConfig.MAX_CLOCK_SKEW_SECONDS)
                .build();

        assertEquals(300, JwtValidationConfig.MAX_CLOCK_SKEW_SECONDS);
        assertEquals(300, config.clockSkewSeconds());
    }
}

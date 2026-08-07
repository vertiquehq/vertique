// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
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
 *
 * <p>{@link #shouldRejectClockSkewAboveMaximumThroughConfigParser()} exercises that Jackson claim
 * end to end rather than leaving it asserted only in prose: an out-of-range value arriving in a
 * {@code jwt.validation} config section must reach an operator as the framework's own
 * {@link ConfigurationException}, naming the config path, not as a raw deserialization error.
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

    @Test
    @DisplayName("An out-of-range clock skew in the jwt config section surfaces as a ConfigurationException")
    void shouldRejectClockSkewAboveMaximumThroughConfigParser() {
        // given: a jwt section whose nested validation object exceeds the maximum — the shape an
        // operator actually produces, deserialized the same way JwtAuthModule deserializes it
        JsonObject jwtSection = new JsonObject()
                .put(
                        "validation",
                        new JsonObject().put("clockSkewSeconds", JwtValidationConfig.MAX_CLOCK_SKEW_SECONDS + 1));
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

        // when: parsing the section
        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> parser.parse(jwtSection, JwtAuthConfig.class));

        // then: the framework's own message reaches the operator, not a bare Jackson diagnostic
        assertTrue(
                failure.getMessage().contains("jwt.validation.clockSkewSeconds"),
                () -> "message must name the config path, was: " + failure.getMessage());
    }
}

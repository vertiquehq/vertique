// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtAuthConfig} and the effective-config resolution in {@link JwtAuthModule}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>An absent {@code jwt} section parses to the canonical defaults ({@code "bearerAuth"} scheme
 *       name and an empty {@link JwtValidationConfig}).</li>
 *   <li>A populated {@code jwt} section — including a nested {@code validation} object — maps every
 *       field correctly through {@link ConfigParser#parse}.</li>
 *   <li>The effective-config resolver prefers an app-bound {@link JwtAuthConfig} over the
 *       config-parsed default when the app override is present.</li>
 * </ul>
 */
class JwtAuthConfigTest {

    // --- Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("Absent jwt section parses to bearerAuth scheme and empty validation defaults")
        void jwtAuthConfig_defaults() {
            // given: an empty config (no jwt section)
            JsonObject jwtSection = new JsonObject();

            // when: parsing the absent section into JwtAuthConfig
            JwtAuthConfig config = configParser().parse(jwtSection, JwtAuthConfig.class);

            // then: defaults are applied
            assertEquals("bearerAuth", config.schemeName(), "scheme name must default to bearerAuth");
            JwtValidationConfig defaults = JwtValidationConfig.builder().build();
            assertEquals(defaults.issuer(), config.validation().issuer(), "issuer must default to null");
            assertEquals(defaults.audience(), config.validation().audience(), "audience must default to null");
            assertEquals(
                    defaults.clockSkewSeconds(),
                    config.validation().clockSkewSeconds(),
                    "clockSkewSeconds must default to 30");
        }

        @Test
        @DisplayName("JwtAuthConfig.defaults() returns bearerAuth scheme and default validation")
        void defaults_factory() {
            JwtAuthConfig config = JwtAuthConfig.defaults();

            assertEquals("bearerAuth", config.schemeName(), "scheme name must default to bearerAuth");
            assertEquals(
                    JwtValidationConfig.builder().build().clockSkewSeconds(),
                    config.validation().clockSkewSeconds(),
                    "validation must default to JwtValidationConfig defaults");
        }
    }

    @Nested
    @DisplayName("fromJson parsing")
    class FromJson {

        @Test
        @DisplayName("Populated jwt section with nested validation maps every field")
        void jwtAuthConfig_fromJson() {
            // given: a jwt section with a scheme name and a nested validation object
            JsonObject jwtSection = new JsonObject()
                    .put("schemeName", "myScheme")
                    .put(
                            "validation",
                            new JsonObject()
                                    .put("issuer", "https://issuer.example.com")
                                    .put("audience", new io.vertx.core.json.JsonArray().add("https://api.example.com"))
                                    .put("clockSkewSeconds", 90));

            // when: parsing through ConfigParser
            JwtAuthConfig config = configParser().parse(jwtSection, JwtAuthConfig.class);

            // then: each field is mapped correctly
            assertEquals("myScheme", config.schemeName(), "scheme name must come from config");
            assertEquals("https://issuer.example.com", config.validation().issuer(), "issuer must come from config");
            assertEquals(
                    List.of("https://api.example.com"),
                    config.validation().audience(),
                    "audience must come from config");
            assertEquals(90, config.validation().clockSkewSeconds(), "clockSkewSeconds must come from config");
        }

        @Test
        @DisplayName("Partial jwt section (only schemeName) keeps validation defaults")
        void jwtAuthConfig_partial() {
            // given: a jwt section with only a scheme name, no validation
            JsonObject jwtSection = new JsonObject().put("schemeName", "partialScheme");

            // when: parsing through ConfigParser
            JwtAuthConfig config = configParser().parse(jwtSection, JwtAuthConfig.class);

            // then: scheme name from config, validation falls back to defaults
            assertEquals("partialScheme", config.schemeName(), "scheme name must come from config");
            assertEquals(
                    JwtValidationConfig.builder().build().clockSkewSeconds(),
                    config.validation().clockSkewSeconds(),
                    "validation must default when omitted");
        }
    }

    @Nested
    @DisplayName("Effective-config resolution")
    class EffectiveResolution {

        @Test
        @DisplayName("App-bound JwtAuthConfig wins over the config-parsed default")
        void appOverride_bindsOwnJwtAuthConfig() {
            // given: an app override and a config that also carries a jwt section
            JwtAuthConfig appOverride = new JwtAuthConfig(
                    "appScheme",
                    JwtValidationConfig.builder().issuer("app-issuer").build());
            JsonObject config = new JsonObject().put("jwt", new JsonObject().put("schemeName", "configScheme"));

            // when: resolving the effective config with the app override present
            JwtAuthConfig effective =
                    JwtAuthModule.effectiveJwtAuthConfig(Optional.of(appOverride), config, configParser());

            // then: the app's instance is returned, not the config-parsed default
            assertSame(appOverride, effective, "app override must take precedence over config parse");
        }

        @Test
        @DisplayName("Absent app override falls back to the config-parsed jwt section")
        void noOverride_usesConfigSection() {
            // given: no app override and a config jwt section
            JsonObject config = new JsonObject().put("jwt", new JsonObject().put("schemeName", "configScheme"));

            // when: resolving the effective config with no app override
            JwtAuthConfig effective = JwtAuthModule.effectiveJwtAuthConfig(Optional.empty(), config, configParser());

            // then: the config-parsed value is used
            assertEquals("configScheme", effective.schemeName(), "config section must be parsed when no override");
        }
    }
}

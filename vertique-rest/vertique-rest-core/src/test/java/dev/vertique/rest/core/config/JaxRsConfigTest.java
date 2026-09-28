// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link JaxRsConfig} defaults relevant to request-validation strategy selection, and the
 * pattern-input bounds of the {@code web-validation} gate: their defaults and their JSON binding.
 */
class JaxRsConfigTest {

    @Test
    @DisplayName("validationStrategy defaults to \"web-validation\"")
    void jaxRsConfigDefaultsValidationStrategyToWebValidation() {
        JaxRsConfig config = JaxRsConfig.builder().build();

        assertEquals(
                "web-validation",
                config.validationStrategy(),
                "default JaxRsConfig must select the web-validation strategy");
    }

    /**
     * TP-001 (rest-022 T004): {@code jaxrs.validationPatternMaxChars} and {@code
     * jaxrs.validationPatternMaxTotalChars} default to 4,096 and 262,144, bind from JSON, and leave the
     * other keys at their defaults.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    @DisplayName("TP-001: the two pattern-input bounds default to 4,096 and 262,144 and bind from JSON")
    void patternInputBoundsDefaultAndBindFromJson() throws Exception {
        JaxRsConfig built = JaxRsConfig.builder().build();
        JaxRsConfig parsed = new ObjectMapper()
                .readValue(
                        "{\"validationPatternMaxChars\": 16, \"validationPatternMaxTotalChars\": 64}",
                        JaxRsConfig.class);

        assertAll(
                "the built config's defaults",
                () -> assertEquals(4096, built.validationPatternMaxChars(), "validationPatternMaxChars"),
                () -> assertEquals(262144, built.validationPatternMaxTotalChars(), "validationPatternMaxTotalChars"));
        assertAll(
                "the parsed config",
                () -> assertEquals(16, parsed.validationPatternMaxChars(), "validationPatternMaxChars"),
                () -> assertEquals(64, parsed.validationPatternMaxTotalChars(), "validationPatternMaxTotalChars"),
                () -> assertEquals("aggregate", parsed.validationMode(), "the other keys keep their defaults"));
    }
}

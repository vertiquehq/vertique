// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link JaxRsConfig} defaults relevant to request-validation strategy selection.
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
}

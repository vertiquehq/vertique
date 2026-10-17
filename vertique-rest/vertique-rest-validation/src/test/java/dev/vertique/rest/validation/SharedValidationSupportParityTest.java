// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.json.schema.validation.PatternInputGuard;
import dev.vertique.json.schema.validation.SchemaKeywords;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.validation.SchemaErrorKeywords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that the transport-neutral schema-validation support module and the REST-side definitions agree on the
 * structural keyword set and on the pattern-input bound defaults, so moving this module onto the shared
 * support cannot change either observable behavior.
 *
 * <p>{@code PatternInputGuard} is imported explicitly from the shared module; the single-type import shadows
 * this package's own guard of the same simple name.
 */
class SharedValidationSupportParityTest {

    /** Given both keyword sets, when compared, then they contain exactly the same keywords. */
    @Test
    @DisplayName("shared structural keyword set equals the REST structural keyword set")
    void structuralKeywordSetsAreIdentical() {
        // Given
        var shared = SchemaKeywords.STRUCTURAL_KEYWORDS;
        var rest = SchemaErrorKeywords.STRUCTURAL_KEYWORDS;

        // When / Then
        assertEquals(rest, shared);
    }

    /** Given a default JAX-RS config, when its pattern bounds are read, then they equal the shared guard defaults. */
    @Test
    @DisplayName("shared pattern-input guard defaults equal the JAX-RS config defaults")
    void patternBoundDefaultsMatchJaxRsConfig() {
        // Given
        var config = JaxRsConfig.builder().build();

        // When
        int configMaxChars = config.validationPatternMaxChars();
        int configMaxTotalChars = config.validationPatternMaxTotalChars();

        // Then
        assertAll(
                () -> assertEquals(configMaxChars, PatternInputGuard.DEFAULT_MAX_CHARS),
                () -> assertEquals(configMaxTotalChars, PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS));
    }
}

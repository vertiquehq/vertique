// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonProfileCodec;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SchemaImplementationGuard}'s own behavior for a map-valued
 * {@code @Schema(implementation = ...)} redirect: the guard walks a map value at every position
 * (the inherited {@link Map} binding at index 1), so a redirect over an overridden map value
 * fails closed. This class characterizes that fail-closed behavior.
 *
 * <p>The fixture reuses the real {@code vertique-strict} profile ({@link HardeningFixtures#strictProfile()}),
 * whose only declared override is a {@code BOTH}-direction {@code BigDecimal} decimal-string fragment —
 * the same override every other {@code GeneratorCompositionTest} redirect proof exercises — applied here
 * to a <em>directly declared</em>, non-subclass {@code Map<String, BigDecimal>} member, distinct from
 * {@code GeneratorCompositionTest.mapSubclassValueImplementationRedirectFailsGeneration}'s own {@code Map}
 * <em>subclass</em> fixture: both reach the guard's own map-value walk, through different
 * {@code declaredTypeGraphChildren} branches (a directly declared type's own self-parameters versus an
 * inherited {@code Map} binding), so both must fail identically.
 */
class SchemaImplementationGuardMapValueTest {

    @Test
    @DisplayName("A redirect over a directly declared Map<K,V> member with an overridden V fails generation")
    void redirectPlusOverriddenMapValueFailsGeneration() {
        JsonProfileCodec strict = HardeningFixtures.strictProfile();
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(strict);

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(RedirectedMapValueDto.class),
                "a redirect over a Map<String, BigDecimal> member, where BigDecimal is overridden by the"
                        + " active profile, must fail generation — the guard's own walk already visits the"
                        + " inherited Map binding's value position (index 1)");

        String message = failure.getMessage();
        assertNotNull(message, "the guard failure must carry a message");
        assertTrue(
                message.contains("amounts"),
                "the message must name the redirected property 'amounts'; was: " + message);
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("Sensitivity proof: a non-map-valued redirect still fails generation")
    void nonMapValuedRedirectStillFails() {
        JsonProfileCodec strict = HardeningFixtures.strictProfile();
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(strict);

        // The directly overridden declared type — GeneratorCompositionTest's own shape — must
        // fail identically: the guard's reasoning for a non-map redirect position is separate.
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.ImplementationOnOverriddenDto.class),
                "a non-map-valued redirect must still fail generation, unaffected by the" + " map-value handling");
        assertTrue(
                failure.getMessage().contains("amount"),
                "the message must still name the redirected property 'amount'; was: " + failure.getMessage());

        // A map-key-only override must still succeed: the guard's own key exclusion applies.
        String canonical = generator.generateCanonical(HardeningFixtures.MapSubclassKeyOnlyImplementationDto.class);
        assertNotNull(canonical, "a map-key-only override must still succeed");
    }

    // --- Fixtures ---

    /** A directly declared (non-subclass) {@code Map<String, BigDecimal>} member carrying a redirect. */
    static final class RedirectedMapValueDto {

        /** The value position under test — a directly declared, non-subclass Map value. */
        @Schema(implementation = Number.class)
        public Map<String, BigDecimal> amounts;
    }
}

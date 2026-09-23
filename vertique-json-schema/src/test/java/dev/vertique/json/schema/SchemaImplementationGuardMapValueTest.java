// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonMapperProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rest-023 T003 (TP-003; {@code D001}). {@link SchemaImplementationGuard}'s own behavior for a
 * map-valued {@code @Schema(implementation = ...)} redirect is <strong>unchanged</strong> by this
 * task: round-1 architecture review found the guard already walks a map value at every position this
 * task describes ({@code SchemaImplementationGuard.java:325-347}, the inherited {@link Map} binding at
 * index 1), so a red-first proof here would only be achievable by narrowing the guard's own walk —
 * which this task's contract forbids ({@code evidence/security-design-review-round-1.md} MEDIUM). This
 * class characterizes the guard's own unchanged fail-closed behavior instead: it must be green both at
 * this task's own pre-change baseline and after this task's own production change lands.
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
    @DisplayName("A redirect over a directly declared Map<K,V> member with an overridden V fails generation,"
            + " identically at this task's own baseline and after")
    void redirectPlusOverriddenMapValueFailsAtBaselineAndAfter() {
        JsonMapperProfile strict = HardeningFixtures.strictProfile();
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(strict);

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(RedirectedMapValueDto.class),
                "a redirect over a Map<String, BigDecimal> member, where BigDecimal is overridden by the"
                        + " active profile, must fail generation — the guard's own walk already visits the"
                        + " inherited Map binding's value position (index 1) at this task's own pre-change"
                        + " baseline, and this task changes no code path of the guard, only its rationale"
                        + " Javadoc");

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
    @DisplayName("Sensitivity proof: a non-map-valued redirect (the M10-like shape) is unaffected by this task")
    void nonMapValuedRedirectIsUnaffected() {
        JsonMapperProfile strict = HardeningFixtures.strictProfile();
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(strict);

        // The directly overridden declared type — GeneratorCompositionTest's own baseline shape — must
        // still fail identically: this task touches no reasoning for a non-map redirect position.
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.ImplementationOnOverriddenDto.class),
                "a non-map-valued redirect must still fail generation, unaffected by this task's own"
                        + " map-value renderer extension");
        assertTrue(
                failure.getMessage().contains("amount"),
                "the message must still name the redirected property 'amount'; was: " + failure.getMessage());

        // A map-key-only override must still succeed: the guard's own key exclusion is unchanged.
        String canonical = generator.generateCanonical(HardeningFixtures.MapSubclassKeyOnlyImplementationDto.class);
        assertNotNull(canonical, "a map-key-only override must still succeed, unaffected by this task");
    }

    // --- Fixtures ---

    /** A directly declared (non-subclass) {@code Map<String, BigDecimal>} member carrying a redirect. */
    static final class RedirectedMapValueDto {

        /** The value position under test — a directly declared, non-subclass Map value. */
        @Schema(implementation = Number.class)
        public Map<String, BigDecimal> amounts;
    }
}

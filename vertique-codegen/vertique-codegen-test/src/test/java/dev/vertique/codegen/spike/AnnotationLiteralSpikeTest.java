// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Annotation-literal feasibility spike for CODEGEN-013 Phase 0 slice 0.3 (OQ-2).
 *
 * <p>The AOP codegen materializes an aspect-trigger annotation as a generated {@code Xxx$AopLiteral
 * implements Xxx} of compile-time constants (no {@code getAnnotation} reflection at call time). OQ-2
 * asks whether such a generated literal can be made <strong>contract-correct</strong> per {@link
 * java.lang.annotation.Annotation} — i.e. {@link java.lang.annotation.Annotation#annotationType()}
 * returns the annotation type, and {@code equals}/{@code hashCode} match a reflectively-obtained
 * instance so the literal may even serve as a map/set key.
 *
 * <p>This test proves the <em>strong form of branch (a)</em> against a representative hand-written
 * literal ({@code SampleTriggerLiteral}). The literal class is the GREEN step and is deliberately
 * ABSENT in the RED phase, so this test fails to compile until it is written. An emitter generating
 * the identical shape in Phase 1/2 then follows trivially.
 *
 * @see SampleTrigger
 * @see SampleTriggerHolder
 */
class AnnotationLiteralSpikeTest {

    @Test
    @DisplayName(
            "hand-written literal round-trips annotationType, equals/hashCode, and set-key usability against a reflective instance")
    void literalRoundTripsAnnotationTypeAndEquality() {
        // --- Given: a reflectively-obtained instance and a literal with the SAME values ---
        SampleTrigger reflective = SampleTriggerHolder.class.getAnnotation(SampleTrigger.class);
        SampleTrigger literal = new SampleTriggerLiteral("x", 5, String.class);

        // --- Then: annotationType() returns the annotation type ---
        assertSame(
                SampleTrigger.class,
                literal.annotationType(),
                "literal.annotationType() must return the annotation type SampleTrigger.class");

        // --- Then: equals is symmetric with the reflective instance ---
        assertTrue(
                literal.equals(reflective),
                "literal.equals(reflective) must be true per the Annotation equals contract");
        assertTrue(reflective.equals(literal), "reflective.equals(literal) must be true (equals symmetry)");

        // --- Then: hashCode matches the reflective instance (Annotation hashCode contract) ---
        assertEquals(
                reflective.hashCode(),
                literal.hashCode(),
                "literal.hashCode() must equal reflective.hashCode() per the Annotation hashCode contract");

        // --- Then: the literal is usable as a set/map key (strong form of branch (a)) ---
        assertTrue(
                Set.of(reflective).contains(literal),
                "a Set built from the reflective instance must contain the literal (map/set-key usability)");
    }
}

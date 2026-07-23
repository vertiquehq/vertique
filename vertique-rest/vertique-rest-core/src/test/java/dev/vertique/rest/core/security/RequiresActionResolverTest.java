// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.RequiresAction;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequiresActionResolver}.
 *
 * <p>Verifies that {@code @RequiresAction} is resolved from pre-resolved method/class annotation
 * lists with Jakarta override semantics (method beats class), that an absent annotation yields
 * {@link Optional#empty()}, and that an unparseable action value fails fast.
 */
class RequiresActionResolverTest {

    private final RequiresActionResolver resolver = new RequiresActionResolver();

    /** Synthesizes a {@link RequiresAction} instance carrying the given value. */
    private static RequiresAction requiresAction(String value) {
        return new RequiresAction() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return RequiresAction.class;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("Method-level @RequiresAction takes precedence over class-level")
    void method_annotation_takes_precedence() {
        Optional<ActionRef> result =
                resolver.resolve(List.of(requiresAction("a.b.c")), List.of(requiresAction("x.y.z")));

        assertTrue(result.isPresent());
        assertEquals(ActionRef.parse("a.b.c"), result.get());
    }

    @Test
    @DisplayName("Class-level @RequiresAction used when method has none")
    void class_annotation_used_when_method_absent() {
        Optional<ActionRef> result = resolver.resolve(List.of(), List.of(requiresAction("x.y.z")));

        assertTrue(result.isPresent());
        assertEquals(ActionRef.parse("x.y.z"), result.get());
    }

    @Test
    @DisplayName("No @RequiresAction on either level returns empty")
    void no_annotation_returnsEmpty() {
        Optional<ActionRef> result = resolver.resolve(List.of(), List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Unparseable @RequiresAction value throws IllegalArgumentException")
    void invalid_action_value_throws() {
        assertThrows(
                IllegalArgumentException.class, () -> resolver.resolve(List.of(requiresAction("INVALID")), List.of()));
    }
}

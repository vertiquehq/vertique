// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import java.lang.reflect.TypeVariable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the structural contracts of the {@link ContextValue} marker interface and confirms that
 * the core framework context types — {@link CorrelationContext} and {@link DurablePropagationMetadata}
 * — implement it. The assertion that {@code SecurityContext} also implements {@code ContextValue}
 * lives with the security module (it is no longer a core type).
 */
class ContextValueTest {

    @Test
    @DisplayName("ContextValue is a behaviorless marker interface")
    void markerHasNoMethods() {
        assertTrue(ContextValue.class.isInterface());
        assertEquals(0, ContextValue.class.getDeclaredMethods().length);
    }

    @Test
    @DisplayName("CorrelationContext implements ContextValue")
    void correlationContextIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(CorrelationContext.class));
    }

    @Test
    @DisplayName("DurablePropagationMetadata implements ContextValue")
    void durablePropagationMetadataIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(DurablePropagationMetadata.class));
    }

    // --- ContextHolder.bind generic bound ---

    @Test
    @DisplayName("ContextHolder.bind type parameter is bounded by ContextValue")
    void holderBindTypeParameterBoundedByContextValue() throws NoSuchMethodException {
        // T extends ContextValue erases the second parameter to ContextValue at the bytecode level.
        TypeVariable<?>[] typeParams = ContextHolder.class
                .getMethod("bind", Class.class, ContextValue.class)
                .getTypeParameters();
        assertEquals(1, typeParams.length, "bind must have exactly one type parameter");
        assertSame(
                ContextValue.class,
                typeParams[0].getBounds()[0],
                "bind type parameter must be bounded by ContextValue");
    }
}

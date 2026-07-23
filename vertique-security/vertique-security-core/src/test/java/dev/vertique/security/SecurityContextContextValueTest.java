// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SecurityContext} implements the core {@link ContextValue} marker interface so
 * it can be bound into the dispatch context and resolved by context-aware consumers.
 *
 * <p>This assertion previously lived in {@code vertique-core}'s {@code ContextValueTest}; it moved
 * here with {@link SecurityContext} when the security types were extracted into the
 * {@code vertique-security} family, so {@code vertique-core} keeps no test dependency on a security
 * type.
 */
class SecurityContextContextValueTest {

    @Test
    @DisplayName("SecurityContext implements ContextValue")
    void securityContextIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(SecurityContext.class));
    }
}

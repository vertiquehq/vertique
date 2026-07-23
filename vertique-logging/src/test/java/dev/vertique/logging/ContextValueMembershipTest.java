// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link MDCContext} is a member of the {@link ContextValue} marker hierarchy,
 * satisfying the compile-time safety and runtime-validation contract described in
 * {@link ContextValue}. The test lives in the same package as {@code MDCContext} so it can
 * reference the package-private type.
 */
class ContextValueMembershipTest {

    @Test
    @DisplayName("MDCContext implements ContextValue")
    void mdcContextIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(MDCContext.class));
    }
}

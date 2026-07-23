// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link LocalizationContext} is a member of the {@link ContextValue} marker
 * hierarchy, satisfying the compile-time safety and runtime-validation contract described in
 * {@link ContextValue}.
 */
class ContextValueMembershipTest {

    @Test
    @DisplayName("LocalizationContext implements ContextValue")
    void localizationContextIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(LocalizationContext.class));
    }
}

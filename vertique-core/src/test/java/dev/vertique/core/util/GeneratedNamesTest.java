// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link GeneratedNames#companionFqn(Class, String)} reproduces the codegen processor's
 * flattened companion naming for both top-level and nested origin types.
 */
class GeneratedNamesTest {

    @Test
    @DisplayName("top-level origin: package + simple name + suffix")
    void topLevelOrigin() {
        assertEquals(
                "dev.vertique.core.util.GeneratedNamesTest_DelayedJobProxy",
                GeneratedNames.companionFqn(GeneratedNamesTest.class, "_DelayedJobProxy"));
    }

    @Test
    @DisplayName("nested origin: $ separator becomes _ to match the flattened generated name")
    void nestedOrigin() {
        assertEquals(
                "dev.vertique.core.util.GeneratedNamesTest_Inner_DelayedJobProxy",
                GeneratedNames.companionFqn(Inner.class, "_DelayedJobProxy"));
    }

    @Test
    @DisplayName("doubly-nested origin flattens every enclosing level")
    void doublyNestedOrigin() {
        assertEquals(
                "dev.vertique.core.util.GeneratedNamesTest_Inner_Leaf_BindingMeta",
                GeneratedNames.companionFqn(Inner.Leaf.class, "_BindingMeta"));
    }

    /** Nested fixture type to exercise the {@code $}-to-{@code _} flattening. */
    static final class Inner {
        /** Doubly-nested fixture type. */
        static final class Leaf {}
    }
}

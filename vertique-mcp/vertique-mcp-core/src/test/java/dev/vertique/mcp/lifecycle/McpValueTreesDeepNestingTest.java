// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link McpValueTrees#deepUnmodifiableMap} walks a {@code Map}/{@code List} nesting with an
 * explicit work stack rather than native call recursion, so an adversarially deep argument tree
 * cannot exhaust the JVM call stack. Every generated invoker's {@code prepare()} calls it.
 *
 * <p>The nesting depth here (200,000 levels) reliably overflows a JVM default call stack when walked
 * by native recursion, and comfortably completes with an explicit-stack walk.
 */
class McpValueTreesDeepNestingTest {

    private static final int DEPTH = 200_000;

    @Test
    @DisplayName("copies a 200,000-level nested argument tree without a StackOverflowError")
    void shouldCopyAVeryDeeplyNestedTreeWithoutOverflowing() {
        Map<String, Object> deep = deeplyNestedMap(DEPTH);

        assertThatCode(() -> McpValueTrees.deepUnmodifiableMap(deep)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DECISIVE: the copy is unmodifiable at every nesting level")
    void shouldRejectMutationAtTheDeepestNestingLevel() {
        Map<String, Object> copy = McpValueTrees.deepUnmodifiableMap(deeplyNestedMap(3));

        @SuppressWarnings("unchecked")
        Map<String, Object> level1 = (Map<String, Object>) copy.get("nested");
        @SuppressWarnings("unchecked")
        Map<String, Object> level2 = (Map<String, Object>) level1.get("nested");

        assertThat(level2)
                .as("the deepest level must still be a live nested map")
                .isNotNull();
        assertThrows(
                UnsupportedOperationException.class,
                () -> level2.put("mutated", "value"),
                "an unmodifiable view at every nesting level, not merely the outermost one");
    }

    /** Builds a {@code Map<String, Object>} nested {@code depth} levels deep under the key {@code "nested"}. */
    private static Map<String, Object> deeplyNestedMap(int depth) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("value", "leaf");
        Map<String, Object> current = leaf;
        for (int i = 0; i < depth; i++) {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("nested", current);
            current = parent;
        }
        return current;
    }
}

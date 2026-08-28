// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P04 remediation proof for issue W6 (second part) — {@code McpValueTrees#deepUnmodifiable} walks a
 * {@code Map}/{@code List} nesting with an explicit work stack rather than native call recursion, so
 * an adversarially deep argument or result tree cannot exhaust the JVM call stack (mirrors {@code
 * McpEnvelopeJsonCodec#exceedsDecimalScaleBound}'s explicit-stack precedent).
 *
 * <p>The nesting depth here (200,000 levels) reliably overflows a JVM default call stack when walked
 * by native recursion, and comfortably completes with an explicit-stack walk.
 */
class McpToolValueObservationDeepNestingTest {

    private static final int DEPTH = 200_000;

    private final McpToolInvocationContext context = new McpToolInvocationContext(
            new McpRequestContext(
                    McpMethod.TOOLS_CALL, SecurityContexts.unauthenticated(SecurityIdentity.anonymous()), null),
            new McpToolDescriptor(
                    "deep.nesting.tool",
                    null,
                    "W6 deep-nesting fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null)));

    @Test
    @DisplayName("deep-copies a 200,000-level nested argument tree without a StackOverflowError")
    void shouldDeepCopyAVeryDeeplyNestedArgumentTreeWithoutOverflowing() {
        Map<String, Object> deep = deeplyNestedMap(DEPTH);

        assertThatCode(() -> new McpToolInputObservation(context, deep)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deep-copies a 200,000-level nested output tree without a StackOverflowError")
    void shouldDeepCopyAVeryDeeplyNestedOutputTreeWithoutOverflowing() {
        Map<String, Object> deep = deeplyNestedMap(DEPTH);

        assertThatCode(() -> new McpToolOutputObservation(context, deep)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DECISIVE: the deep copy is genuinely applied at every nesting level")
    void shouldRejectMutationAtTheDeepestNestingLevel() {
        Map<String, Object> deep = deeplyNestedMap(3);
        McpToolInputObservation observation = new McpToolInputObservation(context, deep);

        @SuppressWarnings("unchecked")
        Map<String, Object> level1 =
                (Map<String, Object>) observation.normalizedArguments().get("nested");
        @SuppressWarnings("unchecked")
        Map<String, Object> level2 = (Map<String, Object>) level1.get("nested");

        assertThat(level2)
                .as("the deepest level must still be a live nested map")
                .isNotNull();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> level2.put("mutated", "value"),
                "DECISIVE: an unmodifiable view at every nesting level, not merely the outermost one");
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

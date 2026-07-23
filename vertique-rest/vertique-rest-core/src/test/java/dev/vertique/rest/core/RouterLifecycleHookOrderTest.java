// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RouterLifecycleHook} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, and lower priority sorts first within a phase.
 *
 * <p>The sort used at the hook site is {@link OrderedExtension#comparator()}.
 */
class RouterLifecycleHookOrderTest {

    /**
     * Minimal {@link RouterLifecycleHook} test double with configurable phase and priority.
     * All lifecycle callbacks are no-ops; only ordering behaviour is under test.
     */
    private static final class TestHook implements RouterLifecycleHook {

        private final ExtensionPhase phase;
        private final int priority;

        TestHook(ExtensionPhase phase, int priority) {
            this.phase = phase;
            this.priority = priority;
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestHook systemFirst = new TestHook(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestHook application = new TestHook(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<RouterLifecycleHook> hooks = new ArrayList<>(List.of(application, systemFirst));
        hooks.sort(OrderedExtension.comparator());

        assertSame(systemFirst, hooks.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, hooks.get(1));
    }

    @Test
    @DisplayName("lower priority sorts first within the APPLICATION phase")
    void lowerPriorityFirst() {
        TestHook low = new TestHook(ExtensionPhase.APPLICATION, 0);
        TestHook high = new TestHook(ExtensionPhase.APPLICATION, 100);

        List<RouterLifecycleHook> hooks = new ArrayList<>(List.of(high, low));
        hooks.sort(OrderedExtension.comparator());

        assertSame(low, hooks.get(0), "priority 0 must sort before priority 100");
        assertSame(high, hooks.get(1));
    }
}

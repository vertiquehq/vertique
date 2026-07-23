// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ErrorInterceptor} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, and lower priority sorts first within a phase.
 *
 * <p>The sort used at the interceptor site is {@link OrderedExtension#comparator()}.
 */
class ErrorInterceptorOrderTest {

    /**
     * Minimal {@link ErrorInterceptor} test double with configurable phase and priority.
     * All callback methods retain their default pass-through implementations;
     * only ordering behaviour is under test.
     */
    private static final class TestInterceptor implements ErrorInterceptor {

        private final ExtensionPhase phase;
        private final int priority;

        TestInterceptor(ExtensionPhase phase, int priority) {
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
        TestInterceptor systemFirst = new TestInterceptor(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestInterceptor application = new TestInterceptor(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<ErrorInterceptor> interceptors = new ArrayList<>(List.of(application, systemFirst));
        interceptors.sort(OrderedExtension.comparator());

        assertSame(
                systemFirst, interceptors.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, interceptors.get(1));
    }

    @Test
    @DisplayName("lower priority sorts first within the APPLICATION phase")
    void lowerPriorityFirst() {
        TestInterceptor low = new TestInterceptor(ExtensionPhase.APPLICATION, 0);
        TestInterceptor high = new TestInterceptor(ExtensionPhase.APPLICATION, 100);

        List<ErrorInterceptor> interceptors = new ArrayList<>(List.of(high, low));
        interceptors.sort(OrderedExtension.comparator());

        assertSame(low, interceptors.get(0), "priority 0 must sort before priority 100");
        assertSame(high, interceptors.get(1));
    }
}

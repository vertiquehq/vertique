// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that {@link ServiceInterceptor} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, and lower priority sorts first within a phase.
 *
 * <p>Also verifies that {@link ServiceAuthorizationInterceptor} advertises the correct phase and
 * priority so it sorts ahead of all {@link ExtensionPhase#APPLICATION} interceptors.
 *
 * <p>The sort used at the interceptor site is {@link OrderedExtension#comparator()}.
 */
class ServiceInterceptorOrderTest {

    /**
     * Minimal {@link ServiceInterceptor} test double with configurable phase and priority.
     * All callback methods retain their default no-op / pass-through implementations;
     * only ordering behaviour is under test.
     */
    private static final class TestInterceptor implements ServiceInterceptor {

        private final ExtensionPhase phase;
        private final int priority;

        /**
         * Constructs a test interceptor with the given ordering parameters.
         *
         * @param phase    the {@link ExtensionPhase} this interceptor reports
         * @param priority the numeric priority within the phase
         */
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

        List<ServiceInterceptor> interceptors = new ArrayList<>(List.of(application, systemFirst));
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

        List<ServiceInterceptor> interceptors = new ArrayList<>(List.of(high, low));
        interceptors.sort(OrderedExtension.comparator());

        assertSame(low, interceptors.get(0), "priority 0 must sort before priority 100");
        assertSame(high, interceptors.get(1));
    }

    @Test
    @DisplayName("ServiceAuthorizationInterceptor sorts before any APPLICATION interceptor")
    void authorizationInterceptorSortsBeforeApplicationInterceptors() {
        // Build a minimal ServiceAuthorizationInterceptor with no metas (no @RequiresAction to
        // scan, so construction does not require a real registry/authorizer).
        ContextHolder holder = Mockito.mock(ContextHolder.class);
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
        ServiceAuthorizationInterceptor authzInterceptor =
                new ServiceAuthorizationInterceptor(Optional.empty(), Optional.empty(), emitter, holder, Set.of());

        TestInterceptor applicationInterceptor = new TestInterceptor(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<ServiceInterceptor> interceptors = new ArrayList<>(List.of(applicationInterceptor, authzInterceptor));
        interceptors.sort(OrderedExtension.comparator());

        assertSame(
                authzInterceptor,
                interceptors.get(0),
                "ServiceAuthorizationInterceptor (SYSTEM_FIRST/-100) must sort before any APPLICATION interceptor");
        assertTrue(authzInterceptor.phase() == ExtensionPhase.SYSTEM_FIRST, "phase must be SYSTEM_FIRST");
        assertTrue(
                authzInterceptor.priority() < 0,
                "priority must be negative (currently -100) to sort at the front of SYSTEM_FIRST");
    }
}

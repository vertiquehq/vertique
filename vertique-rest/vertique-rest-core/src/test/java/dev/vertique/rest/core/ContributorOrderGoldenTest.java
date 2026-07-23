// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Golden test for the {@link OperationHandlerContributor} ordering contract, ported to the migrated
 * {@link OperationRegistrationContext} shape (neutral {@link RestOperationDescriptor} +
 * {@link RouteRegistration}).
 *
 * <p>Verifies two contracts that the route-registration migration must preserve:
 *
 * <ul>
 *   <li><strong>Sort order</strong> — contributors sort by the {@link OrderedExtension} contract
 *       (phase first, then {@link OrderedExtension#priority()} ascending), and
 *       {@link OperationHandlerContributor#priority()} remains mandatory (abstract).</li>
 *   <li><strong>Handler-chain order</strong> — when sorted contributors {@code contribute(...)} onto a
 *       shared {@link RouteRegistration}, the handlers are added in sorted-priority order, and a
 *       terminal invoker handler (mirroring {@code ResourceMethodInvoker}) added last is the final
 *       handler in the chain.</li>
 * </ul>
 */
class ContributorOrderGoldenTest {

    /**
     * Recording {@link RouteRegistration} test double that captures the order in which handlers are
     * added via {@link #addHandler(Handler)}.
     */
    private static final class RecordingRouteRegistration implements RouteRegistration {

        final List<Handler<RoutingContext>> added = new ArrayList<>();
        private final RestOperationDescriptor operation;

        RecordingRouteRegistration(RestOperationDescriptor operation) {
            this.operation = operation;
        }

        @Override
        public RouteRegistration addHandler(Handler<RoutingContext> handler) {
            added.add(handler);
            return this;
        }

        @Override
        public RestOperationDescriptor operation() {
            return operation;
        }
    }

    /**
     * Minimal {@link OperationHandlerContributor} test double with configurable phase and priority
     * that contributes a single uniquely-identifiable handler onto the context's
     * {@link RouteRegistration}.
     *
     * <p>{@link #priority()} is explicitly implemented — proving it is still mandatory (abstract) on
     * the interface and not defaulted by {@link OrderedExtension}.
     */
    private static final class TestContributor implements OperationHandlerContributor {

        private final ExtensionPhase phase;
        private final int priority;
        final Handler<RoutingContext> handler;

        @SuppressWarnings("unchecked")
        TestContributor(ExtensionPhase phase, int priority) {
            this.phase = phase;
            this.priority = priority;
            this.handler = Mockito.mock(Handler.class);
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        /** Must be implemented — {@link OperationHandlerContributor#priority()} is abstract. */
        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            context.route().addHandler(handler);
        }
    }

    private static OperationRegistrationContext contextFor(RouteRegistration route) {
        return new OperationRegistrationContext(
                "op", new SecurityPolicy.None(), Optional.empty(), route.operation(), route);
    }

    @Test
    @DisplayName("priority bands preserved: 80 sorts before 100, which sorts before 350")
    void bandsPreservedByPriority() {
        TestContributor preAuth = new TestContributor(ExtensionPhase.APPLICATION, 80);
        TestContributor auth = new TestContributor(ExtensionPhase.APPLICATION, 100);
        TestContributor postContext = new TestContributor(ExtensionPhase.APPLICATION, 350);

        List<OperationHandlerContributor> contributors = new ArrayList<>(List.of(postContext, auth, preAuth));
        contributors.sort(OrderedExtension.comparator());

        assertSame(preAuth, contributors.get(0), "priority 80 (pre-auth band) must sort first");
        assertSame(auth, contributors.get(1), "priority 100 (auth band) must sort second");
        assertSame(postContext, contributors.get(2), "priority 350 (post-context band) must sort last");
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestContributor systemFirst = new TestContributor(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestContributor application = new TestContributor(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<OperationHandlerContributor> contributors = new ArrayList<>(List.of(application, systemFirst));
        contributors.sort(OrderedExtension.comparator());

        assertSame(
                systemFirst, contributors.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, contributors.get(1));
    }

    @Test
    @DisplayName("handlers are added in sorted-priority order; the terminal invoker is added last")
    @SuppressWarnings("unchecked")
    void handlerChainOrderMatchesSortedPriorityWithInvokerLast() {
        // Use the same priority bands as the framework's real contributors:
        // ActionGateAuthentication(40), JwtClaimsValidator(50), IdentityResolution(80),
        // Authorization(100), OperationIdCapture(350).
        TestContributor c40 = new TestContributor(ExtensionPhase.APPLICATION, 40);
        TestContributor c50 = new TestContributor(ExtensionPhase.APPLICATION, 50);
        TestContributor c80 = new TestContributor(ExtensionPhase.APPLICATION, 80);
        TestContributor c100 = new TestContributor(ExtensionPhase.APPLICATION, 100);
        TestContributor c350 = new TestContributor(ExtensionPhase.APPLICATION, 350);

        List<OperationHandlerContributor> contributors = new ArrayList<>(List.of(c100, c350, c40, c80, c50));
        contributors.sort(OrderedExtension.comparator());

        RestOperationDescriptor descriptor = Mockito.mock(RestOperationDescriptor.class);
        RecordingRouteRegistration route = new RecordingRouteRegistration(descriptor);
        OperationRegistrationContext ctx = contextFor(route);

        for (OperationHandlerContributor contributor : contributors) {
            contributor.contribute(ctx);
        }
        // Terminal invoker is always added last, after every contributor handler.
        Handler<RoutingContext> invoker = Mockito.mock(Handler.class);
        route.addHandler(invoker);

        assertEquals(6, route.added.size(), "five contributor handlers plus the terminal invoker");
        assertSame(c40.handler, route.added.get(0), "priority 40 handler first");
        assertSame(c50.handler, route.added.get(1), "priority 50 handler second");
        assertSame(c80.handler, route.added.get(2), "priority 80 handler third");
        assertSame(c100.handler, route.added.get(3), "priority 100 handler fourth");
        assertSame(c350.handler, route.added.get(4), "priority 350 handler fifth");
        assertSame(invoker, route.added.get(5), "terminal invoker added last");
    }
}

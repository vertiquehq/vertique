// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SecurityIdentityResolver} participates in the {@link OrderedExtension}
 * ordering contract — phase dominates priority, lower priority sorts first within a phase, and
 * tie-breaks use {@link SecurityIdentityResolver#id()} (via {@link SecurityIdentityResolver#orderKey()})
 * rather than the fully-qualified class name.
 */
class SecurityIdentityResolverOrderTest {

    /**
     * Minimal test double for {@link SecurityIdentityResolver}.
     *
     * <p>Overrides {@link #id()}, {@link #priority()}, and {@link #phase()} so that ordering
     * assertions can be made independently of the test-double class name.
     */
    private static final class TestResolver implements SecurityIdentityResolver {

        private final String id;
        private final int priority;
        private final ExtensionPhase phase;

        TestResolver(String id, int priority, ExtensionPhase phase) {
            this.id = id;
            this.priority = priority;
            this.phase = phase;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.empty());
        }
    }

    @Test
    @DisplayName("id() tie-break: same phase and priority, different id — sorts ascending by id()")
    void idTieBreakPreserved() {
        TestResolver aaa = new TestResolver("aaa", 0, ExtensionPhase.APPLICATION);
        TestResolver zzz = new TestResolver("zzz", 0, ExtensionPhase.APPLICATION);

        List<SecurityIdentityResolver> resolvers = new ArrayList<>(List.of(zzz, aaa));
        resolvers.sort(OrderedExtension.comparator());

        assertEquals("aaa", resolvers.get(0).id(), "resolver with id 'aaa' must sort before 'zzz'");
        assertEquals("zzz", resolvers.get(1).id());
    }

    @Test
    @DisplayName("lower priority sorts first within the same phase")
    void lowerPriorityFirst() {
        TestResolver low = new TestResolver("low", 50, ExtensionPhase.APPLICATION);
        TestResolver high = new TestResolver("high", 150, ExtensionPhase.APPLICATION);

        List<SecurityIdentityResolver> resolvers = new ArrayList<>(List.of(high, low));
        resolvers.sort(OrderedExtension.comparator());

        assertEquals("low", resolvers.get(0).id(), "priority 50 must sort before priority 150");
        assertEquals("high", resolvers.get(1).id());
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestResolver systemFirst = new TestResolver("system", Integer.MAX_VALUE, ExtensionPhase.SYSTEM_FIRST);
        TestResolver application = new TestResolver("app", Integer.MIN_VALUE, ExtensionPhase.APPLICATION);

        List<SecurityIdentityResolver> resolvers = new ArrayList<>(List.of(application, systemFirst));
        resolvers.sort(OrderedExtension.comparator());

        assertEquals(
                "system", resolvers.get(0).id(), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertEquals("app", resolvers.get(1).id());
    }
}

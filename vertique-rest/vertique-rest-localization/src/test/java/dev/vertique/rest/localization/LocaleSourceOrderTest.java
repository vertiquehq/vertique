// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link LocaleSource} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, lower priority sorts first within a phase — as used by
 * {@link RequestLocaleInterceptor} to determine the locale resolution order.
 *
 * <p>The sort used at the interceptor site is {@link OrderedExtension#comparator()}, with no
 * additional tie-breaks beyond those built into that comparator.
 */
class LocaleSourceOrderTest {

    /**
     * Minimal {@link LocaleSource} test double with configurable name, phase, and priority.
     *
     * <p>{@link #orderKey()} is not overridden so it defaults to this class's FQCN, keeping all
     * instances at the same key. When distinct keys are needed, use the {@code name} field as a
     * prefix in a subclass; for these tests phase and priority are the distinguishing factors.
     *
     * @param name     descriptive label used in assertion messages
     * @param phase    the {@link ExtensionPhase} this source reports
     * @param priority the priority this source reports
     */
    private record TestLocaleSource(String name, ExtensionPhase phase, int priority) implements LocaleSource {

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Optional<ResolvedLocale> resolve(RoutingContext rc) {
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("APPLICATION source at priority 0 sorts before framework-style source at priority 1000")
    void appBeatsAcceptLanguageDefault() {
        TestLocaleSource appSource = new TestLocaleSource("app", ExtensionPhase.APPLICATION, 0);
        TestLocaleSource frameworkSource = new TestLocaleSource("framework", ExtensionPhase.APPLICATION, 1000);

        List<LocaleSource> sources = new ArrayList<>(List.of(frameworkSource, appSource));
        sources.sort(OrderedExtension.comparator());

        assertSame(appSource, sources.get(0), "priority 0 must sort before priority 1000");
        assertSame(frameworkSource, sources.get(1));
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestLocaleSource systemFirst = new TestLocaleSource("system", ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestLocaleSource application = new TestLocaleSource("app", ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<LocaleSource> sources = new ArrayList<>(List.of(application, systemFirst));
        sources.sort(OrderedExtension.comparator());

        assertSame(systemFirst, sources.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, sources.get(1));
    }
}

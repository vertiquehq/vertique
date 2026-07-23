// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link MountCustomizer} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, lower priority sorts first within a phase, and tie-breaks
 * use {@link OrderedExtension#orderKey()} (the fully-qualified class name by default).
 *
 * <p>The sort used at the customizer site is {@link OrderedExtension#comparator()}, with no
 * additional tie-breaks.
 */
class MountCustomizerOrderTest {

    /**
     * Minimal {@link MountCustomizer} test double with configurable phase and priority.
     *
     * <p>{@link #orderKey()} is not overridden, so it defaults to this class's FQCN.
     * The {@link AnotherCustomizer} nested class is used when a distinct order key is needed
     * at equal phase and priority.
     */
    private static final class TestCustomizer implements MountCustomizer {

        private final ExtensionPhase phase;
        private final int priority;

        TestCustomizer(ExtensionPhase phase, int priority) {
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

        @Override
        public void customize(Router mountRouter, MountMeta meta) {
            // no-op test double
        }
    }

    /**
     * Second distinct nested class used to produce a different {@link OrderedExtension#orderKey()}
     * value (defaults to its own FQCN) when phase and priority are equal — the FQCN of this class
     * contains {@code "Another"} which sorts before {@code "Test"} alphabetically.
     */
    private static final class AnotherCustomizer implements MountCustomizer {

        @Override
        public void customize(Router mountRouter, MountMeta meta) {
            // no-op test double
        }
    }

    @Test
    @DisplayName("lower priority sorts first within the APPLICATION phase")
    void sortsByOrderedExtension() {
        TestCustomizer low = new TestCustomizer(ExtensionPhase.APPLICATION, 0);
        TestCustomizer high = new TestCustomizer(ExtensionPhase.APPLICATION, 100);

        List<MountCustomizer> customizers = new ArrayList<>(List.of(high, low));
        customizers.sort(OrderedExtension.comparator());

        assertSame(low, customizers.get(0), "priority 0 must sort before priority 100");
        assertSame(high, customizers.get(1));
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestCustomizer systemFirst = new TestCustomizer(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestCustomizer application = new TestCustomizer(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<MountCustomizer> customizers = new ArrayList<>(List.of(application, systemFirst));
        customizers.sort(OrderedExtension.comparator());

        assertSame(systemFirst, customizers.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, customizers.get(1));
    }
}

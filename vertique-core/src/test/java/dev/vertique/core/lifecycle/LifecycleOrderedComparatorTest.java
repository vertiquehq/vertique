// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LifecycleOrdered#comparator()}: the canonical lifecycle ordering. Verifies
 * that a mixed list of participants sorts by {@link LifecyclePhase} ordinal first, then ascending
 * {@link LifecycleOrdered#priority() priority}, then ascending {@link LifecycleOrdered#orderKey()
 * orderKey} — including a same-{@code (phase, priority)} pair that must be broken by {@code orderKey}.
 */
class LifecycleOrderedComparatorTest {

    @Test
    @DisplayName("comparator orders by phase ordinal, then priority asc, then orderKey asc")
    void comparator_ordersByPhaseThenPriorityThenOrderKey() {
        // A later phase (EDGE) must sort after an earlier one (CONFIGURE) regardless of priority.
        Named edgeLowPriority = new Named(LifecyclePhase.EDGE, -100, "edge");
        Named configureHighPriority = new Named(LifecyclePhase.CONFIGURE, 100, "configure");

        // Same phase: priority asc dominates orderKey.
        Named servicesP0 = new Named(LifecyclePhase.SERVICES, 0, "zeta");
        Named servicesP5 = new Named(LifecyclePhase.SERVICES, 5, "alpha");

        // Same (phase, priority): orderKey breaks the tie ("aaa" before "bbb").
        Named infraBbb = new Named(LifecyclePhase.INFRA, 0, "bbb");
        Named infraAaa = new Named(LifecyclePhase.INFRA, 0, "aaa");

        List<LifecycleOrdered> sorted = new java.util.ArrayList<>(
                List.of(edgeLowPriority, servicesP5, infraBbb, configureHighPriority, infraAaa, servicesP0));
        sorted.sort(LifecycleOrdered.comparator());

        assertEquals(
                List.of(
                        configureHighPriority, // CONFIGURE (ordinal 0) — phase wins over its high priority
                        infraAaa, // INFRA, p0, "aaa"
                        infraBbb, // INFRA, p0, "bbb" — tie broken by orderKey
                        servicesP0, // SERVICES, p0
                        servicesP5, // SERVICES, p5 — priority asc within phase
                        edgeLowPriority), // EDGE (ordinal 6) — phase wins over its low priority
                sorted);
    }

    /** A concrete {@link LifecycleOrdered} test double with explicit phase, priority, and orderKey. */
    private record Named(LifecyclePhase phase, int priority, String orderKey) implements LifecycleOrdered {}
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LifecyclePhase}: the single public lifecycle phase vocabulary. Verifies the
 * eight constants exist in their exact declared (ordinal) order — which the comparator and the
 * verticle-subset invariant both depend on — and that {@link LifecyclePhase#isVerticlePhase()}
 * reports exactly the {@code BOOTSTRAP/INFRA/SERVICES/EDGE} subset.
 */
class LifecyclePhaseTest {

    @Test
    @DisplayName("values() lists the eight phases in the exact declared order")
    void values_areInDeclaredOrder() {
        LifecyclePhase[] expected = {
            LifecyclePhase.CONFIGURE,
            LifecyclePhase.VALIDATE,
            LifecyclePhase.MIGRATE,
            LifecyclePhase.BOOTSTRAP,
            LifecyclePhase.INFRA,
            LifecyclePhase.SERVICES,
            LifecyclePhase.EDGE,
            LifecyclePhase.AFTER_START
        };

        assertArrayEquals(expected, LifecyclePhase.values());
    }

    @Test
    @DisplayName("isVerticlePhase() is true for exactly BOOTSTRAP/INFRA/SERVICES/EDGE")
    void isVerticlePhase_trueForVerticleSubset() {
        Set<LifecyclePhase> verticlePhases = EnumSet.of(
                LifecyclePhase.BOOTSTRAP, LifecyclePhase.INFRA, LifecyclePhase.SERVICES, LifecyclePhase.EDGE);

        for (LifecyclePhase phase : LifecyclePhase.values()) {
            if (verticlePhases.contains(phase)) {
                assertTrue(phase.isVerticlePhase(), phase + " should be a verticle phase");
            } else {
                assertFalse(phase.isVerticlePhase(), phase + " should not be a verticle phase");
            }
        }
    }
}

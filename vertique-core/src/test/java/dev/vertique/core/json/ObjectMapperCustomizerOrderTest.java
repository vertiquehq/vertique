// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.extension.ExtensionPhase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link JacksonConfigurer} applies {@link ObjectMapperCustomizer}s in
 * {@link dev.vertique.core.extension.OrderedExtension} order — phase dominates priority, then
 * priority ascending — rather than in Dagger-set encounter order.
 */
class ObjectMapperCustomizerOrderTest {

    /** A customizer that records the order in which it is applied. */
    private static final class RecordingCustomizer implements ObjectMapperCustomizer {
        private final String name;
        private final ExtensionPhase phase;
        private final int priority;
        private final List<String> applied;

        RecordingCustomizer(String name, ExtensionPhase phase, int priority, List<String> applied) {
            this.name = name;
            this.phase = phase;
            this.priority = priority;
            this.applied = applied;
        }

        @Override
        public void customize(ObjectMapper mapper) {
            applied.add(name);
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
    @DisplayName("SYSTEM_FIRST applies before APPLICATION regardless of priority")
    void systemFirstAppliesBeforeApplication() {
        List<String> applied = new ArrayList<>();
        // Insertion order puts APPLICATION first on purpose, to prove sorting (not encounter order).
        Set<ObjectMapperCustomizer> customizers = new LinkedHashSet<>();
        customizers.add(new RecordingCustomizer("app", ExtensionPhase.APPLICATION, Integer.MIN_VALUE, applied));
        customizers.add(new RecordingCustomizer("system", ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE, applied));

        new JacksonConfigurer(customizers).configure(new ObjectMapper());

        assertEquals(
                List.of("system", "app"), applied, "SYSTEM_FIRST must apply before APPLICATION regardless of priority");
    }

    @Test
    @DisplayName("within a phase, lower priority applies first")
    void lowerPriorityAppliesFirst() {
        List<String> applied = new ArrayList<>();
        Set<ObjectMapperCustomizer> customizers = new LinkedHashSet<>();
        customizers.add(new RecordingCustomizer("high", ExtensionPhase.APPLICATION, 100, applied));
        customizers.add(new RecordingCustomizer("low", ExtensionPhase.APPLICATION, -100, applied));

        new JacksonConfigurer(customizers).configure(new ObjectMapper());

        assertEquals(List.of("low", "high"), applied, "lower priority must apply first");
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the last-wins fold semantics for {@link RestExceptionMapperCustomizer} when sorted by
 * {@link OrderedExtension#comparator()}: a customizer that sorts later is applied last and therefore
 * wins any registration conflict on the same exception type.
 *
 * <p>Two properties are tested:
 * <ol>
 *   <li>Within {@link ExtensionPhase#APPLICATION APPLICATION}, the customizer with the higher numeric
 *       priority sorts last and is applied last.</li>
 *   <li>A {@link ExtensionPhase#SYSTEM_LAST SYSTEM_LAST} customizer sorts after all
 *       {@code APPLICATION} customizers regardless of its numeric priority, so it is always applied
 *       last.</li>
 * </ol>
 */
class RestExceptionMapperLastWinsTest {

    /**
     * Minimal {@link RestExceptionMapperCustomizer} test double that records its name into a shared
     * list when {@link #customize} is called, instead of touching a real mapper.
     */
    private static final class RecordingCustomizer implements RestExceptionMapperCustomizer {

        private final String name;
        private final ExtensionPhase phase;
        private final int priority;
        private final List<String> applied;

        /**
         * Constructs a recording customizer with the given identity and ordering.
         *
         * @param name     a human-readable label recorded on application
         * @param phase    the {@link ExtensionPhase} this customizer reports
         * @param priority the numeric priority within the phase
         * @param applied  the shared list that accumulates application order
         */
        RecordingCustomizer(String name, ExtensionPhase phase, int priority, List<String> applied) {
            this.name = name;
            this.phase = phase;
            this.priority = priority;
            this.applied = applied;
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
        public String orderKey() {
            // Use the name as the order key so the test controls tie-breaking without relying on FQCN.
            return name;
        }

        @Override
        public void customize(RestExceptionMapper mapper) {
            applied.add(name);
        }
    }

    @Test
    @DisplayName("higher numeric priority is applied last and wins the last-wins fold")
    void higherPriorityWins() {
        List<String> applied = new ArrayList<>();

        RecordingCustomizer low = new RecordingCustomizer("low", ExtensionPhase.APPLICATION, 0, applied);
        RecordingCustomizer high = new RecordingCustomizer("high", ExtensionPhase.APPLICATION, 100, applied);

        List<RestExceptionMapperCustomizer> customizers = new ArrayList<>(List.of(high, low));
        customizers.sort(OrderedExtension.comparator());
        customizers.forEach(c -> c.customize(null));

        assertEquals(List.of("low", "high"), applied, "lower priority must be applied first, higher priority last");
        assertEquals(
                "high", applied.get(applied.size() - 1), "high priority customizer must be the last applied (wins)");
    }

    @Test
    @DisplayName("SYSTEM_LAST is applied after APPLICATION regardless of numeric priority")
    void systemLastAppliesLastAndWins() {
        List<String> applied = new ArrayList<>();

        // APPLICATION with a high priority — would win over SYSTEM_LAST on priority alone.
        RecordingCustomizer app = new RecordingCustomizer("app", ExtensionPhase.APPLICATION, 999, applied);
        // SYSTEM_LAST with a LOW numeric priority — phase must dominate.
        RecordingCustomizer systemLast = new RecordingCustomizer("system-last", ExtensionPhase.SYSTEM_LAST, 0, applied);

        List<RestExceptionMapperCustomizer> customizers = new ArrayList<>(List.of(systemLast, app));
        customizers.sort(OrderedExtension.comparator());
        customizers.forEach(c -> c.customize(null));

        assertEquals(
                List.of("app", "system-last"),
                applied,
                "APPLICATION must be applied before SYSTEM_LAST regardless of numeric priority");
        assertEquals(
                "system-last",
                applied.get(applied.size() - 1),
                "SYSTEM_LAST customizer must be the last applied (wins)");
    }
}

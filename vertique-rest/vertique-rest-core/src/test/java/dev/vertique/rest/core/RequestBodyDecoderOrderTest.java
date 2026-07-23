// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RequestBodyDecoder} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, and application decoders at priority 0 sort before
 * framework defaults at priority 1000.
 *
 * <p>The sort used at the decoder site is {@link OrderedExtension#comparator()}, with no
 * additional tie-breaks.
 */
class RequestBodyDecoderOrderTest {

    /**
     * Minimal {@link RequestBodyDecoder} test double with configurable phase and priority.
     *
     * <p>{@link #orderKey()} is not overridden, so it defaults to this class's FQCN.
     * A distinct nested class is used when a different order key is needed at equal phase
     * and priority.
     */
    private static final class TestDecoder implements RequestBodyDecoder {

        private final ExtensionPhase phase;
        private final int priority;

        TestDecoder(ExtensionPhase phase, int priority) {
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
        public boolean canDecode(Class<?> targetType, String contentType) {
            return false;
        }
    }

    @Test
    @DisplayName("application decoder at priority 0 sorts before framework default at priority 1000")
    void appBeatsFrameworkDefault() {
        TestDecoder appDecoder = new TestDecoder(ExtensionPhase.APPLICATION, 0);
        TestDecoder frameworkDecoder = new TestDecoder(ExtensionPhase.APPLICATION, 1000);

        List<RequestBodyDecoder> decoders = new ArrayList<>(List.of(frameworkDecoder, appDecoder));
        decoders.sort(OrderedExtension.comparator());

        assertSame(appDecoder, decoders.get(0), "priority 0 must sort before priority 1000");
        assertSame(frameworkDecoder, decoders.get(1));
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestDecoder systemFirst = new TestDecoder(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestDecoder application = new TestDecoder(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<RequestBodyDecoder> decoders = new ArrayList<>(List.of(application, systemFirst));
        decoders.sort(OrderedExtension.comparator());

        assertSame(systemFirst, decoders.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, decoders.get(1));
    }
}

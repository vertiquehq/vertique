// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ResponseBodyEncoder} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, and application encoders at priority 0 sort before
 * framework defaults at priority 1000.
 *
 * <p>The sort used at the encoder site is {@link OrderedExtension#comparator()}, with no
 * additional tie-breaks.
 */
class ResponseBodyEncoderOrderTest {

    /**
     * Minimal {@link ResponseBodyEncoder} test double with configurable phase and priority.
     *
     * <p>{@link #orderKey()} is not overridden, so it defaults to this class's FQCN.
     * A distinct nested class is used when a different order key is needed at equal phase
     * and priority.
     */
    private static final class TestEncoder implements ResponseBodyEncoder {

        private final ExtensionPhase phase;
        private final int priority;

        TestEncoder(ExtensionPhase phase, int priority) {
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
        public boolean canEncode(Class<?> entityType, String contentType) {
            return false;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            throw new UnsupportedOperationException("test double");
        }
    }

    @Test
    @DisplayName("application encoder at priority 0 sorts before framework default at priority 1000")
    void appBeatsFrameworkDefault() {
        TestEncoder appEncoder = new TestEncoder(ExtensionPhase.APPLICATION, 0);
        TestEncoder frameworkEncoder = new TestEncoder(ExtensionPhase.APPLICATION, 1000);

        List<ResponseBodyEncoder> encoders = new ArrayList<>(List.of(frameworkEncoder, appEncoder));
        encoders.sort(OrderedExtension.comparator());

        assertSame(appEncoder, encoders.get(0), "priority 0 must sort before priority 1000");
        assertSame(frameworkEncoder, encoders.get(1));
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestEncoder systemFirst = new TestEncoder(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE);
        TestEncoder application = new TestEncoder(ExtensionPhase.APPLICATION, Integer.MIN_VALUE);

        List<ResponseBodyEncoder> encoders = new ArrayList<>(List.of(application, systemFirst));
        encoders.sort(OrderedExtension.comparator());

        assertSame(systemFirst, encoders.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, encoders.get(1));
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for the {@link ProtocolCorrelationSpec} extension contract.
 *
 * <p>Uses small inline stub specs to verify the default-method semantics ({@code generate}
 * throwing when not overridden, {@code attributes} defaulting to empty) and to exercise the
 * resolve-shape that {@code CorrelationIngressMiddleware} will rely on.
 */
class ProtocolCorrelationSpecTest {

    // --- Default-method semantics ---

    @Test
    @DisplayName("default generate() throws UnsupportedOperationException with a clear message")
    void defaultGenerateThrows() {
        ProtocolCorrelationSpec spec = new MinimalEchoSpec();
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, spec::generate);
        assertTrue(ex.getMessage().contains(spec.headerName()), "message must reference the header name");
    }

    @Test
    @DisplayName("default attributes() is empty")
    void defaultAttributesEmpty() {
        ProtocolCorrelationSpec spec = new MinimalEchoSpec();
        assertTrue(spec.attributes().isEmpty());
    }

    // --- acceptInbound contract ---

    @Test
    @DisplayName("acceptInbound returns the value when valid")
    void acceptInboundReturnsValid() {
        ProtocolCorrelationSpec spec = new MinimalEchoSpec();
        assertEquals(Optional.of("valid-1"), spec.acceptInbound("valid-1"));
    }

    @Test
    @DisplayName("acceptInbound returns empty for null or blank inbound values")
    void acceptInboundEmptyForBlank() {
        ProtocolCorrelationSpec spec = new MinimalEchoSpec();
        assertEquals(Optional.empty(), spec.acceptInbound(null));
        assertEquals(Optional.empty(), spec.acceptInbound(""));
        assertEquals(Optional.empty(), spec.acceptInbound("   "));
    }

    // --- A FAPI-style spec demonstrates the inbound + generate path ---

    @Test
    @DisplayName("FAPI-style spec mints a UUID v4 from generate() when inbound is absent")
    void fapiStyleSpecGeneratesUuidV4() {
        ProtocolCorrelationSpec spec = new FapiStyleSpec();
        String minted = spec.generate();
        UUID parsed = UUID.fromString(minted);
        assertEquals(4, parsed.version());
        assertEquals(CorrelationResponseMode.ECHO_OR_GENERATE_RFC4122, spec.responseMode());
    }

    @Test
    @DisplayName("FAPI-style spec attributes carry standard='fapi'")
    void fapiStyleSpecAttributes() {
        ProtocolCorrelationSpec spec = new FapiStyleSpec();
        assertEquals("fapi", spec.attributes().get("standard"));
    }

    // --- Stubs ---

    /** Minimal echo-same-header spec with no generation support. */
    private static final class MinimalEchoSpec implements ProtocolCorrelationSpec {
        @Override
        public String headerName() {
            return "X-Echo";
        }

        @Override
        public CorrelationResponseMode responseMode() {
            return CorrelationResponseMode.ECHO_SAME_HEADER;
        }

        @Override
        public CorrelationPropagationMode propagationMode() {
            return CorrelationPropagationMode.PROPAGATE_SAME_HEADER;
        }

        @Override
        public boolean durableSafe() {
            return true;
        }

        @Override
        public Optional<String> acceptInbound(String inboundValue) {
            if (inboundValue == null || inboundValue.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(inboundValue);
        }
    }

    /** FAPI-style spec — generates a UUID v4 when the inbound is absent. */
    private static final class FapiStyleSpec implements ProtocolCorrelationSpec {
        @Override
        public String headerName() {
            return "X-FAPI-Interaction-ID";
        }

        @Override
        public CorrelationResponseMode responseMode() {
            return CorrelationResponseMode.ECHO_OR_GENERATE_RFC4122;
        }

        @Override
        public CorrelationPropagationMode propagationMode() {
            return CorrelationPropagationMode.NONE;
        }

        @Override
        public boolean durableSafe() {
            return true;
        }

        @Override
        public Optional<String> acceptInbound(String inboundValue) {
            if (inboundValue == null || inboundValue.isBlank()) {
                return Optional.empty();
            }
            // FAPI: validate as RFC 4122 UUID.
            try {
                UUID.fromString(inboundValue);
                return Optional.of(inboundValue);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        @Override
        public String generate() {
            return UUID.randomUUID().toString();
        }

        @Override
        public java.util.Map<String, String> attributes() {
            return java.util.Map.of("standard", "fapi");
        }
    }
}

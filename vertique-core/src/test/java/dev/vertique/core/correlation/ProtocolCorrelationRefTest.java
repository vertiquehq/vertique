// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProtocolCorrelationRef}.
 *
 * <p>Verifies: happy-path construction; header name and value validation via
 * {@link CorrelationHeaderValidator}; null rejection for source, responseMode, and propagationMode;
 * defensive copy of attributes; unmodifiability of the resulting attributes map.
 */
class ProtocolCorrelationRefTest {

    private static final String VALID_HEADER = "X-Request-Id";
    private static final String VALID_VALUE = "abc-123";
    private static final String SOURCE = "http-ingress";

    // --- happy path ---

    @Test
    @DisplayName("constructs with valid fields and empty attributes")
    void happyPathEmptyAttributes() {
        ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                VALID_HEADER,
                VALID_VALUE,
                SOURCE,
                CorrelationResponseMode.ECHO_SAME_HEADER,
                CorrelationPropagationMode.PROPAGATE_SAME_HEADER,
                true,
                Map.of());
        assertEquals(VALID_HEADER, ref.headerName());
        assertEquals(VALID_VALUE, ref.value());
        assertEquals(SOURCE, ref.source());
        assertEquals(CorrelationResponseMode.ECHO_SAME_HEADER, ref.responseMode());
        assertEquals(CorrelationPropagationMode.PROPAGATE_SAME_HEADER, ref.propagationMode());
        assertTrue(ref.durableSafe());
        assertTrue(ref.attributes().isEmpty());
    }

    @Test
    @DisplayName("constructs with non-empty attributes")
    void happyPathWithAttributes() {
        ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                VALID_HEADER,
                VALID_VALUE,
                SOURCE,
                CorrelationResponseMode.NONE,
                CorrelationPropagationMode.NONE,
                false,
                Map.of("key", "val"));
        assertEquals("val", ref.attributes().get("key"));
    }

    @Test
    @DisplayName("null attributes map is treated as empty")
    void nullAttributesTreatedAsEmpty() {
        ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                VALID_HEADER,
                VALID_VALUE,
                SOURCE,
                CorrelationResponseMode.NONE,
                CorrelationPropagationMode.NONE,
                false,
                null);
        assertTrue(ref.attributes().isEmpty());
    }

    // --- header name validation ---

    @Test
    @DisplayName("invalid header name throws IllegalArgumentException")
    void invalidHeaderNameThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolCorrelationRef(
                        "Bad Header Name",
                        VALID_VALUE,
                        SOURCE,
                        CorrelationResponseMode.NONE,
                        CorrelationPropagationMode.NONE,
                        false,
                        null));
    }

    // --- header value validation ---

    @Test
    @DisplayName("invalid header value throws IllegalArgumentException")
    void invalidHeaderValueThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolCorrelationRef(
                        VALID_HEADER,
                        "bad\nvalue",
                        SOURCE,
                        CorrelationResponseMode.NONE,
                        CorrelationPropagationMode.NONE,
                        false,
                        null));
    }

    // --- null rejection ---

    @Test
    @DisplayName("null source throws exception")
    void nullSourceThrows() {
        assertThrows(
                Exception.class,
                () -> new ProtocolCorrelationRef(
                        VALID_HEADER,
                        VALID_VALUE,
                        null,
                        CorrelationResponseMode.NONE,
                        CorrelationPropagationMode.NONE,
                        false,
                        null));
    }

    @Test
    @DisplayName("null responseMode throws NullPointerException")
    void nullResponseModeThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolCorrelationRef(
                        VALID_HEADER, VALID_VALUE, SOURCE, null, CorrelationPropagationMode.NONE, false, null));
    }

    @Test
    @DisplayName("null propagationMode throws NullPointerException")
    void nullPropagationModeThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolCorrelationRef(
                        VALID_HEADER, VALID_VALUE, SOURCE, CorrelationResponseMode.NONE, null, false, null));
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating input map after construction does not affect ref attributes")
    void defensiveCopyOfAttributes() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("k", "v");
        ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                VALID_HEADER,
                VALID_VALUE,
                SOURCE,
                CorrelationResponseMode.NONE,
                CorrelationPropagationMode.NONE,
                false,
                mutable);
        mutable.put("extra", "injected");
        assertEquals(1, ref.attributes().size(), "Attributes must not reflect mutation of input map");
    }

    @Test
    @DisplayName("returned attributes map is unmodifiable")
    void attributesMapIsUnmodifiable() {
        ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                VALID_HEADER,
                VALID_VALUE,
                SOURCE,
                CorrelationResponseMode.NONE,
                CorrelationPropagationMode.NONE,
                false,
                Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ref.attributes().put("x", "y"));
    }
}

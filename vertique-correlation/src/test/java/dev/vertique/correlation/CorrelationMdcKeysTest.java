// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationMdcKeys}.
 *
 * <p>Verifies that the constants have the expected literal values and that {@code MIRRORED}
 * contains exactly the five mirrored keys.
 */
class CorrelationMdcKeysTest {

    @Test
    @DisplayName("REQUEST_ID constant has value 'requestId'")
    void requestIdConstant() {
        assertEquals("requestId", CorrelationMdcKeys.REQUEST_ID);
    }

    @Test
    @DisplayName("CORRELATION_ID constant has value 'correlationId'")
    void correlationIdConstant() {
        assertEquals("correlationId", CorrelationMdcKeys.CORRELATION_ID);
    }

    @Test
    @DisplayName("CAUSATION_ID constant has value 'causationId'")
    void causationIdConstant() {
        assertEquals("causationId", CorrelationMdcKeys.CAUSATION_ID);
    }

    @Test
    @DisplayName("TRACE_ID constant has value 'traceId'")
    void traceIdConstant() {
        assertEquals("traceId", CorrelationMdcKeys.TRACE_ID);
    }

    @Test
    @DisplayName("SPAN_ID constant has value 'spanId'")
    void spanIdConstant() {
        assertEquals("spanId", CorrelationMdcKeys.SPAN_ID);
    }

    @Test
    @DisplayName("MIRRORED contains exactly the five expected keys")
    void mirroredContainsExactlyFiveKeys() {
        Set<String> expected = Set.of(
                CorrelationMdcKeys.REQUEST_ID,
                CorrelationMdcKeys.CORRELATION_ID,
                CorrelationMdcKeys.CAUSATION_ID,
                CorrelationMdcKeys.TRACE_ID,
                CorrelationMdcKeys.SPAN_ID);
        assertEquals(expected, CorrelationMdcKeys.MIRRORED);
    }

    @Test
    @DisplayName("MIRRORED does not contain session or protocol keys")
    void mirroredExcludesSessionAndProtocol() {
        assertTrue(CorrelationMdcKeys.MIRRORED.stream().noneMatch(k -> k.contains("session")));
        assertTrue(CorrelationMdcKeys.MIRRORED.stream().noneMatch(k -> k.contains("protocol")));
    }
}

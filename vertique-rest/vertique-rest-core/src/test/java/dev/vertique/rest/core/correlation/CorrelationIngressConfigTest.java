// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.correlation.CorrelationIngressConfig.InvalidValuePolicy;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationIngressConfig}.
 *
 * <p>Verifies header-name validation at construction, JSON-deserialization defaults, and
 * round-trips of the configurable fields (header names, echo flags, causation parsing, invalid
 * value policy).
 */
class CorrelationIngressConfigTest {

    // --- Header-name validation at construction ---

    @Test
    @DisplayName("constructor rejects an invalid request-id header name (space in token)")
    void rejectsInvalidRequestIdHeaderName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationIngressConfig(
                        "X Request Id",
                        "X-Correlation-Id",
                        "X-Causation-Id",
                        true,
                        false,
                        false,
                        InvalidValuePolicy.REPLACE_WITH_GENERATED));
    }

    @Test
    @DisplayName("constructor rejects an invalid correlation-id header name (forbidden char)")
    void rejectsInvalidCorrelationIdHeaderName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationIngressConfig(
                        "X-Request-Id",
                        "X-Correlation\nId",
                        "X-Causation-Id",
                        true,
                        false,
                        false,
                        InvalidValuePolicy.REPLACE_WITH_GENERATED));
    }

    @Test
    @DisplayName("constructor rejects an invalid causation-id header name (empty)")
    void rejectsBlankCausationIdHeaderName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationIngressConfig(
                        "X-Request-Id",
                        "X-Correlation-Id",
                        "",
                        true,
                        false,
                        false,
                        InvalidValuePolicy.REPLACE_WITH_GENERATED));
    }

    @Test
    @DisplayName("constructor rejects null invalidValuePolicy")
    void rejectsNullPolicy() {
        assertThrows(
                NullPointerException.class,
                () -> new CorrelationIngressConfig(
                        "X-Request-Id", "X-Correlation-Id", "X-Causation-Id", true, false, false, null));
    }

    @Test
    @DisplayName("defaults() returns the framework-standard config")
    void defaultsAreFrameworkStandard() {
        CorrelationIngressConfig d = CorrelationIngressConfig.defaults();
        assertEquals("X-Request-Id", d.requestIdHeader());
        assertEquals("X-Correlation-Id", d.correlationIdHeader());
        assertEquals("X-Causation-Id", d.causationIdHeader());
        assertTrue(d.echoRequestId());
        assertFalse(d.echoCorrelationId());
        assertFalse(d.parseCausationId());
        assertSame(InvalidValuePolicy.REPLACE_WITH_GENERATED, d.invalidValuePolicy());
    }

    // --- JSON deserialization ---

    @Test
    @DisplayName("empty JSON object deserialises to defaults via @JsonCreator")
    void emptyJsonDeserialisesToDefaults() {
        CorrelationIngressConfig parsed = new JsonObject().mapTo(CorrelationIngressConfig.class);
        assertEquals(CorrelationIngressConfig.defaults(), parsed);
    }

    @Test
    @DisplayName("partial JSON fills missing fields with defaults")
    void partialJsonFillsDefaults() {
        JsonObject json = new JsonObject().put("echoCorrelationId", true).put("parseCausationId", true);
        CorrelationIngressConfig parsed = json.mapTo(CorrelationIngressConfig.class);
        assertTrue(parsed.echoCorrelationId());
        assertTrue(parsed.parseCausationId());
        // Untouched defaults
        assertEquals("X-Request-Id", parsed.requestIdHeader());
        assertSame(InvalidValuePolicy.REPLACE_WITH_GENERATED, parsed.invalidValuePolicy());
    }

    @Test
    @DisplayName("custom header names and REJECT policy round-trip through JSON")
    void customConfigRoundTrip() {
        JsonObject json = new JsonObject()
                .put("requestIdHeader", "X-App-Request")
                .put("correlationIdHeader", "X-App-Correlation")
                .put("causationIdHeader", "X-App-Cause")
                .put("echoRequestId", false)
                .put("echoCorrelationId", true)
                .put("parseCausationId", true)
                .put("invalidValuePolicy", "REJECT");
        CorrelationIngressConfig parsed = json.mapTo(CorrelationIngressConfig.class);
        assertEquals("X-App-Request", parsed.requestIdHeader());
        assertEquals("X-App-Correlation", parsed.correlationIdHeader());
        assertEquals("X-App-Cause", parsed.causationIdHeader());
        assertFalse(parsed.echoRequestId());
        assertTrue(parsed.echoCorrelationId());
        assertTrue(parsed.parseCausationId());
        assertSame(InvalidValuePolicy.REJECT, parsed.invalidValuePolicy());
    }

    @Test
    @DisplayName("invalid header name in JSON propagates the validator error")
    void invalidHeaderNameInJsonFails() {
        JsonObject json = new JsonObject().put("requestIdHeader", "Bad Header Name");
        assertThrows(Exception.class, () -> json.mapTo(CorrelationIngressConfig.class));
    }
}

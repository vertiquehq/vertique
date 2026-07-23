// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.correlation.CorrelationEnvelope.EnvelopeProtocolCorrelation;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationEnvelope}'s JSON shape and the
 * {@link CorrelationDurableKeys} constants.
 *
 * <p>The shape is asserted against {@code io.vertx.core.json.jackson.DatabindCodec.mapper()},
 * which is the mapper the durable encoder/decoder use at runtime.
 */
class CorrelationEnvelopeTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "http-header");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "http-header");

    // --- CorrelationDurableKeys ---

    @Test
    @DisplayName("CorrelationDurableKeys.CORRELATION is the short namespace name 'correlation'")
    void durableKeyConstant() {
        // The namespace is the short name owned by the encoder/decoder. The vertique- prefix is
        // added only at the Kafka wire boundary by DurableMetadataHeaderCodec.toHeaders().
        assertEquals("correlation", CorrelationDurableKeys.CORRELATION);
    }

    // --- CURRENT_SCHEMA_VERSION ---

    @Test
    @DisplayName("CURRENT_SCHEMA_VERSION is 1 in PR2")
    void schemaVersionConstant() {
        assertEquals(1, CorrelationEnvelope.CURRENT_SCHEMA_VERSION);
    }

    // --- JSON shape: minimal envelope ---

    @Test
    @DisplayName("minimal envelope (requestId + correlationId only) serialises to those keys + schemaVersion")
    void minimalEnvelopeJsonShape() throws Exception {
        CorrelationEnvelope env = new CorrelationEnvelope(
                CorrelationEnvelope.CURRENT_SCHEMA_VERSION, REQ_ID, CORR_ID, null, null, null, null, null);
        String json = DatabindCodec.mapper().writeValueAsString(env);
        JsonObject obj = new JsonObject(json);

        assertEquals(1, obj.getInteger("schemaVersion"));
        assertNotNull(obj.getJsonObject("requestId"));
        assertNotNull(obj.getJsonObject("correlationId"));
        // NON_NULL keeps optional keys out of the wire shape entirely.
        assertFalse(obj.containsKey("causationId"), "causationId omitted when null");
        assertFalse(obj.containsKey("trace"), "trace omitted when null");
        assertFalse(obj.containsKey("session"), "session omitted when null");
        assertFalse(obj.containsKey("protocolCorrelations"), "protocolCorrelations omitted when null");
        assertFalse(obj.containsKey("attributes"), "attributes omitted when null");
    }

    @Test
    @DisplayName("envelope round-trips through Jackson preserving equality")
    void envelopeRoundTrip() throws Exception {
        EnvelopeProtocolCorrelation refDto = new EnvelopeProtocolCorrelation(
                "X-FAPI-Interaction-ID",
                "b7f65c7e-4f42-4e7c-9d97-5e8f0d86a111",
                "http-header",
                CorrelationResponseMode.ECHO_OR_GENERATE_RFC4122,
                CorrelationPropagationMode.NONE,
                true,
                Map.of("standard", "fapi"));
        CorrelationSessionRef session =
                new CorrelationSessionRef("sess-1", "jwt-sid", "jwt-claim", "sid", true, Map.of());
        CorrelationEnvelope env = new CorrelationEnvelope(
                CorrelationEnvelope.CURRENT_SCHEMA_VERSION,
                REQ_ID,
                CORR_ID,
                new CorrelationIdentifier("cause-1", "upstream"),
                null,
                session,
                List.of(refDto),
                Map.of("k", "v"));

        String json = DatabindCodec.mapper().writeValueAsString(env);
        CorrelationEnvelope parsed = DatabindCodec.mapper().readValue(json, CorrelationEnvelope.class);
        assertEquals(env, parsed);
    }

    @Test
    @DisplayName("envelope ignores unknown JSON properties (forward compatibility)")
    void unknownPropertiesIgnored() throws Exception {
        String json = "{\"schemaVersion\":1," + "\"requestId\":{\"value\":\"r\",\"source\":\"src\"},"
                + "\"correlationId\":{\"value\":\"c\",\"source\":\"src\"},"
                + "\"unknownField\":\"future-value\","
                + "\"trace\":null}";
        CorrelationEnvelope parsed = DatabindCodec.mapper().readValue(json, CorrelationEnvelope.class);
        assertEquals(1, parsed.schemaVersion());
        assertEquals("r", parsed.requestId().value());
        assertNull(parsed.trace());
    }
}

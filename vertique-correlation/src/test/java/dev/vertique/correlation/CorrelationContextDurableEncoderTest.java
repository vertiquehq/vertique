// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationContextDurableEncoder}.
 *
 * <p>Verifies the wire-shape contract (FR-COR-143/144/148): the encoder returns a
 * {@link DurableMetadata} document under the {@code "correlation"} namespace containing a
 * JSON body with {@code schemaVersion} stamped, absent optional fields omitted, durable-unsafe
 * session blocks dropped entirely, protocol refs filtered to safe entries only, and live mutation
 * after snapshot does not bleed into the produced JSON.
 */
class CorrelationContextDurableEncoderTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "http-header");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "http-header");

    private static final DurableEncodeContext ENCODE_CTX = new DurableEncodeContext("kafka");

    private final CorrelationContextDurableEncoder encoder = new CorrelationContextDurableEncoder();

    // --- Contract ---

    @Test
    @DisplayName("type() returns CorrelationContext.class")
    void typeIsContextInterface() {
        assertEquals(CorrelationContext.class, encoder.type());
    }

    @Test
    @DisplayName("namespace() returns the CORRELATION namespace 'correlation'")
    void namespaceIsCorrelation() {
        assertEquals(CorrelationDurableKeys.CORRELATION, encoder.namespace());
        assertEquals("correlation", encoder.namespace());
    }

    // --- Wire shape: minimal context ---

    @Test
    @DisplayName("minimal context (requestId + correlationId only) writes only those + schemaVersion")
    void minimalContextOmitsOptionalKeys() {
        JsonObject json = bodyAt(encoder.encode(new MutableCorrelationContext(REQ_ID, CORR_ID), ENCODE_CTX));
        assertEquals(CorrelationEnvelope.CURRENT_SCHEMA_VERSION, json.getInteger("schemaVersion"));
        assertNotNull(json.getJsonObject("requestId"));
        assertNotNull(json.getJsonObject("correlationId"));
        assertFalse(json.containsKey("causationId"));
        assertFalse(json.containsKey("trace"));
        assertFalse(json.containsKey("session"));
        assertFalse(json.containsKey("protocolCorrelations"));
        assertFalse(json.containsKey("attributes"));
    }

    // --- Filtering: durableSafe ---

    @Test
    @DisplayName("session with durableSafe=false: entire session key is omitted (FR-COR-144)")
    void durableUnsafeSessionOmitted() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.setSession(new CorrelationSessionRef("sess-1", "jwt-jti", "jwt-claim", "jti", false, Map.of()));
        JsonObject json = bodyAt(encoder.encode(live, ENCODE_CTX));
        assertFalse(json.containsKey("session"), "non-durable-safe session must be omitted, not zeroed");
    }

    @Test
    @DisplayName("session with durableSafe=true: serialised in full")
    void durableSafeSessionRetained() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.setSession(new CorrelationSessionRef("sess-1", "jwt-sid", "jwt-claim", "sid", true, Map.of()));
        JsonObject json = bodyAt(encoder.encode(live, ENCODE_CTX));
        JsonObject session = json.getJsonObject("session");
        assertNotNull(session);
        assertEquals("sess-1", session.getString("id"));
        assertEquals(true, session.getBoolean("durableSafe"));
    }

    @Test
    @DisplayName(
            "protocolCorrelations filtered: durableSafe=false items dropped, durableSafe=true retained (FR-COR-148)")
    void protocolCorrelationsFiltered() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.addProtocolCorrelation(safeRef("X-FAPI-Interaction-ID", "abc"));
        live.addProtocolCorrelation(unsafeRef("X-Internal-Trace", "secret"));

        JsonObject json = bodyAt(encoder.encode(live, ENCODE_CTX));
        assertTrue(json.containsKey("protocolCorrelations"));
        assertEquals(1, json.getJsonArray("protocolCorrelations").size());
        assertEquals(
                "X-FAPI-Interaction-ID",
                json.getJsonArray("protocolCorrelations").getJsonObject(0).getString("headerName"));
    }

    @Test
    @DisplayName("protocolCorrelations omitted entirely when all items fail the durableSafe filter")
    void protocolCorrelationsOmittedWhenAllUnsafe() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.addProtocolCorrelation(unsafeRef("X-A", "1"));
        live.addProtocolCorrelation(unsafeRef("X-B", "2"));
        JsonObject json = bodyAt(encoder.encode(live, ENCODE_CTX));
        assertFalse(json.containsKey("protocolCorrelations"));
    }

    // --- Snapshot semantics ---

    @Test
    @DisplayName("encoder snapshots first so concurrent live mutation does not bleed into JSON")
    void snapshotIsolation() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.putAttribute("k", "v1");
        DurableMetadata encoded = encoder.encode(live, ENCODE_CTX);
        // Mutate after encode — JSON body must reflect pre-encode state.
        live.putAttribute("k", "v2");
        JsonObject json = bodyAt(encoded);
        assertEquals("v1", json.getJsonObject("attributes").getString("k"));
    }

    // --- All fields present ---

    @Test
    @DisplayName("fully populated context serialises every field correctly")
    void fullyPopulatedContext() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.setCausationId(new CorrelationIdentifier("cause-1", "upstream"));
        live.setTrace(new TraceReference("trace-abc", "span-def", "traceparent"));
        live.setSession(new CorrelationSessionRef("sess-1", "jwt-sid", "jwt-claim", "sid", true, Map.of()));
        live.addProtocolCorrelation(safeRef("X-FAPI-Interaction-ID", "abc"));
        live.putAttribute("tenant", "acme");

        JsonObject json = bodyAt(encoder.encode(live, ENCODE_CTX));
        assertEquals(1, json.getInteger("schemaVersion"));
        assertEquals("req-1", json.getJsonObject("requestId").getString("value"));
        assertEquals("cause-1", json.getJsonObject("causationId").getString("value"));
        assertEquals("trace-abc", json.getJsonObject("trace").getString("traceId"));
        assertEquals("sess-1", json.getJsonObject("session").getString("id"));
        assertEquals(1, json.getJsonArray("protocolCorrelations").size());
        assertEquals("acme", json.getJsonObject("attributes").getString("tenant"));
    }

    // --- DurableMetadata structure ---

    @Test
    @DisplayName("encode() returns a DurableMetadata document with exactly the 'correlation' namespace")
    void encodedDocumentHasCorrelationNamespace() {
        DurableMetadata md = encoder.encode(new MutableCorrelationContext(REQ_ID, CORR_ID), ENCODE_CTX);
        assertTrue(md.has("correlation"), "DurableMetadata must contain the 'correlation' namespace");
        assertEquals(1, md.namespaces().size(), "DurableMetadata must contain exactly one namespace");
        assertTrue(md.body("correlation").isPresent(), "body('correlation') must be present");
    }

    // --- Helpers ---

    /**
     * Extracts the {@code "correlation"} namespace body from the {@link DurableMetadata} produced
     * by the encoder. Fails the test with a descriptive message if the namespace is absent.
     *
     * @param metadata the encoded durable metadata document
     * @return the JSON body of the {@code "correlation"} namespace
     */
    private static JsonObject bodyAt(DurableMetadata metadata) {
        return metadata.body(CorrelationDurableKeys.CORRELATION)
                .orElseThrow(() -> new AssertionError("encoder must populate the 'correlation' namespace"));
    }

    private static ProtocolCorrelationRef safeRef(String name, String value) {
        return new ProtocolCorrelationRef(
                name,
                value,
                "http-header",
                CorrelationResponseMode.ECHO_SAME_HEADER,
                CorrelationPropagationMode.NONE,
                true,
                Map.of("standard", "fapi"));
    }

    private static ProtocolCorrelationRef unsafeRef(String name, String value) {
        return new ProtocolCorrelationRef(
                name,
                value,
                "internal",
                CorrelationResponseMode.NONE,
                CorrelationPropagationMode.NONE,
                false,
                Map.of());
    }

    // Suppress unused warning — keep List visible for future test additions
    @SuppressWarnings("unused")
    private static List<ProtocolCorrelationRef> dummyForImport() {
        return List.of();
    }
}

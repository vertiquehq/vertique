// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.DurableDecodeContext;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationContextDurableDecoder}.
 *
 * <p>The decoder now accepts a {@link DurableMetadata} document and reads its
 * {@code "correlation"} namespace body as a {@link JsonObject}. The old "malformed JSON string"
 * path is no longer reachable at this layer: {@link DurableMetadata} only stores valid JSON
 * objects. The malformed-header case is filtered upstream by
 * {@link dev.vertique.core.context.DurableMetadataHeaderCodec#fromHeaders(Map)}.
 *
 * <p>Verifies schema enforcement, header revalidation, unknown-enum failure, and unknown-optional
 * forward compatibility. Failure paths return a single warning targeting the
 * {@code vertique-correlation} key (the propagator log-throttles on behalf of the decoder, so
 * the decoder never throws).
 */
class CorrelationContextDurableDecoderTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "http-header");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "http-header");

    private static final DurableDecodeContext DECODE_CTX = new DurableDecodeContext("kafka");
    private static final DurableEncodeContext ENCODE_CTX = new DurableEncodeContext("kafka");

    private final CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
    private final CorrelationContextDurableDecoder decoder = new CorrelationContextDurableDecoder(factory);
    private final CorrelationContextDurableEncoder encoder = new CorrelationContextDurableEncoder();

    // --- Contract ---

    @Test
    @DisplayName("type() returns CorrelationContext.class")
    void typeIsContextInterface() {
        assertEquals(CorrelationContext.class, decoder.type());
    }

    @Test
    @DisplayName("namespace() returns the CORRELATION namespace 'correlation'")
    void namespaceIsCorrelation() {
        assertEquals(CorrelationDurableKeys.CORRELATION, decoder.namespace());
        assertEquals("correlation", decoder.namespace());
    }

    // --- Round-trip ---

    @Test
    @DisplayName("encoder + decoder round-trip rebuilds an equivalent live context")
    void encoderDecoderRoundTrip() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.setCausationId(new CorrelationIdentifier("cause-1", "upstream"));
        live.setTrace(new TraceReference("trace-abc", "span-def", "traceparent"));
        live.setSession(new CorrelationSessionRef("sess-1", "jwt-sid", "jwt-claim", "sid", true, Map.of()));
        live.addProtocolCorrelation(new ProtocolCorrelationRef(
                "X-FAPI-Interaction-ID",
                "abc",
                "http-header",
                CorrelationResponseMode.ECHO_SAME_HEADER,
                CorrelationPropagationMode.NONE,
                true,
                Map.of("standard", "fapi")));
        live.putAttribute("tenant", "acme");

        // Encoder returns DurableMetadata; pass directly to decoder (no serialization step needed)
        DurableMetadata encoded = encoder.encode(live, ENCODE_CTX);
        ContextDecodeResult<CorrelationContext> result = decoder.decode(encoded, DECODE_CTX);

        assertTrue(result.value().isPresent());
        CorrelationContext rebuilt = result.value().get();
        assertEquals(live.snapshot(), rebuilt.snapshot());
        assertTrue(result.warnings().isEmpty());
    }

    // --- Absent/empty ---

    @Test
    @DisplayName("absent 'correlation' namespace (empty DurableMetadata) returns empty result with no warnings")
    void absentNamespaceReturnsEmpty() {
        ContextDecodeResult<CorrelationContext> result = decoder.decode(DurableMetadata.empty(), DECODE_CTX);
        assertFalse(result.value().isPresent());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("DurableMetadata with a different namespace returns empty result with no warnings")
    void differentNamespaceReturnsEmpty() {
        // Simulates a carrier that has context for a different type but not correlation.
        DurableMetadata otherOnly = DurableMetadata.of("other", new JsonObject().put("k", "v"));
        ContextDecodeResult<CorrelationContext> result = decoder.decode(otherOnly, DECODE_CTX);
        assertFalse(result.value().isPresent());
        assertTrue(result.warnings().isEmpty());
    }

    // --- Schema enforcement ---

    @Test
    @DisplayName("missing schemaVersion returns single failure warning")
    void missingSchemaVersionFails() {
        JsonObject body = new JsonObject()
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"));
        assertSingleFailure(body, "schemaVersion");
    }

    @Test
    @DisplayName("schemaVersion > supported returns single failure warning")
    void schemaVersionTooHighFails() {
        JsonObject body = new JsonObject()
                .put("schemaVersion", 99)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"));
        assertSingleFailure(body, "unsupported schemaVersion");
    }

    // --- Required-field enforcement ---

    @Test
    @DisplayName("missing requestId returns single failure warning")
    void missingRequestIdFails() {
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"));
        assertSingleFailure(body, "requestId");
    }

    @Test
    @DisplayName("blank requestId.value returns single failure warning")
    void blankRequestIdValueFails() {
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"));
        assertSingleFailure(body, "blank");
    }

    @Test
    @DisplayName("missing correlationId returns single failure warning")
    void missingCorrelationIdFails() {
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"));
        assertSingleFailure(body, "correlationId");
    }

    // --- Enum + header validation ---

    @Test
    @DisplayName("unknown CorrelationResponseMode enum value fails")
    void unknownResponseModeFails() {
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"))
                .put(
                        "protocolCorrelations",
                        io.vertx.core.json.JsonArray.of(new JsonObject()
                                .put("headerName", "X-H")
                                .put("value", "v")
                                .put("source", "s")
                                .put("responseMode", "NOT_A_MODE")
                                .put("propagationMode", "NONE")
                                .put("durableSafe", true)));
        assertSingleFailure(body, "responseMode");
    }

    @Test
    @DisplayName("invalid protocol header name fails the validator at decode")
    void invalidHeaderNameFails() {
        // Space is not allowed in HTTP token grammar so CorrelationHeaderValidator rejects it.
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"))
                .put(
                        "protocolCorrelations",
                        io.vertx.core.json.JsonArray.of(new JsonObject()
                                .put("headerName", "Bad Header")
                                .put("value", "v")
                                .put("source", "s")
                                .put("responseMode", "NONE")
                                .put("propagationMode", "NONE")
                                .put("durableSafe", true)));
        assertSingleFailure(body, "headerName");
    }

    @Test
    @DisplayName("invalid protocol header value fails the validator at decode")
    void invalidHeaderValueFails() {
        // "@" is not in the CorrelationHeaderValidator allow-list.
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"))
                .put(
                        "protocolCorrelations",
                        io.vertx.core.json.JsonArray.of(new JsonObject()
                                .put("headerName", "X-H")
                                .put("value", "bad@value")
                                .put("source", "s")
                                .put("responseMode", "NONE")
                                .put("propagationMode", "NONE")
                                .put("durableSafe", true)));
        assertSingleFailure(body, "value");
    }

    // --- Value-record constructor IAE → ContextDecodeResult.failure (FR-COR-145) ---

    @Test
    @DisplayName("blank trace.traceId surfaces as a single failure warning (IAE from constructor)")
    void blankTraceIdSurfacesAsFailure() {
        // TraceReference's constructor rejects blank traceId with IllegalArgumentException.
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"))
                .put("trace", new JsonObject().put("traceId", "").put("source", "trace-context"));
        assertSingleFailure(body, "blank");
    }

    @Test
    @DisplayName("blank session.id surfaces as a single failure warning (IAE from constructor)")
    void blankSessionIdSurfacesAsFailure() {
        // CorrelationSessionRef's constructor rejects blank id with IllegalArgumentException.
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"))
                .put(
                        "session",
                        new JsonObject()
                                .put("id", "")
                                .put("kind", "jwt-sid")
                                .put("source", "jwt-claim")
                                .put("durableSafe", true));
        assertSingleFailure(body, "blank");
    }

    // Note: the "malformed JSON string" test that existed in the old Map<String,String>-based API
    // has been removed. DurableMetadata only stores valid JsonObject bodies — a malformed raw
    // header string is filtered out upstream by DurableMetadataHeaderCodec.fromHeaders() (which
    // silently skips reserved headers whose value does not parse as a JSON object). The decoder
    // therefore never receives unparseable content.

    // --- Forward compatibility ---

    @Test
    @DisplayName("unknown OPTIONAL fields are ignored (FR-COR-152)")
    void unknownOptionalFieldsIgnored() {
        JsonObject body = new JsonObject()
                .put("schemaVersion", 1)
                .put("requestId", new JsonObject().put("value", "r").put("source", "s"))
                .put("correlationId", new JsonObject().put("value", "c").put("source", "s"))
                .put("futureField", "future-value")
                .put("requestId-extra", "ignored");
        ContextDecodeResult<CorrelationContext> result =
                decoder.decode(DurableMetadata.of("correlation", body), DECODE_CTX);
        assertTrue(result.value().isPresent());
        assertEquals("r", result.value().get().requestId().value());
    }

    // --- Helpers ---

    /**
     * Asserts that decoding the given JSON body (wrapped under the {@code "correlation"} namespace)
     * fails with exactly one warning whose {@code reason} contains the given fragment.
     *
     * @param body           a semantically invalid JSON body for the correlation namespace
     * @param reasonFragment substring expected in the single warning's reason
     */
    private void assertSingleFailure(JsonObject body, String reasonFragment) {
        ContextDecodeResult<CorrelationContext> result =
                decoder.decode(DurableMetadata.of(CorrelationDurableKeys.CORRELATION, body), DECODE_CTX);
        assertFalse(result.value().isPresent(), "decode must fail (no value) for: " + reasonFragment);
        assertEquals(1, result.warnings().size(), "failure must surface as exactly one warning");
        assertNotNull(result.warnings().get(0).reason());
        assertTrue(
                result.warnings().get(0).reason().toLowerCase().contains(reasonFragment.toLowerCase()),
                "warning reason should mention '" + reasonFragment + "', was: "
                        + result.warnings().get(0).reason());
        assertEquals(
                CorrelationDurableKeys.CORRELATION, result.warnings().get(0).key());
    }
}

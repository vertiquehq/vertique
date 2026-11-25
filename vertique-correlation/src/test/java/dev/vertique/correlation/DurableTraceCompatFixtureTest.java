// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.TraceReference;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Durable-format compatibility fixture for {@link CorrelationContextDurableDecoder}: a frozen
 * payload written without {@code TraceReference}'s {@code sampled}/{@code traceState} components must
 * keep decoding, with those components defaulted.
 *
 * <p><strong>Fixture provenance.</strong> {@link #FROZEN_PAYLOAD} is the literal JSON
 * {@link CorrelationContextDurableEncoder} produced for one representative
 * {@link MutableCorrelationContext} carrying a requestId, a correlationId, and a {@link
 * TraceReference} (traceId + spanId + source, no causationId/session/protocolCorrelations/
 * attributes — kept minimal so the fixture is easy to read and diff). It was captured once from
 * {@code CorrelationContextDurableEncoder.encode(live, DurableEncodeContext)} via
 * {@code encoded.body("correlation").get().encode()} and copied here verbatim. It must never be
 * regenerated from a live encoder run (a frozen fixture that regenerates itself on every run cannot
 * prove backward compatibility).
 *
 * <p><strong>Additive defaults.</strong> {@code TraceReference} has {@code sampled}/{@code traceState}
 * as trailing components; neither exists in the frozen payload above, so the decoder must default
 * them additively: {@code sampled=false}, {@code traceState=null}. {@code CorrelationContextSnapshot}
 * carries no {@code linkedTrace} slot: {@code RestRequestCompletedEvent} ships the snapshot wholesale
 * into the audit capture SPI, so the untrusted body-borne reference must never enter the identity
 * carrier.
 */
class DurableTraceCompatFixtureTest {

    /**
     * The exact, frozen wire payload for the {@code CorrelationDurableKeys#CORRELATION}
     * namespace body. Never regenerate this string from a live encoder run inside this test class:
     * doing so would make the fixture track the production encoder instead of pinning what it
     * produced originally, defeating the whole point of a backward-compatibility fixture.
     */
    private static final String FROZEN_PAYLOAD = "{\"schemaVersion\":1,"
            + "\"requestId\":{\"value\":\"req-1\",\"source\":\"http-header\"},"
            + "\"correlationId\":{\"value\":\"corr-1\",\"source\":\"http-header\"},"
            + "\"trace\":{\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
            + "\"spanId\":\"00f067aa0ba902b7\",\"source\":\"traceparent\"}}";

    private static final DurableDecodeContext DECODE_CTX = new DurableDecodeContext("kafka");

    private final CorrelationContextFactory factory = new CorrelationContextFactory(java.util.Optional.empty());
    private final CorrelationContextDurableDecoder decoder = new CorrelationContextDurableDecoder(factory);

    @Test
    @DisplayName("the frozen payload decodes with its exact fields and defaults the additive trace components")
    void decodesTheFrozenPayloadAndDefaultsAdditiveFields() {
        DurableMetadata metadata =
                DurableMetadata.of(CorrelationDurableKeys.CORRELATION, new JsonObject(FROZEN_PAYLOAD));

        ContextDecodeResult<CorrelationContext> result = decoder.decode(metadata, DECODE_CTX);

        assertTrue(result.warnings().isEmpty(), "the frozen payload must decode without warnings");
        assertTrue(result.value().isPresent(), "the frozen payload must decode to a live context");
        CorrelationContext rebuilt = result.value().get();

        assertEquals("req-1", rebuilt.requestId().value());
        assertEquals("http-header", rebuilt.requestId().source());
        assertEquals("corr-1", rebuilt.correlationId().value());
        assertEquals("http-header", rebuilt.correlationId().source());
        assertNull(rebuilt.causationId(), "the frozen payload carries no causationId");

        TraceReference trace = rebuilt.trace();
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.traceId());
        assertEquals("00f067aa0ba902b7", trace.spanId());
        assertEquals("traceparent", trace.source());

        assertNull(rebuilt.session(), "the frozen payload carries no session");
        assertTrue(rebuilt.protocolCorrelations().isEmpty(), "the frozen payload carries no protocol refs");
        assertTrue(rebuilt.attributes().isEmpty(), "the frozen payload carries no attributes");

        // The decisive additive-compat assertion: the payload predates TraceReference's newer fields, so
        // they decode as defaults, never fail, and are never populated from absent wire data.
        assertFalse(trace.sampled(), "a payload without a 'sampled' bit must default to false");
        assertNull(trace.traceState(), "a payload without 'traceState' must default to null");
    }
}

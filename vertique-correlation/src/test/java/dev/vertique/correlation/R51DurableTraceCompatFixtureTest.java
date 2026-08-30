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
 * R51 (trace-reference consolidation) — red-first durable-format compatibility fixture for
 * {@link CorrelationContextDurableDecoder}.
 *
 * <p><strong>Fixture provenance.</strong> {@link #FROZEN_PRE_R51_PAYLOAD} is the literal JSON
 * {@link CorrelationContextDurableEncoder} produced, on the pre-R51 code, for one representative
 * {@link MutableCorrelationContext} carrying a requestId, a correlationId, and a {@link
 * TraceReference} (traceId + spanId + source, no causationId/session/protocolCorrelations/
 * attributes — kept minimal so the fixture is easy to read and diff). It was captured by a
 * one-shot scratch test that called {@code CorrelationContextDurableEncoder.encode(live,
 * DurableEncodeContext)} and printed {@code encoded.body("correlation").get().encode()}; the
 * scratch test was deleted immediately after its output was copied here verbatim — it is not part
 * of this deliverable and must never be recreated as a permanent fixture-refresh mechanism (a
 * frozen fixture that regenerates itself on every run cannot prove backward compatibility).
 *
 * <p><strong>Today (pre-R51 production code):</strong> {@link
 * #decodesTheFrozenPreR51PayloadWithTodaysFields} passes now — it is the baseline this test
 * class exists to protect for the production change that follows. It is deliberately NOT
 * red-today; the "red-first" property in the task contract refers to the property this same
 * frozen payload must still decode identically once the R51 production change adds {@code
 * sampled}/{@code traceState}/{@code linkedTrace} to the decoder additively — i.e. a decoder that
 * regressed backward compatibility would turn this exact assertion red on the FIXED code, not on
 * today's.
 *
 * <p><strong>After the R51 production change.</strong> {@code TraceReference} gains {@code
 * sampled}/{@code traceState} as trailing components; neither exists in the frozen payload above,
 * so the decoder must default them additively: {@code sampled=false}, {@code traceState=null}.
 * {@code CorrelationContextSnapshot} is explicitly NOT modified by R51 (the authoring survey
 * proved {@code RestRequestCompletedEvent} ships the snapshot wholesale into the audit capture
 * SPI, so the untrusted body-borne reference must never enter the identity carrier) — there is no
 * {@code linkedTrace} slot on the snapshot to default.
 */
class R51DurableTraceCompatFixtureTest {

    /**
     * The exact, frozen pre-R51 wire payload for the {@code CorrelationDurableKeys#CORRELATION}
     * namespace body. Never regenerate this string from a live encoder run inside this test class:
     * doing so would make the fixture track the production encoder instead of pinning what it
     * produced before R51, defeating the whole point of a backward-compatibility fixture.
     */
    private static final String FROZEN_PRE_R51_PAYLOAD = "{\"schemaVersion\":1,"
            + "\"requestId\":{\"value\":\"req-1\",\"source\":\"http-header\"},"
            + "\"correlationId\":{\"value\":\"corr-1\",\"source\":\"http-header\"},"
            + "\"trace\":{\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
            + "\"spanId\":\"00f067aa0ba902b7\",\"source\":\"traceparent\"}}";

    private static final DurableDecodeContext DECODE_CTX = new DurableDecodeContext("kafka");

    private final CorrelationContextFactory factory = new CorrelationContextFactory(java.util.Optional.empty());
    private final CorrelationContextDurableDecoder decoder = new CorrelationContextDurableDecoder(factory);

    @Test
    @DisplayName("R51: the frozen pre-R51 payload decodes with today's exact fields (baseline, green"
            + " now and must stay green after the additive decoder change)")
    void decodesTheFrozenPreR51PayloadWithTodaysFields() {
        DurableMetadata metadata =
                DurableMetadata.of(CorrelationDurableKeys.CORRELATION, new JsonObject(FROZEN_PRE_R51_PAYLOAD));

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

        // R51: the decisive additive-compat assertion — a payload written before R51 must decode
        // with TraceReference's new fields defaulted, never fail, and never silently populate them
        // from stale/absent wire data. CorrelationContextSnapshot carries no linkedTrace slot (R51
        // deliberately does not modify it), so there is nothing further to default here.
        assertFalse(trace.sampled(), "a pre-R51 payload carries no 'sampled' bit; must default false");
        assertNull(trace.traceState(), "a pre-R51 payload carries no 'traceState'; must default null");
    }
}

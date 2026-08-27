// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.mcp.interceptor.McpTraceContext;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T022 {@link McpServerSpanObserver} contract (contract §4.10 amendment): the span is
 * captured exactly once, at {@code open}, and every enrichment — including the optional body-trace
 * link — lands on that exact retained span, never on whatever span (if any) is current at callback
 * time. No span is ever created or renamed.
 *
 * <p>Each row builds its own {@link TracerFixture} (an isolated {@link SdkTracerProvider} plus {@link
 * InMemorySpanExporter}) so rows never share tracer state, mirroring the sibling Micrometer
 * observer's per-row {@code SimpleMeterRegistry} isolation.
 *
 * <p><b>Identity rows ({@code shouldCaptureTheCurrentServerSpanAtOpen}, {@code
 * shouldEnrichTheCapturedSpanFromAnOffContextTerminal}).</b> The HTTP server span is made current
 * only for the duration of {@code observer.open(...)}, on the test's own thread; the terminal
 * callback is then delivered on a genuinely different thread (a dedicated single-thread executor).
 * The first row additionally makes a <em>second, distinct</em> span current on that delivering
 * thread, so an implementation that (incorrectly) resolved {@code Span.current()} inside the
 * terminal callback would enrich the wrong span, not merely an empty one — the decisive assertion
 * checks both spans by object identity (via their own {@link ReadableSpan#getAttribute}), so it
 * would fail if enrichment landed on the wrong object. The second row instead leaves nothing current
 * on the delivering thread, proving the mechanism does not depend on the no-op fallback either.
 */
class McpServerSpanObserverTest {

    private static final String CAPTURE_AT_OPEN_ROW = "shouldCaptureTheCurrentServerSpanAtOpen";
    private static final String OFF_CONTEXT_TERMINAL_ROW = "shouldEnrichTheCapturedSpanFromAnOffContextTerminal";
    private static final String DISTINCT_LINK_ROW = "shouldAddOneLinkForAValidDistinctBodyContext";
    private static final String MATCHING_LINK_ROW = "shouldAddNoLinkForAMatchingBodyContext";
    private static final String MALFORMED_LINK_ROW = "shouldIgnoreMalformedBodyTraceDataWithABoundedDiagnostic";
    private static final String PROTOCOL_VERSION_ROW = "shouldEmitProtocolVersionOnlyWhenNegotiated";

    private static final Instant STARTED_AT = Instant.parse("2026-08-23T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(25);
    private static final String KNOWN_TOOL = "hello";

    /** A fixed, valid W3C trace/span id pair distinct from any SDK-generated id (RFC example values). */
    private static final String DISTINCT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String DISTINCT_SPAN_ID = "00f067aa0ba902b7";

    private static java.util.stream.Stream<String> rows() {
        return java.util.stream.Stream.of(
                CAPTURE_AT_OPEN_ROW,
                OFF_CONTEXT_TERMINAL_ROW,
                DISTINCT_LINK_ROW,
                MATCHING_LINK_ROW,
                MALFORMED_LINK_ROW,
                PROTOCOL_VERSION_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("T022 observer matrix: capture-at-open identity and the bounded body-trace link")
    void shouldEnforceT022ContractMatrix(String row) throws Exception {
        switch (row) {
            case CAPTURE_AT_OPEN_ROW -> shouldCaptureTheCurrentServerSpanAtOpen();
            case OFF_CONTEXT_TERMINAL_ROW -> shouldEnrichTheCapturedSpanFromAnOffContextTerminal();
            case DISTINCT_LINK_ROW -> shouldAddOneLinkForAValidDistinctBodyContext();
            case MATCHING_LINK_ROW -> shouldAddNoLinkForAMatchingBodyContext();
            case MALFORMED_LINK_ROW -> shouldIgnoreMalformedBodyTraceDataWithABoundedDiagnostic();
            case PROTOCOL_VERSION_ROW -> shouldEmitProtocolVersionOnlyWhenNegotiated();
            default -> fail("unknown T022 observer row: " + row);
        }
    }

    // --- shouldCaptureTheCurrentServerSpanAtOpen ---

    private void shouldCaptureTheCurrentServerSpanAtOpen() throws Exception {
        try (TracerFixture fixture = TracerFixture.create()) {
            // Given: the HTTP server span made current only during open, and a second, distinct span
            // made current only while the terminal callback runs on a different thread.
            Span httpSpan = fixture.startSpan("http-server-span-identity");
            Span wrongSpan = fixture.startSpan("wrong-span-current-at-terminal");

            McpServerSpanObserver observer = new McpServerSpanObserver();
            McpRequestObservation session;
            try (Scope scope = httpSpan.makeCurrent()) {
                session = observer.open(STARTED_AT);
            }

            // When: deliver the terminal off the opening thread, with wrongSpan current there instead.
            deliverOnDifferentThread(session, wrongSpan, successTerminal(), null);

            httpSpan.end();
            wrongSpan.end();

            // Then: no span was created, and neither span was renamed — asserted first so this fact is
            // independently visible even when the identity assertion below fails.
            assertThat(fixture.exporter.getFinishedSpanItems())
                    .as("the created-span count must stay 0 (only the two test-created spans exist) "
                            + "regardless of where enrichment lands")
                    .hasSize(2);
            assertThat(((ReadableSpan) httpSpan).getName()).isEqualTo("http-server-span-identity");

            // Then (DECISIVE, by object identity via each span's own ReadableSpan view): enrichment
            // landed on the span captured at open, never on the span merely current at callback time.
            assertThat(((ReadableSpan) httpSpan).getAttribute(McpServerSpanObserver.VERTIQUE_MCP_OUTCOME))
                    .as("the span captured at open must be enriched")
                    .isEqualTo("SUCCESS");
            assertThat(((ReadableSpan) wrongSpan).getAttribute(McpServerSpanObserver.VERTIQUE_MCP_OUTCOME))
                    .as("DECISIVE: the span merely current during the off-thread terminal callback must "
                            + "never be enriched")
                    .isNull();
        }
    }

    // --- shouldEnrichTheCapturedSpanFromAnOffContextTerminal ---

    private void shouldEnrichTheCapturedSpanFromAnOffContextTerminal() throws Exception {
        try (TracerFixture fixture = TracerFixture.create()) {
            // Given: the HTTP server span made current only during open; the terminal callback runs on
            // a different thread with nothing at all made current there.
            Span httpSpan = fixture.startSpan("http-server-span-off-context");
            McpServerSpanObserver observer = new McpServerSpanObserver();
            McpRequestObservation session;
            try (Scope scope = httpSpan.makeCurrent()) {
                session = observer.open(STARTED_AT);
            }

            deliverOnDifferentThread(session, null, successTerminal(), null);

            httpSpan.end();

            // Then (DECISIVE): the retained span was still enriched, proving the mechanism does not
            // depend on Span.current() resolving to anything (not even the no-op fallback) at callback time.
            assertThat(((ReadableSpan) httpSpan).getAttribute(McpServerSpanObserver.MCP_METHOD_NAME))
                    .as("DECISIVE: enrichment lands on the span captured at open even when nothing is "
                            + "current on the thread delivering the terminal callback")
                    .isEqualTo("TOOLS_CALL");
            assertThat(fixture.exporter.getFinishedSpanItems())
                    .as("no span may be created by the observer")
                    .hasSize(1);
            assertThat(((ReadableSpan) httpSpan).getName()).isEqualTo("http-server-span-off-context");
        }
    }

    // --- shouldAddOneLinkForAValidDistinctBodyContext ---

    private void shouldAddOneLinkForAValidDistinctBodyContext() {
        try (TracerFixture fixture = TracerFixture.create()) {
            Span httpSpan = fixture.startSpan("http-server-span-distinct-link");
            McpServerSpanObserver observer = new McpServerSpanObserver();
            McpRequestObservation session;
            try (Scope scope = httpSpan.makeCurrent()) {
                session = observer.open(STARTED_AT);
            }

            // Given: a body trace context whose trace/span id are valid and distinct from httpSpan's own.
            McpTraceContext distinctBody = new McpTraceContext(DISTINCT_TRACE_ID, DISTINCT_SPAN_ID, true, null);
            session.onTerminal(terminalObservation(successTerminal(), distinctBody));
            httpSpan.end();

            List<LinkData> links = ((ReadableSpan) httpSpan).toSpanData().getLinks();
            assertThat(links)
                    .as("DECISIVE: exactly one link for a valid, distinct body trace context")
                    .hasSize(1);
            SpanContext linkContext = links.get(0).getSpanContext();
            assertThat(linkContext.getTraceId()).isEqualTo(DISTINCT_TRACE_ID);
            assertThat(linkContext.getSpanId()).isEqualTo(DISTINCT_SPAN_ID);
        }
    }

    // --- shouldAddNoLinkForAMatchingBodyContext ---

    /**
     * R39 repair: the body reference here deliberately shares only the captured span's <em>trace</em>
     * id, with a <em>different</em> span id (the span id an HTTP {@code traceparent} header's parent
     * context would carry) — the shape a real HTTP-header self-reference actually takes, since the
     * captured span's own span id is freshly minted at {@code open} and never equals its parent's.
     * Suppression must therefore key on trace id alone: comparing full span identity (trace id
     * <em>and</em> span id, as the pre-fix implementation did) would miss this case and wrongly add a
     * link for the request's own HTTP parent.
     */
    private void shouldAddNoLinkForAMatchingBodyContext() {
        try (TracerFixture fixture = TracerFixture.create()) {
            Span httpSpan = fixture.startSpan("http-server-span-matching-link");
            McpServerSpanObserver observer = new McpServerSpanObserver();
            McpRequestObservation session;
            try (Scope scope = httpSpan.makeCurrent()) {
                session = observer.open(STARTED_AT);
            }

            // Given: a body trace context sharing the HTTP span's own trace id but carrying a
            // different span id — the shape of the request's own HTTP-header parent context, not the
            // span's own context.
            SpanContext httpContext = httpSpan.getSpanContext();
            McpTraceContext matchingBody = new McpTraceContext(httpContext.getTraceId(), DISTINCT_SPAN_ID, true, null);
            session.onTerminal(terminalObservation(successTerminal(), matchingBody));
            httpSpan.end();

            assertThat(((ReadableSpan) httpSpan).toSpanData().getLinks())
                    .as("DECISIVE: zero links for a body trace context sharing the captured span's own "
                            + "trace id, even with a different span id (e.g. the HTTP parent context)")
                    .isEmpty();
            assertThat(((ReadableSpan) httpSpan).getAttribute(McpServerSpanObserver.VERTIQUE_MCP_OUTCOME))
                    .as("the rest of enrichment must still happen even though the link is skipped")
                    .isEqualTo("SUCCESS");
        }
    }

    // --- shouldIgnoreMalformedBodyTraceDataWithABoundedDiagnostic ---

    private void shouldIgnoreMalformedBodyTraceDataWithABoundedDiagnostic() {
        try (TracerFixture fixture = TracerFixture.create()) {
            Span httpSpan = fixture.startSpan("http-server-span-malformed-link");
            McpServerSpanObserver observer = new McpServerSpanObserver();
            McpRequestObservation session;
            try (Scope scope = httpSpan.makeCurrent()) {
                session = observer.open(STARTED_AT);
            }

            // Given: a distinct, otherwise-valid trace/span id, but a traceState string that is not a
            // well-formed W3C key=value entry — bounds-valid for McpTraceContext, but malformed input
            // for the observer's own traceState parser.
            McpTraceContext malformedBody =
                    new McpTraceContext(DISTINCT_TRACE_ID, DISTINCT_SPAN_ID, true, "not-a-key-value-pair");

            assertThatCode(() -> session.onTerminal(terminalObservation(successTerminal(), malformedBody)))
                    .as("malformed body trace data must never propagate an exception out of onTerminal")
                    .doesNotThrowAnyException();
            httpSpan.end();

            assertThat(((ReadableSpan) httpSpan).toSpanData().getLinks())
                    .as("DECISIVE: malformed trace-state data adds zero links")
                    .isEmpty();
            assertThat(((ReadableSpan) httpSpan).getAttribute(McpServerSpanObserver.VERTIQUE_MCP_OUTCOME))
                    .as("every other enrichment attribute must still be recorded despite the malformed link data")
                    .isEqualTo("SUCCESS");
        }
    }

    // --- shouldEmitProtocolVersionOnlyWhenNegotiated ---

    /**
     * R05 (issue #431): {@code mcp.protocol.version} is set only from a terminal event whose {@link
     * McpRequestTerminalEvent#protocolVersion()} is non-{@code null} — never a hardcoded constant, and
     * never emitted for a request whose negotiation never completed.
     */
    private void shouldEmitProtocolVersionOnlyWhenNegotiated() {
        try (TracerFixture fixture = TracerFixture.create()) {
            Span negotiatedSpan = fixture.startSpan("http-server-span-protocol-version-negotiated");
            McpServerSpanObserver negotiatedObserver = new McpServerSpanObserver();
            McpRequestObservation negotiatedSession;
            try (Scope scope = negotiatedSpan.makeCurrent()) {
                negotiatedSession = negotiatedObserver.open(STARTED_AT);
            }
            negotiatedSession.onTerminal(terminalObservation(successTerminalWithProtocolVersion("2026-07-28"), null));
            negotiatedSpan.end();

            assertThat(((ReadableSpan) negotiatedSpan).getAttribute(McpServerSpanObserver.MCP_PROTOCOL_VERSION))
                    .as("DECISIVE: the negotiated value from the terminal event is emitted verbatim, never a "
                            + "hardcoded literal chosen by this observer")
                    .isEqualTo("2026-07-28");

            Span unnegotiatedSpan = fixture.startSpan("http-server-span-protocol-version-absent");
            McpServerSpanObserver unnegotiatedObserver = new McpServerSpanObserver();
            McpRequestObservation unnegotiatedSession;
            try (Scope scope = unnegotiatedSpan.makeCurrent()) {
                unnegotiatedSession = unnegotiatedObserver.open(STARTED_AT);
            }
            unnegotiatedSession.onTerminal(terminalObservation(successTerminal(), null));
            unnegotiatedSpan.end();

            assertThat(((ReadableSpan) unnegotiatedSpan).getAttribute(McpServerSpanObserver.MCP_PROTOCOL_VERSION))
                    .as("DECISIVE: a terminal event whose negotiation never completed (protocolVersion=null) "
                            + "emits no attribute at all, not an empty or default one")
                    .isNull();
            assertThat(((ReadableSpan) unnegotiatedSpan).getAttribute(McpServerSpanObserver.VERTIQUE_MCP_OUTCOME))
                    .as("the rest of enrichment must still happen even when protocolVersion is absent")
                    .isEqualTo("SUCCESS");
        }
    }

    // --- Fixtures ---

    private static McpRequestTerminalEvent successTerminal() {
        return McpRequestTerminalEvent.success(
                STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, KNOWN_TOOL, 200, null, null, null, null);
    }

    private static McpRequestTerminalEvent successTerminalWithProtocolVersion(String protocolVersion) {
        return McpRequestTerminalEvent.success(
                STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, KNOWN_TOOL, 200, protocolVersion, null, null, null);
    }

    private static McpRequestTerminalObservation terminalObservation(
            McpRequestTerminalEvent terminal, McpTraceContext bodyTraceContext) {
        return new McpRequestTerminalObservation(terminal, bodyTraceContext);
    }

    /**
     * Delivers {@code session}'s terminal callback on a dedicated, genuinely different thread from the
     * caller's — optionally making {@code currentOnDeliveryThread} current there for the duration of
     * the call, or leaving nothing current when {@code null}.
     */
    private static void deliverOnDifferentThread(
            McpRequestObservation session,
            Span currentOnDeliveryThread,
            McpRequestTerminalEvent terminal,
            McpTraceContext bodyTraceContext)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit((java.util.concurrent.Callable<Void>) () -> {
                        if (currentOnDeliveryThread != null) {
                            try (Scope scope = currentOnDeliveryThread.makeCurrent()) {
                                session.onTerminal(terminalObservation(terminal, bodyTraceContext));
                            }
                        } else {
                            session.onTerminal(terminalObservation(terminal, bodyTraceContext));
                        }
                        return null;
                    })
                    .get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /** An isolated OpenTelemetry SDK tracer provider plus its in-memory exporter, one per row. */
    private static final class TracerFixture implements AutoCloseable {
        private final SdkTracerProvider provider;
        final InMemorySpanExporter exporter;
        private final Tracer tracer;

        private TracerFixture(SdkTracerProvider provider, InMemorySpanExporter exporter, Tracer tracer) {
            this.provider = provider;
            this.exporter = exporter;
            this.tracer = tracer;
        }

        static TracerFixture create() {
            InMemorySpanExporter exporter = InMemorySpanExporter.create();
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
            return new TracerFixture(provider, exporter, provider.get("mcp-otel-test"));
        }

        Span startSpan(String name) {
            return tracer.spanBuilder(name).setSpanKind(SpanKind.SERVER).startSpan();
        }

        @Override
        public void close() {
            provider.close();
        }
    }
}

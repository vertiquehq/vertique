// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import dev.vertique.mcp.interceptor.McpTraceContext;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link McpRequestLifecycleObserver} implementation that captures the current Vert.x HTTP server
 * span at {@link #open} and enriches that exact retained span at logical settlement — never a span
 * resolved from {@code Span.current()} at callback time (T022, contract §4.10 amendment).
 *
 * <p>Contributed to the {@link McpRequestLifecycleObserver} multibinding by {@link
 * McpOpenTelemetryModule}. {@link #open} resolves {@link Span#current()} exactly once and retains
 * it on the returned session; {@link Session#onTerminal} enriches that retained {@link Span}
 * directly, so a terminal callback delivered on a different thread — where {@code Span.current()}
 * would return a different or no-op span — still enriches the correct span. This mirrors REST's
 * {@code ServerSpanEnrichmentContributor}/{@code ServerSpanOutcomeInterceptor} early-capture
 * technique, adapted to MCP's single terminal callback instead of a per-response interceptor.
 *
 * <p><b>No span is ever created or renamed.</b> This class calls no tracer and no span-builder; it
 * only mutates the one span already current when {@link #open} runs. When no span is current (or
 * the current span's context is invalid — e.g. no OpenTelemetry SDK installed, or Vert.x tracing is
 * not configured), {@link #open} returns a no-op session and enrichment never runs, at zero cost.
 *
 * <p>Enrichment sets the bounded attributes {@code rpc.system.name=jsonrpc}, {@code
 * mcp.method.name} (an {@link McpMethod} name, with {@link McpMethod#OTHER} remapped to {@value
 * #OTHER_METHOD_TAG} exactly like the sibling Micrometer adapter's {@code method} tag),
 * {@code vertique.mcp.outcome}, {@code vertique.mcp.result.type}, and — only for a request whose
 * terminal event carries a resolved tool identity (never the unresolved-call placeholder) —
 * {@code gen_ai.tool.name}. These are experimental OpenTelemetry semantic-convention names, so they
 * are declared as internal, Vertique-owned {@link AttributeKey} constants here rather than pulled
 * from an incubating semconv artifact dependency.
 *
 * <p>It then <em>would</em> add at most one {@link Span#addLink(SpanContext) link} for the request's
 * optional body trace context ({@link McpRequestTerminalObservation#bodyTraceContext()}): a link is
 * added only when the body trace context is present, structurally convertible into a valid
 * OpenTelemetry {@link SpanContext}, and distinct (different trace id or span id) from the captured
 * HTTP span's own context — a body context identical to the HTTP span's own context adds no link,
 * since it would be a self-reference rather than a genuine cross-boundary correlation. Malformed body
 * trace data (a {@code traceState} that does not parse as W3C {@code key=value} entries) is caught,
 * logged at WARN with a bounded diagnostic (the exception class name only — never the raw trace
 * data), and produces no link; every other enrichment attribute is still recorded. <b>This path
 * exists but is not yet fed (P05 review remediation):</b> {@code McpCompletionCoordinator} always
 * constructs {@link McpRequestTerminalObservation} with a {@code null} body trace context, so {@link
 * #addBodyTraceLink} never runs today — connecting a real source is deferred, because it means
 * accepting a client-supplied trace reference, and doing so without deliberately deciding how to
 * bound the trust placed in it would open a trace-correlation-spoofing surface. No span status is
 * ever set here: transport status remains owned by Vert.x HTTP tracing (contract §4.10).
 *
 * <p>Every callback body is wrapped in try/catch that logs at WARN and swallows, so a misbehaving
 * OpenTelemetry implementation never affects MCP request processing.
 *
 * @see McpOpenTelemetryModule
 */
@Slf4j
@Singleton
final class McpServerSpanObserver implements McpRequestLifecycleObserver {

    /** Experimental RPC system attribute key, isolated behind an internal constant (contract §4.10). */
    static final AttributeKey<String> RPC_SYSTEM_NAME = AttributeKey.stringKey("rpc.system.name");

    /** Experimental MCP method-name attribute key. */
    static final AttributeKey<String> MCP_METHOD_NAME = AttributeKey.stringKey("mcp.method.name");

    /** Vertique-owned bounded logical outcome attribute key. */
    static final AttributeKey<String> VERTIQUE_MCP_OUTCOME = AttributeKey.stringKey("vertique.mcp.outcome");

    /** Vertique-owned bounded logical result-type attribute key. */
    static final AttributeKey<String> VERTIQUE_MCP_RESULT_TYPE = AttributeKey.stringKey("vertique.mcp.result.type");

    /** Experimental GenAI tool-name attribute key, set only for a resolved tool identity. */
    static final AttributeKey<String> GEN_AI_TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");

    /** The frozen {@code rpc.system.name} value for every MCP request (contract §4.10). */
    static final String RPC_SYSTEM_JSONRPC = "jsonrpc";

    /**
     * Bounded fallback attribute value for an unrecognized client-provided {@link McpMethod},
     * mirroring the sibling Micrometer adapter's {@code _OTHER} tag convention (FR-MCP-203).
     */
    static final String OTHER_METHOD_TAG = "_OTHER";

    /** No-op session returned by {@link #open} when no valid span is current. */
    private static final McpRequestObservation NO_OP = new McpRequestObservation() {};

    /**
     * Constructs the observer. No dependencies are required; the OpenTelemetry API is accessed via
     * the static {@link Span#current()} method, exactly once, inside {@link #open}.
     */
    @Inject
    McpServerSpanObserver() {}

    /**
     * Captures {@link Span#current()} exactly once, at the moment this method runs, and retains it
     * on the returned session for later enrichment — the one capture point this whole class exists
     * to prove out (T022, contract §4.10 amendment). Never re-resolves {@code Span.current()} at any
     * later callback.
     *
     * @param startedAt unused beyond the SPI contract; enrichment is timestamp-free
     * @return a session retaining the captured span, or a no-op session when no valid span is current
     */
    @Override
    public McpRequestObservation open(Instant startedAt) {
        try {
            Span span = Span.current();
            SpanContext spanContext = span.getSpanContext();
            if (!spanContext.isValid()) {
                return NO_OP;
            }
            return new Session(span, spanContext);
        } catch (Exception e) {
            log.warn(
                    "McpServerSpanObserver failed to capture the current span at open: {}",
                    e.getClass().getName());
            return NO_OP;
        }
    }

    /**
     * Per-request session retaining the {@link Span} and {@link SpanContext} captured at {@link
     * #open}, so {@link #onTerminal} enriches exactly that span regardless of which thread delivers
     * the terminal callback or what span (if any) is current on it.
     */
    private static final class Session implements McpRequestObservation {
        private final Span span;
        private final SpanContext httpSpanContext;

        Session(Span span, SpanContext httpSpanContext) {
            this.span = span;
            this.httpSpanContext = httpSpanContext;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            try {
                enrich(span, observation.event());
            } catch (Exception e) {
                log.warn(
                        "McpServerSpanObserver failed to enrich the captured span: {}",
                        e.getClass().getName());
            }
            McpTraceContext bodyTraceContext = observation.bodyTraceContext();
            if (bodyTraceContext != null) {
                addBodyTraceLink(span, httpSpanContext, bodyTraceContext);
            }
        }
    }

    /**
     * Sets the frozen bounded attribute set on {@code span} from {@code terminal} — never a span
     * status, and never a rename.
     *
     * <p>Guarded on {@link Span#isRecording()} (P05 review remediation): the span's validity is
     * checked once at {@link #open}, but a late terminal callback can observe a span that has
     * already ended by the time this method runs. Setting attributes on a non-recording span is
     * harmless but emits SDK warnings, so this mirrors the same guard {@code
     * ServiceDispatchSpanEnrichmentInterceptor} already applies before its own attribute writes.
     *
     * @param span the span captured at {@code open}; never {@code null}
     * @param terminal the logical terminal facts; never {@code null}
     */
    private static void enrich(Span span, McpRequestTerminalEvent terminal) {
        if (!span.isRecording()) {
            return;
        }
        span.setAttribute(RPC_SYSTEM_NAME, RPC_SYSTEM_JSONRPC);
        span.setAttribute(MCP_METHOD_NAME, methodTag(terminal.method()));
        span.setAttribute(VERTIQUE_MCP_OUTCOME, terminal.outcome().name());
        span.setAttribute(VERTIQUE_MCP_RESULT_TYPE, terminal.resultType().name());
        if (!McpRequestTerminalEvent.UNKNOWN_TOOL_NAME.equals(terminal.toolName())) {
            span.setAttribute(GEN_AI_TOOL_NAME, terminal.toolName());
        }
    }

    /**
     * Maps a recognized {@link McpMethod} to its bounded attribute value, collapsing {@link
     * McpMethod#OTHER} to {@value #OTHER_METHOD_TAG} (FR-MCP-203).
     *
     * @param method the terminal event's recognized method class; never {@code null}
     * @return the bounded attribute value; never {@code null}
     */
    private static String methodTag(McpMethod method) {
        return method == McpMethod.OTHER ? OTHER_METHOD_TAG : method.name();
    }

    /**
     * Adds exactly one {@link Span#addLink(SpanContext) link} to {@code span} when {@code body}
     * converts to a valid, distinct {@link SpanContext} — and none otherwise.
     *
     * <p>Any failure while interpreting {@code body} (currently: a {@code traceState} that does not
     * parse as W3C {@code key=value} entries) is caught here so a malformed body-trace value never
     * fails the request or the rest of enrichment; only a bounded diagnostic (the exception class
     * name) is logged.
     *
     * @param span the span captured at {@code open}, already enriched with the bounded attributes
     * @param httpSpanContext {@code span}'s own captured {@link SpanContext}, used to detect a
     *     self-referential body trace context
     * @param body the request's optional, already-validated body trace context; never {@code null}
     */
    private static void addBodyTraceLink(Span span, SpanContext httpSpanContext, McpTraceContext body) {
        try {
            TraceFlags traceFlags = body.sampled() ? TraceFlags.getSampled() : TraceFlags.getDefault();
            TraceState traceState = parseTraceState(body.traceState());
            SpanContext bodySpanContext =
                    SpanContext.createFromRemoteParent(body.traceId(), body.spanId(), traceFlags, traceState);
            if (!bodySpanContext.isValid()) {
                return;
            }
            if (isSameSpan(bodySpanContext, httpSpanContext)) {
                // A body trace context identical to the HTTP span's own context is a self-reference,
                // not a genuine cross-boundary correlation — no link is added for it.
                return;
            }
            span.addLink(bodySpanContext);
        } catch (Exception e) {
            log.warn(
                    "McpServerSpanObserver failed to parse the body trace context: {}",
                    e.getClass().getName());
        }
    }

    private static boolean isSameSpan(SpanContext a, SpanContext b) {
        return a.getTraceId().equals(b.getTraceId()) && a.getSpanId().equals(b.getSpanId());
    }

    /**
     * Parses a W3C {@code tracestate}-shaped string ({@code key1=value1,key2=value2}) into a {@link
     * TraceState}. Any entry that does not contain exactly one non-edge {@code =} is treated as
     * malformed and throws, so the caller's catch block can log a bounded diagnostic and skip the
     * link rather than silently propagating unparseable data.
     *
     * @param raw the body trace context's optional trace-state string; may be {@code null}
     * @return the parsed trace state, or {@link TraceState#getDefault()} when {@code raw} is {@code
     *     null}
     * @throws IllegalArgumentException if any entry is not a well-formed {@code key=value} pair
     */
    private static TraceState parseTraceState(@Nullable String raw) {
        if (raw == null) {
            return TraceState.getDefault();
        }
        TraceStateBuilder builder = TraceState.builder();
        for (String entry : raw.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("malformed traceState entry");
            }
            builder.put(
                    entry.substring(0, separator).trim(),
                    entry.substring(separator + 1).trim());
        }
        return builder.build();
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import dev.vertique.mcp.interceptor.McpTraceContext;
import dev.vertique.mcp.lifecycle.McpCompletionScope;
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
 * <p><b>{@code mcp.protocol.version} (R05, issue #431).</b> Set only when {@link
 * McpRequestTerminalEvent#protocolVersion()} is non-{@code null} — i.e. only when this request's
 * protocol negotiation actually completed (contract §4.7). A request rejected at or before
 * negotiation, or one whose terminal event predates negotiation in the fixed pipeline (a
 * cheap-admission rejection, a malformed envelope), carries no value here and none is emitted: this
 * observer never falls back to a hardcoded version literal, exactly as the frozen {@code
 * RequestMetaObject} field this attribute mirrors is a per-request negotiated fact, not a server
 * constant.
 *
 * <p>It also adds at most one {@link Span#addLink(SpanContext) link} for the request's optional body
 * trace context ({@link McpRequestTerminalObservation#bodyTraceContext()}, populated by {@code
 * McpCompletionCoordinator} from the request body's {@code params._meta.traceparent}/{@code
 * tracestate}, R39): a link is added only when the body trace context is present, structurally
 * convertible into a valid OpenTelemetry {@link SpanContext}, and from a <em>different trace</em> than
 * the captured HTTP span's own trace id. A body reference sharing the captured span's trace id is
 * suppressed as a self-reference rather than linked — this catches a body context identical to the
 * HTTP {@code traceparent} header that established this request's parent, since that header shares
 * the captured span's trace id (only the span id differs: the captured span's own span id is freshly
 * minted at {@code open} and never equals its parent's). Suppression therefore compares trace id only,
 * never full span identity — see {@link #addBodyTraceLink}. Malformed body trace data (a {@code
 * traceState} that does not parse as W3C {@code key=value} entries) is caught, logged at WARN with a
 * bounded diagnostic (the exception class name only — never the raw trace data), and produces no
 * link; every other enrichment attribute is still recorded. No span status is ever set here: transport
 * status remains owned by Vert.x HTTP tracing (contract §4.10).
 *
 * <p><b>Completion scope (R06, issue #435).</b> The session returned by {@link #open} also implements
 * {@link dev.vertique.mcp.lifecycle.McpCompletionScope}: {@code openCompletionScope()} re-makes the
 * captured span current for the duration of the framework's completion dispatch loop ({@code
 * McpCompletionCoordinator}, in {@code vertique-mcp-server}), so a co-installed Micrometer observer's
 * timer recording happens with a valid span current and a registry-level exemplar bridge can attach
 * its trace id — the frozen contract's "the adapter always invokes the Micrometer exemplar path"
 * obligation. No OpenTelemetry type crosses into {@code vertique-mcp-core} or
 * {@code vertique-micrometer-mcp} to make this work.
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

    /**
     * Experimental MCP protocol-version attribute key (R05, issue #431). Set only when the request's
     * terminal event carries a negotiated {@link McpRequestTerminalEvent#protocolVersion()}.
     */
    static final AttributeKey<String> MCP_PROTOCOL_VERSION = AttributeKey.stringKey("mcp.protocol.version");

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
     *
     * <p>Also implements {@link McpCompletionScope} (R06, issue #435): {@link #openCompletionScope()}
     * re-makes this exact captured span current for the duration of the completion dispatch loop, so a
     * co-installed Micrometer observer's timer recording happens with a valid span current and a
     * registry-level exemplar bridge can attach its trace id — mirroring REST's {@code
     * ServerSpanCompletionScope}. Never re-resolves {@link Span#current()}; always reactivates the one
     * span captured at {@link #open}.
     */
    private static final class Session implements McpCompletionScope {
        /** No-op scope returned when the captured span's context is not (or no longer) valid. */
        private static final AutoCloseable NO_OP_SCOPE = () -> {};

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

        @Override
        public AutoCloseable openCompletionScope() {
            try {
                if (!span.getSpanContext().isValid()) {
                    return NO_OP_SCOPE;
                }
                return span.makeCurrent();
            } catch (Exception e) {
                log.warn(
                        "McpServerSpanObserver failed to open the completion scope: {}",
                        e.getClass().getName());
                return NO_OP_SCOPE;
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
        if (terminal.protocolVersion() != null) {
            span.setAttribute(MCP_PROTOCOL_VERSION, terminal.protocolVersion());
        }
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
     * converts to a valid {@link SpanContext} from a <em>different trace</em> than the captured HTTP
     * span's own trace — and none otherwise.
     *
     * <p>Suppression compares trace id only, never full span identity: the HTTP {@code traceparent}
     * header that established this request's parent context shares the captured span's trace id (the
     * captured span's own span id is freshly minted at {@code open} and never equals its parent's), so
     * a body reference identical to that HTTP header is recognized as a self-reference by trace id
     * alone.
     *
     * <p>Any failure while interpreting {@code body} (currently: a {@code traceState} that does not
     * parse as W3C {@code key=value} entries) is caught here so a malformed body-trace value never
     * fails the request or the rest of enrichment; only a bounded diagnostic (the exception class
     * name) is logged.
     *
     * @param span the span captured at {@code open}, already enriched with the bounded attributes
     * @param httpSpanContext {@code span}'s own captured {@link SpanContext}, used to detect a
     *     same-trace (self-referential) body trace context
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
            if (isSameTrace(bodySpanContext, httpSpanContext)) {
                // A body trace context sharing this request's trace id is part of this request's own
                // trace, not a genuine cross-boundary correlation — no link is added for it. The HTTP
                // traceparent header that established this request's parent context shares the
                // captured span's trace id (only the span id differs, since the captured span's own
                // span id is freshly minted at open and never equals its parent's), so a body
                // reference identical to that HTTP header — the self-reference case this suppression
                // exists to catch — would never match on span id. Suppression therefore keys on trace
                // id alone; comparing full span identity would miss the HTTP-header self-reference and
                // wrongly add a link for it.
                return;
            }
            span.addLink(bodySpanContext);
        } catch (Exception e) {
            // DEBUG, not WARN (repair task R47): the body trace context is client-supplied and
            // syntactically unvalidated at this layer, so a malformed value is client-triggerable at
            // will by an anonymous caller — not a framework or application contract violation.
            log.debug(
                    "McpServerSpanObserver failed to parse the body trace context: {}",
                    e.getClass().getName());
        }
    }

    private static boolean isSameTrace(SpanContext a, SpanContext b) {
        return a.getTraceId().equals(b.getTraceId());
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

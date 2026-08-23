// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Opt-in capability a per-request {@link McpRequestObservation} session may implement to bracket the
 * synchronous completion dispatch loop with an ambient scope (repair R06, issue #435; contract
 * §4.10).
 *
 * <p>The motivating use is the Micrometer exemplar obligation the frozen contract freezes: "when a
 * sampled HTTP span is current at terminal settlement, the adapter always invokes the Micrometer
 * exemplar path." Vert.x's OpenTelemetry tracer ends the HTTP server span before any completion
 * callback runs, so without this scope {@code Span.current()} is a no-op span by the time a Micrometer
 * observer records its timer — exactly REST's pre-{@code RequestCompletionScope} situation. An
 * OpenTelemetry MCP observer session can implement this capability to re-make its own captured span
 * current for the duration of every observer's and listener's {@code onCompleted}, so a co-installed
 * Micrometer session records while that span is current and a registry-level exemplar bridge (already
 * shared, un-MCP-specific plumbing — see {@code OpenTelemetrySpanContext}) attaches its trace id.
 *
 * <p>This mirrors {@code dev.vertique.rest.core.events.RequestCompletionScope}'s role for REST, but as
 * an opt-in session capability rather than a separately multibound set: the MCP completion dispatch
 * already threads through the per-request {@link McpRequestObservation} sessions {@link
 * McpRequestLifecycleObserver#open} returned (see {@link McpToolValueObservation} for the established
 * precedent of layering an opt-in capability the same way), so this needs no new Dagger wiring and no
 * OpenTelemetry type ever needs to enter this module or {@code vertique-micrometer-mcp}.
 *
 * <p>{@link #openCompletionScope()} is called once per request, before any retained {@link
 * McpRequestObservation#onCompleted} or {@link McpRequestCompletedListener#onCompleted} runs; the
 * returned {@link AutoCloseable} is closed once after every one of them has returned. Both the open
 * call and the close call are failure-isolated exactly like every other lifecycle callback: an
 * exception from either is caught and swallowed by the coordinator, so a misbehaving scope can affect
 * neither the request, any other observer or listener, nor any other scope.
 */
public interface McpCompletionScope extends McpRequestObservation {

    /**
     * Opens an ambient scope for the duration of the completion dispatch loop.
     *
     * <p>Must be cheap and non-blocking. Return a no-op {@code () -> {}} when nothing needs to be
     * scoped for this request (e.g. no span was captured at {@code open}, or its context is invalid).
     *
     * @return a closeable that will be closed once every observation and listener has been notified
     *     of completion; never {@code null}
     */
    AutoCloseable openCompletionScope();
}

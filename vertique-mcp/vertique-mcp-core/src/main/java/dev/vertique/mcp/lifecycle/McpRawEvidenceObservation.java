// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Opt-in capability a per-request {@link McpRequestObservation} session may implement to also receive
 * raw, unredacted request/response evidence — body bytes, headers, and the two per-request identifying
 * facts (the client-supplied JSON-RPC id and the caller's principal id) — below the neutral,
 * payload-free {@link McpRequestObservation}/{@link McpToolValueObservation} contract.
 *
 * <p>Delivery is opt-in by type, exactly like {@link McpToolValueObservation}: the server delivers
 * {@link #onRequestAdmitted}/{@link #onResponseWritten} only to a session whose {@link
 * McpRequestLifecycleObserver#open(java.time.Instant)} returned an instance of this interface. An
 * ordinary metrics or tracing session that implements only the plain {@link McpRequestObservation}
 * contract has no method on its own interface capable of receiving raw evidence, through any callback.
 * This is not an access-control boundary: the interface is public and observers are contributed
 * through an open set, so any contributed observer can opt in and then receives raw request and
 * response bodies, headers, the client's JSON-RPC id and the caller's principal id. Contribute such
 * an observer only from code trusted with that data.
 *
 * <p>This interface exists so an observer that needs the raw envelope, such as an audit adapter, can
 * reach it without widening the public {@link McpRequestObservation}/{@link McpToolValueObservation}
 * contract every other neutral observer (Micrometer, OpenTelemetry, any future integration) also
 * implements.
 *
 * <p>Observation is read-only. Evidence bodies are snapshots, so an observer cannot change the
 * request the server processes or the response it writes.
 */
public interface McpRawEvidenceObservation extends McpRequestObservation {

    /**
     * Receives this request's raw admission-time evidence.
     *
     * <p>Fires at most once per request, before tool-name resolution or authorization — so it fires
     * even for a request rejected before the input pipeline runs (an unknown tool, an authorization
     * denial, a protocol-level rejection) — unlike {@link
     * McpToolValueObservation#onToolInput(McpToolInputObservation)}, which never fires for such a
     * request.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param evidence this request's raw admission-time evidence; never {@code null}
     */
    default void onRequestAdmitted(McpRequestAdmissionEvidence evidence) {}

    /**
     * Receives this request's raw response-side evidence.
     *
     * <p>Fires at most once per request, immediately before the single shared terminal writer sends
     * the response to the wire — for every terminal write, including a bounded error or rejection
     * response.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param evidence this request's raw response-side evidence; never {@code null}
     */
    default void onResponseWritten(McpResponseEvidence evidence) {}
}

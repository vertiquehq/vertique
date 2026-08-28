// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Opt-in capability a per-request {@link McpRequestObservation} session may implement to also
 * receive the bounded, normalized value tree of a {@code tools/call} invocation (contract
 * §4.4).
 *
 * <p>Least privilege is structural, not a runtime check: the server sends a tool-value callback
 * only to a session whose {@link McpRequestLifecycleObserver#open(java.time.Instant)} returned an
 * instance of this interface. An ordinary metrics or tracing session that implements only the plain
 * {@link McpRequestObservation} contract has no method on its own interface capable of receiving an
 * argument or result reference, through any callback — it is not merely that nothing is delivered,
 * but that nothing on its type could ever be delivered to.
 *
 * <p>Values are callback-scoped. The framework retains no reference to a delivered {@link
 * McpToolInputObservation}/{@link McpToolOutputObservation} or its value tree once the callback that
 * received it returns; an implementor that retains a reference beyond its own callback does so under
 * its own documented obligation, not a framework-enforced one — an immutable record cannot revoke
 * itself. Only an installed audit adapter may copy a policy-permitted value into its own private
 * evidence handle.
 */
public interface McpToolValueObservation extends McpRequestObservation {

    /**
     * Receives the bounded, normalized input argument tree for one {@code tools/call} invocation.
     *
     * <p>Fires at most once per request, after schema validation, INP-001 canonicalization and
     * sanitization, materialization, and Bean Validation have all succeeded, but strictly before any
     * ordered, fail-closed {@code McpToolInterceptor} runs. Never fires for a request that never
     * reached a prepared invocation (a protocol, authentication, authorization, or input-validation
     * failure) — such a request still receives {@link #onTerminal} and {@link #onCompleted}.
     *
     * @param observation the bounded, unmodifiable, normalized input observation; never {@code null}
     */
    default void onToolInput(McpToolInputObservation observation) {}

    /**
     * Receives the bounded, schema-valid normalized output value for one {@code tools/call}
     * invocation.
     *
     * <p>Fires at most once per request, only after bounded output normalization and output-schema
     * validation have both succeeded. This callback belongs to the output pipeline slice; no producer
     * in this task invokes it.
     *
     * @param observation the bounded, normalized output observation; never {@code null}
     */
    default void onToolOutput(McpToolOutputObservation observation) {}
}

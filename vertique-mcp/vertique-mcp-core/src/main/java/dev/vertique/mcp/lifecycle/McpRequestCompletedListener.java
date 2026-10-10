// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Observe-only callback invoked once after response transport completion.
 *
 * <p>Implementations must not block or alter request processing. The server isolates listener
 * failures and does not define listener ordering.
 *
 * <p>The server always calls {@link #onCompleted(McpRequestCompletedEvent, McpRequestView)}. Its
 * default delegates to {@link #onCompleted(McpRequestCompletedEvent)}, so a listener that needs only
 * the event implements the one-argument form, as it always did, and a lambda still works. A listener
 * that needs the view overrides the two-argument form and implements the one-argument form as an
 * empty method. Never make the one-argument form delegate back to the two-argument one, which would
 * recurse.
 *
 * <h2>Which form for which need</h2>
 *
 * <ul>
 *   <li>{@link #onCompleted(McpRequestCompletedEvent)} sees the request's facts: outcome, tool name,
 *       transport outcome and timing. Use it for metrics and logging.
 *   <li>{@link #onCompleted(McpRequestCompletedEvent, McpRequestView)} also sees the raw request
 *       and response and the normalized tool values, through a framework-owned view. Use it for
 *       audit evidence.
 * </ul>
 */
public interface McpRequestCompletedListener {

    /**
     * Receives the completed request facts.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param event the transport completion facts for this request
     */
    void onCompleted(McpRequestCompletedEvent event);

    /**
     * Receives the completed request facts together with the framework-owned view of the request.
     *
     * <p>The server always calls this form. The default delegates to {@link
     * #onCompleted(McpRequestCompletedEvent)}.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param event the transport completion facts for this request
     * @param request the request's raw bytes and normalized values; each listener receives its own
     *     read-only view
     */
    default void onCompleted(McpRequestCompletedEvent event, McpRequestView request) {
        onCompleted(event);
    }
}

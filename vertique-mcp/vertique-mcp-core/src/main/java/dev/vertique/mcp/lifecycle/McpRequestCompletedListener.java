// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Observe-only callback invoked once after response transport completion.
 *
 * <p>Implementations must not block or alter request processing. The server isolates listener
 * failures and does not define listener ordering.
 */
public interface McpRequestCompletedListener {

    /**
     * Receives the completed request facts.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation. A {@link LinkageError} and an {@link AssertionError} are
     * contained the same way, and so is a {@link StackOverflowError}; a {@link LinkageError} is
     * reported at error level at a limited rate.
     *
     * @param event the transport completion facts for this request
     */
    void onCompleted(McpRequestCompletedEvent event);
}

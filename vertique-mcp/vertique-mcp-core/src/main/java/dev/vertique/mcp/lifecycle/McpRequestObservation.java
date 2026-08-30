// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Per-request, observe-only lifecycle session.
 *
 * <p>Callbacks are synchronous and non-blocking. The server invokes terminal once at logical
 * settlement and completion once after transport settlement, and isolates callback failures.
 */
public interface McpRequestObservation {

    /**
     * Receives the one logical terminal observation for this request.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param observation the logical terminal facts for this request
     */
    default void onTerminal(McpRequestTerminalObservation observation) {}

    /**
     * Receives the one transport completion event for this request.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param event the transport completion facts for this request
     */
    default void onCompleted(McpRequestCompletedEvent event) {}
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

/**
 * Signals that a {@code tools/call} argument tree failed one of the fixed request-time input stages
 * (contract §4.7): INP-001 canonicalization/sanitization, materialization through the effective
 * mapper, or Bean Validation.
 *
 * <p>Thrown only by {@link dev.vertique.mcp.tool.McpToolInvoker#prepare} implementations — the
 * generated fixed input boundary that owns stages 2–4 — never by the schema-validation stage the
 * dispatcher itself owns (stage 1 has no {@code prepare()} call to throw from). {@link
 * McpRequestDispatcher} distinguishes this from an ordinary programming-error {@link RuntimeException}
 * so a pipeline rejection settles as the bounded text-only {@code isError=true} tool-error outcome the
 * contract requires, never the internal-error fallback a genuine bug produces.
 *
 * <p>{@link #getMessage()} must already be a safe, bounded message: it is returned to the caller
 * verbatim as the tool-error text content.
 */
final class McpInputRejectionException extends RuntimeException {

    /**
     * Creates a rejection carrying the exact safe message returned to the caller.
     *
     * @param safeMessage the bounded, non-leaking message to return as the tool-error text
     */
    McpInputRejectionException(String safeMessage) {
        super(safeMessage);
    }

    /**
     * Creates a rejection carrying the exact safe message returned to the caller, and the internal
     * cause for diagnostics — the cause is never serialized to the wire.
     *
     * @param safeMessage the bounded, non-leaking message to return as the tool-error text
     * @param cause the internal failure that triggered the rejection
     */
    McpInputRejectionException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}

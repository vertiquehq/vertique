// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/** Bounded failure classifications for lifecycle events. */
public enum McpErrorType {
    NONE,
    HTTP,
    PROTOCOL,
    AUTHENTICATION,
    AUTHORIZATION,
    INPUT_VALIDATION,
    INPUT_PROCESSING,
    INTERCEPTOR,
    HANDLER,
    OUTPUT_VALIDATION,
    SERIALIZATION,
    TIMEOUT,
    TRANSPORT,
    INTERNAL
}

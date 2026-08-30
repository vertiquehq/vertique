// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link McpInputRejectionException} is rooted in the core
 * {@link ValidationException} semantic root (java-conventions.md § Exception Classes; ADR-0112), so
 * callers catching input-validation failures receive tool-input rejections without a separate catch
 * clause, and REST-style default mappers classify it as HTTP 400 without an MCP-specific mapping rule.
 */
class McpInputRejectionExceptionHierarchyTest {

    @Test
    @DisplayName("McpInputRejectionException is a ValidationException")
    void mcpInputRejectionExceptionExtendsValidationException() {
        McpInputRejectionException ex = new McpInputRejectionException("bad input");

        assertThat(ex).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("McpInputRejectionException(safeMessage) preserves the message")
    void mcpInputRejectionExceptionPreservesMessage() {
        McpInputRejectionException ex = new McpInputRejectionException("bad input");

        assertThat(ex.getMessage()).isEqualTo("bad input");
    }

    @Test
    @DisplayName("McpInputRejectionException(safeMessage, cause) preserves message and cause")
    void mcpInputRejectionExceptionWithCausePreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("underlying");
        McpInputRejectionException ex = new McpInputRejectionException("bad input", cause);

        assertThat(ex.getMessage()).isEqualTo("bad input");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex).isInstanceOf(ValidationException.class);
    }
}

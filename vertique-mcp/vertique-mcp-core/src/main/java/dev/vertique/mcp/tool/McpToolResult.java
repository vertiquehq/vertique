// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * The immutable result of one tool call.
 *
 * <p>A handler may return this type directly when it needs to control text content or report a tool
 * execution error; a handler that returns a plain value has its result wrapped by generated code
 * instead. Use the named factories rather than the canonical constructor.
 *
 * <p>A success from a handler whose declared result has an output schema must carry non-null
 * structured content. An error result may be text-only even when the tool declares an output
 * schema; structured error content, when present, is still validated against that schema.
 *
 * <p>{@code isError} marks a <em>tool execution</em> error — a failure the model is expected to
 * see and reason about. An unhandled exception, an output-schema failure, or a serialization
 * failure is not expressed here: it becomes a bounded protocol-level internal error instead.
 *
 * @param <T> the structured content type, or {@link Void} for a text-only result
 * @param textContent the text content items, defensively copied and free of null elements
 * @param structuredContent the structured content, or {@code null} for a text-only result
 * @param isError whether the call reports a tool execution error
 */
public record McpToolResult<T>(
        List<String> textContent, @Nullable T structuredContent, boolean isError) {

    /**
     * Validates that the result carries content and defensively copies the text items.
     *
     * @throws NullPointerException if {@code textContent} is null or contains a null element
     * @throws IllegalArgumentException if the result has neither a text item nor structured content
     */
    public McpToolResult {
        Objects.requireNonNull(textContent, "textContent");
        textContent = List.copyOf(textContent);
        if (textContent.isEmpty() && structuredContent == null) {
            throw new IllegalArgumentException("a result requires at least one text item or structured content");
        }
    }

    /**
     * Creates a successful text-only result.
     *
     * @param text the single text content item
     * @return a successful result carrying only {@code text}
     * @throws NullPointerException if {@code text} is null
     */
    public static McpToolResult<Void> text(String text) {
        return new McpToolResult<>(List.of(text), null, false);
    }

    /**
     * Creates a successful structured result.
     *
     * @param value the structured content, serialized and validated against the tool's output
     *     schema before the call succeeds
     * @param <T> the structured content type
     * @return a successful result carrying {@code value} as structured content
     * @throws NullPointerException if {@code value} is null
     */
    public static <T> McpToolResult<T> structured(T value) {
        Objects.requireNonNull(value, "value");
        return new McpToolResult<>(List.of(), value, false);
    }

    /**
     * Creates a tool execution error result.
     *
     * @param safeMessage the message to return to the client; it must not reveal internal detail
     * @return an error result carrying {@code safeMessage} as its only text item
     * @throws NullPointerException if {@code safeMessage} is null
     */
    public static McpToolResult<Void> error(String safeMessage) {
        return new McpToolResult<>(List.of(safeMessage), null, true);
    }
}

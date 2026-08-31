// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
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
 * @param <T> the structured content type, or {@link Void} when the result has no structured value
 */
public final class McpToolResult<T> {
    private final List<String> textContent;
    private final List<McpContent> content;

    @Nullable
    private final T structuredContent;

    private final boolean isError;

    /**
     * Validates that the result carries content and defensively copies the text items.
     *
     * @throws NullPointerException if {@code textContent} is null or contains a null element
     * @throws IllegalArgumentException if the result has neither a text item nor structured content
     */
    public McpToolResult(List<String> textContent, @Nullable T structuredContent, boolean isError) {
        Objects.requireNonNull(textContent, "textContent");
        this.textContent = List.copyOf(textContent);
        this.content = List.of();
        this.structuredContent = structuredContent;
        this.isError = isError;
        if (textContent.isEmpty() && structuredContent == null) {
            throw new IllegalArgumentException("a result requires at least one text item or structured content");
        }
    }

    private McpToolResult(List<McpContent> content, @Nullable T structuredContent, boolean isError, boolean rich) {
        Objects.requireNonNull(content, "content");
        this.content = List.copyOf(content);
        this.textContent = this.content.stream()
                .filter(McpContent.Text.class::isInstance)
                .map(McpContent.Text.class::cast)
                .map(McpContent.Text::text)
                .toList();
        this.structuredContent = structuredContent;
        this.isError = isError;
        if (this.content.isEmpty() && structuredContent == null) {
            throw new IllegalArgumentException("a result requires at least one content item or structured content");
        }
    }

    public final List<String> textContent() {
        return textContent;
    }

    /** Returns the handler-authored standard MCP content blocks in order. */
    public final List<McpContent> content() {
        if (!content.isEmpty()) {
            return content;
        }
        return textContent.stream()
                .map(McpContent.Text::new)
                .map(McpContent.class::cast)
                .toList();
    }

    @Nullable
    public final T structuredContent() {
        return structuredContent;
    }

    public final boolean isError() {
        return isError;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpToolResult<?> that)) return false;
        return isError == that.isError
                && textContent.equals(that.textContent)
                && content.equals(that.content)
                && Objects.equals(structuredContent, that.structuredContent);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(textContent, content, structuredContent, isError);
    }

    @Override
    public final String toString() {
        return "McpToolResult[content=" + content() + ", structuredContent=" + structuredContent + ", isError="
                + isError + "]";
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

    /** Creates a successful result carrying standard MCP content blocks in the given order. */
    public static McpToolResult<Void> content(List<? extends McpContent> content) {
        return new McpToolResult<>(new ArrayList<>(content), null, false, true);
    }

    /** Creates a successful result carrying standard content blocks and structured content. */
    public static <T> McpToolResult<T> content(List<? extends McpContent> content, @Nullable T structuredContent) {
        return new McpToolResult<>(new ArrayList<>(content), structuredContent, false, true);
    }

    /** Creates an error result carrying standard MCP content blocks in the given order. */
    public static McpToolResult<Void> errorContent(List<? extends McpContent> content) {
        return new McpToolResult<>(new ArrayList<>(content), null, true, true);
    }

    /** Creates an error result carrying standard content blocks and structured content. */
    public static <T> McpToolResult<T> errorContent(List<? extends McpContent> content, @Nullable T structuredContent) {
        return new McpToolResult<>(new ArrayList<>(content), structuredContent, true, true);
    }
}

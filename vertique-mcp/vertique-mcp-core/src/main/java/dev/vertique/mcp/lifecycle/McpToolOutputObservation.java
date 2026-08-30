// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * The bounded, schema-valid normalized output value an opt-in {@link McpToolValueObservation}
 * session receives through {@link
 * McpToolValueObservation#onToolOutput(McpToolOutputObservation)} (contract §4.4).
 *
 * <p>{@code normalizedOutput} is deep-copied into an unmodifiable view at every level of its nested
 * {@code Map}/{@code List} structure by this record's compact constructor, exactly like {@link
 * McpToolInputObservation#normalizedArguments()}, regardless of whether the value handed in was
 * already immutable. This type exposes no accessor for raw body bytes, headers, credentials, or
 * exception text. The dispatch of this callback belongs to the output pipeline slice; this task
 * introduces only the type and its bounds.
 *
 * @param context the pre-dispatch request snapshot and resolved tool descriptor; never {@code null}
 * @param normalizedOutput the bounded, deeply unmodifiable, schema-valid normalized output value, or
 *     {@code null} when the tool produced no structured output
 */
public record McpToolOutputObservation(
        McpToolInvocationContext context, @Nullable Object normalizedOutput) {

    /**
     * Validates the required field and deep-copies {@code normalizedOutput} into an unmodifiable
     * view at every nesting level.
     *
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public McpToolOutputObservation {
        Objects.requireNonNull(context, "context");
        normalizedOutput = McpValueTrees.deepUnmodifiable(normalizedOutput);
    }
}

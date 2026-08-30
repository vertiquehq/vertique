// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import java.util.Map;
import java.util.Objects;

/**
 * The bounded, unmodifiable, normalized input argument tree an opt-in {@link
 * McpToolValueObservation} session receives through {@link
 * McpToolValueObservation#onToolInput(McpToolInputObservation)} (contract §4.4).
 *
 * <p>{@code normalizedArguments} is exactly the argument tree {@code McpPreparedToolCall
 * #normalizedArguments()} produced — after schema validation, INP-001 canonicalization and
 * sanitization, materialization, and Bean Validation — deep-copied into an unmodifiable view at
 * every level of its nested {@code Map}/{@code List} structure by this record's compact
 * constructor, regardless of whether the tree handed in was already immutable. This type exposes no
 * accessor for raw body bytes, headers, credentials, or exception text; it carries only {@code
 * context} (the same argument-free snapshot a tool interceptor observes) and the bounded value tree.
 *
 * @param context the pre-dispatch request snapshot and resolved tool descriptor; never {@code null}
 * @param normalizedArguments the bounded, deeply unmodifiable normalized argument tree; never {@code
 *     null}
 */
public record McpToolInputObservation(McpToolInvocationContext context, Map<String, Object> normalizedArguments) {

    /**
     * Validates the required fields and deep-copies {@code normalizedArguments} into an
     * unmodifiable view at every nesting level.
     *
     * @throws NullPointerException if {@code context} or {@code normalizedArguments} is {@code null}
     */
    public McpToolInputObservation {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(normalizedArguments, "normalizedArguments");
        normalizedArguments = McpValueTrees.deepUnmodifiableMap(normalizedArguments);
    }
}

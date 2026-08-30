// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.interceptor;

import dev.vertique.mcp.tool.McpToolDescriptor;
import java.util.Objects;

/**
 * The immutable, argument-free snapshot an {@link McpToolInterceptor} observes at the frozen
 * post-validation stage: after Bean Validation has already run inside the generated invoker's
 * {@code prepare(...)}, but before the generated invocation ({@code McpPreparedToolCall#invoke()})
 * ever runs.
 *
 * <p>This record deliberately projects only the pre-dispatch {@link McpRequestContext} and the
 * resolved {@link McpToolDescriptor}: it carries no accessor for the raw wire argument tree, the
 * post-processing normalized argument tree, or any invocation result. A tool interceptor may permit
 * or reject a call, but it can never observe or mutate the arguments it is guarding.
 *
 * @param request the pre-dispatch request snapshot the frozen contract already establishes for this
 *     call; never {@code null}
 * @param tool the resolved, immutable descriptor of the tool being called; never {@code null}
 */
public record McpToolInvocationContext(McpRequestContext request, McpToolDescriptor tool) {

    /**
     * Validates the required fields.
     *
     * @throws NullPointerException if {@code request} or {@code tool} is {@code null}
     */
    public McpToolInvocationContext {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(tool, "tool");
    }
}

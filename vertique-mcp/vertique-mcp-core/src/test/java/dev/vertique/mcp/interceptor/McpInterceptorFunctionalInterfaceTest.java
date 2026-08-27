// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R44 remediation proof — {@link McpRequestInterceptor} and {@link McpToolInterceptor} each declare
 * exactly one abstract method (their {@code OrderedExtension} supertype contributes none), so a
 * lambda is a valid implementation and composes into a Vert.x {@link Future} chain like any other
 * implementor. This pins the {@code @FunctionalInterface} property mechanically, not just by
 * annotation presence.
 */
class McpInterceptorFunctionalInterfaceTest {

    private final McpRequestContext requestContext = new McpRequestContext(
            McpMethod.TOOLS_CALL, SecurityContexts.unauthenticated(SecurityIdentity.anonymous()), null, null);

    private final McpToolInvocationContext invocationContext = new McpToolInvocationContext(
            requestContext,
            new McpToolDescriptor(
                    "functional.interface.tool",
                    null,
                    "R44 functional-interface fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null)));

    @Test
    @DisplayName("DECISIVE: a lambda-declared McpRequestInterceptor composes and runs")
    void lambdaRequestInterceptorComposesAndRuns() {
        AtomicInteger invocations = new AtomicInteger();
        McpRequestInterceptor interceptor = context -> {
            invocations.incrementAndGet();
            return Future.succeededFuture();
        };

        Future<Void> outcome = interceptor.beforeRequest(requestContext).compose(v -> Future.succeededFuture());

        assertThat(outcome.succeeded())
                .as("the composed future must settle successfully")
                .isTrue();
        assertThat(invocations.get())
                .as("the lambda body must have actually run, not merely type-checked")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("DECISIVE: a lambda-declared McpToolInterceptor composes and runs")
    void lambdaToolInterceptorComposesAndRuns() {
        AtomicInteger invocations = new AtomicInteger();
        McpToolInterceptor interceptor = context -> {
            invocations.incrementAndGet();
            return Future.succeededFuture();
        };

        Future<Void> outcome = interceptor.beforeInvocation(invocationContext).compose(v -> Future.succeededFuture());

        assertThat(outcome.succeeded())
                .as("the composed future must settle successfully")
                .isTrue();
        assertThat(invocations.get())
                .as("the lambda body must have actually run, not merely type-checked")
                .isEqualTo(1);
    }
}

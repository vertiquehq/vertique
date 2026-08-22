// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.interceptor;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.security.SecurityContext;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * The immutable, payload-free snapshot an {@link McpRequestInterceptor} observes at the frozen
 * pre-dispatch stage: after the envelope is decoded and identity is established, but before the
 * method is dispatched to a handler and before any tool is resolved or argument is processed.
 *
 * <p>{@code securityContext} is always non-null: an anonymous endpoint binds a context whose actor
 * is {@code PrincipalType.ANONYMOUS}, authentication method is {@code none}, and claims are empty —
 * downstream interception never uses {@code null} to mean anonymous. {@code correlation} and
 * {@code bodyTraceContext} are optional and present only once the framework has captured them for
 * this request.
 *
 * <p>This record intentionally exposes no request payload, header, or credential accessor: an
 * {@link McpRequestInterceptor} may permit or reject a request, but it can never observe the body it
 * is guarding.
 *
 * @param method the recognized method class this request is about to dispatch to
 * @param securityContext the established caller security context; never {@code null}
 * @param correlation the correlation context snapshot, when captured for this request
 * @param bodyTraceContext the normalized W3C trace reference, when captured for this request
 */
public record McpRequestContext(
        McpMethod method,
        SecurityContext securityContext,
        @Nullable CorrelationContextSnapshot correlation,
        @Nullable McpTraceContext bodyTraceContext) {

    /**
     * Validates the required fields.
     *
     * @throws NullPointerException if {@code method} or {@code securityContext} is {@code null}
     */
    public McpRequestContext {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(securityContext, "securityContext");
    }
}

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
 * downstream interception never uses {@code null} to mean anonymous. {@code correlation} is
 * optional and present only once the framework has captured it for this request.
 *
 * <p>This record intentionally exposes no request payload, header, or credential accessor: an
 * {@link McpRequestInterceptor} may permit or reject a request, but it can never observe the body it
 * is guarding.
 *
 * <p>This record carries
 * no body trace context: no interceptor ever needs one, so it stays off this payload-free
 * record. The request body's optional, untrusted W3C trace reference — extracted
 * only when {@code McpBodyTracePolicy.LINK} is configured — travels solely on the payload-free
 * terminal lifecycle observation ({@code dev.vertique.mcp.lifecycle.McpRequestTerminalObservation#linkedTrace()}),
 * never on this pre-dispatch context and never on {@link CorrelationContextSnapshot}.
 *
 * @param method the recognized method class this request is about to dispatch to
 * @param securityContext the established caller security context; never {@code null}
 * @param correlation the correlation context snapshot, when captured for this request
 */
public record McpRequestContext(
        McpMethod method,
        SecurityContext securityContext,
        @Nullable CorrelationContextSnapshot correlation) {

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

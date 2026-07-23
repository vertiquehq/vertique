// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.security.SecurityContext;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable request record passed to an {@link AuthorizationPolicy} for evaluation.
 *
 * <p>An {@code AuthorizationRequest} combines the current {@link SecurityContext} (who is asking),
 * the requested action verb, the target {@link ResourceRef}, the {@link InvocationOrigin} (the
 * transport boundary the request was raised through), and optional evaluation-time context
 * attributes. The policy receives this record and returns an {@link AuthorizationDecision}.
 *
 * <p><strong>{@code context} is advisory input, never a trust source.</strong> The map carries
 * caller-supplied evaluation hints (tenant, environment) only; the authoritative identity and
 * authorities always come from {@link #securityContext()}. Callers MUST NOT place a security
 * decision or an authority claim in {@code context} expecting the engine to trust it, and MUST NOT
 * place sensitive data (tokens, credentials, PII) in it — the engine may surface it in audit output.
 * {@link #origin()} carries the same advisory contract — see {@link InvocationOrigin}.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code securityContext}, {@code action}, {@code resource}, and {@code origin} are required
 *       (non-null; {@code action} must also be non-blank)</li>
 *   <li>A null {@code context} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The {@code context} map is defensively copied</li>
 * </ul>
 *
 * <p>Two constructors are provided:
 * <ul>
 *   <li>The canonical 5-arg constructor, which takes an explicit {@link InvocationOrigin}.</li>
 *   <li>A 4-arg convenience constructor — {@link #AuthorizationRequest(SecurityContext, String,
 *       ResourceRef, Map)} — for callers that don't seed an invocation origin; it delegates to the
 *       canonical constructor with {@link InvocationOrigin#unspecified()}.</li>
 * </ul>
 *
 * @param securityContext the current request-scoped security context; must not be {@code null}
 * @param action          the action being requested (e.g., {@code "READ"}, {@code "DELETE"});
 *                        must not be blank
 * @param resource        reference to the target resource; must not be {@code null}
 * @param origin          the transport-neutral invocation boundary the request was raised through;
 *                        must not be {@code null}; advisory input only — see {@link InvocationOrigin}
 * @param context         optional evaluation-time attributes (e.g., tenant, environment); may be
 *                        {@code null} (treated as empty); advisory input only — must not be used as
 *                        a trust source and must not carry sensitive data
 */
public record AuthorizationRequest(
        SecurityContext securityContext,
        String action,
        ResourceRef resource,
        InvocationOrigin origin,
        Map<String, Object> context) {

    /**
     * Compact constructor — validates required fields and defensively copies the context map.
     */
    public AuthorizationRequest {
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(action, "action");
        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(origin, "origin");
        context = Map.copyOf(context == null ? Map.of() : context);
    }

    /**
     * Convenience constructor for callers that don't seed an invocation origin — delegates to the
     * canonical constructor with {@link InvocationOrigin#unspecified()}.
     *
     * @param securityContext the current request-scoped security context; must not be {@code null}
     * @param action          the action being requested; must not be blank
     * @param resource        reference to the target resource; must not be {@code null}
     * @param context         optional evaluation-time attributes; may be {@code null} (treated as
     *                        empty); advisory input only
     */
    public AuthorizationRequest(
            SecurityContext securityContext, String action, ResourceRef resource, Map<String, Object> context) {
        this(securityContext, action, resource, InvocationOrigin.unspecified(), context);
    }
}

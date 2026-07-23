// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.authz.ActionRef;
import java.util.Optional;

/**
 * Context provided to {@link OperationHandlerContributor} instances during route registration.
 *
 * <p>Contains the operation metadata as a transport-neutral {@link RestOperationDescriptor} and the
 * per-operation {@link RouteRegistration} onto which contributors add handlers. Both are neutral
 * rest-core types — the context no longer exposes the Vert.x OpenAPI {@code OpenAPIRoute} or
 * {@code RouterBuilder} (FR-022).
 *
 * <p>The {@link #requiredAction()} component carries the canonical {@link ActionRef} resolved from a
 * {@code @RequiresAction} annotation on the operation's method or class (method overrides class),
 * or {@link Optional#empty()} when the operation declares no action gate. It is deliberately kept
 * separate from {@link #securityPolicy()} because {@code @RequiresAction} AND-composes with the
 * Jakarta role/scope policy rather than being one of its variants. The value is validated against
 * the {@code ActionRegistry} at startup, so a present {@link ActionRef} is guaranteed registered.
 *
 * @param operationId    the {@code operationId} linking this operation to the spec
 * @param securityPolicy the security policy derived from annotations on the resource method
 * @param requiredAction the canonical action gate resolved from {@code @RequiresAction}, or
 *                       {@link Optional#empty()} when none is declared; never {@code null}
 * @param operation      the transport-neutral descriptor for this operation, exposing its identity,
 *                       security policy, route template, and annotations
 * @param route          the per-operation registration surface to which handlers can be added via
 *                       {@link RouteRegistration#addHandler}
 */
public record OperationRegistrationContext(
        String operationId,
        SecurityPolicy securityPolicy,
        Optional<ActionRef> requiredAction,
        RestOperationDescriptor operation,
        RouteRegistration route) {

    /**
     * Compact constructor normalising a {@code null} {@code requiredAction} to
     * {@link Optional#empty()} so callers that pass {@code null} for "no action gate" do not produce
     * an invalid context.
     */
    public OperationRegistrationContext {
        requiredAction = requiredAction == null ? Optional.empty() : requiredAction;
    }

    /**
     * Convenience constructor for operations with no {@code @RequiresAction} gate; defaults
     * {@link #requiredAction()} to {@link Optional#empty()}.
     *
     * @param operationId    the {@code operationId} linking this operation to the spec
     * @param securityPolicy the security policy derived from annotations on the resource method
     * @param operation      the transport-neutral descriptor for this operation
     * @param route          the per-operation registration surface to which handlers can be added
     */
    public OperationRegistrationContext(
            String operationId,
            SecurityPolicy securityPolicy,
            RestOperationDescriptor operation,
            RouteRegistration route) {
        this(operationId, securityPolicy, Optional.empty(), operation, route);
    }
}

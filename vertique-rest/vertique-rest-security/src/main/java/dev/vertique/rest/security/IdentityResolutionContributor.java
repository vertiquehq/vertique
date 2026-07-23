// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OperationHandlerContributor} that adds {@link IdentityResolutionMiddleware} to every
 * OpenAPI operation route.
 *
 * <p>Mounts the middleware via {@code route.addHandler()}, which places it in the OpenAPI route
 * handler chain — after authentication and authorization handlers, before the operation handler
 * ({@code ResourceMethodInvoker}).
 *
 * <p>Priority {@value #PRIORITY} places identity resolution before authorization contributors
 * (priority 100+). The {@link dev.vertique.security.SecurityContext} must be bound before
 * authorization handlers can evaluate claims from {@code SecurityContext.authorization()}.
 *
 * @see IdentityResolutionMiddleware
 */
@Slf4j
@Singleton
public final class IdentityResolutionContributor implements OperationHandlerContributor {

    /** Handler priority. Before authorization (100+) so SecurityContext is bound when claims are checked. */
    public static final int PRIORITY = 80;

    private final IdentityResolutionMiddleware middleware;

    /**
     * Creates a new {@code IdentityResolutionContributor}.
     *
     * @param middleware the identity resolution middleware to add to each route; must not be
     *                   {@code null}
     */
    @Inject
    public IdentityResolutionContributor(IdentityResolutionMiddleware middleware) {
        this.middleware = Objects.requireNonNull(middleware, "middleware");
    }

    /**
     * Returns the priority for this contributor.
     *
     * @return {@value #PRIORITY}
     */
    @Override
    public int priority() {
        return PRIORITY;
    }

    /**
     * Adds the {@link IdentityResolutionMiddleware} to the given operation route.
     *
     * @param context the operation registration context providing access to the route; must not be
     *                {@code null}
     */
    @Override
    public void contribute(OperationRegistrationContext context) {
        log.debug("operationId={}: Adding IdentityResolution handler", context.operationId());
        context.route().addHandler(middleware);
    }
}

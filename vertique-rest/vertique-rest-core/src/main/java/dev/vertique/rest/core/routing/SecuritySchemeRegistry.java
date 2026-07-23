// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import io.vertx.ext.web.handler.AuthenticationHandler;

/**
 * Scheme-scoped registration surface for security scheme handlers.
 *
 * <p>This is the neutral replacement for the {@code builder.security(name).httpHandler(...)} portion
 * of the previous {@code RouterBuilder} surface. A scheme handler registers its
 * {@link AuthenticationHandler} here; the framework applies it per the operation's
 * {@link RestOperationDescriptor#securityRequirementSets()}.
 *
 * <p>The handler type is an {@link AuthenticationHandler} (not a bare {@code Handler<RoutingContext>})
 * so the framework can compose alternative single-scheme requirements into a Vert.x
 * {@code ChainAuthHandler.any()} — an OR over schemes, matching the OpenAPI {@code security} array
 * semantics (see {@link RestOperationDescriptor#securityRequirementSets()}).
 */
public interface SecuritySchemeRegistry {

    /**
     * Registers the {@link AuthenticationHandler} for the scheme this registry is scoped to. The
     * framework applies it to operations whose security requirements reference the scheme.
     *
     * @param handler the authentication handler to apply
     */
    void authenticationHandler(AuthenticationHandler handler);
}

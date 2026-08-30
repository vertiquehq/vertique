// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;

/**
 * Configures an authentication handler for a named security scheme via the transport-neutral
 * {@link SecuritySchemeRegistry}. Contributed via Dagger {@code Set<SecuritySchemeHandler>}
 * multibinding.
 *
 * <p>The framework calls {@link #configure(SecuritySchemeRegistry)} during router creation,
 * after {@link dev.vertique.rest.core.lifecycle.RouterLifecycleHook#beforeAuthSetup} hooks and before
 * {@link dev.vertique.rest.core.lifecycle.RouterLifecycleHook#afterAuthSetup} hooks. The registry is
 * scoped to this handler's {@link #schemeName()}, so the handler only supplies its
 * {@link io.vertx.ext.web.handler.AuthenticationHandler}; the framework applies it per each
 * operation's security requirements.
 *
 * <p>Example:
 * <pre>{@code
 * @Provides @IntoSet
 * SecuritySchemeHandler jwtScheme(JWTAuth jwtAuth) {
 *     return new SecuritySchemeHandler() {
 *         public String schemeName() { return "bearerAuth"; }
 *         public void configure(SecuritySchemeRegistry registry) {
 *             registry.authenticationHandler(JWTAuthHandler.create(jwtAuth));
 *         }
 *     };
 * }
 * }</pre>
 */
public interface SecuritySchemeHandler {

    /**
     * The security scheme name this handler provides (e.g., "bearerAuth", "apiKeyAuth").
     *
     * @return the security scheme name matching the OpenAPI spec
     */
    String schemeName();

    /**
     * Configures the authentication handler on the scheme-scoped {@link SecuritySchemeRegistry}.
     * Called once during JaxRsRouterMount startup.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param registry the scheme-scoped registry on which to register the authentication handler
     */
    void configure(SecuritySchemeRegistry registry);
}

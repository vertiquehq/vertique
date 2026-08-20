// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * Provides a route-level authentication handler independent of OpenAPI {@code RouterBuilder}.
 *
 * <p>This SPI decouples authentication handler creation from the OpenAPI security scheme
 * registration path ({@link SecuritySchemeHandler}). Both JAX-RS and non-JAX-RS transports
 * (e.g. WebSocket) can consume {@code RouteAuthHandler} instances to install authentication
 * on plain Vert.x routes.
 *
 * <p>Each authentication module (JWT, API key, etc.) provides a {@code RouteAuthHandler}
 * alongside its existing {@link SecuritySchemeHandler} via Dagger {@code @IntoSet} multibinding.
 * The {@link #schemeName()} serves as a selector key when multiple handlers are registered.
 *
 * <p>Register via Dagger multibinding:
 * <pre>{@code
 * @Provides @IntoSet
 * static RouteAuthHandler jwtRouteAuth(JWTAuth jwtAuth) {
 *     return new RouteAuthHandler() {
 *         public String schemeName() { return "bearerAuth"; }
 *         public Handler<RoutingContext> createHandler() {
 *             return JWTAuthHandler.create(jwtAuth);
 *         }
 *     };
 * }
 * }</pre>
 */
public interface RouteAuthHandler {

    /**
     * Scheme name matching the OpenAPI security scheme or logical auth identifier.
     * Used for selection when multiple auth handlers are registered.
     *
     * @return the scheme name; never {@code null}
     */
    String schemeName();

    /**
     * Creates a Vert.x handler that authenticates the request and populates
     * {@code ctx.user()} on success. The handler must call {@code ctx.next()} on success
     * or {@code ctx.fail(...)} on authentication failure.
     *
     * @return a new handler instance; never {@code null}
     */
    Handler<RoutingContext> createHandler();

    /**
     * Creates a handler that authenticates only when credentials for this scheme are present.
     *
     * <p>The default explicitly advertises that an implementation supports required authentication
     * only. This preserves the required-authentication semantics of {@link #createHandler()} for
     * existing REST and WebSocket routes: transports that need optional authentication must select
     * only handlers that opt in by returning a non-empty value.
     *
     * <p>An opt-in handler must call {@link RoutingContext#next()} once without mutating the user
     * or authentication evidence when credentials are absent. Present credentials must either
     * authenticate through the same verification path as {@link #createHandler()} or fail the
     * routing context; they must never be downgraded to anonymous.
     *
     * @return an optional-authentication handler when supported; never {@code null}
     */
    default Optional<Handler<RoutingContext>> createOptionalHandler() {
        return Optional.empty();
    }
}

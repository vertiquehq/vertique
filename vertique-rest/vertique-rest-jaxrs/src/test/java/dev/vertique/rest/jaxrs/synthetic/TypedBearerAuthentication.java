// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixture's bearer authentication: a test stand-in for a verified bearer scheme that feeds the
 * real identity and authorization middleware.
 *
 * <p>A request authenticates as the subject named after {@code Bearer } in its {@code Authorization}
 * header; a missing, non-bearer, or blank credential fails with 401. The caller's roles come from the
 * {@value #ROLES_HEADER} header (comma separated) and its scopes from the {@value #SCOPES_HEADER}
 * header (space separated), and reach the middleware as the {@code roles} and {@code scope} claims
 * of the authenticated user, exactly where the framework's claim mapper reads them. Verified
 * evidence is appended once per request, however many authentication handlers a route carries.
 */
final class TypedBearerAuthentication {

    /** The security scheme every protected fixture operation names. */
    static final String SCHEME = "bearerAuth";

    /** Header carrying the caller's comma-separated roles. */
    static final String ROLES_HEADER = "X-Test-Roles";

    /** Header carrying the caller's space-separated scopes. */
    static final String SCOPES_HEADER = "X-Test-Scopes";

    private static final String BEARER_PREFIX = "Bearer ";

    private TypedBearerAuthentication() {}

    /**
     * Returns the scheme handler that guards every route naming {@code schemeName}.
     *
     * @param schemeName the scheme the handler configures
     * @return the scheme handler
     */
    static SecuritySchemeHandler schemeHandler(String schemeName) {
        return new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return schemeName;
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {
                registry.authenticationHandler(TypedBearerAuthentication::authenticate);
            }
        };
    }

    /**
     * Returns the route authentication handler an action-only route is authenticated with.
     *
     * @param schemeName the scheme the handler reports
     * @return the route authentication handler
     */
    static RouteAuthHandler routeHandler(String schemeName) {
        return new RouteAuthHandler() {
            @Override
            public String schemeName() {
                return schemeName;
            }

            @Override
            public Handler<RoutingContext> createHandler() {
                return TypedBearerAuthentication::authenticate;
            }
        };
    }

    private static void authenticate(RoutingContext ctx) {
        String credential = ctx.request().getHeader("Authorization");
        if (credential == null
                || !credential.startsWith(BEARER_PREFIX)
                || credential.substring(BEARER_PREFIX.length()).isBlank()) {
            ctx.fail(401);
            return;
        }
        String subject = credential.substring(BEARER_PREFIX.length()).trim();
        JsonObject principal = new JsonObject()
                .put("sub", subject)
                .put("roles", new JsonArray(values(ctx.request().getHeader(ROLES_HEADER), ",")))
                .put("scope", String.join(" ", values(ctx.request().getHeader(SCOPES_HEADER), " ")));
        ((UserContextInternal) ctx.userContext()).setUser(User.create(principal));
        if (RestAuthenticationEvidence.get(ctx).isEmpty()) {
            RestAuthenticationEvidence.append(
                    ctx,
                    new AuthenticationEvidence(
                            DefaultAuthMethod.jwt(),
                            Optional.of(subject),
                            Instant.now(),
                            Optional.empty(),
                            new CustomVerificationSource("test", Map.of()),
                            Map.of("sub", subject)));
        }
        ctx.next();
    }

    private static List<String> values(String header, String separator) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(separator))
                .filter(value -> !value.isBlank())
                .toList();
    }
}

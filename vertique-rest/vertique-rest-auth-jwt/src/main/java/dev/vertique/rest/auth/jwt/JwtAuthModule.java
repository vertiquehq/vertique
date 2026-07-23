// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.CredentialRejectionReporter;
import dev.vertique.rest.security.SecurityModule;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Dagger module that wires JWT bearer token authentication into the REST framework.
 *
 * <p>Include this module in your application's Dagger {@code @Component} instead of
 * {@link AuthModule} and {@link SecurityModule} directly — it includes both automatically.
 * The application must provide a {@link JWTAuth} binding (typically in the app's own module).
 *
 * <p>Provided bindings:
 * <ul>
 *   <li>{@link JwtBearerSecuritySchemeHandler} — registers the JWT auth handler for the
 *       named OpenAPI security scheme. Scheme name and validation constraints are read from the
 *       effective {@link JwtAuthConfig} (the {@code "jwt"} config section, defaulting to scheme
 *       {@code "bearerAuth"}; override by providing your own {@code @Provides JwtAuthConfig} in your
 *       app module)</li>
 *   <li>{@link JwtClaimAuthorizationProvider} — extracts roles and scopes from JWT claims
 *       ({@code roles}, {@code scope}, {@code scp}, {@code permissions})</li>
 * </ul>
 *
 * <p>JWT configuration is parsed from the {@code "jwt"} section of the application config into a
 * typed {@link JwtAuthConfig} (scheme name + {@link JwtValidationConfig}). An application may bind
 * its own {@code @Provides JwtAuthConfig} to override the parsed config entirely; the
 * {@link JwtEffective}-qualified resolver prefers the app binding when present and otherwise falls
 * back to the config-parsed default.
 *
 * <p>To add custom JWT claim validation beyond standard signature/expiry checks, provide a
 * {@link JwtClaimsValidator} in the application module. The validator is invoked after the
 * standard authentication succeeds:
 * <pre>{@code
 * @Provides
 * JwtClaimsValidator tenantValidator() {
 *     return claims -> {
 *         if (!claims.containsKey("tenant_id")) {
 *             throw new SecurityException("Missing tenant_id claim");
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>Example application component:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestModule.class, JwtAuthModule.class, AppModule.class})
 * interface AppComponent { ... }
 * }</pre>
 */
@Module(includes = {AuthModule.class, SecurityModule.class})
public abstract class JwtAuthModule {

    /**
     * Optional binding for an application-supplied {@link JwtAuthConfig}.
     * Applications may provide {@code @Provides JwtAuthConfig} to override the config-parsed default
     * entirely. When absent, {@link #effectiveJwtAuthConfig} parses the {@code "jwt"} section.
     *
     * @return the optional app-supplied JWT auth config binding
     */
    @BindsOptionalOf
    abstract JwtAuthConfig appJwtAuthConfig();

    /**
     * Resolves the effective {@link JwtAuthConfig}: the application-supplied override when present,
     * otherwise the config-default parsed from the {@code "jwt"} section of the application config.
     *
     * <p>This single resolver collapses what were previously two independent optional override
     * seams (scheme name and {@link JwtValidationConfig}) into one typed-config override channel.
     *
     * @param appOverride the optional application-supplied JWT auth config
     * @param config      the full application configuration injected via {@code @VertxConfig}
     * @param parser      the injected config parser
     * @return the effective JWT auth config; never {@code null}
     */
    @Provides
    @Singleton
    @JwtEffective
    static JwtAuthConfig effectiveJwtAuthConfig(
            Optional<JwtAuthConfig> appOverride, @VertxConfig JsonObject config, ConfigParser parser) {
        return appOverride.orElseGet(
                () -> parser.parse(JsonConfigPaths.navigateObject(config, "jwt"), JwtAuthConfig.class));
    }

    /**
     * Optional binding for a custom {@link JwtClaimsValidator}.
     * Applications may provide an implementation to enforce additional claim constraints
     * beyond what the standard JWT signature and expiry checks provide.
     *
     * @return the optional claims validator binding
     */
    @BindsOptionalOf
    abstract JwtClaimsValidator optionalJwtClaimsValidator();

    /**
     * Provides the {@link JwtBearerSecuritySchemeHandler} contributed to the
     * {@link SecuritySchemeHandler} multibinding.
     *
     * @param jwtAuth           the JWT authentication provider
     * @param effective         the effective JWT auth config (scheme name + validation constraints)
     * @param rejectionReporter the credential rejection reporter
     * @return the configured security scheme handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler jwtBearerSchemeHandler(
            JWTAuth jwtAuth, @JwtEffective JwtAuthConfig effective, CredentialRejectionReporter rejectionReporter) {
        return new JwtBearerSecuritySchemeHandler(
                effective.schemeName(), jwtAuth, effective.validation(), rejectionReporter);
    }

    /**
     * Provides the {@link JwtClaimAuthorizationProvider} contributed to the
     * {@link AuthorizationProvider} multibinding.
     *
     * @return the JWT claim authorization provider
     */
    @Provides
    @IntoSet
    static AuthorizationProvider jwtClaimAuthorizationProvider() {
        return new JwtClaimAuthorizationProvider();
    }

    /**
     * Provides a route-level JWT authentication handler contributed to the
     * {@link RouteAuthHandler} multibinding. Used by non-OpenAPI transports (e.g. WebSocket) to
     * authenticate requests.
     *
     * <p>The handler delegates to {@link JwtBearerSecuritySchemeHandler#handle(RoutingContext)} —
     * NOT a raw {@code JWTAuthHandler} — so that successful WebSocket JWT authentication appends
     * {@link dev.vertique.security.AuthenticationEvidence} (allowing the downstream
     * identity-resolution middleware to resolve a non-anonymous identity) and rejections are
     * reported through {@link CredentialRejectionReporter}.
     *
     * @param jwtAuth           the JWT authentication provider
     * @param effective         the effective JWT auth config (scheme name + validation constraints)
     * @param rejectionReporter the credential rejection reporter
     * @return the configured route auth handler
     */
    @Provides
    @IntoSet
    static RouteAuthHandler jwtRouteAuthHandler(
            JWTAuth jwtAuth, @JwtEffective JwtAuthConfig effective, CredentialRejectionReporter rejectionReporter) {
        String name = effective.schemeName();
        JwtBearerSecuritySchemeHandler schemeHandler =
                new JwtBearerSecuritySchemeHandler(name, jwtAuth, effective.validation(), rejectionReporter);
        return new RouteAuthHandler() {
            @Override
            public String schemeName() {
                return name;
            }

            @Override
            public Handler<RoutingContext> createHandler() {
                return schemeHandler;
            }
        };
    }

    /**
     * Conditionally contributes a {@link JwtClaimsValidatorContributor} to the
     * {@link OperationHandlerContributor} multibinding when an application-provided
     * {@link JwtClaimsValidator} is present in the Dagger graph.
     *
     * <p>When no validator is configured, a no-op contributor is contributed instead so
     * that the multibinding always has this entry without affecting route handling.
     *
     * <p>The {@link CredentialRejectionReporter} and {@link JwtValidationConfig} (from the
     * effective config) are passed to the contributor so that claims-validation failures emit
     * a {@link dev.vertique.security.events.CredentialRejectedEvent} with reason code
     * {@code JWT_CLAIMS_INVALID} — consistent with the token-level rejection path in
     * {@link JwtBearerSecuritySchemeHandler}.
     *
     * @param claimsValidator   the optional custom claims validator
     * @param effective         the effective JWT auth config (provides the validation config)
     * @param rejectionReporter the credential rejection reporter
     * @return a contributor that runs the validator, or a no-op if none is configured
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor jwtClaimsValidatorContributor(
            Optional<JwtClaimsValidator> claimsValidator,
            @JwtEffective JwtAuthConfig effective,
            CredentialRejectionReporter rejectionReporter) {
        return claimsValidator
                .<OperationHandlerContributor>map(
                        v -> new JwtClaimsValidatorContributor(v, rejectionReporter, effective.validation()))
                .orElseGet(JwtAuthModule::noOpContributor);
    }

    /**
     * Returns a no-op {@link OperationHandlerContributor} used as a placeholder when no
     * {@link JwtClaimsValidator} is configured.
     *
     * @return a contributor that does nothing and runs at {@link Integer#MAX_VALUE} priority
     */
    private static OperationHandlerContributor noOpContributor() {
        return new OperationHandlerContributor() {
            @Override
            public int priority() {
                return Integer.MAX_VALUE;
            }

            @Override
            public void contribute(OperationRegistrationContext context) {
                // No-op: no JwtClaimsValidator is configured
            }
        };
    }
}

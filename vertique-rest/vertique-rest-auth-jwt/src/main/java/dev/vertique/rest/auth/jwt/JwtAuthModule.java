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
import dev.vertique.core.exception.ConfigurationException;
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
     * <p>Built through {@link #verifiedSchemeHandler}, which runs {@link #verifyAppliedValidation}
     * first, so a {@link JWTAuth} built with a clock skew other than the configured one fails
     * startup instead of silently ignoring the setting.
     *
     * @param jwtAuth           the JWT authentication provider
     * @param effective         the effective JWT auth config (scheme name + validation constraints)
     * @param rejectionReporter the credential rejection reporter
     * @return the configured security scheme handler
     * @throws dev.vertique.core.exception.ConfigurationException if the provider attests a clock
     *         skew that differs from {@code jwt.validation.clockSkewSeconds}
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler jwtBearerSchemeHandler(
            JWTAuth jwtAuth, @JwtEffective JwtAuthConfig effective, CredentialRejectionReporter rejectionReporter) {
        return verifiedSchemeHandler(jwtAuth, effective, rejectionReporter);
    }

    /**
     * Runs the startup provenance check and builds the scheme handler both JWT bindings hand out.
     *
     * <p>Every binding that exposes the {@link JWTAuth} to traffic goes through here, so the
     * fail-fast cannot be skipped by resolving only one of them: an application that authenticates
     * solely over a non-OpenAPI transport (e.g. WebSocket) never resolves the
     * {@link SecuritySchemeHandler} multibinding, and one that serves only OpenAPI routes never
     * resolves the {@link RouteAuthHandler} one. The check is idempotent, so an application
     * resolving both simply runs it twice.
     *
     * @param jwtAuth           the JWT authentication provider bound by the application
     * @param effective         the effective JWT auth config (scheme name + validation constraints)
     * @param rejectionReporter the credential rejection reporter
     * @return the configured scheme handler
     * @throws ConfigurationException if the provider attests a clock skew that differs from
     *         {@code jwt.validation.clockSkewSeconds}
     */
    private static JwtBearerSecuritySchemeHandler verifiedSchemeHandler(
            JWTAuth jwtAuth, JwtAuthConfig effective, CredentialRejectionReporter rejectionReporter) {
        verifyAppliedValidation(jwtAuth, effective);
        return new JwtBearerSecuritySchemeHandler(
                effective.schemeName(), jwtAuth, effective.validation(), rejectionReporter);
    }

    /**
     * Fails startup when a framework-built {@link JWTAuth} applied a different clock skew than the
     * effective configuration asks for.
     *
     * <p>{@link JwtValidationConfig#clockSkewSeconds()} is <em>permissive</em>: once the
     * {@link JWTAuth} has rejected a token as expired, no downstream handler can un-reject it. Unlike
     * issuer and audience — which {@link JwtBearerSecuritySchemeHandler} re-enforces
     * post-authentication — leeway has no backstop, so it must be right at construction. Clock skew
     * is therefore the only field compared here.
     *
     * <p>A {@link JWTAuth} that carries no attestation was not built by {@link JwtAuthFactory}; the
     * framework has nothing to compare and returns silently rather than second-guessing an
     * application's own provider.
     *
     * <p>The comparison is deliberately <em>field-wise</em>. {@link JwtValidationConfig} declares no
     * {@code equals}, so it inherits identity semantics, and the two instances compared here are
     * always distinct objects on the all-defaults path — the factory builds one default and
     * {@link JwtAuthConfig}'s {@code @JsonCreator} independently builds another. An
     * {@code equals}-based check would therefore fail startup on essentially every default
     * deployment.
     *
     * @param jwtAuth   the JWT authentication provider bound by the application
     * @param effective the effective JWT auth config
     * @throws dev.vertique.core.exception.ConfigurationException if the provider attests a clock
     *         skew that differs from {@code jwt.validation.clockSkewSeconds}
     */
    static void verifyAppliedValidation(JWTAuth jwtAuth, JwtAuthConfig effective) {
        if (!(jwtAuth instanceof ValidationAttested attested)) {
            return;
        }
        int applied = attested.appliedValidation().clockSkewSeconds();
        int configured = effective.validation().clockSkewSeconds();
        if (applied != configured) {
            throw new ConfigurationException("The bound JWTAuth was built with a clock skew of " + applied
                    + " seconds, but jwt.validation.clockSkewSeconds is " + configured
                    + ". Clock skew is applied at JWTAuth construction and cannot be enforced later, so the "
                    + "configured value would be silently ignored. Pass the effective JwtValidationConfig "
                    + "(inject @JwtEffective JwtAuthConfig and use its validation()) into the JwtAuthFactory call "
                    + "that builds the JWTAuth, or align jwt.validation.clockSkewSeconds with it.");
        }
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
     * <p>Built through {@link #verifiedSchemeHandler}, so this binding runs the startup provenance
     * check too — an application authenticating solely over a non-OpenAPI transport never resolves
     * {@link #jwtBearerSchemeHandler} and would otherwise skip the fail-fast entirely.
     *
     * @param jwtAuth           the JWT authentication provider
     * @param effective         the effective JWT auth config (scheme name + validation constraints)
     * @param rejectionReporter the credential rejection reporter
     * @return the configured route auth handler
     * @throws dev.vertique.core.exception.ConfigurationException if the provider attests a clock
     *         skew that differs from {@code jwt.validation.clockSkewSeconds}
     */
    @Provides
    @IntoSet
    static RouteAuthHandler jwtRouteAuthHandler(
            JWTAuth jwtAuth, @JwtEffective JwtAuthConfig effective, CredentialRejectionReporter rejectionReporter) {
        JwtBearerSecuritySchemeHandler schemeHandler = verifiedSchemeHandler(jwtAuth, effective, rejectionReporter);
        String name = effective.schemeName();
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

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.security.dispatch.SecurityContextServiceDispatchDecoder;
import dev.vertique.rest.security.dispatch.SecurityContextServiceDispatchEncoder;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.channel.ChannelIdentityManager;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module providing authentication and authorization bindings.
 *
 * <p>Include this module in your application's Dagger component to enable:
 * <ul>
 *   <li>{@link SecuritySchemeHandler} multibinding for OpenAPI security scheme configuration</li>
 *   <li>{@link AuthorizationProvider} multibinding, retained for compatibility / future adapters;
 *       in v1 the default {@link VertxProviderDecisionPoint} evaluates decisions from the resolved
 *       {@link dev.vertique.security.authz.AuthorizationClaims} and does not consult this set
 *       (GitHub issue #73)</li>
 *   <li>{@link IdentityResolutionMiddleware} — resolves {@link dev.vertique.security.SecurityIdentity}
 *       from accumulated {@link dev.vertique.security.AuthenticationEvidence} and binds the new
 *       {@link dev.vertique.security.SecurityContext} (added to each OpenAPI route via
 *       {@link IdentityResolutionContributor})</li>
 *   <li>{@link AuthorizationContributor} — adds authorization handlers to routes</li>
 *   <li>{@link IdentityResolutionContributor} — adds identity resolution handler to routes</li>
 *   <li>{@link DefaultSecurityPolicyValidator} — validates security policy consistency</li>
 * </ul>
 *
 * <p>The {@link JaxRsSecurityContext} factory binding lives in {@link SecurityModule} so that an
 * application that wires {@code SecurityModule} alone has a complete Dagger graph for the
 * runtime binding without also pulling in {@code AuthModule}.
 *
 * <p>Applications contribute authentication by adding {@link SecuritySchemeHandler} bindings.
 * Authorization in v1 is driven by the resolved {@link dev.vertique.security.authz.AuthorizationClaims};
 * custom {@link AuthorizationProvider} bindings are accepted but not consulted by the default
 * decision point (GitHub issue #73). Applications needing custom authorization should bind an
 * {@link AuthorizationPolicy} or {@link AuthorizationDecisionPoint} instead.
 *
 * <p>To override JWT claim extraction, provide a custom {@link SecurityClaimMapper} binding in
 * the application's Dagger module. The optional binding declared here allows any single
 * application-provided implementation to replace {@link DefaultSecurityClaimMapper}:
 * <pre>{@code
 * @Provides
 * SecurityClaimMapper myClaimMapper() {
 *     return claims -> { ... };
 * }
 * }</pre>
 */
@Module(includes = SecurityEventsModule.class)
public abstract class AuthModule {

    /**
     * Multibinding set for {@link AuthorizationProvider}.
     * Applications contribute providers via {@code @Provides @IntoSet}.
     */
    @Multibinds
    abstract Set<AuthorizationProvider> authorizationProviders();

    /**
     * Multibinding set for {@link RouteAuthHandler}.
     * Authentication modules contribute handlers via {@code @Provides @IntoSet} to enable
     * route-level authentication on non-OpenAPI transports (e.g. WebSocket).
     */
    @Multibinds
    abstract Set<RouteAuthHandler> routeAuthHandlers();

    /**
     * Multibinding set for {@link SecurityIdentityResolver}.
     *
     * <p>The framework contributes {@link DefaultSecurityIdentityResolver} (priority 100) via
     * {@link #defaultSecurityIdentityResolver}. Applications contribute custom resolvers via
     * {@code @Provides @IntoSet} with a priority {@code < 100} to run before the default.
     */
    @Multibinds
    abstract Set<SecurityIdentityResolver> securityIdentityResolvers();

    /**
     * Contributes the framework's default {@link SecurityIdentityResolver} into the multibinding
     * set.
     *
     * <p>The default resolver runs at priority {@code 100} (last among framework defaults). It
     * maps empty evidence to {@link dev.vertique.security.SecurityIdentity#anonymous()} and
     * non-empty evidence to {@code USER}, {@code SERVICE}, or {@code ANONYMOUS} based on
     * {@link dev.vertique.security.AuthMethodKind} and safe-attribute hints.
     *
     * @param impl the default resolver instance (injected by Dagger)
     * @return the resolver contributed to the set
     */
    @Provides
    @IntoSet
    static SecurityIdentityResolver defaultSecurityIdentityResolver(DefaultSecurityIdentityResolver impl) {
        return impl;
    }

    /**
     * Optional binding for a custom {@link SecurityClaimMapper}.
     * Applications may provide their own implementation to override the default claim extraction.
     *
     * @return the optional custom claim mapper binding
     */
    @BindsOptionalOf
    abstract SecurityClaimMapper optionalSecurityClaimMapper();

    /**
     * Optional binding for a custom synchronous {@link AuthorizationPolicy} (core SPI).
     *
     * <p>When an application provides this binding, {@link SecurityPolicyEnforcer} wraps it in a
     * {@link SyncPolicyDecisionPoint} so the sync policy participates in the async pipeline. Per
     * ADR-0114 the enforcement layer ({@code SecurityPolicyEnforcer}) — not the decision point —
     * emits exactly one {@link dev.vertique.security.events.AuthorizationDecisionEvent} per
     * decision; the wrapped {@link AuthorizationPolicy} (and the {@link SyncPolicyDecisionPoint}
     * around it) does not emit. Takes precedence over the default {@link VertxProviderDecisionPoint},
     * but is overridden by a fully async {@link AuthorizationDecisionPoint} override.
     *
     * @return the optional sync authorization policy binding
     */
    @BindsOptionalOf
    abstract AuthorizationPolicy optionalAuthorizationPolicy();

    /**
     * Optional binding for a custom async {@link AuthorizationDecisionPoint}.
     *
     * <p>When an application provides this binding it takes precedence over both the optional
     * {@link AuthorizationPolicy} and the default {@link VertxProviderDecisionPoint}. Use this
     * for policies requiring async I/O (remote PDP calls, database-backed policies, etc.).
     *
     * @return the optional async authorization decision point binding
     */
    @BindsOptionalOf
    abstract AuthorizationDecisionPoint optionalAuthorizationDecisionPoint();

    /**
     * Optional binding for the core action {@link dev.vertique.security.authz.Authorizer} that
     * evaluates the {@code @RequiresAction} gate.
     *
     * <p>The binding is satisfied when the application installs the authorization engine
     * ({@code SecurityAuthzModule}, which provides the default
     * {@link dev.vertique.security.authz.Authorizer}); it is empty otherwise. The
     * {@link SecurityPolicyEnforcer} composes the action gate only when an operation carries a present
     * {@code @RequiresAction}, and a present {@code @RequiresAction} can only pass startup validation
     * when the engine is installed (slice 11). Mirrors the optional-presence idiom used for the
     * {@code ActionRegistry} binding in {@code RestModule}.
     *
     * @return the optional core {@code Authorizer} binding
     */
    @BindsOptionalOf
    abstract dev.vertique.security.authz.Authorizer optionalAuthorizer();

    /**
     * Registers the authorization handler contributor.
     *
     * @param contributor the authorization contributor (injected by Dagger)
     * @return the contributor as an {@link OperationHandlerContributor}
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor authorizationContributor(AuthorizationContributor contributor) {
        return contributor;
    }

    /**
     * Registers the identity resolution handler contributor.
     *
     * <p>Mounts {@link IdentityResolutionMiddleware} on every OpenAPI route at priority
     * {@link IdentityResolutionContributor#PRIORITY} — before authorization contributors so the
     * {@link dev.vertique.security.SecurityContext} is bound when claims are evaluated.
     *
     * @param contributor the identity resolution contributor (injected by Dagger)
     * @return the contributor as an {@link OperationHandlerContributor}
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor identityResolutionContributor(IdentityResolutionContributor contributor) {
        return contributor;
    }

    /**
     * Registers the action-gate authentication contributor.
     *
     * <p>Installs a {@link RouteAuthHandler} on an action-only route ({@code SecurityPolicy.None}
     * carrying {@code @RequiresAction}) at priority
     * {@link ActionGateAuthenticationContributor#PRIORITY} — before the JWT claims validator (50),
     * identity resolution (80), and authorization (100) — so the caller is authenticated and
     * {@link dev.vertique.security.AuthenticationEvidence} is appended before any of them run.
     * Without it, an action-only route would get no OpenAPI security handler and the gate would see
     * an anonymous identity (silent bypass).
     *
     * @param contributor the action-gate authentication contributor (injected by Dagger)
     * @return the contributor as an {@link OperationHandlerContributor}
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor actionGateAuthenticationContributor(
            ActionGateAuthenticationContributor contributor) {
        return contributor;
    }

    /**
     * Provides the default security policy validator.
     *
     * @param validator the default security policy validator (injected by Dagger)
     * @return the validator as a {@link SecurityPolicyValidator}
     */
    @Provides
    static SecurityPolicyValidator securityPolicyValidator(DefaultSecurityPolicyValidator validator) {
        return validator;
    }

    /**
     * Signals that the auth enforcement runtime is installed. Used by the route registrar to
     * validate that restrictive security annotations have proper runtime support.
     *
     * <p>Returns the framework-owned {@link AuthEnforcementCapability#INSTANCE}; the type is
     * non-instantiable from application code so only this binding can supply the install signal.
     *
     * @return the singleton {@link AuthEnforcementCapability} marker instance
     */
    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    /**
     * Provides the built-in {@link dev.vertique.security.SecurityContext} service-dispatch
     * encoder into the encoder multibinding set.
     *
     * <p>The encoder performs an identity pass-through so the ambient holder-bound
     * {@link dev.vertique.security.SecurityContext} is automatically captured into the
     * outgoing dispatch-context map on every outbound service call. Lives in the security module
     * (not the substrate) so {@code vertique-context} does not import security types.
     *
     * @return the encoder instance
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextEncoder<?> securityContextEncoder() {
        return new SecurityContextServiceDispatchEncoder();
    }

    /**
     * Provides the built-in {@link dev.vertique.security.SecurityContext} service-dispatch
     * decoder into the decoder multibinding set.
     *
     * <p>The decoder uses {@code isInstance} filtering so concrete subtypes of
     * {@link dev.vertique.security.SecurityContext} (e.g., {@code AuthenticatedSecurityContext})
     * are accepted and reinstated as-is on the receive side.
     *
     * @return the decoder instance
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextDecoder<?> securityContextDecoder() {
        return new SecurityContextServiceDispatchDecoder();
    }

    /**
     * Provides the default {@link RequestOriginConfig}.
     *
     * <p>Defaults to trusting no proxy (empty CIDR set), capping the forwarded chain at
     * {@value RequestOriginConfig#DEFAULT_FORWARDED_FOR_CAP} entries, and not trusting forwarded
     * scheme or host headers. Production deployments behind a load balancer should override this
     * binding with their proxy's outbound CIDR range.
     *
     * @return the default request origin configuration
     */
    @Provides
    @Singleton
    static RequestOriginConfig requestOriginConfig() {
        return RequestOriginConfig.defaults();
    }

    /**
     * Provides the default {@link CredentialRejectionReporter} implementation.
     *
     * <p>Narrows the Dagger binding from the concrete {@link DefaultCredentialRejectionReporter}
     * to the SPI interface, allowing applications to override by providing their own
     * {@code @Provides CredentialRejectionReporter} binding. The default implementation
     * assembles a {@link dev.vertique.security.events.CredentialRejectedEvent} and emits it
     * immediately via {@link SecurityEventEmitter}.
     *
     * @param impl the default implementation (injected by Dagger); must not be {@code null}
     * @return the reporter bound as the SPI interface
     */
    @Provides
    @Singleton
    static CredentialRejectionReporter credentialRejectionReporter(DefaultCredentialRejectionReporter impl) {
        return impl;
    }

    /**
     * Binds {@link ChannelIdentityManager} to the {@link DefaultChannelIdentityManager} singleton.
     *
     * <p>The default implementation maintains an in-memory {@link java.util.concurrent.ConcurrentHashMap}
     * registry of {@code (channelId → SecurityContext + ChannelBinding + expiry timer)} entries.
     * It schedules a raw Vert.x timer for each channel whose
     * {@link dev.vertique.security.AuthenticationState#earliestNotAfter()} is non-empty,
     * firing {@link DefaultChannelIdentityManager#closeChannel(String, String)} with reason code
     * {@code "IDENTITY_EXPIRED"} on expiry.
     *
     * @param impl the default implementation (injected by Dagger); must not be {@code null}
     * @return the manager bound as the SPI interface
     */
    @Binds
    @Singleton
    abstract ChannelIdentityManager channelIdentityManager(DefaultChannelIdentityManager impl);

    /**
     * Contributes {@link OriginCaptureMiddleware} to the {@link Middleware} multibinding set.
     *
     * <p>{@link OriginCaptureMiddleware} is ROOT-scoped and runs at
     * {@link OriginCaptureMiddleware#ORDER} ({@link
     * dev.vertique.rest.core.correlation.CorrelationIngressMiddleware#ORDER} + 10), placing it
     * between correlation ingress and any authentication handlers.
     *
     * @param middleware the origin capture middleware (injected by Dagger)
     * @return the middleware contributed to the set
     */
    @Provides
    @IntoSet
    static Middleware originCaptureMiddleware(OriginCaptureMiddleware middleware) {
        return middleware;
    }
}

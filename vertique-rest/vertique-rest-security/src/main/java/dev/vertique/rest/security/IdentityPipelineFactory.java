// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Handler;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single assembly point for the identity and authorization pipeline: it holds every security
 * collaborator once and hands out the {@link IdentityResolutionMiddleware} instances and the one
 * {@link SecurityPolicyEnforcer} that HTTP transports install.
 *
 * <p>A transport does not re-assemble the pipeline from the individual collaborators. It obtains the
 * factory — {@link AuthModule} provides it, optionally bound where the pipeline may be absent — and
 * asks for the pieces it needs: {@link #restIdentityResolution()} for the REST middleware,
 * {@link #identityResolutionHandler(IdentityPipelineOptions)} for a non-REST transport's identity
 * step, {@link #policyEnforcer()} for authorization enforcement, and {@link #securityRuntime()} for
 * the runtime those pieces bind into. Every accessor is memoized, so a graph holding one factory
 * holds exactly one middleware per capture choice and exactly one enforcer.
 *
 * <p>This class has no {@code @Inject} constructor by design: an optional binding of an
 * {@code @Inject}-constructible type is not expressible in Dagger, and the transports that consume
 * this factory must be able to declare it optional. {@link AuthModule} is therefore its only
 * binding, and no other module may declare the collaborator bindings that would let a graph assemble
 * a second pipeline behind its back.
 *
 * <p>The factory adds no behavior: it constructs the same collaborators, in the same order, with the
 * same arguments a hand-wired call site would.
 */
public final class IdentityPipelineFactory {

    private final Set<SecurityIdentityResolver> identityResolvers;
    private final Optional<SecurityClaimMapper> claimMapper;
    private final SecurityEventEmitter emitter;
    private final SecurityRuntime securityRuntime;
    private final ContextHolder contextHolder;
    private final Optional<IdentitySnapshotCapture> identitySnapshotCapture;
    private final Optional<VertxAuthorizationImporter> authorizationImporter;
    private final Optional<AuthorizationDecisionPoint> authorizationDecisionPoint;
    private final Optional<AuthorizationPolicy> authorizationPolicy;
    private final Set<AuthorizationProvider> authorizationProviders;
    private final Optional<Authorizer> authorizer;
    private final Optional<AuthorizationGateConfig> authorizationGateConfig;

    /**
     * The assembled middlewares, keyed by whether the bound {@link IdentitySnapshotCapture} is wired
     * into them, so two transports making the same capture choice share one instance.
     */
    private final Map<Boolean, IdentityResolutionMiddleware> middlewareByCapture = new ConcurrentHashMap<>();

    /** The one enforcer, assembled on first use under {@link #enforcerLock}. */
    private volatile SecurityPolicyEnforcer policyEnforcer;

    private final Object enforcerLock = new Object();

    /**
     * Creates the assembly point over every collaborator the identity pipeline needs. The parameter
     * list is the union of the collaborators {@link IdentityResolutionMiddleware} and
     * {@link SecurityPolicyEnforcer} inject; adding a collaborator to either of them adds it here.
     *
     * <p><b>Do not construct this in application code.</b> Obtain the factory from the Dagger graph
     * ({@code AuthModule} provides it). Memoization is per instance: a second factory yields a second
     * enforcer and a second middleware, which share neither the operator-configured gate deadline nor
     * the application's security-event observers with the graph's pipeline.
     *
     * @param identityResolvers           the identity resolver set; must not be {@code null}
     * @param claimMapper                 the optional custom claim mapper; must not be {@code null}
     *                                    as an {@link Optional}
     * @param emitter                     the security event emitter; must not be {@code null}
     * @param securityRuntime             the security runtime the pipeline binds contexts into; must
     *                                    not be {@code null}
     * @param contextHolder               the context holder the pipeline reads and binds ambient
     *                                    values on; must not be {@code null}
     * @param identitySnapshotCapture     the optional ingress capture seam; present only when
     *                                    identity-snapshot durable carriage is installed; must not be
     *                                    {@code null} as an {@link Optional}
     * @param authorizationImporter       the optional Vert.x authorization importer; must not be
     *                                    {@code null} as an {@link Optional}
     * @param authorizationDecisionPoint  the optional application-provided async decision point; must
     *                                    not be {@code null} as an {@link Optional}
     * @param authorizationPolicy         the optional application-provided sync authorization policy;
     *                                    must not be {@code null} as an {@link Optional}
     * @param authorizationProviders      the Vert.x authorization provider set consulted by the
     *                                    default decision point; must not be {@code null}
     * @param authorizer                  the optional action {@link Authorizer}; empty when the
     *                                    authorization engine is not installed; must not be
     *                                    {@code null} as an {@link Optional}
     * @param authorizationGateConfig     the optional operator-configured gate deadline; must not be
     *                                    {@code null} as an {@link Optional}
     */
    public IdentityPipelineFactory(
            Set<SecurityIdentityResolver> identityResolvers,
            Optional<SecurityClaimMapper> claimMapper,
            SecurityEventEmitter emitter,
            SecurityRuntime securityRuntime,
            ContextHolder contextHolder,
            Optional<IdentitySnapshotCapture> identitySnapshotCapture,
            Optional<VertxAuthorizationImporter> authorizationImporter,
            Optional<AuthorizationDecisionPoint> authorizationDecisionPoint,
            Optional<AuthorizationPolicy> authorizationPolicy,
            Set<AuthorizationProvider> authorizationProviders,
            Optional<Authorizer> authorizer,
            Optional<AuthorizationGateConfig> authorizationGateConfig) {
        this.identityResolvers = Objects.requireNonNull(identityResolvers, "identityResolvers");
        this.claimMapper = Objects.requireNonNull(claimMapper, "claimMapper");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime");
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
        this.identitySnapshotCapture = Objects.requireNonNull(identitySnapshotCapture, "identitySnapshotCapture");
        this.authorizationImporter = Objects.requireNonNull(authorizationImporter, "authorizationImporter");
        this.authorizationDecisionPoint =
                Objects.requireNonNull(authorizationDecisionPoint, "authorizationDecisionPoint");
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        this.authorizationProviders = Objects.requireNonNull(authorizationProviders, "authorizationProviders");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.authorizationGateConfig = Objects.requireNonNull(authorizationGateConfig, "authorizationGateConfig");
    }

    // --- Accessors ---

    /**
     * Returns the security runtime the assembled pipeline binds resolved contexts into — the same
     * instance a transport needs for its own context handling.
     *
     * @return the security runtime; never {@code null}
     */
    public SecurityRuntime securityRuntime() {
        return securityRuntime;
    }

    /**
     * Returns the REST middleware: the pipeline assembled for {@link IdentityPipelineOptions#rest()},
     * which binds {@link IdentityResolutionMiddleware#REST_ORIGIN} from
     * {@link IdentityResolutionMiddleware#handle(RoutingContext)} and wires identity-snapshot capture
     * when the application binds it. Memoized.
     *
     * <p>There is deliberately no accessor that takes {@link IdentityPipelineOptions} and returns a
     * middleware: a middleware's {@code handle(ctx)} always binds the REST origin, so a non-REST
     * transport must go through {@link #identityResolutionHandler(IdentityPipelineOptions)} instead.
     *
     * @return the REST identity-resolution middleware; never {@code null}
     */
    public IdentityResolutionMiddleware restIdentityResolution() {
        return middlewareFor(IdentityPipelineOptions.rest().identitySnapshotCapture());
    }

    /**
     * Returns the identity-resolution handler a transport installs for the supplied options: the
     * middleware assembled for {@code options.identitySnapshotCapture()} — memoized per capture
     * choice, with {@code false} wiring no capture at all even when the application binds one — bound
     * to {@code options.origin()} for the request lifecycle. This is the only accessor that honours an
     * origin.
     *
     * @param options the transport's assembly options; must not be {@code null}
     * @return a handler running the shared identity pipeline under {@code options.origin()}; never
     *         {@code null}
     * @throws IllegalArgumentException if {@code options.origin()} is the REST origin — REST goes
     *         through {@link #restIdentityResolution()}; labelling another transport {@code rest}
     *         would misattribute its authorization decisions and sidestep origin-narrowing policies
     */
    public Handler<RoutingContext> identityResolutionHandler(IdentityPipelineOptions options) {
        Objects.requireNonNull(options, "options");
        if (IdentityResolutionMiddleware.REST_ORIGIN.equals(options.origin())) {
            throw new IllegalArgumentException("identityResolutionHandler() is for non-REST transports; use"
                    + " restIdentityResolution() for the REST origin");
        }
        return middlewareFor(options.identitySnapshotCapture()).handlerFor(options.origin());
    }

    /**
     * Returns the policy enforcer every transport in this graph enforces authorization with,
     * assembled with the operator-configured gate deadline. Memoized.
     *
     * @return the policy enforcer; never {@code null}
     */
    public SecurityPolicyEnforcer policyEnforcer() {
        SecurityPolicyEnforcer existing = policyEnforcer;
        if (existing != null) {
            return existing;
        }
        synchronized (enforcerLock) {
            if (policyEnforcer == null) {
                policyEnforcer = new SecurityPolicyEnforcer(
                        authorizationDecisionPoint,
                        authorizationPolicy,
                        authorizationProviders,
                        emitter,
                        contextHolder,
                        securityRuntime,
                        authorizer,
                        authorizationGateConfig);
            }
            return policyEnforcer;
        }
    }

    // --- Assembly ---

    /**
     * Returns the middleware assembled for one capture choice, constructing it at most once.
     *
     * @param captureIdentitySnapshot whether the bound {@link IdentitySnapshotCapture} is wired into
     *                                the middleware
     * @return the memoized middleware for that choice; never {@code null}
     */
    private IdentityResolutionMiddleware middlewareFor(boolean captureIdentitySnapshot) {
        return middlewareByCapture.computeIfAbsent(
                captureIdentitySnapshot,
                capture -> new IdentityResolutionMiddleware(
                        identityResolvers,
                        claimMapper,
                        emitter,
                        securityRuntime,
                        contextHolder,
                        // A transport that does not capture at ingress is assembled with no capture
                        // seam at all, not with a capture it declines to invoke.
                        capture ? identitySnapshotCapture : Optional.<IdentitySnapshotCapture>empty(),
                        authorizationImporter));
    }
}

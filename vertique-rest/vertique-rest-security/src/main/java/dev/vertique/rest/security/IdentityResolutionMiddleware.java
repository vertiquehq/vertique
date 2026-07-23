// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.middleware.MdcKeys;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Post-authentication handler that resolves a {@link SecurityIdentity} from accumulated
 * {@link AuthenticationEvidence}, builds the new {@link AuthenticatedSecurityContext}, binds it
 * for downstream handlers, and emits security lifecycle events.
 *
 * <p>Registered on every OpenAPI route via {@link IdentityResolutionContributor} at priority
 * {@link IdentityResolutionContributor#PRIORITY} (80 — before authorization at 100, so the
 * {@link dev.vertique.security.SecurityContext} is bound when authorization evaluates
 * claims). Runs after authentication handlers, before authorization contributors and the
 * operation handler.
 *
 * <p><strong>Processing steps:</strong>
 * <ol>
 *   <li>Read accumulated {@link AuthenticationEvidence} entries via
 *       {@link RestAuthenticationEvidence#get(RoutingContext)}.</li>
 *   <li>Build a {@link SecurityIdentityResolutionContext} from the evidence, the pre-auth
 *       {@link RequestOrigin} stashed on the routing context by {@link OriginCaptureMiddleware},
 *       and the ambient {@link CorrelationContext} from the context holder.</li>
 *   <li>Run the priority-ordered {@link SecurityIdentityResolver} chain — the first resolver that
 *       returns a non-empty result wins; anonymous fallback is used if the chain is exhausted.</li>
 *   <li>Build {@link AuthorizationClaims} from {@code ctx.user()} via the injected
 *       {@link SecurityClaimMapper}.</li>
 *   <li>Construct an {@link AuthenticatedSecurityContext} and bind it via
 *       {@link SecurityRuntime#bindCurrent(dev.vertique.security.SecurityContext)}, registering
 *       the returned scope for cleanup with the per-request {@link RequestContextLifecycle}.</li>
 *   <li>Install {@link InvocationOrigin#of(String)} {@code "rest"} as the ambient invocation origin
 *       on {@link ContextHolder} (identity-002 P2.S5b-i), so downstream authorization
 *       ({@code SecurityPolicyEnforcer}) and identity-snapshot capture read a real REST origin
 *       instead of falling back to {@link InvocationOrigin#unspecified()}. Scoped to the request
 *       lifecycle exactly like the {@code SecurityContext} binding above.</li>
 *   <li>Enrich SLF4J MDC with identity keys ({@code userId}, {@code clientId}, {@code authMethod});
 *       absent components are never emitted as empty strings, and {@code authMethod} is only
 *       emitted for non-anonymous requests.</li>
 *   <li>Emit a {@link CredentialAcceptedEvent} when evidence is non-empty (i.e., the request was
 *       authenticated).</li>
 * </ol>
 *
 * <p><strong>Design note:</strong> This class is NOT a
 * {@link dev.vertique.rest.core.middleware.Middleware}. The Middleware abstraction mounts handlers
 * at Router level, which runs in a different layer from OpenAPI route handlers. This handler must
 * run inside the route handler chain (after auth, before operation) so it is added via
 * {@code route.addHandler()} by {@link IdentityResolutionContributor}.
 *
 * <p><strong>Duplicate resolver guard (NFR-ID-003):</strong> Two resolvers with the same
 * {@code (priority, id)} pair are a configuration error. The constructor throws
 * {@link IllegalStateException} at startup, naming both conflicting classes.
 *
 * @see IdentityResolutionContributor
 * @see SecurityIdentityResolver
 * @see SecurityEventEmitter
 */
@Slf4j
@Singleton
public final class IdentityResolutionMiddleware implements Handler<RoutingContext> {

    private final List<SecurityIdentityResolver> orderedResolvers;
    private final SecurityClaimMapper claimMapper;
    private final SecurityEventEmitter emitter;
    private final SecurityRuntime securityRuntime;
    private final ContextHolder contextHolder;
    private final Optional<IdentitySnapshotCapture> identitySnapshotCapture;

    /**
     * Creates a new {@code IdentityResolutionMiddleware} with no identity-snapshot capture wired.
     *
     * <p>Convenience delegate for manual construction sites (e.g. {@code WebSocketMount} and tests)
     * that predate the ingress capture seam; equivalent to passing {@link Optional#empty()} as the
     * capture, i.e. no snapshot is captured at ingress. The Dagger-injected constructor below is the
     * one used in the assembled application graph.
     *
     * @param resolvers       the set of identity resolvers contributed via Dagger multibinding;
     *                        must not be {@code null}
     * @param claimMapper     optional custom claim mapper; uses {@link DefaultSecurityClaimMapper}
     *                        when absent; must not be {@code null}
     * @param emitter         the security event emitter for lifecycle events; must not be
     *                        {@code null}
     * @param securityRuntime the security runtime for binding the resolved context; must not be
     *                        {@code null}
     * @param contextHolder   the context holder for reading the ambient
     *                        {@link CorrelationContext}; must not be {@code null}
     * @throws IllegalStateException if two resolvers share the same {@code (priority, id)} pair
     */
    public IdentityResolutionMiddleware(
            Set<SecurityIdentityResolver> resolvers,
            Optional<SecurityClaimMapper> claimMapper,
            SecurityEventEmitter emitter,
            SecurityRuntime securityRuntime,
            ContextHolder contextHolder) {
        this(resolvers, claimMapper, emitter, securityRuntime, contextHolder, Optional.empty());
    }

    /**
     * Creates a new {@code IdentityResolutionMiddleware}.
     *
     * <p>Sorts the resolver set by {@code (priority, id)} in ascending order. Throws
     * {@link IllegalStateException} at construction if any two resolvers share the same
     * {@code (priority, id)} pair (NFR-ID-003).
     *
     * @param resolvers               the set of identity resolvers contributed via Dagger
     *                                multibinding; must not be {@code null}
     * @param claimMapper             optional custom claim mapper; uses
     *                                {@link DefaultSecurityClaimMapper} when absent; must not be
     *                                {@code null}
     * @param emitter                 the security event emitter for lifecycle events; must not be
     *                                {@code null}
     * @param securityRuntime         the security runtime for binding the resolved context; must
     *                                not be {@code null}
     * @param contextHolder           the context holder for reading the ambient
     *                                {@link CorrelationContext}; must not be {@code null}
     * @param identitySnapshotCapture the optional ingress capture seam; present only when
     *                                identity-snapshot durable carriage is installed, in which case
     *                                an identity snapshot is captured for the request; must not be
     *                                {@code null} as an {@link Optional}
     * @throws IllegalStateException if two resolvers share the same {@code (priority, id)} pair
     */
    @Inject
    public IdentityResolutionMiddleware(
            Set<SecurityIdentityResolver> resolvers,
            Optional<SecurityClaimMapper> claimMapper,
            SecurityEventEmitter emitter,
            SecurityRuntime securityRuntime,
            ContextHolder contextHolder,
            Optional<IdentitySnapshotCapture> identitySnapshotCapture) {
        Objects.requireNonNull(resolvers, "resolvers");
        Objects.requireNonNull(claimMapper, "claimMapper");

        List<SecurityIdentityResolver> sorted = new ArrayList<>(resolvers);
        sorted.sort(OrderedExtension.comparator());

        // Detect duplicate (priority, id) pairs — fail loudly at startup per NFR-ID-003.
        // Keyed by a typed (priority, id) composite, independent of sort order: OrderedExtension
        // sorts phase-first, so two resolvers sharing a (priority, id) but differing in phase would
        // not be adjacent in the sorted list.
        record PriorityId(int priority, String id) {}
        Map<PriorityId, SecurityIdentityResolver> byPriorityAndId = new LinkedHashMap<>();
        for (SecurityIdentityResolver resolver : sorted) {
            PriorityId key = new PriorityId(resolver.priority(), resolver.id());
            SecurityIdentityResolver existing = byPriorityAndId.putIfAbsent(key, resolver);
            if (existing != null) {
                throw new IllegalStateException("Duplicate SecurityIdentityResolver (priority="
                        + resolver.priority()
                        + ", id="
                        + resolver.id()
                        + ") between "
                        + existing.getClass().getName()
                        + " and "
                        + resolver.getClass().getName());
            }
        }

        this.orderedResolvers = List.copyOf(sorted);
        this.claimMapper = claimMapper.orElseGet(DefaultSecurityClaimMapper::new);
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime");
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
        this.identitySnapshotCapture = Objects.requireNonNull(identitySnapshotCapture, "identitySnapshotCapture");
    }

    // --- Handler ---

    /**
     * Processes the request: resolves identity, builds and binds the
     * {@link AuthenticatedSecurityContext}, enriches MDC, and emits lifecycle events.
     *
     * @param ctx the current routing context; must not be {@code null}
     */
    @Override
    public void handle(RoutingContext ctx) {
        // Credential-rejection events are emitted directly by DefaultCredentialRejectionReporter
        // (the failing auth handler calls ctx.fail(...), short-circuiting this middleware).

        // Read accumulated evidence.
        List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(ctx);

        // 3. Build resolution context.
        Optional<RequestOrigin> origin = Optional.ofNullable(ctx.get(RequestOrigin.class.getName()));
        Optional<CorrelationContext> correlation = contextHolder.current(CorrelationContext.class);
        SecurityIdentityResolutionContext resolutionCtx =
                new SecurityIdentityResolutionContext(evidence, origin, correlation, Map.of());

        // 4. Run the resolver chain — first non-empty result wins; exhausted chain → anonymous.
        runResolverChain(resolutionCtx, 0).onComplete(ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }
            SecurityIdentity identity = ar.result();

            // 5. Build authentication state from evidence.
            AuthMethod primaryMethod = evidence.isEmpty()
                    ? DefaultAuthMethod.none()
                    : evidence.get(0).method();
            AuthenticationState authentication =
                    new AuthenticationState(primaryMethod, evidence, Optional.empty(), Optional.empty(), Map.of());

            // 6. Build authorization claims from the Vert.x User principal if present.
            AuthorizationClaims authorization = ctx.user() != null
                    ? claimMapper.map(
                            ctx.user().principal().getMap() != null
                                    ? ctx.user().principal().getMap()
                                    : Map.of())
                    : AuthorizationClaims.empty();

            // 7. Construct the SecurityContext and bind it into the request-scoped context.
            AuthenticatedSecurityContext securityContext =
                    new AuthenticatedSecurityContext(identity, authentication, authorization, origin);
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);
            lifecycle.onClose(securityRuntime.bindCurrent(securityContext));

            // Install the ambient InvocationOrigin for the request lifecycle (identity-002
            // P2.S5b-i) — this middleware is the REST ingress boundary, so every request that
            // reaches identity resolution is unconditionally a "rest" invocation. Downstream
            // authorization (SecurityPolicyEnforcer) and identity-snapshot capture read this back
            // via contextHolder.current(InvocationOrigin.class) instead of falling back to
            // InvocationOrigin.unspecified().
            lifecycle.onClose(contextHolder.bind(InvocationOrigin.class, InvocationOrigin.of("rest")));

            // Capture an identity snapshot for durable carriage when the seam is wired (carriage
            // installed). The snapshot's bind scope unwinds with the request lifecycle, exactly like
            // the live-context binding above; when the Optional is empty the REST hot path is inert.
            identitySnapshotCapture.ifPresent(capture -> lifecycle.onClose(capture.captureFrom(securityContext)));

            // 8. Enrich MDC with identity info — absent components are never emitted as empty
            //    strings; authMethod is only emitted for non-anonymous requests.
            Map<String, String> mdc = new LinkedHashMap<>();
            if (identity.actor().type() == PrincipalType.USER) {
                mdc.put(MdcKeys.USER_ID, identity.actor().id());
            }
            identity.client().map(ClientRef::clientId).ifPresent(v -> mdc.put(MdcKeys.CLIENT_ID, v));
            if (primaryMethod.normalizedKind() != AuthMethodKind.NONE) {
                mdc.put(MdcKeys.AUTH_METHOD, primaryMethod.id());
            }
            lifecycle.bindMdc(mdc);

            // 9. Emit CredentialAcceptedEvent when evidence is non-empty (skip for anonymous).
            if (!evidence.isEmpty()) {
                correlation.ifPresentOrElse(
                        corr -> emitter.emit(
                                new CredentialAcceptedEvent(Instant.now(), corr, origin, authentication, identity)),
                        () -> log.warn("CorrelationContext not bound during identity resolution; "
                                + "CredentialAcceptedEvent will not be emitted"));
            }

            ctx.next();
        });
    }

    // --- Private helpers ---

    /**
     * Recursively walks the ordered resolver chain. Returns the first non-empty identity result.
     * Falls back to {@link SecurityIdentity#anonymous()} when the chain is exhausted.
     *
     * @param ctx   the resolution context
     * @param index the current position in {@link #orderedResolvers}
     * @return a {@link Future} succeeding with the resolved identity; never fails unless a resolver
     *         propagates a failure
     */
    private Future<SecurityIdentity> runResolverChain(SecurityIdentityResolutionContext ctx, int index) {
        if (index >= orderedResolvers.size()) {
            return Future.succeededFuture(SecurityIdentity.anonymous());
        }
        return orderedResolvers.get(index).resolve(ctx).compose(result -> {
            if (result.isPresent()) {
                return Future.succeededFuture(result.get());
            }
            return runResolverChain(ctx, index + 1);
        });
    }
}

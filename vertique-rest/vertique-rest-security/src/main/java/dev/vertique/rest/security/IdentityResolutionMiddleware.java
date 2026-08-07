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
import io.vertx.ext.auth.User;
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
 *   <li>When — and only when — the optional {@link VertxAuthorizationImporter} is wired
 *       ({@link VertxAuthorizationImportModule} included) and the request carries a Vert.x
 *       {@link io.vertx.ext.auth.User}, import the authorizations granted by the registered Vert.x
 *       {@link io.vertx.ext.auth.authorization.AuthorizationProvider}s and merge them into the
 *       claims built in the previous step. The import is asynchronous and all-or-nothing: any
 *       provider failure fails the request (the {@link AuthenticatedSecurityContext} is never bound
 *       and {@link RoutingContext#next()} is never called). With no importer wired, or on an
 *       anonymous request, this step is skipped entirely and adds no overhead.</li>
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
    private final Optional<VertxAuthorizationImporter> authorizationImporter;

    /**
     * Creates a new {@code IdentityResolutionMiddleware} with no identity-snapshot capture wired.
     *
     * <p>Convenience delegate for manual construction sites (e.g. {@code WebSocketMount} and tests)
     * that predate the ingress capture seam; equivalent to passing {@link Optional#empty()} as the
     * capture, i.e. no snapshot is captured at ingress. The Dagger-injected constructor below is the
     * one used in the assembled application graph. No Vert.x authorization importer is wired either,
     * so the authorization-import step is skipped.
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
     * Creates a new {@code IdentityResolutionMiddleware} with no Vert.x authorization importer
     * wired.
     *
     * <p>Convenience delegate equivalent to passing {@link Optional#empty()} as the importer to the
     * canonical constructor below, i.e. the authorization-import step is skipped.
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
    public IdentityResolutionMiddleware(
            Set<SecurityIdentityResolver> resolvers,
            Optional<SecurityClaimMapper> claimMapper,
            SecurityEventEmitter emitter,
            SecurityRuntime securityRuntime,
            ContextHolder contextHolder,
            Optional<IdentitySnapshotCapture> identitySnapshotCapture) {
        this(
                resolvers,
                claimMapper,
                emitter,
                securityRuntime,
                contextHolder,
                identitySnapshotCapture,
                Optional.empty());
    }

    /**
     * Creates a new {@code IdentityResolutionMiddleware} — the canonical constructor, and the one
     * Dagger injects.
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
     * @param authorizationImporter   the optional Vert.x authorization importer; present only when
     *                                the application opts in by including
     *                                {@link VertxAuthorizationImportModule}, in which case every
     *                                authenticated request's claims are enriched with the
     *                                authorizations granted by the registered Vert.x
     *                                {@link io.vertx.ext.auth.authorization.AuthorizationProvider}s.
     *                                When absent the import step is skipped outright — no provider is
     *                                consulted and the hot path pays no overhead. Must not be
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
            Optional<IdentitySnapshotCapture> identitySnapshotCapture,
            Optional<VertxAuthorizationImporter> authorizationImporter) {
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
        this.authorizationImporter = Objects.requireNonNull(authorizationImporter, "authorizationImporter");
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
            // Everything downstream of the resolver chain runs inside this callback, so a
            // synchronous throw here would escape into the Vert.x context exception handler and
            // hang the request instead of failing it. Route it to ctx.fail(...) exactly once.
            try {
                composeClaimsAndContinue(ctx, ar.result(), evidence, origin, correlation);
            } catch (RuntimeException t) {
                ctx.fail(t);
            }
        });
    }

    // --- Private helpers ---

    /**
     * Builds the authentication state and the base {@link AuthorizationClaims}, optionally enriches
     * the claims through the Vert.x authorization importer, and continues with
     * {@link #bindAndEnrich} once the (possibly asynchronous) import has completed.
     *
     * <p>The importer is consulted only when it is wired <em>and</em> the request carries a Vert.x
     * {@link User}; otherwise the base claims are carried forward on an already-completed future, so
     * an application that has not opted in pays no async hop. An import failure fails the request:
     * no {@link AuthenticatedSecurityContext} is bound and {@link RoutingContext#next()} is never
     * called.
     *
     * @param ctx         the current routing context
     * @param identity    the identity resolved by the resolver chain
     * @param evidence    the accumulated authentication evidence, in insertion order
     * @param origin      the pre-auth request origin, when captured
     * @param correlation the ambient correlation context, when bound
     */
    private void composeClaimsAndContinue(
            RoutingContext ctx,
            SecurityIdentity identity,
            List<AuthenticationEvidence> evidence,
            Optional<RequestOrigin> origin,
            Optional<CorrelationContext> correlation) {
        // 5. Build authentication state from evidence.
        AuthMethod primaryMethod =
                evidence.isEmpty() ? DefaultAuthMethod.none() : evidence.get(0).method();
        AuthenticationState authentication =
                new AuthenticationState(primaryMethod, evidence, Optional.empty(), Optional.empty(), Map.of());

        // 6. Build authorization claims from the Vert.x User principal if present.
        User user = ctx.user();
        AuthorizationClaims base = user != null
                ? claimMapper.map(
                        user.principal().getMap() != null ? user.principal().getMap() : Map.of())
                : AuthorizationClaims.empty();

        // 6b. Import Vert.x AuthorizationProvider grants when the opt-in importer is wired and the
        //     request is authenticated. Absent importer or anonymous request → no provider is ever
        //     consulted and the base claims are carried forward unchanged.
        Future<AuthorizationClaims> claimsFuture = authorizationImporter.isPresent() && user != null
                ? authorizationImporter.get().importInto(user, base)
                : Future.succeededFuture(base);

        claimsFuture.onComplete(claimsAr -> {
            if (claimsAr.failed()) {
                ctx.fail(claimsAr.cause());
                return;
            }
            // Same synchronous-throw guard as in handle(): this callback may run on a later tick,
            // where a propagated throw would be lost rather than failing the request.
            try {
                bindAndEnrich(ctx, identity, authentication, claimsAr.result(), origin, correlation);
            } catch (RuntimeException t) {
                ctx.fail(t);
                return;
            }
            ctx.next();
        });
    }

    /**
     * Constructs and binds the {@link AuthenticatedSecurityContext}, installs the ambient
     * {@link InvocationOrigin}, captures the optional identity snapshot, enriches MDC, and emits the
     * {@link CredentialAcceptedEvent}. Does not advance the route — the caller invokes
     * {@link RoutingContext#next()} only when this method returns normally.
     *
     * @param ctx           the current routing context
     * @param identity      the identity resolved by the resolver chain
     * @param authentication the authentication state built from the accumulated evidence
     * @param authorization the final authorization claims, including any imported provider grants
     * @param origin        the pre-auth request origin, when captured
     * @param correlation   the ambient correlation context, when bound
     */
    private void bindAndEnrich(
            RoutingContext ctx,
            SecurityIdentity identity,
            AuthenticationState authentication,
            AuthorizationClaims authorization,
            Optional<RequestOrigin> origin,
            Optional<CorrelationContext> correlation) {
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
        AuthMethod primaryMethod = authentication.primaryMethod();
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
        if (!authentication.evidence().isEmpty()) {
            correlation.ifPresentOrElse(
                    corr -> emitter.emit(
                            new CredentialAcceptedEvent(Instant.now(), corr, origin, authentication, identity)),
                    () -> log.warn("CorrelationContext not bound during identity resolution; "
                            + "CredentialAcceptedEvent will not be emitted"));
        }
    }

    /**
     * Recursively walks the ordered resolver chain. Returns the first non-empty identity result.
     * Falls back to {@link SecurityIdentity#anonymous()} when the chain is exhausted.
     *
     * <p>A resolver that throws synchronously, or returns a {@code null} future, is normalized into
     * a failed future so the failure reaches {@link RoutingContext#fail(Throwable)} through the
     * caller's completion handler instead of propagating out of {@link #handle(RoutingContext)}.
     *
     * @param ctx   the resolution context
     * @param index the current position in {@link #orderedResolvers}
     * @return a {@link Future} succeeding with the resolved identity; failed when a resolver
     *         propagates a failure, throws synchronously, or returns {@code null}
     */
    private Future<SecurityIdentity> runResolverChain(SecurityIdentityResolutionContext ctx, int index) {
        if (index >= orderedResolvers.size()) {
            return Future.succeededFuture(SecurityIdentity.anonymous());
        }
        SecurityIdentityResolver resolver = orderedResolvers.get(index);
        Future<Optional<SecurityIdentity>> resolved;
        try {
            resolved = resolver.resolve(ctx);
        } catch (RuntimeException t) {
            return Future.failedFuture(t);
        }
        if (resolved == null) {
            return Future.failedFuture(new IllegalStateException("SecurityIdentityResolver "
                    + resolver.getClass().getName()
                    + " (id="
                    + resolver.id()
                    + ") returned a null future"));
        }
        return resolved.compose(result -> {
            if (result.isPresent()) {
                return Future.succeededFuture(result.get());
            }
            return runResolverChain(ctx, index + 1);
        });
    }
}

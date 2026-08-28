// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates Vert.x {@link Handler}{@code <RoutingContext>} instances that enforce
 * {@link SecurityPolicy} authorization rules.
 *
 * <p>Pattern-matches on the sealed {@link SecurityPolicy} variants:
 * <ul>
 *   <li>{@link SecurityPolicy.DenyAll} — handler that always fails with 403</li>
 *   <li>{@link SecurityPolicy.PermitAll} — {@code null} (no authorization handler needed)</li>
 *   <li>{@link SecurityPolicy.AuthenticatedOnly} — handler checking the resolved SecurityContext
 *       has a non-anonymous identity</li>
 *   <li>{@link SecurityPolicy.Constrained} — role/scope-based authorization via
 *       {@link AuthorizationDecisionPoint}</li>
 *   <li>{@link SecurityPolicy.None} — {@code null} (no handler needed)</li>
 * </ul>
 *
 * <p><strong>Emission ownership (ADR-0114).</strong> The enforcement layer — not the decision
 * point — owns emission. Every handler this class installs emits <em>exactly one</em>
 * {@link dev.vertique.security.events.AuthorizationDecisionEvent} per authorization attempt,
 * for both permit and deny. {@link SecurityPolicy.AuthenticatedOnly} emits a permit (reason
 * {@link AuthzReasonCodes#PERMITTED}) on the {@code ctx.next()} path as well as a deny on the
 * fail-closed path, matching the {@link SecurityPolicy.DenyAll}/{@link SecurityPolicy.Constrained}/
 * composed paths (FR-AUTHZ-050). The fail-closed short-circuits that historically emitted nothing
 * also emit: {@link SecurityPolicy.DenyAll} (reason {@link AuthzReasonCodes#DENY_ALL});
 * {@link SecurityPolicy.AuthenticatedOnly} with a missing/anonymous {@code SecurityContext} and a
 * {@link SecurityPolicy.Constrained} route with a missing {@code SecurityContext} (reason
 * {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED}); and a decision-point failure or
 * contract violation — a {@link AuthorizationDecisionPoint}/{@link Authorizer} that throws
 * synchronously, returns a {@code null} future, or resolves to a {@code null} decision — which all
 * fail closed with reason {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}. {@link SecurityPolicy.None}
 * and {@link SecurityPolicy.PermitAll} install no handler, so they emit nothing. Event construction
 * never throws and never masks the security outcome: when no {@code CorrelationContext} is bound,
 * the event is built with {@link dev.vertique.core.correlation.CorrelationContext#unbound()}.
 *
 * <p><strong>Decision point chain:</strong> {@link SecurityPolicy.Constrained} handlers are
 * evaluated through an {@link AuthorizationDecisionPoint} selected at construction time using the
 * following priority order:
 * <ol>
 *   <li>App-provided {@link AuthorizationDecisionPoint} override (optional binding)</li>
 *   <li>App-provided {@link AuthorizationPolicy} (sync, core SPI), wrapped as a
 *       {@link SyncPolicyDecisionPoint}</li>
 *   <li>Default {@link VertxProviderDecisionPoint} (evaluates role/scope/permission requirements
 *       from the request's {@link dev.vertique.security.authz.AuthorizationClaims}). Vert.x
 *       {@link AuthorizationProvider}s are consulted only via the opt-in identity-resolution import
 *       ({@link VertxAuthorizationImportModule}), which merges their grants into those claims
 *       upstream; this decision point itself still evaluates claims only.</li>
 * </ol>
 *
 * <p>Used by both {@link AuthorizationContributor} (JAX-RS routes) and the WebSocket module for
 * consistent authorization enforcement across transports — but not as one shared instance.
 * {@link AuthorizationContributor} injects the single Dagger {@code @Singleton} this class declares
 * above; {@code WebSocketMount.Factory} constructs its own, separate instance from the same
 * constructor arguments (including the same operator-configured {@link AuthorizationGateConfig}), so
 * each transport enforces identically-configured policy through its own instance, not a shared one.
 */
@Slf4j
@Singleton
public class SecurityPolicyEnforcer {

    private final AuthorizationDecisionPoint decisionPoint;
    private final SecurityRuntime securityRuntime;
    private final SecurityEventEmitter emitter;
    private final ContextHolder contextHolder;

    /**
     * The core action {@link Authorizer} used to evaluate the {@code @RequiresAction} gate, or
     * {@code null} when the authorization engine is not installed.
     *
     * <p>This is {@code null} only in deployments with no authz engine — and a {@code @RequiresAction}
     * route in such a deployment is rejected at startup (fail-closed invariant, slice 11), so the
     * combined handler is built only when {@code requiredAction} is present, which guarantees this
     * field is non-{@code null} on every path that reads it.
     */
    private final Authorizer authorizer;

    /**
     * The bound, in milliseconds, on every {@link #decide} gate future — {@link
     * AuthorizationGateConfig#DEFAULT_GATE_DEADLINE_MS} by default, operator-configurable via the
     * {@code security.authz} config section (issue #417, R42).
     */
    private final long gateDeadlineMs;

    /**
     * Creates a new enforcer with the framework-default {@link AuthorizationGateConfig} (issue
     * #417's fixed pre-R42 deadline, byte-identical to before R42). Equivalent to the eight-argument
     * constructor with {@link Optional#empty()} for {@code authorizationGateConfig}.
     *
     * <p>Not {@code @Inject}-annotated — hand-wiring call sites (chiefly tests) that do not need to
     * thread a configured deadline use this overload. {@code WebSocketMount.Factory} still
     * hand-constructs its own {@link SecurityPolicyEnforcer} instance — that never changed at R42 —
     * but always through the eight-argument constructor below, threading the same operator-configured
     * {@link AuthorizationGateConfig} REST and MCP use, not this deadline-less overload. Dagger itself
     * always resolves the eight-argument constructor too.
     *
     * @param authorizationDecisionPoint optional app-provided async decision point; takes precedence
     *                                   over everything else
     * @param authorizationPolicy        optional app-provided sync authorization policy; used when no
     *                                   async override is present
     * @param authorizationProviders     Vert.x authorization provider set for the default decision
     *                                   point
     * @param emitter                    the security event emitter used to emit the one
     *                                   {@link AuthorizationDecisionEvent} per authorization attempt
     * @param contextHolder              the context holder for reading the ambient
     *                                   {@link dev.vertique.core.correlation.CorrelationContext}
     * @param securityRuntime            the security runtime for reading the current
     *                                   {@link dev.vertique.security.SecurityContext}
     * @param authorizer                 the optional core action {@link Authorizer} used to evaluate the
     *                                   {@code @RequiresAction} gate; empty when the authorization engine
     *                                   is not installed (in which case no {@code @RequiresAction} route
     *                                   can pass startup validation — slice 11). Must not be {@code null};
     *                                   {@link Optional#empty()} signals "engine absent".
     */
    public SecurityPolicyEnforcer(
            Optional<AuthorizationDecisionPoint> authorizationDecisionPoint,
            Optional<AuthorizationPolicy> authorizationPolicy,
            Set<AuthorizationProvider> authorizationProviders,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            SecurityRuntime securityRuntime,
            Optional<Authorizer> authorizer) {
        this(
                authorizationDecisionPoint,
                authorizationPolicy,
                authorizationProviders,
                emitter,
                contextHolder,
                securityRuntime,
                authorizer,
                Optional.empty());
    }

    /**
     * As the seven-argument constructor above, but with an explicit, operator-configurable {@link
     * #decide} gate deadline instead of the framework default (issue #417, R42).
     *
     * <p>{@code @Inject}-constructed instances receive {@code Optional<AuthorizationGateConfig>} —
     * empty unless the application installs {@link AuthorizationGateConfigModule} (or binds {@link
     * AuthorizationGateConfig} some other way), in which case it defaults to {@link
     * AuthorizationGateConfig#defaults()} (byte-identical to the pre-R42 hardcoded constant).
     *
     * @param authorizationDecisionPoint optional app-provided async decision point; takes precedence
     *                                   over everything else
     * @param authorizationPolicy        optional app-provided sync authorization policy; used when no
     *                                   async override is present
     * @param authorizationProviders     Vert.x authorization provider set for the default decision
     *                                   point
     * @param emitter                    the security event emitter used to emit the one
     *                                   {@link AuthorizationDecisionEvent} per authorization attempt
     * @param contextHolder              the context holder for reading the ambient
     *                                   {@link dev.vertique.core.correlation.CorrelationContext}
     * @param securityRuntime            the security runtime for reading the current
     *                                   {@link dev.vertique.security.SecurityContext}
     * @param authorizer                 the optional core action {@link Authorizer} used to evaluate the
     *                                   {@code @RequiresAction} gate; empty when the authorization engine
     *                                   is not installed (in which case no {@code @RequiresAction} route
     *                                   can pass startup validation — slice 11). Must not be {@code null};
     *                                   {@link Optional#empty()} signals "engine absent".
     * @param authorizationGateConfig    the optional operator-configured gate deadline; empty defaults
     *                                   to {@link AuthorizationGateConfig#defaults()}
     */
    @Inject
    public SecurityPolicyEnforcer(
            Optional<AuthorizationDecisionPoint> authorizationDecisionPoint,
            Optional<AuthorizationPolicy> authorizationPolicy,
            Set<AuthorizationProvider> authorizationProviders,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            SecurityRuntime securityRuntime,
            Optional<Authorizer> authorizer,
            Optional<AuthorizationGateConfig> authorizationGateConfig) {
        Objects.requireNonNull(authorizationDecisionPoint, "authorizationDecisionPoint");
        Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        Objects.requireNonNull(authorizationProviders, "authorizationProviders");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer").orElse(null);
        this.gateDeadlineMs = Objects.requireNonNull(authorizationGateConfig, "authorizationGateConfig")
                .orElseGet(AuthorizationGateConfig::defaults)
                .gateDeadlineMs();

        if (authorizationDecisionPoint.isPresent()) {
            this.decisionPoint = authorizationDecisionPoint.get();
            log.debug("AuthorizationDecisionPoint: using app-provided override");
        } else if (authorizationPolicy.isPresent()) {
            this.decisionPoint = new SyncPolicyDecisionPoint(authorizationPolicy.get());
            log.debug("AuthorizationDecisionPoint: wrapping app-provided AuthorizationPolicy");
        } else {
            this.decisionPoint = new VertxProviderDecisionPoint(authorizationProviders);
            log.debug("AuthorizationDecisionPoint: using default VertxProviderDecisionPoint");
        }
    }

    /**
     * Creates an authorization handler for the given policy with no {@code @RequiresAction} gate, or
     * {@code null} if no authorization enforcement is needed.
     *
     * <p>Equivalent to {@link #createHandler(SecurityPolicy, Optional)} with an
     * {@linkplain Optional#empty() empty} action: {@link SecurityPolicy.None} and
     * {@link SecurityPolicy.PermitAll} return {@code null}; all other variants return a handler that
     * enforces the respective role/scope constraint.
     *
     * @param policy the security policy to enforce; must not be {@code null}
     * @return a Vert.x handler enforcing the policy, or {@code null} if no enforcement is needed
     * @throws IllegalStateException if a {@link SecurityPolicy.Constrained} policy has both empty
     *     roles and empty scopes (invariant violation)
     */
    public Handler<RoutingContext> createHandler(SecurityPolicy policy) {
        return createHandler(policy, Optional.empty());
    }

    /**
     * Creates an authorization handler that AND-composes the Jakarta role/scope policy with the
     * {@code @RequiresAction} action gate, emitting <em>exactly one</em> combined
     * {@link AuthorizationDecisionEvent} per attempt (ADR-0113 / ADR-0114).
     *
     * <p>A handler is installed whenever the {@code policy} is {@linkplain SecurityPolicy#isRestrictive()
     * restrictive} <strong>or</strong> a {@code requiredAction} is present. In particular an
     * <em>action-only</em> route ({@link SecurityPolicy.None} with a present action) is enforced even
     * though {@code None} alone installs no handler. {@code None}/{@link SecurityPolicy.PermitAll} with
     * no action return {@code null}.
     *
     * <p>When a {@code requiredAction} is present the two gates compose without blocking:
     * <ol>
     *   <li>the role/scope gate (the existing {@link AuthorizationDecisionPoint} for
     *       {@link SecurityPolicy.Constrained}, the non-anonymous check for
     *       {@link SecurityPolicy.AuthenticatedOnly}, an automatic pass for
     *       {@link SecurityPolicy.None}/{@link SecurityPolicy.PermitAll}); and</li>
     *   <li>the action gate ({@link Authorizer#authorize(AuthorizationRequest)}, called with an
     *       explicit {@link AuthorizationRequest} carrying the ambient {@link InvocationOrigin} —
     *       not the {@link Authorizer#authorize(SecurityContext, ActionRef, ResourceRef)}
     *       convenience overload, which seeds {@link InvocationOrigin#unspecified()}).</li>
     * </ol>
     * The request is permitted iff both pass. The single emitted decision carries a top-level
     * {@code reasonCode} equal to the <strong>first failing predicate</strong> (the role/scope failure
     * code when role/scope fails; otherwise the action gate's code; {@link AuthzReasonCodes#PERMITTED}
     * when both pass), and {@code safeAttributes} carrying {@code rolesSatisfied} /
     * {@code actionSatisfied} / {@code actionEvaluated}. The action gate is <em>not</em> evaluated once
     * the role/scope gate denies (fail-fast — {@code actionEvaluated=false}), but the attempt still
     * emits exactly one event.
     *
     * <p>{@code @RequiresAction} cannot occur with {@link SecurityPolicy.PermitAll} or
     * {@link SecurityPolicy.DenyAll} (rejected at compile time and at startup, slice 11), so those
     * combinations are not reachable here.
     *
     * @param policy         the security policy to enforce; must not be {@code null}
     * @param requiredAction the canonical action gate resolved from {@code @RequiresAction}, or
     *                       {@link Optional#empty()} when the operation declares no action gate; must not
     *                       be {@code null}
     * @return a Vert.x handler enforcing the composed constraints, or {@code null} if no enforcement is
     *     needed (only {@code None}/{@code PermitAll} with no action)
     * @throws IllegalStateException if a {@link SecurityPolicy.Constrained} policy has both empty roles
     *     and empty scopes (invariant violation)
     */
    public Handler<RoutingContext> createHandler(SecurityPolicy policy, Optional<ActionRef> requiredAction) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(requiredAction, "requiredAction");
        if (requiredAction.isEmpty()) {
            return createNoActionHandler(policy);
        }
        // Action present → AND-compose the role/scope gate with the action gate into one event.
        return buildComposedHandler(policy, requiredAction.get());
    }

    /**
     * Builds the role/scope-only handler for a policy with no {@code @RequiresAction} gate, preserving
     * the established per-variant behavior (ADR-0114 emission ownership).
     *
     * <p>{@link SecurityPolicy.None} and {@link SecurityPolicy.PermitAll} install no handler;
     * {@link SecurityPolicy.DenyAll} fails closed; {@link SecurityPolicy.AuthenticatedOnly} requires a
     * non-anonymous identity; {@link SecurityPolicy.Constrained} routes through the
     * {@link AuthorizationDecisionPoint}.
     *
     * @param policy the security policy to enforce; must not be {@code null}
     * @return a Vert.x handler enforcing the policy, or {@code null} when no enforcement is needed
     * @throws IllegalStateException if a {@link SecurityPolicy.Constrained} policy has both empty roles
     *     and empty scopes
     */
    private Handler<RoutingContext> createNoActionHandler(SecurityPolicy policy) {
        return switch (policy) {
            case SecurityPolicy.None ignored -> null;
            case SecurityPolicy.DenyAll ignored ->
                ctx -> {
                    // Fail-closed blanket deny — emit one deny event then short-circuit (FR-054).
                    // Capture the correlation bound at entry (this path is synchronous, but doing
                    // so keeps every emission site uniform) and record the real route, not a
                    // placeholder, so the event identifies which endpoint denied.
                    emitDecision(
                            AuthorizationDecision.deny(AuthzReasonCodes.DENY_ALL),
                            securityRuntime.current(),
                            pathOf(ctx),
                            methodOf(ctx),
                            captureCorrelation());
                    ctx.fail(403);
                };
            case SecurityPolicy.PermitAll ignored -> null;
            case SecurityPolicy.AuthenticatedOnly ignored ->
                ctx -> {
                    // Capture the correlation bound at entry so both the permit and deny events carry
                    // the inbound correlation (this path is synchronous, but capturing keeps every
                    // emission site uniform).
                    CorrelationContext correlation = captureCorrelation();
                    // Check the resolved framework SecurityContext, not ctx.user(): custom/app
                    // auth handlers may stash AuthenticationEvidence and resolve a non-anonymous
                    // identity without populating a Vert.x User. IdentityResolutionContributor
                    // (priority 80) binds the SecurityContext before this handler (priority 100).
                    SecurityContext secCtx = securityRuntime.current();
                    boolean authenticated = secCtx != null
                            && secCtx.identity().actor().type() != dev.vertique.security.PrincipalType.ANONYMOUS;
                    if (!authenticated) {
                        // Fail-closed: missing/anonymous identity emits one deny event (FR-054)
                        // recording the real route so the denied endpoint is identifiable.
                        emitDecision(
                                AuthorizationDecision.deny(AuthzReasonCodes.AUTHENTICATION_REQUIRED),
                                secCtx,
                                pathOf(ctx),
                                methodOf(ctx),
                                correlation);
                        ctx.fail(401);
                    } else {
                        // Permit path: emit one permit event (reason PERMITTED) before proceeding so
                        // AuthenticatedOnly satisfies the exactly-one-event-per-attempt contract on the
                        // permit path too (FR-AUTHZ-050 / ADR-0114), matching the DenyAll/Constrained/
                        // composed paths that already emit on permit.
                        emitDecision(
                                AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED),
                                secCtx,
                                pathOf(ctx),
                                methodOf(ctx),
                                correlation);
                        ctx.next();
                    }
                };
            case SecurityPolicy.Constrained c -> buildConstrainedHandler(c, null);
        };
    }

    /**
     * Creates an authorization handler for the given constrained policy, including a descriptive
     * context label used in error messages.
     *
     * @param policy      the constrained security policy; must not be {@code null}
     * @param contextLabel a human-readable label (e.g., operationId) included in exception messages,
     *                    or {@code null} to omit
     * @return a Vert.x handler enforcing the role/scope constraints
     * @throws IllegalStateException if the policy has both empty roles and empty scopes
     */
    public Handler<RoutingContext> createHandler(SecurityPolicy.Constrained policy, String contextLabel) {
        return buildConstrainedHandler(policy, contextLabel);
    }

    /**
     * Evaluates a {@link SecurityPolicy} plus an optional {@code @RequiresAction} gate against an
     * already-resolved {@link SecurityContext} and <strong>returns</strong> the composed
     * {@link AuthorizationDecision}, instead of driving a {@link RoutingContext} (T005).
     *
     * <p>This is the non-HTTP counterpart to {@link #createHandler(SecurityPolicy, Optional)}: it
     * mirrors that method's contract exactly (ADR-0113 / ADR-0114) — the same role/scope-plus-action
     * AND-composition, the same fail-fast ordering, the same first-failing-predicate
     * {@code reasonCode}, the same {@code rolesSatisfied}/{@code actionSatisfied}/{@code
     * actionEvaluated} safe attributes, and the same fail-closed handling of a contract-violating
     * gate — but it never touches a {@link RoutingContext}, never synthesizes one, and performs no
     * HTTP status mapping. The caller (e.g. the MCP policy enforcer) owns translating the returned
     * decision to its own transport response. {@code securityContext} is the already-established
     * canonical anonymous or authenticated context; this operation never resolves ambient state
     * itself.
     *
     * <p>{@link ResourceRef#id() resource.id()} is threaded as the coarse
     * {@link AuthorizationRequest#action()} on the role/scope-gate request, mirroring how the REST
     * path threads the HTTP method into that same field: it names the operation being attempted
     * rather than a granted authority. Note what that field is <em>not</em> on this request: it is
     * never parsed as an {@link ActionRef}, and the role/scope gate never reads it at all — neither
     * {@code SyncPolicyDecisionPoint} nor {@link VertxProviderDecisionPoint} references
     * {@code action()}, which reaches the decision point only as descriptive context and reaches the
     * emitted event as forensics. The same is already true of the REST path's {@code "GET"}, so a
     * custom {@link AuthorizationDecisionPoint} must not assume this field is
     * {@link ActionRef}-parseable on a role/scope request. The action gate is different: its
     * separate request carries {@code action.value()}, which narrowers do parse.
     *
     * <ul>
     *   <li>{@link SecurityPolicy.None} or {@link SecurityPolicy.PermitAll} with an
     *       {@linkplain Optional#isEmpty() empty} {@code requiredAction} resolve a permit decision
     *       ({@link AuthzReasonCodes#PERMITTED}) and emit <strong>no</strong> event — the only path
     *       that emits nothing, matching the handler factories that install no handler.</li>
     *   <li>{@link SecurityPolicy.DenyAll} resolves a deny carrying
     *       {@link AuthzReasonCodes#DENY_ALL} and emits exactly one event.</li>
     *   <li>Every other combination evaluates the role/scope gate through
     *       {@link #evaluateRoleScopeGate}, then AND-composes a present action gate through the core
     *       {@link Authorizer} — only once the role/scope gate permits (fail-fast;
     *       {@code actionEvaluated=false} when it denies or when no action is required) — and emits
     *       exactly one combined event per restrictive evaluation via {@link #combinedDecision},
     *       identical in shape to {@link #buildComposedHandler}.</li>
     *   <li>A contract-violating gate — a synchronous throw, a {@code null} future, a failed future,
     *       or a {@code null} decision, from either the {@link AuthorizationDecisionPoint} or the
     *       {@link Authorizer} — resolves a fail-closed {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}
     *       deny (via {@link #combinedDecision}) with exactly one event, rather than propagating or
     *       failing the returned future.</li>
     * </ul>
     *
     * @param securityContext the already-resolved security context of the caller; must not be
     *                        {@code null}
     * @param policy          the security policy to enforce; must not be {@code null}
     * @param requiredAction  the resolved action gate, or {@link Optional#empty()} when the
     *                        operation declares no action gate; must not be {@code null}
     * @param resource        the target resource; {@link ResourceRef#id()} is threaded as the
     *                        coarse role/scope-gate action, mirroring the REST path's use of the
     *                        HTTP method; must not be {@code null}
     * @param origin          the invocation origin the caller was raised through; must not be
     *                        {@code null}
     * @return a future carrying the composed {@link AuthorizationDecision}; never {@code null} and
     *     never a failed future — an ordinary deny and a fail-closed contract violation both resolve
     *     a succeeded future carrying a deny decision
     * @throws IllegalStateException if {@code policy} is a {@link SecurityPolicy.Constrained} with
     *     both empty roles and empty scopes (invariant violation)
     */
    public Future<AuthorizationDecision> decide(
            SecurityContext securityContext,
            SecurityPolicy policy,
            Optional<ActionRef> requiredAction,
            ResourceRef resource,
            InvocationOrigin origin) {
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(requiredAction, "requiredAction");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(origin, "origin");

        // R07 item 5 (security/architecture review, post-R01): captured here, before any async hop,
        // so the promise built below can be re-anchored to it — see that promise's own comment for why.
        Context callerContext = Vertx.currentContext();

        // Capture correlation once at entry, before any async hop — mirrors buildComposedHandler so
        // an off-context gate completion (remote PDP / async Authorizer) still emits the inbound
        // correlation (FR-054).
        CorrelationContext correlation = captureCorrelation();

        if (requiredAction.isEmpty()
                && (policy instanceof SecurityPolicy.None || policy instanceof SecurityPolicy.PermitAll)) {
            // The only path that emits nothing — mirrors createHandler's null-handler case.
            return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        }

        // Building the request can itself throw — requireNonEmptyConstrained rejects a Constrained
        // policy with neither roles nor scopes, and AuthorizationRequest rejects a blank action, which
        // a ResourceRef with a blank id produces. A Future-returning fail-closed API must not throw
        // synchronously past its own envelope: one bad candidate would abort a whole tools/list loop,
        // the exact failure decide() exists to avoid. So the construction sits inside the envelope and
        // an invalid input denies rather than escapes.
        AuthorizationRequest authzRequest;
        try {
            Map<String, Object> policyContext =
                    policy instanceof SecurityPolicy.Constrained c ? requireNonEmptyConstrained(c) : Map.of();
            authzRequest = new AuthorizationRequest(securityContext, resource.id(), resource, origin, policyContext);
        } catch (RuntimeException e) {
            log.warn("Authorization request could not be built; failing closed", e);
            return Future.succeededFuture(
                    roleScopeOnlyDecision(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR)));
        }

        if (policy instanceof SecurityPolicy.DenyAll) {
            AuthorizationDecision decision =
                    roleScopeOnlyDecision(AuthorizationDecision.deny(AuthzReasonCodes.DENY_ALL));
            emitDecision(authzRequest, decision, correlation);
            return Future.succeededFuture(decision);
        }

        // Fail-closed against a contract-violating role/scope gate (mirrors buildComposedHandler): a
        // synchronous throw or a null Future from the decision point must not escape (F-W3).
        Future<AuthorizationDecision> roleScopeFuture;
        try {
            roleScopeFuture = evaluateRoleScopeGate(policy, authzRequest);
        } catch (RuntimeException e) {
            log.warn("Authorization decision point threw at role/scope gate; failing closed", e);
            return Future.succeededFuture(failClosedInternalError(authzRequest, correlation));
        }
        if (roleScopeFuture == null) {
            log.warn("Authorization decision point returned a null future at role/scope gate; failing closed");
            return Future.succeededFuture(failClosedInternalError(authzRequest, correlation));
        }
        // Bound the gate (issue #417): a non-blocking future that simply never resolves is not a
        // synchronous throw, a null future, a failed future, or a null decision, so none of the
        // existing fail-closed branches above ever catch it. .timeout() races the original future
        // against gateDeadlineMs and forwards it unchanged when it settles first — inert for a gate
        // that completes normally (verified by the unmodified SecurityPolicyEnforcerDecisionTest
        // permit/deny suite still passing byte-for-byte after this change).
        //
        // R07 item 5 (security/architecture review, post-R01): Vert.x 5.1.6's FutureBase#timeout
        // branches on the SOURCE future's context, not the caller's. A gate future built with the
        // static Promise.promise() — or bridged from a CompletableFuture by a remote-PDP client, the
        // exact case this deadline exists for — has context == null, so its .timeout() continuation
        // runs on Netty's GlobalEventExecutor rather than any Vert.x event-loop thread. Left
        // unaddressed, that thread would then be the one that eventually calls promise.complete(...)
        // below, and McpCompletionCoordinator's non-volatile settled/completionEmitted latches are
        // documented as "mutated only on the request-owning Vert.x context" — a torn latch on this
        // path means double settlement or a lost completion. This method does not attempt to re-anchor
        // roleScopeFuture/actionFuture themselves (their own timeout continuations may still run off
        // any context); instead it re-anchors the one future this method actually hands back to its
        // caller — see completeOnCallerContext, used at every promise.complete(...) call site below.
        roleScopeFuture = roleScopeFuture.timeout(gateDeadlineMs, TimeUnit.MILLISECONDS);

        // R07 item 5: {@code promise} itself stays the plain, context-less default — Vert.x 5.1.6's
        // {@link Context} exposes no {@code promise()} factory to anchor one to a context directly.
        // Every settlement of it instead goes through {@link #completeOnCallerContext}, which
        // redispatches onto {@code callerContext} via {@link Context#runOnContext} before completing —
        // mirroring McpCompletionCoordinator's own established {@code context.runOnContext(...)}
        // re-anchoring idiom — so every caller of decide() (McpPolicyEnforcer,
        // McpRequestDispatcher's tools/list scan) keeps observing this future settle on the same
        // context it called decide() from, closing the gap a context-less gate future's own
        // off-context timeout would otherwise reopen. When decide() is itself called off any Vert.x
        // context (a unit test with no Vertx instance, matching this class's own pre-existing
        // SecurityPolicyEnforcerGateDeadlineTest fixture), callerContext is null and this degrades to
        // the exact pre-fix behavior — completing directly, on whichever thread settled the gate.
        Promise<AuthorizationDecision> promise = Promise.promise();
        roleScopeFuture.onComplete(roleScopeAr -> {
            if (roleScopeAr.failed()) {
                log.warn("Authorization decision point failed", roleScopeAr.cause());
                completeOnCallerContext(promise, callerContext, failClosedInternalError(authzRequest, correlation));
                return;
            }
            AuthorizationDecision roleScope = roleScopeAr.result();
            if (roleScope == null) {
                log.warn("Authorization decision point resolved to a null role/scope decision; failing closed");
                completeOnCallerContext(promise, callerContext, failClosedInternalError(authzRequest, correlation));
                return;
            }
            if (!roleScope.permitted() || requiredAction.isEmpty()) {
                // Either the role/scope gate already denied (fail-fast — the action gate is not
                // evaluated) or there is no action gate to compose. Either way the decision point's
                // own policyId/policyVersion/safeAttributes are carried through rather than dropped,
                // matching what buildConstrainedHandler emits verbatim on the REST no-action path.
                AuthorizationDecision decision = roleScopeOnlyDecision(roleScope);
                emitDecision(authzRequest, decision, correlation);
                completeOnCallerContext(promise, callerContext, decision);
                return;
            }

            // Role/scope permitted and an action gate is present → evaluate it. authorizer is
            // guaranteed non-null here because a present requiredAction implies the engine is
            // installed (slice 11). Built as an explicit 5-arg AuthorizationRequest reusing the SAME
            // origin passed to decide(), not a fresh ambient read — mirrors buildComposedHandler's
            // reuse of authzRequest.origin() (W1 residual fix).
            ActionRef action = requiredAction.get();
            AuthorizationRequest actionRequest =
                    new AuthorizationRequest(securityContext, action.value(), resource, origin, Map.of());

            // Fail-closed against a contract-violating Authorizer (mirrors buildComposedHandler): a
            // synchronous throw or a null Future must not escape the gate (F-W3).
            Future<AuthorizationDecision> actionFuture;
            try {
                actionFuture = authorizer.authorize(actionRequest);
            } catch (RuntimeException e) {
                log.warn("Authorizer threw at action gate; failing closed", e);
                completeOnCallerContext(promise, callerContext, failClosedInternalError(authzRequest, correlation));
                return;
            }
            if (actionFuture == null) {
                log.warn("Authorizer returned a null future at action gate; failing closed");
                completeOnCallerContext(promise, callerContext, failClosedInternalError(authzRequest, correlation));
                return;
            }
            // Bound the action gate exactly like the role/scope gate above (issue #417): the same
            // amplification risk applies to a hanging Authorizer, not only a hanging decision point.
            actionFuture.timeout(gateDeadlineMs, TimeUnit.MILLISECONDS).onComplete(actionAr -> {
                AuthorizationDecision actionResult = actionAr.succeeded() ? actionAr.result() : null;
                // The Authorizer contract forbids a failed future for a normal deny and forbids a
                // null decision; fail closed (INTERNAL_AUTHZ_ERROR) if a misbehaving impl does either.
                AuthorizationDecision actionDecision = actionResult != null
                        ? actionResult
                        : AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                AuthorizationDecision decision = combinedDecision(roleScope, actionDecision);
                emitDecision(authzRequest, decision, correlation);
                completeOnCallerContext(promise, callerContext, decision);
            });
        });
        return promise.future();
    }

    /**
     * Completes {@code promise} with {@code decision}, redispatched onto {@code callerContext} first
     * when it is non-{@code null} (R07 item 5), so every handler {@code promise.future()} carries —
     * however many async hops away, and from whatever thread the settling gate future happened to run
     * its continuation on — observes the completion on the exact Vert.x context {@link #decide} was
     * originally called from. {@code callerContext == null} (no Vert.x context was active when {@link
     * #decide} was called — e.g. a plain unit test with no {@link Vertx} instance) completes directly,
     * on whatever thread this method runs on; this is the pre-fix behavior and is unaffected.
     *
     * @param promise the promise this {@link #decide} invocation returned the future of; must not be
     *                {@code null}
     * @param callerContext the context captured at {@link #decide} entry, or {@code null} when none
     *                      was active
     * @param decision the decision to complete {@code promise} with; must not be {@code null}
     */
    private static void completeOnCallerContext(
            Promise<AuthorizationDecision> promise, Context callerContext, AuthorizationDecision decision) {
        if (callerContext != null) {
            callerContext.runOnContext(ignored -> promise.complete(decision));
        } else {
            promise.complete(decision);
        }
    }

    /**
     * Fail-closed deny used by {@link #decide} when a gate violates its contract (the
     * {@link AuthorizationDecisionPoint} or the {@link Authorizer} throws synchronously, returns a
     * {@code null} future, resolves to a {@code null} decision, or exceeds its deadline). Emits
     * exactly one combined deny event whose top-level reason is
     * {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} — mirroring {@link #internalErrorDenyComposed},
     * but returning the decision instead of failing a {@link RoutingContext} closed.
     *
     * <p>The action gate is recorded as not evaluated ({@code action == null} into
     * {@link #combinedDecision}), matching the existing role/scope-gate-failure shape.
     *
     * @param authzRequest the request that was being evaluated, carried on the emitted event; must
     *                     not be {@code null}
     * @param correlation  the correlation captured at call entry; must not be {@code null}
     * @return the fail-closed {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} deny decision; never
     *     {@code null}
     */
    private AuthorizationDecision failClosedInternalError(
            AuthorizationRequest authzRequest, CorrelationContext correlation) {
        AuthorizationDecision decision =
                roleScopeOnlyDecision(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
        emitDecision(authzRequest, decision, correlation);
        return decision;
    }

    /**
     * Returns the resolved {@link AuthorizationDecisionPoint} in effect for this enforcer.
     *
     * <p>Primarily exposed for testing to verify which decision point was selected.
     *
     * @return the active decision point; never {@code null}
     */
    AuthorizationDecisionPoint decisionPoint() {
        return decisionPoint;
    }

    // --- Handler construction ---

    /**
     * Builds an async authorization handler for the given constrained policy. The handler calls
     * {@link AuthorizationDecisionPoint#decide(AuthorizationRequest)} and emits exactly one
     * {@link AuthorizationDecisionEvent} per invocation (ADR-0114):
     * <ul>
     *   <li>missing {@code SecurityContext} → emit deny ({@link AuthzReasonCodes#AUTHENTICATION_REQUIRED}),
     *       then {@code ctx.fail(401)}</li>
     *   <li>permit → emit the decision, then {@code ctx.next()}</li>
     *   <li>deny → emit the decision, then {@code ctx.fail(403)}</li>
     *   <li>decision-point failure (failed future) → emit deny
     *       ({@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}), then {@code ctx.fail(cause)}</li>
     *   <li>decision-point contract violation (synchronous throw, {@code null} future, or {@code null}
     *       decision) → emit deny ({@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}), then
     *       {@code ctx.fail(403)} (fail-closed; mirrors {@code ServiceAuthorizationInterceptor})</li>
     * </ul>
     *
     * @param c            the constrained security policy with role/scope requirements
     * @param contextLabel optional label for error messages; {@code null} to omit
     * @return an authorization handler enforcing the combined role/scope requirements
     * @throws IllegalStateException if both roles and scopes are empty
     */
    private Handler<RoutingContext> buildConstrainedHandler(SecurityPolicy.Constrained c, String contextLabel) {
        if (c.requiredRoles().isEmpty() && c.requiredScopes().isEmpty()) {
            String label = contextLabel != null ? contextLabel : "<unknown>";
            throw new IllegalStateException("Constrained policy with empty roles and empty scopes for " + label
                    + "; factory should have produced AuthenticatedOnly");
        }

        Map<String, Object> policyContext =
                buildPolicyContext(c.requiredRoles(), c.requiredScopes(), c.requireAllScopes());

        return ctx -> {
            // Capture the correlation bound on the request's Vert.x context at handler entry. An
            // app-provided async AuthorizationDecisionPoint (the documented remote-PDP pattern) may
            // complete its Future off this context, where the holder no longer sees the request
            // correlation; capturing here ensures the emitted event carries the inbound correlation
            // rather than degrading to unbound() after the async hop (FR-054).
            CorrelationContext correlation = captureCorrelation();
            String path = pathOf(ctx);
            String method = methodOf(ctx);

            SecurityContext secCtx = securityRuntime.current();
            if (secCtx == null) {
                log.warn("SecurityContext not bound when evaluating constrained policy; treating as 401");
                // Fail-closed: no bound identity emits one deny event (FR-054). There is no
                // SecurityContext to attach, so build the event against an anonymous stand-in,
                // recording the real route so the denied endpoint is identifiable.
                emitDecision(
                        AuthorizationDecision.deny(AuthzReasonCodes.AUTHENTICATION_REQUIRED),
                        null,
                        path,
                        method,
                        correlation);
                ctx.fail(401);
                return;
            }

            // Build a resource ref from the path — use a generic type "route" so the
            // resource is identifiable in the event but does not require OpenAPI metadata here.
            ResourceRef resource = new ResourceRef("route", path, Map.of());
            AuthorizationRequest authzRequest =
                    new AuthorizationRequest(secCtx, method, resource, currentOrigin(), policyContext);

            // Fail-closed against a contract-violating decision point (mirrors
            // ServiceAuthorizationInterceptor): a synchronous throw or a null Future must not escape
            // the gate. The AuthorizationDecisionPoint contract forbids both, but a misbehaving impl
            // must still deny deterministically with exactly one event rather than let an NPE/throw
            // propagate (F-W3).
            Future<AuthorizationDecision> decisionFuture;
            try {
                decisionFuture = decisionPoint.decide(authzRequest);
            } catch (RuntimeException e) {
                log.warn("Authorization decision point threw; failing closed", e);
                internalErrorDeny(ctx, authzRequest, correlation);
                return;
            }
            if (decisionFuture == null) {
                log.warn("Authorization decision point returned a null future; failing closed");
                internalErrorDeny(ctx, authzRequest, correlation);
                return;
            }

            decisionFuture.onComplete(ar -> {
                if (ar.failed()) {
                    log.warn("Authorization decision point failed", ar.cause());
                    // Fail-closed: an evaluation error still emits one deny event (FR-054). Use the
                    // correlation captured at entry — the callback may run off the request context.
                    emitDecision(
                            authzRequest,
                            AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR),
                            correlation);
                    ctx.fail(ar.cause());
                    return;
                }
                AuthorizationDecision decision = ar.result();
                if (decision == null) {
                    // The contract forbids a null decision; treat it as a fail-closed
                    // INTERNAL_AUTHZ_ERROR deny rather than NPE on emitDecision/permitted() (F-W3).
                    log.warn("Authorization decision point resolved to a null decision; failing closed");
                    internalErrorDeny(ctx, authzRequest, correlation);
                    return;
                }
                emitDecision(authzRequest, decision, correlation);
                if (decision.permitted()) {
                    ctx.next();
                } else {
                    log.debug(
                            "Authorization denied: reasonCode={}, path={}, method={}",
                            decision.reasonCode(),
                            path,
                            method);
                    ctx.fail(403);
                }
            });
        };
    }

    /**
     * Fail-closed deny for the role/scope-only path when the {@link AuthorizationDecisionPoint}
     * violates its contract (throws synchronously, returns a {@code null} future, or resolves to a
     * {@code null} decision). Emits exactly one {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} deny
     * event and fails the request closed with 403 — mirroring
     * {@code ServiceAuthorizationInterceptor.internalErrorDeny} for the services PEP.
     *
     * @param ctx         the routing context to fail closed; must not be {@code null}
     * @param authzRequest the request that was being evaluated, carried on the emitted event; must not
     *                     be {@code null}
     * @param correlation the correlation captured at handler entry; must not be {@code null}
     */
    private void internalErrorDeny(
            RoutingContext ctx, AuthorizationRequest authzRequest, CorrelationContext correlation) {
        emitDecision(authzRequest, AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR), correlation);
        ctx.fail(403);
    }

    // --- @RequiresAction composition (ADR-0113 / ADR-0114) ---

    /**
     * Builds the handler that AND-composes the role/scope gate with the action gate, emitting exactly
     * one combined {@link AuthorizationDecisionEvent} per attempt.
     *
     * <p>The role/scope gate is evaluated first (it carries the {@link AuthorizationRequest} used for
     * the emitted event); the action gate runs only when the role/scope gate permits (fail-fast). A
     * missing {@code SecurityContext} short-circuits to a 401 deny without evaluating either gate,
     * mirroring the role/scope-only path.
     *
     * @param policy the security policy whose role/scope gate participates in the composition; must not
     *               be {@code null}
     * @param action the resolved, registered action gate; must not be {@code null}
     * @return a Vert.x handler enforcing the composed constraints; never {@code null}
     * @throws IllegalStateException if {@code policy} is a {@link SecurityPolicy.Constrained} with both
     *     empty roles and empty scopes
     */
    private Handler<RoutingContext> buildComposedHandler(SecurityPolicy policy, ActionRef action) {
        // Pre-compute the constrained policy context once (also validates the empty-roles/empty-scopes
        // invariant at handler-creation time, matching the role/scope-only path).
        Map<String, Object> policyContext =
                policy instanceof SecurityPolicy.Constrained c ? requireNonEmptyConstrained(c) : Map.of();

        return ctx -> {
            // Capture correlation at handler entry (before any async hop) so an off-context action gate
            // completion (remote PDP / async Authorizer) still emits the inbound correlation (FR-054).
            CorrelationContext correlation = captureCorrelation();
            String path = pathOf(ctx);
            String method = methodOf(ctx);

            SecurityContext secCtx = securityRuntime.current();
            ResourceRef resource = new ResourceRef("route", path, Map.of());
            AuthorizationRequest authzRequest = new AuthorizationRequest(
                    secCtx != null ? secCtx : anonymousStandIn(), method, resource, currentOrigin(), policyContext);

            if (secCtx == null) {
                // Fail-closed: no bound identity → one deny event, neither gate evaluated, 401.
                log.warn("SecurityContext not bound when evaluating @RequiresAction route; treating as 401");
                AuthorizationDecision decision =
                        combinedDecision(AuthorizationDecision.deny(AuthzReasonCodes.AUTHENTICATION_REQUIRED), null);
                emitDecision(authzRequest, decision, correlation);
                ctx.fail(401);
                return;
            }

            // Fail-closed against a contract-violating role/scope gate (mirrors the role/scope-only
            // path): a synchronous throw or a null Future from the decision point must not escape the
            // gate. Both are forbidden by the AuthorizationDecisionPoint contract, but a misbehaving
            // impl must still deny deterministically with exactly one event (F-W3).
            Future<AuthorizationDecision> roleScopeFuture;
            try {
                roleScopeFuture = evaluateRoleScopeGate(policy, authzRequest);
            } catch (RuntimeException e) {
                log.warn("Authorization decision point threw at role/scope gate; failing closed", e);
                internalErrorDenyComposed(ctx, authzRequest, correlation);
                return;
            }
            if (roleScopeFuture == null) {
                log.warn("Authorization decision point returned a null future at role/scope gate; failing closed");
                internalErrorDenyComposed(ctx, authzRequest, correlation);
                return;
            }

            roleScopeFuture.onComplete(roleScopeAr -> {
                if (roleScopeAr.failed()) {
                    // Fail-closed: a role/scope evaluation error denies; the action gate is not reached.
                    log.warn("Authorization decision point failed", roleScopeAr.cause());
                    AuthorizationDecision decision =
                            combinedDecision(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR), null);
                    emitDecision(authzRequest, decision, correlation);
                    ctx.fail(roleScopeAr.cause());
                    return;
                }
                AuthorizationDecision roleScope = roleScopeAr.result();
                if (roleScope == null) {
                    // The contract forbids a null decision; treat it as a fail-closed
                    // INTERNAL_AUTHZ_ERROR deny rather than NPE on roleScope.permitted() (F-W3).
                    log.warn("Authorization decision point resolved to a null role/scope decision; failing closed");
                    internalErrorDenyComposed(ctx, authzRequest, correlation);
                    return;
                }
                if (!roleScope.permitted()) {
                    // First failing predicate is the role/scope gate → action gate not evaluated.
                    AuthorizationDecision decision = combinedDecision(roleScope, null);
                    emitDecision(authzRequest, decision, correlation);
                    log.debug(
                            "Authorization denied at role/scope gate: reasonCode={}, path={}, method={}",
                            roleScope.reasonCode(),
                            path,
                            method);
                    ctx.fail(statusForRoleScope(roleScope));
                    return;
                }

                // Role/scope permitted → evaluate the action gate. authorizer is guaranteed non-null
                // because a present requiredAction implies the engine is installed (slice 11).
                // Built as an explicit 5-arg AuthorizationRequest reusing authzRequest.origin() — the
                // SAME InvocationOrigin captured at handler entry, not a fresh currentOrigin() read
                // here. This callback runs after the role/scope gate's async hop (a remote/async
                // AuthorizationDecisionPoint may resolve its Future off this Vert.x context), where a
                // fresh currentOrigin() read could observe a different (or absent, falling back to
                // "rest") ambient origin than the one the inbound request actually carried — mirrors
                // captureCorrelation()'s entry-capture-once pattern above (W1 residual fix). Not the
                // 3-arg authorize(SecurityContext, ActionRef, ResourceRef) convenience overload, which
                // internally seeds InvocationOrigin.unspecified() — so an origin-aware narrower
                // evaluates the SAME origin the emitted event records (mirrors
                // ServiceAuthorizationInterceptor's 5-arg construction for the services PEP).
                AuthorizationRequest actionRequest =
                        new AuthorizationRequest(secCtx, action.value(), resource, authzRequest.origin(), Map.of());
                // Fail-closed against a contract-violating Authorizer (mirrors
                // ServiceAuthorizationInterceptor): a synchronous throw or a null Future must not
                // escape the gate (F-W3).
                Future<AuthorizationDecision> actionFuture;
                try {
                    actionFuture = authorizer.authorize(actionRequest);
                } catch (RuntimeException e) {
                    log.warn("Authorizer threw at action gate; failing closed", e);
                    internalErrorDenyComposed(ctx, authzRequest, correlation);
                    return;
                }
                if (actionFuture == null) {
                    log.warn("Authorizer returned a null future at action gate; failing closed");
                    internalErrorDenyComposed(ctx, authzRequest, correlation);
                    return;
                }
                actionFuture.onComplete(actionAr -> {
                    AuthorizationDecision actionResult = actionAr.succeeded() ? actionAr.result() : null;
                    // The Authorizer contract forbids a failed future for a normal deny and forbids a
                    // null decision; fail closed (INTERNAL_AUTHZ_ERROR) if a misbehaving impl does either.
                    AuthorizationDecision actionDecision = actionResult != null
                            ? actionResult
                            : AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                    AuthorizationDecision decision = combinedDecision(roleScope, actionDecision);
                    emitDecision(authzRequest, decision, correlation);
                    if (decision.permitted()) {
                        ctx.next();
                    } else {
                        log.debug(
                                "Authorization denied at action gate: reasonCode={}, path={}, method={}",
                                decision.reasonCode(),
                                path,
                                method);
                        ctx.fail(403);
                    }
                });
            });
        };
    }

    /**
     * Fail-closed deny for the composed {@code @RequiresAction} path when a gate violates its contract
     * (the {@link AuthorizationDecisionPoint} or the {@link Authorizer} throws synchronously, returns a
     * {@code null} future, or resolves to a {@code null} decision). Emits exactly one combined deny
     * event whose top-level reason is {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} and fails the
     * request closed with 403 — mirroring {@code ServiceAuthorizationInterceptor.internalErrorDeny}.
     *
     * <p>The action gate is recorded as not evaluated ({@code action == null} into
     * {@link #combinedDecision}), matching the existing role/scope-gate-failure shape.
     *
     * @param ctx         the routing context to fail closed; must not be {@code null}
     * @param authzRequest the request that was being evaluated, carried on the emitted event; must not
     *                     be {@code null}
     * @param correlation the correlation captured at handler entry; must not be {@code null}
     */
    private void internalErrorDenyComposed(
            RoutingContext ctx, AuthorizationRequest authzRequest, CorrelationContext correlation) {
        AuthorizationDecision decision =
                combinedDecision(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR), null);
        emitDecision(authzRequest, decision, correlation);
        ctx.fail(403);
    }

    /**
     * Validates the {@link SecurityPolicy.Constrained} role/scope invariant and returns its policy
     * context map.
     *
     * @param c the constrained policy; must not be {@code null}
     * @return the immutable policy-context map for the decision point
     * @throws IllegalStateException if both roles and scopes are empty
     */
    private static Map<String, Object> requireNonEmptyConstrained(SecurityPolicy.Constrained c) {
        if (c.requiredRoles().isEmpty() && c.requiredScopes().isEmpty()) {
            throw new IllegalStateException("Constrained policy with empty roles and empty scopes for <unknown>"
                    + "; factory should have produced AuthenticatedOnly");
        }
        return buildPolicyContext(c.requiredRoles(), c.requiredScopes(), c.requireAllScopes());
    }

    /**
     * Evaluates the role/scope gate for the composed path, returning a non-blocking
     * {@link Future} carrying the gate's {@link AuthorizationDecision}.
     *
     * <ul>
     *   <li>{@link SecurityPolicy.None} / {@link SecurityPolicy.PermitAll} → automatic permit (the
     *       action gate is the only constraint on an action-only route).</li>
     *   <li>{@link SecurityPolicy.AuthenticatedOnly} → permit when the identity is non-anonymous, else
     *       deny {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED}.</li>
     *   <li>{@link SecurityPolicy.Constrained} → the existing {@link AuthorizationDecisionPoint}.</li>
     *   <li>{@link SecurityPolicy.DenyAll} → not reachable (conflicts with {@code @RequiresAction});
     *       defended as a deny {@link AuthzReasonCodes#DENY_ALL}.</li>
     * </ul>
     *
     * @param policy      the policy whose role/scope gate is being evaluated; must not be {@code null}
     * @param authzRequest the request carrying the bound {@link SecurityContext} (never anonymous
     *                     stand-in here — the caller short-circuits a {@code null} context); must not be
     *                     {@code null}
     * @return a future with the role/scope decision; never a failed future except when an app-provided
     *     {@link AuthorizationDecisionPoint} itself fails
     */
    private Future<AuthorizationDecision> evaluateRoleScopeGate(
            SecurityPolicy policy, AuthorizationRequest authzRequest) {
        return switch (policy) {
            case SecurityPolicy.None ignored ->
                Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            case SecurityPolicy.PermitAll ignored ->
                Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            case SecurityPolicy.DenyAll ignored ->
                Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.DENY_ALL));
            case SecurityPolicy.AuthenticatedOnly ignored -> {
                boolean authenticated =
                        authzRequest.securityContext().identity().actor().type()
                                != dev.vertique.security.PrincipalType.ANONYMOUS;
                yield Future.succeededFuture(
                        authenticated
                                ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                                : AuthorizationDecision.deny(AuthzReasonCodes.AUTHENTICATION_REQUIRED));
            }
            case SecurityPolicy.Constrained ignored -> decisionPoint.decide(authzRequest);
        };
    }

    /**
     * Maps a denied role/scope decision to the HTTP status used on the composed path:
     * {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED} → 401, everything else → 403.
     *
     * @param roleScope the denied role/scope decision; must not be {@code null}
     * @return the HTTP status code for the deny
     */
    private static int statusForRoleScope(AuthorizationDecision roleScope) {
        return AuthzReasonCodes.AUTHENTICATION_REQUIRED.equals(roleScope.reasonCode()) ? 401 : 403;
    }

    /**
     * Combines the role/scope decision and the (optional) action decision into the single decision
     * carried on the one emitted event.
     *
     * <p>The top-level {@code reasonCode} is the <strong>first failing predicate</strong>: the
     * role/scope reason code when role/scope fails (the action gate is then {@code null} — not
     * evaluated); otherwise the action gate's reason code; {@link AuthzReasonCodes#PERMITTED} only when
     * both pass. {@code safeAttributes} record {@code rolesSatisfied}, {@code actionSatisfied}, and
     * {@code actionEvaluated} so audit/observers can see each predicate's outcome.
     *
     * <p>{@code action == null} means the action gate was not evaluated because the role/scope gate
     * already denied, so {@code permitted} is unconditionally {@code false} — the helper stays
     * fail-closed by construction rather than by call-site discipline. A path that legitimately has
     * no action gate at all and may still permit uses {@link #roleScopeOnlyDecision} instead.
     *
     * @param roleScope the role/scope gate decision; must not be {@code null}
     * @param action    the action gate decision, or {@code null} when the action gate was not evaluated
     *                  (role/scope already failed, a missing-context short-circuit, or no action gate
     *                  was required)
     * @return the combined decision; never {@code null}
     */
    private static AuthorizationDecision combinedDecision(
            AuthorizationDecision roleScope, AuthorizationDecision action) {
        boolean rolesSatisfied = roleScope.permitted();
        boolean actionEvaluated = action != null;
        boolean actionSatisfied = action != null && action.permitted();
        boolean permitted = rolesSatisfied && actionSatisfied;

        // First failing predicate wins the top-level reason: role/scope first, then action.
        String reasonCode;
        if (!rolesSatisfied) {
            reasonCode = roleScope.reasonCode();
        } else if (action != null) {
            reasonCode = action.reasonCode();
        } else {
            // Role/scope passed and there is no action decision — only reachable on the permit path
            // of a route whose action gate is absent; the composed path always has an action, so this
            // collapses to the role/scope permit code.
            reasonCode = roleScope.reasonCode();
        }

        Map<String, Object> safeAttributes = Map.of(
                "rolesSatisfied", rolesSatisfied,
                "actionSatisfied", actionSatisfied,
                "actionEvaluated", actionEvaluated);
        return new AuthorizationDecision(permitted, reasonCode, Optional.empty(), Optional.empty(), safeAttributes);
    }

    /**
     * Composes the single decision for an evaluation with <strong>no action gate</strong> — the
     * {@link #decide} counterpart to {@link #combinedDecision}.
     *
     * <p>Kept separate from {@link #combinedDecision} deliberately. That helper's {@code action == null}
     * case means "the action gate was skipped because role/scope already denied", so it must stay
     * fail-closed by construction; here {@code action == null} means "there is no action gate", where a
     * permit is the correct outcome. Folding the two into one helper would make a shared REST/WebSocket
     * authorization primitive permit on a null action decision, which is exactly the fail-open shape the
     * enforcer's F-W3 hardening exists to prevent.
     *
     * <p>Unlike {@link #combinedDecision}, this carries the decision point's own
     * {@code policyId}, {@code policyVersion}, and {@code safeAttributes} through instead of discarding
     * them, matching {@link #buildConstrainedHandler}, which emits the point's decision verbatim on the
     * REST no-action path. The three composition keys are layered on top, so an audit consumer sees the
     * same {@code rolesSatisfied}/{@code actionSatisfied}/{@code actionEvaluated} shape on every
     * {@code decide} event; a decision-point attribute of the same name would be shadowed, which is the
     * intended precedence.
     *
     * @param roleScope the role/scope gate decision; must not be {@code null}
     * @return the decision to emit and return; never {@code null}
     */
    private static AuthorizationDecision roleScopeOnlyDecision(AuthorizationDecision roleScope) {
        Map<String, Object> safeAttributes = new HashMap<>(roleScope.safeAttributes());
        safeAttributes.put("rolesSatisfied", roleScope.permitted());
        safeAttributes.put("actionSatisfied", false);
        safeAttributes.put("actionEvaluated", false);
        return new AuthorizationDecision(
                roleScope.permitted(),
                roleScope.reasonCode(),
                roleScope.policyId(),
                roleScope.policyVersion(),
                Map.copyOf(safeAttributes));
    }

    /**
     * Builds the context map for an {@link AuthorizationRequest} from the constrained policy's
     * required roles, required scopes, and scope-match mode.
     *
     * @param requiredRoles    roles required by the policy
     * @param requiredScopes   scopes required by the policy
     * @param requireAllScopes {@code true} for AND scope semantics; {@code false} for OR
     * @return immutable map suitable for {@link AuthorizationRequest#context()}
     */
    private static Map<String, Object> buildPolicyContext(
            List<String> requiredRoles, List<String> requiredScopes, boolean requireAllScopes) {
        Map<String, Object> ctx = new HashMap<>();
        if (!requiredRoles.isEmpty()) {
            ctx.put(VertxProviderDecisionPoint.CTX_REQUIRED_ROLES, List.copyOf(requiredRoles));
        }
        if (!requiredScopes.isEmpty()) {
            ctx.put(VertxProviderDecisionPoint.CTX_REQUIRED_SCOPES, List.copyOf(requiredScopes));
            ctx.put(VertxProviderDecisionPoint.CTX_REQUIRE_ALL_SCOPES, requireAllScopes);
        }
        return Map.copyOf(ctx);
    }

    // --- Event emission (ADR-0114) ---

    /**
     * Captures the {@link CorrelationContext} bound on the current Vert.x context, falling back to
     * {@link CorrelationContext#unbound()} when nothing is bound.
     *
     * <p>This MUST be called at handler entry — synchronously, before any async hop — so the emitted
     * event reflects the correlation of the inbound request even when the decision resolves on a
     * different context (e.g. a remote-PDP {@link AuthorizationDecisionPoint} whose Future completes
     * off this context). Reading the holder lazily at emit time would observe {@code unbound()} after
     * such a hop, wrongly degrading the event's correlation (FR-054).
     *
     * @return the correlation bound at the call site, or {@link CorrelationContext#unbound()}
     */
    private CorrelationContext captureCorrelation() {
        return contextHolder.current(CorrelationContext.class).orElse(CorrelationContext.unbound());
    }

    /**
     * Returns the ambient {@link InvocationOrigin} bound on {@link #contextHolder} for the current
     * request, falling back to {@link InvocationOrigin#of(String)} {@code "rest"} when nothing is
     * ambient (identity-002 P2.S5b-i).
     *
     * <p>This enforcer is unconditionally the REST authorization boundary, so a missing ambient
     * origin — before {@code IdentityResolutionMiddleware} has installed one, or in a unit test that
     * does not wire the middleware chain — still yields a real REST origin on the emitted
     * {@link AuthorizationRequest} rather than degrading to {@link InvocationOrigin#unspecified()}.
     *
     * @return the ambient invocation origin, or {@code InvocationOrigin.of("rest")}; never
     *         {@code null}
     */
    private InvocationOrigin currentOrigin() {
        return contextHolder.current(InvocationOrigin.class).orElseGet(() -> InvocationOrigin.of("rest"));
    }

    /**
     * Returns the normalized path of the routing context, defaulting to {@code "/"} when unavailable.
     *
     * @param ctx the routing context; must not be {@code null}
     * @return the normalized request path, never {@code null}
     */
    private static String pathOf(RoutingContext ctx) {
        return ctx.normalizedPath() != null ? ctx.normalizedPath() : "/";
    }

    /**
     * Returns the HTTP method name of the routing context's request.
     *
     * @param ctx the routing context; must not be {@code null}
     * @return the request method name (e.g. {@code "GET"}), never {@code null}
     */
    private static String methodOf(RoutingContext ctx) {
        return ctx.request().method().name();
    }

    /**
     * Emits the one {@link AuthorizationDecisionEvent} for a fail-closed short-circuit that has no
     * fully-built {@link AuthorizationRequest} (e.g. {@code @DenyAll}, {@code AuthenticatedOnly} with
     * an anonymous identity, or a constrained route with no bound {@code SecurityContext}).
     *
     * <p>A synthetic {@link AuthorizationRequest} is built from {@code secCtx} so the event still
     * carries the actor; when {@code secCtx} is {@code null} an {@linkplain #anonymousStandIn()
     * anonymous stand-in} is used. The synthetic request records the <em>real</em> route ({@code path}
     * as the resource identifier, {@code method} as the action) so a short-circuit deny identifies the
     * endpoint that denied — matching the constrained path — rather than a {@code route:/} placeholder.
     *
     * @param decision    the deny decision to record; must not be {@code null}
     * @param secCtx      the current security context, or {@code null} when none is bound
     * @param path        the real request path to record as the resource identifier; must not be
     *                    {@code null}
     * @param method      the real HTTP method to record as the action; must not be {@code null}
     * @param correlation the correlation captured at handler entry; must not be {@code null}
     */
    private void emitDecision(
            AuthorizationDecision decision,
            SecurityContext secCtx,
            String path,
            String method,
            CorrelationContext correlation) {
        SecurityContext actor = secCtx != null ? secCtx : anonymousStandIn();
        AuthorizationRequest request = new AuthorizationRequest(
                actor, method, new ResourceRef("route", path, Map.of()), currentOrigin(), Map.of());
        emitDecision(request, decision, correlation);
    }

    /**
     * Emits the one {@link AuthorizationDecisionEvent} for the given request/decision pair. Emission
     * is best-effort and failure-isolated by the emitter (ADR-0062) and never throws.
     *
     * <p>The {@code correlation} is captured by the caller at handler entry (see
     * {@link #captureCorrelation()}) rather than read here, so it reflects the inbound request even
     * when the decision resolved on a different Vert.x context. A missing correlation is supplied as
     * {@link CorrelationContext#unbound()} so it cannot mask the security outcome (FR-054).
     *
     * @param request     the authorization request that was evaluated; must not be {@code null}
     * @param decision    the decision produced; must not be {@code null}
     * @param correlation the correlation captured at handler entry; must not be {@code null}
     */
    private void emitDecision(
            AuthorizationRequest request, AuthorizationDecision decision, CorrelationContext correlation) {
        Optional<RequestOrigin> origin = request.securityContext().origin();
        AuthorizationDecisionEvent event =
                new AuthorizationDecisionEvent(Instant.now(), correlation, origin, request, decision);
        emitter.emit(event);
    }

    /**
     * Builds a minimal anonymous {@link SecurityContext} used only as the actor on a fail-closed
     * event when no real context is bound. It is never returned to callers or used for any
     * authorization decision — only to give the emitted event a non-null actor.
     *
     * @return an anonymous security context stand-in; never {@code null}
     */
    private static SecurityContext anonymousStandIn() {
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new AuthenticatedSecurityContext(
                SecurityIdentity.anonymous(), authentication, AuthorizationClaims.empty(), Optional.empty());
    }
}

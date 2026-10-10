// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.exception.ForbiddenException;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.TimeoutConfig;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.exception.ResilienceException;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.AccessPolicyResolver;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.authz.ClaimAuthorizationPolicy;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.services.ServiceRegistrationViolation;
import dev.vertique.services.config.ServiceAuthorizationConfig;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceRegistrationException;
import io.vertx.core.Future;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Services authorization policy enforcement point (PEP) implemented as a {@link ServiceInterceptor}.
 *
 * <p>For every event-bus service dispatch that targets an operation (or a service class) annotated
 * with {@link RequiresAction}, this interceptor evaluates the declared action against the inbound
 * caller's authenticated identity via the core {@link Authorizer} and emits exactly one
 * {@link AuthorizationDecisionEvent}. An operation that carries a {@link RequiresPolicy} is enforced
 * through its typed policy instead (see the typed-policy section below). A deny — or any evaluation
 * error — produces a failed {@link Future} from {@link #beforeDispatch}, which
 * {@code ServiceMethodInvoker} treats as a short-circuit: the service method is never invoked.
 * Operations with neither pass through unchanged with no authorization work and no event.
 *
 * <h3>Identity binding (security-critical)</h3>
 *
 * <p>The {@link SecurityContext} authorized against is the <em>real propagated caller identity</em>:
 * {@code ServiceMethodInvoker} decodes the inbound dispatch envelope's context map and installs the
 * {@link SecurityContext} (under {@code SecurityContext.class.getName()}) into the request-scoped
 * {@link ContextHolder} <em>before</em> the {@code beforeDispatch} chain runs, on the same dispatch
 * Vert.x context. This interceptor reads it back with {@link ContextHolder#current(Class)}. If no
 * {@link SecurityContext} is bound — i.e. the call crossed the dispatch boundary with no
 * authenticated/propagated identity — the gate <strong>fails closed</strong> with
 * {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED}; it never authorizes an anonymous stand-in.
 *
 * <h3>Fail-closed invariant</h3>
 *
 * <p>Every gated outcome other than an explicit permit results in a failed future:
 * <ul>
 *   <li>missing bound {@link SecurityContext} → deny {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED};</li>
 *   <li>{@link Authorizer} deny → the deny decision's reason code;</li>
 *   <li>{@link Authorizer} that throws, or (against contract) returns a failed future → deny
 *       {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR};</li>
 *   <li>typed policy → denied by its local predicates, or refused with
 *       {@link AuthzReasonCodes#UNSUPPORTED_POLICY_CALLER} for a marked caller, or denied with
 *       {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} when the policy cannot be evaluated.</li>
 * </ul>
 * Each of these emits exactly one event. Event emission is best-effort and failure-isolated by the
 * {@link SecurityEventEmitter}; emission never throws and never alters the dispatch outcome.
 *
 * <h3>Typed access policies</h3>
 *
 * <p>An operation whose annotation lists carry a {@link RequiresPolicy} opts into typed enforcement.
 * Each such operation is resolved once at construction into an immutable gate keyed by its dispatch
 * address; no per-call cache or mutable state exists. At dispatch: a public policy passes with no
 * work and no event; a deny policy refuses; otherwise a missing or anonymous caller is denied with
 * {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED}, and a caller marked by a reconstruction, an
 * identity subject or a delegation is denied with {@link AuthzReasonCodes#UNSUPPORTED_POLICY_CALLER}
 * before any claim or action evaluation. Role and scope requirements are then calculated by
 * {@link ClaimAuthorizationPolicy}; when they pass and the policy declares an action, the
 * {@link Authorizer} is asked once with the declared action only. A policy whose only requirement is
 * an action follows the action path unchanged, so reconstruction and delegation handling stays inside
 * the injected {@link Authorizer}.
 *
 * <p>Every restrictive attempt emits exactly one event. For a local-only policy the request action is
 * the descriptive operation label, never parsed or authorized as an {@link ActionRef}. Any denial, a
 * thrown evaluation or a {@code null}/failed result is a non-recoverable failure; the whole typed
 * branch is exception-safe because an exception escaping {@link #beforeDispatch} would be a plain
 * recoverable failure. Operations with only inline annotations keep the behavior described above.
 * The module reference describes the typed policies in its "Typed access policies" section.
 *
 * <h3>Startup validation (enforcement-implies-audit)</h3>
 *
 * <p>At construction time every registered {@link ServiceMethodMeta} is scanned for
 * {@link RequiresAction} (method- and class-level). Each declared action must parse as an
 * {@link ActionRef} and exist in the {@link ActionRegistry}; if a {@code @RequiresAction} is present
 * but the authorization engine ({@link Authorizer} / {@link ActionRegistry}) is absent from the
 * component, that is itself a violation. Any violation fails fast with a
 * {@link ServiceRegistrationException} so an unenforceable or mistyped action gate can never reach
 * first dispatch (fail-closed invariant; mirrors the REST/WebSocket startup checks).
 *
 * <p>The {@link SecurityEventEmitter} is <strong>always present</strong>: {@code DispatchModule}
 * includes {@code SecurityEventsModule}, so every dispatch graph can emit a decision event even
 * when no observer is contributed (the emitter fans out to an empty observer set). This guarantees
 * <em>enforcement-implies-audit</em> — a gated dispatch always emits its decision event — without
 * making the emitter an optional dependency. When no operation declares {@code @RequiresAction} the
 * interceptor is a pass-through no-op; the authorization engine may then legitimately be absent
 * (injected as {@code Optional<Authorizer>}/{@code Optional<ActionRegistry>}), so a
 * {@code DispatchModule}-only graph with no {@code SecurityAuthzModule} still compiles and runs.
 *
 * <p>The interceptor runs in the {@link ExtensionPhase#SYSTEM_FIRST} phase at a low priority so the
 * action gate is evaluated before application interceptors and before the dispatch pipeline — a deny
 * short-circuits the dispatch before any business logic runs.
 *
 * @see ServiceInterceptor
 * @see Authorizer
 * @see RequiresAction
 */
@Slf4j
@Singleton
public final class ServiceAuthorizationInterceptor implements ServiceInterceptor {

    /** Resource type recorded on the {@link ResourceRef} for a service-dispatch authorization. */
    private static final String RESOURCE_TYPE = "service";

    /** Resilience identity of the authorizer-call fence. */
    private static final AdapterOperationIdentity AUTHORIZER_GATE =
            new AdapterOperationIdentity("services.authz", List.of("gate", "action"));

    private final Authorizer authorizer;

    /** Bounds an authorizer future that has not settled; a settled one is not timed. */
    private final ResiliencePipeline authorizerFence;

    private final SecurityEventEmitter emitter;
    private final ContextHolder contextHolder;

    /** Stateless shared claim calculation applied to typed role and scope requirements. */
    private final ClaimAuthorizationPolicy claimPolicy = new ClaimAuthorizationPolicy();

    /** Immutable typed-policy gates resolved at startup, keyed by the dispatch address. */
    private final Map<String, TypedGate> typedGates;

    /**
     * Creates the services PEP and validates every registered operation's gate at startup: each
     * declared action must exist in the {@link ActionRegistry}, and each typed access policy is
     * resolved once into an immutable gate.
     *
     * @param authorizer       the core action {@link Authorizer}; {@link Optional#empty()} signals the
     *                         authorization engine is not installed. Must not be {@code null}. When a
     *                         registered operation declares {@link RequiresAction} but this is empty,
     *                         construction fails (fail-closed invariant).
     * @param actionRegistry   the {@link ActionRegistry} catalogue; {@link Optional#empty()} signals
     *                         the engine is not installed. Must not be {@code null}. Used to verify each
     *                         declared action exists.
     * @param emitter          the security event emitter used to emit one {@link AuthorizationDecisionEvent}
     *                         per gated dispatch; must not be {@code null}. Always present because
     *                         {@code DispatchModule} includes {@code SecurityEventsModule}
     *                         (enforcement-implies-audit invariant).
     * @param contextHolder    the request-scoped context holder used to read the propagated
     *                         {@link SecurityContext} and {@link CorrelationContext}; must not be {@code null}
     * @param serviceMethodMetas all registered service operation metadata, scanned for
     *                         {@link RequiresAction} and {@link RequiresPolicy}; must not be {@code null}
     * @param authorizationConfig the deadline on each authorizer call; must not be {@code null}
     * @param resilience       the application's resilience runtime, which enforces that deadline; must
     *                         not be {@code null}
     * @throws ServiceRegistrationException if any registered operation declares an unparseable or
     *                         unregistered action, or declares {@link RequiresAction} while the
     *                         authorization engine ({@link Authorizer} / {@link ActionRegistry}) is absent,
     *                         or declares an invalid typed policy
     */
    @Inject
    public ServiceAuthorizationInterceptor(
            Optional<Authorizer> authorizer,
            Optional<ActionRegistry> actionRegistry,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            Set<ServiceMethodMeta> serviceMethodMetas,
            ServiceAuthorizationConfig authorizationConfig,
            Resilience resilience) {
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(authorizationConfig, "authorizationConfig");
        Objects.requireNonNull(resilience, "resilience");
        this.authorizerFence = resilience
                .adapterSupport()
                .pipeline(
                        AUTHORIZER_GATE,
                        new ResolvedResiliencePolicy(
                                Optional.of(TimeoutConfig.ofMillis(authorizationConfig.gateDeadlineMs())),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()));
        Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
        Objects.requireNonNull(serviceMethodMetas, "serviceMethodMetas");
        this.authorizer = authorizer.orElse(null);

        List<ServiceRegistrationViolation> violations = new ArrayList<>();
        Map<String, TypedGate> gates = new HashMap<>();
        validateActionGates(
                serviceMethodMetas, authorizer.orElse(null), actionRegistry.orElse(null), gates, violations);
        if (!violations.isEmpty()) {
            throw new ServiceRegistrationException(violations);
        }
        this.typedGates = Map.copyOf(gates);
    }

    // --- Ordering ---

    /**
     * {@inheritDoc}
     *
     * <p>The action gate is a security enforcement point, so it runs in the
     * {@link ExtensionPhase#SYSTEM_FIRST} phase — before application interceptors and the dispatch
     * pipeline — ensuring a deny short-circuits the dispatch before any business logic executes.
     */
    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A low priority keeps the action gate at the front of the {@link ExtensionPhase#SYSTEM_FIRST}
     * phase relative to other system interceptors.
     */
    @Override
    public int priority() {
        return -100;
    }

    // --- Runtime gate ---

    /**
     * Evaluates the authorization gate for the current dispatch.
     *
     * <p>A dispatch whose annotation lists carry a {@link RequiresPolicy} is enforced by its typed
     * policy gate. Otherwise the effective {@link RequiresAction} is resolved (method-level overrides
     * class-level, per Jakarta semantics); when none is declared, the context passes through
     * unchanged with no authorization work. With an action the propagated {@link SecurityContext} is
     * read from the {@link ContextHolder}; a missing identity fails closed. The {@link Authorizer}
     * decision is enforced fail-closed and emitted as exactly one {@link AuthorizationDecisionEvent}.
     *
     * @param ctx the dispatch context carrying the boot-time-resolved method/class annotations; must
     *            not be {@code null}
     * @return a succeeded future carrying {@code ctx} when the policy or action permits or nothing is
     *     declared; a failed future (carrying a {@link ForbiddenException}) that short-circuits
     *     dispatch on any deny or evaluation error
     */
    @Override
    public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext ctx) {
        if (carriesPolicy(ctx.methodAnnotations()) || carriesPolicy(ctx.classAnnotations())) {
            return beforeTypedDispatch(ctx);
        }
        Optional<ActionRef> action = resolveAction(ctx.methodAnnotations(), ctx.classAnnotations());
        if (action.isEmpty()) {
            return Future.succeededFuture(ctx);
        }
        // Capture correlation and the ambient invocation origin once up front: an async Authorizer
        // may complete on a foreign context where the holder no longer sees these request-scoped
        // values (identity-002 P2.S5b-ii — see currentInvocationOrigin()). When no ambient
        // correlation is bound, mint a joinable UUID context rather than CorrelationContext.unbound()
        // — authorization audit adapters mint sourceEventId from requestId.
        CorrelationContext correlation = contextHolder
                .current(CorrelationContext.class)
                .orElseGet(() -> CorrelationContext.generated("generated:service-authorization"));
        InvocationOrigin origin = currentInvocationOrigin();
        SecurityContext secCtx = contextHolder.current(SecurityContext.class).orElse(null);
        return authorizeAction(ctx, action.get(), secCtx, origin, correlation);
    }

    /**
     * Enforces a declared action for the current dispatch: a missing caller fails closed, otherwise
     * the configured {@link Authorizer} decides and the outcome is emitted as exactly one event.
     *
     * @param ctx         the dispatch context; must not be {@code null}
     * @param actionRef   the declared action; must not be {@code null}
     * @param secCtx      the propagated caller identity, or {@code null} when none is bound
     * @param origin      the invocation origin captured at gate entry; must not be {@code null}
     * @param correlation the correlation captured at gate entry; must not be {@code null}
     * @return a succeeded future carrying {@code ctx} on permit, otherwise a failed future carrying
     *     a non-recoverable {@link ForbiddenException}
     */
    private Future<ServiceDispatchContext> authorizeAction(
            ServiceDispatchContext ctx,
            ActionRef actionRef,
            SecurityContext secCtx,
            InvocationOrigin origin,
            CorrelationContext correlation) {
        if (secCtx == null) {
            // Fail-closed: a @RequiresAction dispatch with no propagated identity is denied; the
            // anonymous stand-in is used only to give the emitted event a non-null actor — it is
            // never authorized against.
            log.warn(
                    "[{}] No SecurityContext bound for @RequiresAction operation {}; denying (fail-closed)",
                    ctx.address(),
                    ctx.operation());
            AuthorizationDecision decision = AuthorizationDecision.deny(AuthzReasonCodes.AUTHENTICATION_REQUIRED);
            emitDecision(
                    decision, AnonymousSecurityContext.INSTANCE, actionRef.value(), Map.of(), ctx, origin, correlation);
            return Future.failedFuture(forbidden(decision, ctx));
        }
        return evaluateAction(ctx, actionRef, secCtx, origin).compose(decision -> {
            try {
                emitDecision(decision, secCtx, actionRef.value(), Map.of(), ctx, origin, correlation);
                if (decision.permitted()) {
                    return Future.succeededFuture(ctx);
                }
                log.debug(
                        "[{}] Authorization denied for action {} on operation {}: reasonCode={}",
                        ctx.address(),
                        actionRef.value(),
                        ctx.operation(),
                        decision.reasonCode());
                return Future.failedFuture(forbidden(decision, ctx));
            } catch (RuntimeException e) {
                // The decision was already emitted; a failure while enforcing it must not escape as a
                // plain failure that an application recoverError could turn into success.
                log.warn("[{}] Enforcing the action decision failed; denying (fail-closed)", ctx.address(), e);
                return Future.failedFuture(
                        forbidden(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR), ctx));
            }
        });
    }

    /**
     * Asks the configured {@link Authorizer} for the declared action. The result never fails and is
     * never {@code null}: a throwing evaluator, a {@code null} future, a failed future or a
     * {@code null} decision all resolve to a deny with {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}.
     * A closing {@code otherwise} keeps the promise even if a mapping step below were to throw.
     *
     * @param ctx       the dispatch context; must not be {@code null}
     * @param actionRef the declared action; must not be {@code null}
     * @param secCtx    the propagated caller identity; must not be {@code null}
     * @param origin    the invocation origin captured at gate entry; must not be {@code null}
     * @return a future that always succeeds with a non-null decision
     */
    private Future<AuthorizationDecision> evaluateAction(
            ServiceDispatchContext ctx, ActionRef actionRef, SecurityContext secCtx, InvocationOrigin origin) {
        ResourceRef resource = resourceRefFor(ctx);
        AuthorizationRequest request = new AuthorizationRequest(secCtx, actionRef.value(), resource, origin, Map.of());
        Future<AuthorizationDecision> decisionFuture;
        try {
            decisionFuture = authorizer.authorize(request);
        } catch (RuntimeException e) {
            // The Authorizer contract forbids throwing, but fail closed if a misbehaving impl does.
            log.warn("[{}] Authorizer threw evaluating action {}; failing closed", ctx.address(), actionRef.value(), e);
            return Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
        }
        if (decisionFuture == null) {
            // The Authorizer contract forbids returning a null future; fail closed rather than let a
            // raw NPE escape the gate (which ServiceMethodInvoker would route through recoverError).
            log.warn(
                    "[{}] Authorizer returned a null future for action {}; failing closed",
                    ctx.address(),
                    actionRef.value());
            return Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
        }
        // An authorizer whose future never settles would hang the dispatch: hand a pending one to the
        // resilience fence, which fails it at the deadline (or once the runtime has closed). A settled
        // future is left alone. A failure of the fence then takes the same fail-closed path below.
        Future<AuthorizationDecision> bounded;
        try {
            bounded = decisionFuture.isComplete() ? decisionFuture : authorizerFence.execute(() -> decisionFuture);
        } catch (RuntimeException e) {
            log.warn("[{}] Authorizer fence failed for action {}; failing closed", ctx.address(), actionRef.value(), e);
            return Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
        }
        return bounded
                // The Authorizer contract forbids a failed future for a normal deny; map a
                // contract-violating failure to a fail-closed INTERNAL_AUTHZ_ERROR deny.
                .otherwise(t -> {
                    if (t instanceof ResilienceException) {
                        // The deadline elapsed or the runtime closed; the exception carries no more.
                        log.warn(
                                "[{}] Authorizer did not answer for action {}; failing closed",
                                ctx.address(),
                                actionRef.value());
                    } else {
                        log.warn(
                                "[{}] Authorizer returned a failed future for action {}; failing closed",
                                ctx.address(),
                                actionRef.value(),
                                t);
                    }
                    return AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                })
                .map(decision -> {
                    if (decision == null) {
                        // The Authorizer contract forbids a null decision; treat it identically to a
                        // failed future — fail-closed INTERNAL_AUTHZ_ERROR deny with exactly one event.
                        log.warn(
                                "[{}] Authorizer resolved to a null decision for action {}; failing closed",
                                ctx.address(),
                                actionRef.value());
                        return AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                    }
                    return decision;
                })
                // A throw inside either lambda above must still resolve to a decision, never to a failed
                // future that a caller could surface as a recoverable failure.
                .otherwise(t -> {
                    log.warn(
                            "[{}] Evaluating action {} failed unexpectedly; failing closed",
                            ctx.address(),
                            actionRef.value(),
                            t);
                    return AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                });
    }

    // --- Typed policy gate ---

    /**
     * Enforces the typed access policy registered for the dispatch address.
     *
     * <p>The whole branch is exception-safe: a {@link RuntimeException} escaping
     * {@link #beforeDispatch} would be wrapped by the interceptor composition as a plain recoverable
     * failure that an application {@code recoverError} could turn into success, so every unexpected
     * failure is converted to a non-recoverable {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} deny.
     *
     * @param ctx the dispatch context whose annotation lists carry a {@link RequiresPolicy}
     * @return a succeeded future carrying {@code ctx} when the policy permits, otherwise a failed
     *     future carrying a non-recoverable {@link ForbiddenException}
     */
    private Future<ServiceDispatchContext> beforeTypedDispatch(ServiceDispatchContext ctx) {
        CorrelationContext correlation = CorrelationContext.unbound();
        InvocationOrigin origin = InvocationOrigin.unspecified();
        SecurityContext caller = null;
        try {
            // Capture everything request-scoped before any asynchronous step.
            correlation = contextHolder.current(CorrelationContext.class).orElse(CorrelationContext.unbound());
            origin = currentInvocationOrigin();
            caller = contextHolder.current(SecurityContext.class).orElse(null);
            return evaluateTyped(ctx, correlation, origin, caller);
        } catch (RuntimeException e) {
            log.warn("[{}] Typed policy evaluation failed; denying (fail-closed)", ctx.address(), e);
            return internalErrorDeny(ctx, caller, origin, correlation);
        }
    }

    private Future<ServiceDispatchContext> evaluateTyped(
            ServiceDispatchContext ctx,
            CorrelationContext correlation,
            InvocationOrigin origin,
            SecurityContext caller) {
        TypedGate gate = typedGates.get(ctx.address());
        if (gate == null) {
            // A dispatch declaring a policy with no resolved gate cannot be enforced: deny closed.
            log.warn("[{}] No typed policy gate registered for operation {}; denying", ctx.address(), ctx.operation());
            return internalErrorDeny(ctx, caller, origin, correlation);
        }
        if (gate.permitAll()) {
            return Future.succeededFuture(ctx);
        }
        if (gate.denyAll()) {
            return localDenial(ctx, gate, caller, AuthzReasonCodes.DENY_ALL, origin, correlation);
        }
        if (caller == null || caller.identity().actor().type() == PrincipalType.ANONYMOUS) {
            return localDenial(ctx, gate, caller, AuthzReasonCodes.AUTHENTICATION_REQUIRED, origin, correlation);
        }
        if (gate.actionOnly()) {
            // The existing action path, including its wrappers around the injected Authorizer;
            // those wrappers, not a local marker check, own reconstruction and delegation.
            return authorizeAction(ctx, gate.action(), caller, origin, correlation);
        }
        if (isMarked(caller)) {
            return localDenial(ctx, gate, caller, AuthzReasonCodes.UNSUPPORTED_POLICY_CALLER, origin, correlation);
        }

        AuthorizationRequest request =
                new AuthorizationRequest(caller, operationLabel(ctx), resourceRefFor(ctx), origin, gate.requirements());
        AuthorizationDecision local = claimPolicy.decide(request);
        if (!local.permitted() || gate.action() == null) {
            return finishTyped(ctx, gate, request, local, null, correlation);
        }
        return evaluateAction(ctx, gate.action(), caller, origin).compose(actionDecision -> {
            try {
                return finishTyped(ctx, gate, request, local, actionDecision, correlation);
            } catch (RuntimeException e) {
                log.warn("[{}] Typed policy completion failed; denying (fail-closed)", ctx.address(), e);
                return internalErrorDeny(ctx, caller, origin, correlation);
            }
        });
    }

    /**
     * Composes the local and action decisions, emits the single event for the attempt and returns
     * the dispatch outcome.
     */
    private Future<ServiceDispatchContext> finishTyped(
            ServiceDispatchContext ctx,
            TypedGate gate,
            AuthorizationRequest request,
            AuthorizationDecision local,
            AuthorizationDecision actionDecision,
            CorrelationContext correlation) {
        AuthorizationDecision decision = composed(local, actionDecision, gate.action() != null);
        emitEvent(request, decision, ctx, correlation);
        if (decision.permitted()) {
            return Future.succeededFuture(ctx);
        }
        log.debug(
                "[{}] Typed policy denied operation {}: reasonCode={}",
                ctx.address(),
                ctx.operation(),
                decision.reasonCode());
        return Future.failedFuture(forbidden(decision, ctx));
    }

    /**
     * Denies before any claim or action evaluation. A missing caller is attributed to the anonymous
     * stand-in actor; a bound caller is the actor of record.
     */
    private Future<ServiceDispatchContext> localDenial(
            ServiceDispatchContext ctx,
            TypedGate gate,
            SecurityContext caller,
            String reasonCode,
            InvocationOrigin origin,
            CorrelationContext correlation) {
        // An action-only policy keeps the event shape of the existing action path.
        AuthorizationDecision denial = AuthorizationDecision.deny(reasonCode);
        AuthorizationDecision decision = gate.actionOnly() ? denial : composed(denial, null, gate.action() != null);
        String label = gate.actionOnly() ? gate.action().value() : operationLabel(ctx);
        SecurityContext actor = caller != null ? caller : AnonymousSecurityContext.INSTANCE;
        emitDecision(decision, actor, label, gate.requirements(), ctx, origin, correlation);
        return Future.failedFuture(forbidden(decision, ctx));
    }

    /**
     * Builds the composed decision carried by the one event of a typed attempt. The top-level reason
     * is the first failing predicate; {@code safeAttributes} record {@code rolesSatisfied},
     * {@code actionSatisfied} and {@code actionEvaluated}.
     *
     * @param local          the local predicate decision; must not be {@code null}
     * @param action         the action decision, or {@code null} when the action was not evaluated
     * @param actionRequired whether the policy declares an action
     * @return the composed decision; never {@code null}
     */
    private static AuthorizationDecision composed(
            AuthorizationDecision local, AuthorizationDecision action, boolean actionRequired) {
        boolean localSatisfied = local.permitted();
        boolean actionEvaluated = action != null;
        boolean actionSatisfied = action != null && action.permitted();
        boolean permitted = localSatisfied && (!actionRequired || actionSatisfied);
        String reasonCode;
        if (!localSatisfied) {
            reasonCode = local.reasonCode();
        } else if (action != null) {
            reasonCode = action.reasonCode();
        } else {
            reasonCode = local.reasonCode();
        }
        Map<String, Object> safeAttributes = Map.of(
                "rolesSatisfied", localSatisfied,
                "actionSatisfied", actionSatisfied,
                "actionEvaluated", actionEvaluated);
        return new AuthorizationDecision(permitted, reasonCode, Optional.empty(), Optional.empty(), safeAttributes);
    }

    /**
     * Whether the caller carries a reconstruction marker, an on-behalf-of subject or a delegation,
     * none of which a local typed predicate supports.
     */
    private static boolean isMarked(SecurityContext caller) {
        return caller.reconstruction().isPresent()
                || caller.identity().subject().isPresent()
                || caller.identity().delegation().isPresent();
    }

    /** Whether the annotation list carries a {@link RequiresPolicy}. */
    private static boolean carriesPolicy(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequiresPolicy) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the descriptive operation label used as the request action of a local-only event. It
     * is audit context only and is never parsed as an {@link ActionRef}.
     */
    private static String operationLabel(ServiceDispatchContext ctx) {
        String operation = ctx.operation();
        return operation != null && !operation.isBlank() ? operation : ctx.address();
    }

    // --- Invocation origin ---

    /**
     * Returns the ambient {@link InvocationOrigin} bound on {@link #contextHolder} for the current
     * dispatch, defaulting to {@link InvocationOrigin#unspecified()} when none is bound — e.g. a
     * legacy dispatch path that bypasses {@code ServiceMethodInvoker}'s inbound-scope install
     * (identity-002 P2.S5b-ii).
     *
     * @return the ambient invocation origin, or {@link InvocationOrigin#unspecified()}; never
     *     {@code null}
     */
    private InvocationOrigin currentInvocationOrigin() {
        return contextHolder.current(InvocationOrigin.class).orElse(InvocationOrigin.unspecified());
    }

    /**
     * Builds the fail-closed {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} deny used when a typed
     * dispatch cannot be evaluated. Emits exactly one {@link AuthorizationDecisionEvent} and returns
     * a failed future carrying the non-recoverable {@link NonRecoverableForbiddenException} that
     * short-circuits dispatch.
     *
     * @param ctx         the dispatch context; must not be {@code null}
     * @param caller      the propagated caller identity, or {@code null} to attribute the event to
     *                    the anonymous stand-in
     * @param origin      the invocation origin captured at gate entry; must not be {@code null}
     * @param correlation the correlation captured at gate entry; must not be {@code null}
     * @return a failed future carrying a {@link NonRecoverableForbiddenException} for the deny
     */
    private Future<ServiceDispatchContext> internalErrorDeny(
            ServiceDispatchContext ctx,
            SecurityContext caller,
            InvocationOrigin origin,
            CorrelationContext correlation) {
        TypedGate gate = typedGates.get(ctx.address());
        AuthorizationDecision decision = AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        boolean actionOnly = gate != null && gate.actionOnly();
        String label = actionOnly ? gate.action().value() : operationLabel(ctx);
        Map<String, Object> requirements = gate != null ? gate.requirements() : Map.of();
        SecurityContext actor = caller != null ? caller : AnonymousSecurityContext.INSTANCE;
        emitDecision(decision, actor, label, requirements, ctx, origin, correlation);
        return Future.failedFuture(forbidden(decision, ctx));
    }

    // --- Action resolution ---

    /**
     * Resolves the effective {@link RequiresAction} from the dispatch context's annotation lists,
     * applying Jakarta override semantics: a method-level annotation completely overrides a
     * class-level one.
     *
     * @param methodAnnotations the boot-time-resolved method annotations; never {@code null}
     * @param classAnnotations  the boot-time-resolved class annotations; never {@code null}
     * @return the resolved action reference, or {@link Optional#empty()} when no {@code @RequiresAction}
     *     is declared at either level
     */
    private static Optional<ActionRef> resolveAction(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        RequiresAction requiresAction = findRequiresAction(methodAnnotations);
        if (requiresAction == null) {
            requiresAction = findRequiresAction(classAnnotations);
        }
        if (requiresAction == null) {
            return Optional.empty();
        }
        return Optional.of(ActionRef.parse(requiresAction.value()));
    }

    /**
     * Finds the first {@link RequiresAction} in the given annotation list.
     *
     * @param annotations the annotation list to search; never {@code null}
     * @return the first {@link RequiresAction}, or {@code null} if absent
     */
    private static RequiresAction findRequiresAction(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequiresAction requiresAction) {
                return requiresAction;
            }
        }
        return null;
    }

    /**
     * Builds the {@link ResourceRef} identifying the dispatch target for the authorization request.
     *
     * @param ctx the dispatch context; never {@code null}
     * @return a {@code service}-typed resource reference identifying the target operation
     */
    private static ResourceRef resourceRefFor(ServiceDispatchContext ctx) {
        String id = ctx.stableTargetId() != null ? ctx.stableTargetId() : ctx.address();
        return new ResourceRef(RESOURCE_TYPE, id, Map.of("operation", ctx.operation()));
    }

    // --- Event emission ---

    /**
     * Builds and emits the single {@link AuthorizationDecisionEvent} for this dispatch attempt.
     * Emission is best-effort and failure-isolated by the {@link SecurityEventEmitter}; this method
     * never throws and never affects the dispatch outcome.
     *
     * @param decision         the decision to record; must not be {@code null}
     * @param actor            the security context to attribute the event to; must not be {@code null}
     * @param action           the request action to record: the evaluated action value, or for a
     *                         local-only typed attempt the descriptive operation label; must not be
     *                         {@code null} or blank
     * @param context          the request context to record; must not be {@code null}
     * @param ctx              the dispatch context; must not be {@code null}
     * @param invocationOrigin the ambient {@link InvocationOrigin} captured at gate entry;
     *                         must not be {@code null}
     * @param correlation      the correlation captured at gate entry; must not be {@code null}
     */
    private void emitDecision(
            AuthorizationDecision decision,
            SecurityContext actor,
            String action,
            Map<String, Object> context,
            ServiceDispatchContext ctx,
            InvocationOrigin invocationOrigin,
            CorrelationContext correlation) {
        try {
            AuthorizationRequest request =
                    new AuthorizationRequest(actor, action, resourceRefFor(ctx), invocationOrigin, context);
            emitEvent(request, decision, ctx, correlation);
        } catch (RuntimeException e) {
            log.warn("[{}] Failed to emit authorization decision event (swallowed)", ctx.address(), e);
        }
    }

    /**
     * Emits the single {@link AuthorizationDecisionEvent} for an already-built request. Never throws.
     *
     * @param request     the request the decision answers; must not be {@code null}
     * @param decision    the decision to record; must not be {@code null}
     * @param ctx         the dispatch context; must not be {@code null}
     * @param correlation the correlation captured at gate entry; must not be {@code null}
     */
    private void emitEvent(
            AuthorizationRequest request,
            AuthorizationDecision decision,
            ServiceDispatchContext ctx,
            CorrelationContext correlation) {
        try {
            // RequestOrigin (network-envelope facts) is distinct from InvocationOrigin (the
            // transport-neutral ingress boundary kind carried on `request`) — see InvocationOrigin's
            // class javadoc.
            Optional<RequestOrigin> requestOrigin = request.securityContext().origin();
            emitter.emit(new AuthorizationDecisionEvent(Instant.now(), correlation, requestOrigin, request, decision));
        } catch (RuntimeException e) {
            log.warn("[{}] Failed to emit authorization decision event (swallowed)", ctx.address(), e);
        }
    }

    /**
     * Builds the fail-closed {@link NonRecoverableForbiddenException} carrying the decision's reason
     * code for a denied or errored gate. This is the failure that short-circuits dispatch.
     *
     * <p>The returned exception is {@link dev.vertique.services.dispatch.NonRecoverableDispatchFailure}
     * so that {@code ServiceMethodInvoker} bypasses the {@code recoverError} chain for it — an
     * authorization denial can never be turned back into a successful dispatch by a permissive
     * application {@code recoverError} (fail-closed invariant).
     *
     * @param decision the deny decision; must not be {@code null}
     * @param ctx      the dispatch context; must not be {@code null}
     * @return a non-recoverable {@link ForbiddenException} describing the deny
     */
    private static ForbiddenException forbidden(AuthorizationDecision decision, ServiceDispatchContext ctx) {
        return new NonRecoverableForbiddenException(
                "Authorization denied for operation " + ctx.operation() + ": " + decision.reasonCode());
    }

    // --- Startup validation ---

    /**
     * Scans all registered operation metadata and validates each gate against the engine,
     * accumulating every problem before the caller fails. Operations whose annotation lists carry a
     * {@link RequiresPolicy} resolve to an immutable typed gate; all other operations keep the
     * inline {@link RequiresAction} validation.
     *
     * @param metas      all registered service operation metadata; never {@code null}
     * @param engine     the {@link Authorizer}, or {@code null} when the engine is absent
     * @param registry   the {@link ActionRegistry}, or {@code null} when the engine is absent
     * @param gates      the accumulator for resolved typed gates keyed by address; never {@code null}
     * @param violations the accumulator to add problems to; never {@code null}
     */
    private static void validateActionGates(
            Set<ServiceMethodMeta> metas,
            Authorizer engine,
            ActionRegistry registry,
            Map<String, TypedGate> gates,
            List<ServiceRegistrationViolation> violations) {
        for (ServiceMethodMeta meta : metas) {
            if (carriesPolicy(meta.methodAnnotations()) || carriesPolicy(meta.classAnnotations())) {
                resolveTypedGate(meta, engine, registry, gates, violations);
                continue;
            }
            RequiresAction requiresAction = findRequiresAction(meta.methodAnnotations());
            if (requiresAction == null) {
                requiresAction = findRequiresAction(meta.classAnnotations());
            }
            if (requiresAction == null) {
                continue;
            }
            validateOne(
                    requiresAction,
                    meta,
                    engine,
                    registry,
                    violations,
                    m -> ServiceRegistrationViolation.ofMethod(
                            meta.method().declaringClass(), meta.method().name(), m));
        }
    }

    /**
     * Resolves one operation's typed policy into an immutable {@link TypedGate}, adding a
     * {@link ServiceRegistrationViolation} for each problem found. A declared action is validated
     * like an inline one; role, scope and authenticated-only policies need no authorization engine.
     *
     * @param meta       the operation metadata; must not be {@code null}
     * @param engine     the {@link Authorizer}, or {@code null} when absent
     * @param registry   the {@link ActionRegistry}, or {@code null} when absent
     * @param gates      the accumulator for resolved gates keyed by address; must not be {@code null}
     * @param violations the accumulator to add problems to; must not be {@code null}
     */
    private static void resolveTypedGate(
            ServiceMethodMeta meta,
            Authorizer engine,
            ActionRegistry registry,
            Map<String, TypedGate> gates,
            List<ServiceRegistrationViolation> violations) {
        List<Annotation> requirements;
        Class<? extends AccessPolicy> policy;
        try {
            Optional<Class<? extends AccessPolicy>> selected =
                    AccessPolicyResolver.select(meta.methodAnnotations(), meta.classAnnotations());
            if (selected.isEmpty()) {
                violations.add(violation(meta, "typed access policy reference could not be selected"));
                return;
            }
            policy = selected.get();
            requirements = AccessPolicyResolver.resolve(policy);
        } catch (IllegalArgumentException e) {
            violations.add(violation(meta, "invalid typed access policy: " + e.getMessage()));
            return;
        }

        int before = violations.size();
        RequiresAction requiresAction = findRequiresAction(requirements);
        if (requiresAction != null) {
            validateOne(requiresAction, meta, engine, registry, violations, m -> violation(meta, m));
        }
        if (violations.size() != before) {
            return;
        }
        TypedGate gate;
        try {
            gate = TypedGate.of(requirements);
        } catch (IllegalArgumentException e) {
            violations.add(violation(meta, "invalid typed access policy " + policy.getName() + ": " + e.getMessage()));
            return;
        }
        TypedGate previous = gates.put(meta.address(), gate);
        if (previous != null && !previous.equals(gate)) {
            violations.add(violation(
                    meta,
                    "address '" + meta.address() + "' resolves to conflicting typed access policies for policy "
                            + policy.getName()));
        }
    }

    /**
     * Builds a violation that names the dispatch identity of the operation. The identity is the
     * dispatch address and the service and operation names, never the declaring class of the
     * contract method, which is a parent interface for an inherited operation.
     *
     * @param meta    the operation metadata; must not be {@code null}
     * @param message the problem description; must not be {@code null}
     * @return a violation whose message leads with the dispatch identity
     */
    private static ServiceRegistrationViolation violation(ServiceMethodMeta meta, String message) {
        String service = meta.namespace() == null || meta.namespace().isBlank()
                ? meta.name()
                : meta.namespace() + "/" + meta.name();
        return ServiceRegistrationViolation.ofImpl("operation '" + meta.operation() + "' of service '" + service
                + "' (address '" + meta.address() + "'): " + message);
    }

    /**
     * Validates one operation's {@link RequiresAction} declaration, adding a
     * {@link ServiceRegistrationViolation} for each problem found.
     *
     * @param requiresAction the declared annotation; must not be {@code null}
     * @param meta           the operation metadata; must not be {@code null}
     * @param engine         the {@link Authorizer}, or {@code null} when absent
     * @param registry       the {@link ActionRegistry}, or {@code null} when absent
     * @param violations     the accumulator to add problems to; must not be {@code null}
     * @param violation      builds the violation for a problem message: the contract and method for an
     *                       inline-only operation, the dispatch identity for a typed one; must not be
     *                       {@code null}
     */
    private static void validateOne(
            RequiresAction requiresAction,
            ServiceMethodMeta meta,
            Authorizer engine,
            ActionRegistry registry,
            List<ServiceRegistrationViolation> violations,
            Function<String, ServiceRegistrationViolation> violation) {
        String value = requiresAction.value();

        if (engine == null || registry == null) {
            violations.add(violation.apply("@RequiresAction(\"" + value
                    + "\") declared but the authorization engine (Authorizer/ActionRegistry) is not installed"));
            return;
        }

        ActionRef actionRef;
        try {
            actionRef = ActionRef.parse(value);
        } catch (RuntimeException e) {
            violations.add(
                    violation.apply("@RequiresAction(\"" + value + "\") is not a valid action: " + e.getMessage()));
            return;
        }
        if (!registry.contains(actionRef)) {
            violations.add(violation.apply(
                    "@RequiresAction(\"" + value + "\") refers to an action not present in the ActionRegistry"));
        }
    }

    // --- Typed gate ---

    /**
     * The immutable, startup-resolved enforcement shape of one typed policy.
     *
     * @param permitAll        the policy is public
     * @param denyAll          the policy denies every caller
     * @param localPredicates  the policy requires authentication, roles or scopes
     * @param action           the declared action, or {@code null} when the policy has none
     * @param requirements     the claim requirement context handed to the claim calculation; empty
     *                         requirement keys are omitted
     */
    record TypedGate(
            boolean permitAll,
            boolean denyAll,
            boolean localPredicates,
            ActionRef action,
            Map<String, Object> requirements) {

        /** Whether the only requirement is an action, which follows the action path unchanged. */
        boolean actionOnly() {
            return action != null && !permitAll && !denyAll && !localPredicates;
        }

        /**
         * Builds the gate from a policy's resolved direct requirements. Role and scope semantics
         * match the REST adapter: any role suffices, scopes are all-of by default and any-of when
         * {@code matchAll} is false.
         *
         * @param requirements the direct requirements of one resolved policy; must not be {@code null}
         * @return the immutable gate
         * @throws IllegalArgumentException if a requirement is not a supported kind, so an
         *     unrecognised requirement is never skipped silently
         */
        static TypedGate of(List<Annotation> requirements) {
            boolean permitAll = false;
            boolean denyAll = false;
            boolean localPredicates = false;
            ActionRef action = null;
            List<String> roles = List.of();
            List<String> scopes = List.of();
            boolean matchAll = true;
            for (Annotation requirement : requirements) {
                if (requirement instanceof PermitAll) {
                    permitAll = true;
                } else if (requirement instanceof DenyAll) {
                    denyAll = true;
                } else if (requirement instanceof RolesAllowed rolesAllowed) {
                    localPredicates = true;
                    roles = List.of(rolesAllowed.value());
                } else if (requirement instanceof Authorized authorized) {
                    localPredicates = true;
                    scopes = List.of(authorized.scopes());
                    matchAll = authorized.matchAll();
                } else if (requirement instanceof RequiresAction requiresAction) {
                    action = ActionRef.parse(requiresAction.value());
                } else {
                    throw new IllegalArgumentException("unsupported policy requirement "
                            + requirement.annotationType().getName());
                }
            }
            Map<String, Object> context = new HashMap<>();
            if (!roles.isEmpty()) {
                context.put(ClaimAuthorizationPolicy.CTX_REQUIRED_ROLES, roles);
            }
            if (!scopes.isEmpty()) {
                context.put(ClaimAuthorizationPolicy.CTX_REQUIRED_SCOPES, scopes);
                context.put(ClaimAuthorizationPolicy.CTX_REQUIRE_ALL_SCOPES, matchAll);
            }
            return new TypedGate(permitAll, denyAll, localPredicates, action, Map.copyOf(context));
        }
    }

    // --- Anonymous stand-in actor ---

    /**
     * Minimal anonymous {@link SecurityContext} used solely as the actor on a fail-closed event when
     * no real context is bound. It is never authorized against and never returned to callers.
     */
    private static final class AnonymousSecurityContext {
        static final SecurityContext INSTANCE = build();

        private AnonymousSecurityContext() {}

        private static SecurityContext build() {
            return new SecurityContext() {
                @Override
                public dev.vertique.security.SecurityIdentity identity() {
                    return dev.vertique.security.SecurityIdentity.anonymous();
                }

                @Override
                public dev.vertique.security.AuthenticationState authentication() {
                    return new dev.vertique.security.AuthenticationState(
                            dev.vertique.security.DefaultAuthMethod.none(),
                            List.of(),
                            Optional.empty(),
                            Optional.empty(),
                            Map.of());
                }

                @Override
                public dev.vertique.security.authz.AuthorizationClaims authorization() {
                    return dev.vertique.security.authz.AuthorizationClaims.empty();
                }

                @Override
                public Optional<RequestOrigin> origin() {
                    return Optional.empty();
                }
            };
        }
    }
}

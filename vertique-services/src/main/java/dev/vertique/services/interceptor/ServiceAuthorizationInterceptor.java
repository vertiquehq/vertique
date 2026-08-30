// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.exception.ForbiddenException;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.services.ServiceRegistrationViolation;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceRegistrationException;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Services {@code @RequiresAction} policy enforcement point (PEP) implemented as a
 * {@link ServiceInterceptor}.
 *
 * <p>For every event-bus service dispatch that targets an operation (or a service class) annotated
 * with {@link RequiresAction}, this interceptor evaluates the declared action against the inbound
 * caller's authenticated identity via the core {@link Authorizer} and emits exactly one
 * {@link AuthorizationDecisionEvent}. A deny — or any evaluation error — produces a failed
 * {@link Future} from {@link #beforeDispatch}, which {@code ServiceMethodInvoker} treats as a
 * short-circuit: the service method is never invoked. Operations with no {@code @RequiresAction}
 * pass through unchanged with no authorization work and no event.
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
 *       {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}.</li>
 * </ul>
 * Each of these emits exactly one event. Event emission is best-effort and failure-isolated by the
 * {@link SecurityEventEmitter}; emission never throws and never alters the dispatch outcome.
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

    private final Authorizer authorizer;
    private final SecurityEventEmitter emitter;
    private final ContextHolder contextHolder;

    /**
     * Creates the services {@code @RequiresAction} PEP and validates every registered operation's
     * action gate at startup.
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
     *                         {@link RequiresAction}; must not be {@code null}
     * @throws ServiceRegistrationException if any registered operation declares an unparseable or
     *                         unregistered action, or declares {@link RequiresAction} while the
     *                         authorization engine ({@link Authorizer} / {@link ActionRegistry}) is absent
     */
    @Inject
    public ServiceAuthorizationInterceptor(
            Optional<Authorizer> authorizer,
            Optional<ActionRegistry> actionRegistry,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            Set<ServiceMethodMeta> serviceMethodMetas) {
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
        Objects.requireNonNull(serviceMethodMetas, "serviceMethodMetas");
        this.authorizer = authorizer.orElse(null);

        validateActionGates(serviceMethodMetas, authorizer.orElse(null), actionRegistry.orElse(null));
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
     * Evaluates the {@link RequiresAction} gate for the current dispatch.
     *
     * <p>Resolves the effective action (method-level overrides class-level, per Jakarta semantics).
     * When no action is declared, the context passes through unchanged with no authorization work.
     * Otherwise the propagated {@link SecurityContext} is read from the {@link ContextHolder}; a
     * missing identity fails closed. The {@link Authorizer} decision is enforced fail-closed and
     * emitted as exactly one {@link AuthorizationDecisionEvent}.
     *
     * @param ctx the dispatch context carrying the boot-time-resolved method/class annotations; must
     *            not be {@code null}
     * @return a succeeded future carrying {@code ctx} when the action is permitted or no action is
     *     declared; a failed future (carrying a {@link ForbiddenException}) that short-circuits
     *     dispatch on any deny or evaluation error
     */
    @Override
    public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext ctx) {
        Optional<ActionRef> action = resolveAction(ctx.methodAnnotations(), ctx.classAnnotations());
        if (action.isEmpty()) {
            return Future.succeededFuture(ctx);
        }
        ActionRef actionRef = action.get();
        // Capture correlation and the ambient invocation origin once up front: an async Authorizer
        // may complete on a foreign context where the holder no longer sees these request-scoped
        // values (identity-002 P2.S5b-ii — see currentInvocationOrigin()).
        CorrelationContext correlation =
                contextHolder.current(CorrelationContext.class).orElse(CorrelationContext.unbound());
        InvocationOrigin origin = currentInvocationOrigin();

        SecurityContext secCtx = contextHolder.current(SecurityContext.class).orElse(null);
        if (secCtx == null) {
            // Fail-closed: a @RequiresAction dispatch with no propagated identity is denied; the
            // anonymous stand-in is used only to give the emitted event a non-null actor — it is
            // never authorized against.
            log.warn(
                    "[{}] No SecurityContext bound for @RequiresAction operation {}; denying (fail-closed)",
                    ctx.address(),
                    ctx.operation());
            AuthorizationDecision decision = AuthorizationDecision.deny(AuthzReasonCodes.AUTHENTICATION_REQUIRED);
            emitDecision(decision, AnonymousSecurityContext.INSTANCE, actionRef, ctx, origin, correlation);
            return Future.failedFuture(forbidden(decision, ctx));
        }

        ResourceRef resource = resourceRefFor(ctx);
        AuthorizationRequest request = new AuthorizationRequest(secCtx, actionRef.value(), resource, origin, Map.of());
        Future<AuthorizationDecision> decisionFuture;
        try {
            decisionFuture = authorizer.authorize(request);
        } catch (RuntimeException e) {
            // The Authorizer contract forbids throwing, but fail closed if a misbehaving impl does.
            log.warn("[{}] Authorizer threw evaluating action {}; failing closed", ctx.address(), actionRef.value(), e);
            return internalErrorDeny(secCtx, actionRef, ctx, origin, correlation);
        }
        if (decisionFuture == null) {
            // The Authorizer contract forbids returning a null future; fail closed rather than let a
            // raw NPE escape the gate (which ServiceMethodInvoker would route through recoverError).
            log.warn(
                    "[{}] Authorizer returned a null future for action {}; failing closed",
                    ctx.address(),
                    actionRef.value());
            return internalErrorDeny(secCtx, actionRef, ctx, origin, correlation);
        }

        return decisionFuture
                // The Authorizer contract forbids a failed future for a normal deny; map a
                // contract-violating failure to a fail-closed INTERNAL_AUTHZ_ERROR deny.
                .otherwise(t -> {
                    log.warn(
                            "[{}] Authorizer returned a failed future for action {}; failing closed",
                            ctx.address(),
                            actionRef.value(),
                            t);
                    return AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                })
                .compose(decision -> {
                    if (decision == null) {
                        // The Authorizer contract forbids a null decision; treat it identically to a
                        // failed future — fail-closed INTERNAL_AUTHZ_ERROR deny with exactly one event.
                        // Do not call decision.permitted()/emitDecision(null, …) on a null decision.
                        log.warn(
                                "[{}] Authorizer resolved to a null decision for action {}; failing closed",
                                ctx.address(),
                                actionRef.value());
                        return internalErrorDeny(secCtx, actionRef, ctx, origin, correlation);
                    }
                    emitDecision(decision, secCtx, actionRef, ctx, origin, correlation);
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
                });
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
     * Builds the fail-closed {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} deny used when a
     * contract-violating {@link Authorizer} throws, returns a {@code null} future, or resolves to a
     * {@code null} decision. Emits exactly one {@link AuthorizationDecisionEvent} and returns a failed
     * future carrying the non-recoverable {@link NonRecoverableForbiddenException} that short-circuits
     * dispatch.
     *
     * @param secCtx      the propagated caller identity to attribute the event to; must not be {@code null}
     * @param actionRef   the action that was being evaluated; must not be {@code null}
     * @param ctx         the dispatch context; must not be {@code null}
     * @param origin      the ambient {@link InvocationOrigin} captured at gate entry; must not be
     *                    {@code null}
     * @param correlation the correlation captured at gate entry; must not be {@code null}
     * @return a failed future carrying a {@link NonRecoverableForbiddenException} for the deny
     */
    private Future<ServiceDispatchContext> internalErrorDeny(
            SecurityContext secCtx,
            ActionRef actionRef,
            ServiceDispatchContext ctx,
            InvocationOrigin origin,
            CorrelationContext correlation) {
        AuthorizationDecision decision = AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        emitDecision(decision, secCtx, actionRef, ctx, origin, correlation);
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
     * @param action           the action that was evaluated; must not be {@code null}
     * @param ctx              the dispatch context; must not be {@code null}
     * @param invocationOrigin the ambient {@link InvocationOrigin} captured at gate entry
     *                         (identity-002 P2.S5b-ii); must not be {@code null}
     * @param correlation      the correlation captured at gate entry; must not be {@code null}
     */
    private void emitDecision(
            AuthorizationDecision decision,
            SecurityContext actor,
            ActionRef action,
            ServiceDispatchContext ctx,
            InvocationOrigin invocationOrigin,
            CorrelationContext correlation) {
        try {
            AuthorizationRequest request =
                    new AuthorizationRequest(actor, action.value(), resourceRefFor(ctx), invocationOrigin, Map.of());
            // RequestOrigin (network-envelope facts) is distinct from InvocationOrigin (the
            // transport-neutral ingress boundary kind carried on `request`) — see InvocationOrigin's
            // class javadoc.
            Optional<RequestOrigin> requestOrigin = actor.origin();
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
     * Scans all registered operation metadata for {@link RequiresAction} and validates each declared
     * action against the engine, accumulating every problem before failing.
     *
     * @param metas    all registered service operation metadata; never {@code null}
     * @param engine   the {@link Authorizer}, or {@code null} when the engine is absent
     * @param registry the {@link ActionRegistry}, or {@code null} when the engine is absent
     * @throws ServiceRegistrationException if any operation declares an unparseable or unregistered
     *     action, or declares {@link RequiresAction} while the engine is absent
     */
    private static void validateActionGates(Set<ServiceMethodMeta> metas, Authorizer engine, ActionRegistry registry) {
        List<ServiceRegistrationViolation> violations = new ArrayList<>();
        for (ServiceMethodMeta meta : metas) {
            RequiresAction requiresAction = findRequiresAction(meta.methodAnnotations());
            if (requiresAction == null) {
                requiresAction = findRequiresAction(meta.classAnnotations());
            }
            if (requiresAction == null) {
                continue;
            }
            validateOne(requiresAction, meta, engine, registry, violations);
        }
        if (!violations.isEmpty()) {
            throw new ServiceRegistrationException(violations);
        }
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
     */
    private static void validateOne(
            RequiresAction requiresAction,
            ServiceMethodMeta meta,
            Authorizer engine,
            ActionRegistry registry,
            List<ServiceRegistrationViolation> violations) {
        Class<?> contract = meta.method().declaringClass();
        String methodName = meta.method().name();
        String value = requiresAction.value();

        if (engine == null || registry == null) {
            violations.add(
                    ServiceRegistrationViolation.ofMethod(
                            contract,
                            methodName,
                            "@RequiresAction(\"" + value
                                    + "\") declared but the authorization engine (Authorizer/ActionRegistry) is not installed"));
            return;
        }

        ActionRef actionRef;
        try {
            actionRef = ActionRef.parse(value);
        } catch (RuntimeException e) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract,
                    methodName,
                    "@RequiresAction(\"" + value + "\") is not a valid action: " + e.getMessage()));
            return;
        }
        if (!registry.contains(actionRef)) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract,
                    methodName,
                    "@RequiresAction(\"" + value + "\") refers to an action not present in the ActionRegistry"));
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

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link SecurityPolicyEnforcer}'s {@code @RequiresAction} composition (slice 12,
 * ADR-0113 / ADR-0114).
 *
 * <p>Verifies the two-argument {@code createHandler(SecurityPolicy, Optional&lt;ActionRef&gt;)} path,
 * which AND-composes the Jakarta role/scope gate with the action gate evaluated by the core
 * {@link Authorizer} and emits exactly one combined {@link AuthorizationDecisionEvent} per attempt:
 *
 * <ul>
 *   <li><strong>Action-only routes</strong> ({@link SecurityPolicy.None} + a present action): the
 *       handler runs the action gate alone (the role/scope gate is a no-op).</li>
 *   <li><strong>AND-composition</strong> ({@link SecurityPolicy.Constrained} + a present action):
 *       permit iff <em>both</em> the role/scope decision and the action decision permit.</li>
 *   <li><strong>Fail-fast reason code</strong> — the top-level {@code reasonCode} is the first failing
 *       predicate; when role/scope fails the action gate is not evaluated
 *       ({@code actionEvaluated=false}).</li>
 *   <li><strong>safeAttributes</strong> carry {@code rolesSatisfied} / {@code actionSatisfied} /
 *       {@code actionEvaluated}.</li>
 *   <li><strong>Exactly one event</strong> is emitted on every composed path.</li>
 *   <li>{@link SecurityPolicy.None} + no action installs no handler; {@link SecurityPolicy.PermitAll}
 *       + no action passes through with no action gate.</li>
 * </ul>
 */
class SecurityPolicyEnforcerActionTest {

    // --- Fixtures ---

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    private static CorrelationContext stubCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    private static ContextHolder holderWith(CorrelationContext correlation) {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    @SuppressWarnings("unchecked")
                    Optional<T> result = (Optional<T>) Optional.of(correlation);
                    return result;
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    /**
     * Builds a {@link SecurityEventEmitter} backed by a single observer that records every
     * {@link AuthorizationDecisionEvent} it receives into {@code sink}.
     */
    private static SecurityEventEmitter capturingEmitter(List<AuthorizationDecisionEvent> sink) {
        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                sink.add(event);
                return Future.succeededFuture();
            }
        };
        return new SecurityEventEmitter(Set.of(observer));
    }

    private static SecurityContext stubSecCtx() {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authorization()).thenReturn(AuthorizationClaims.empty());
        when(ctx.origin()).thenReturn(Optional.empty());
        return ctx;
    }

    /**
     * Builds a non-null {@link SecurityContext} whose actor is anonymous, used to prove the action
     * gate (not a missing-context short-circuit) is the constraint that denies an anonymous caller.
     */
    private static SecurityContext anonymousSecCtx() {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authorization()).thenReturn(AuthorizationClaims.empty());
        when(ctx.origin()).thenReturn(Optional.empty());
        when(ctx.identity()).thenReturn(SecurityIdentity.anonymous());
        return ctx;
    }

    private RoutingContext stubRoutingContext(SecurityContext boundCtx) {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.GET);
        when(rc.request()).thenReturn(req);
        when(rc.normalizedPath()).thenReturn("/api/test");
        when(securityRuntime.current()).thenReturn(boundCtx);
        return rc;
    }

    /** Mock {@link Authorizer} whose 1-arg {@code authorize(AuthorizationRequest)} overload returns {@code decision}. */
    private static Authorizer authorizerReturning(AuthorizationDecision decision) {
        Authorizer authorizer = mock(Authorizer.class);
        when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(Future.succeededFuture(decision));
        return authorizer;
    }

    // --- State ---

    private CorrelationContext correlation;
    private ContextHolder holder;
    private SecurityRuntime securityRuntime;
    private final List<AuthorizationDecisionEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        correlation = stubCorrelation();
        holder = holderWith(correlation);
        securityRuntime = mock(SecurityRuntime.class);
        events.clear();
    }

    private SecurityPolicyEnforcer enforcerWith(AuthorizationDecisionPoint dp, Authorizer authorizer) {
        return new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                capturingEmitter(events),
                holder,
                securityRuntime,
                Optional.ofNullable(authorizer),
                TestResilience.shared());
    }

    private static AuthorizationDecisionPoint permittingDecisionPoint() {
        AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
        when(dp.decide(any()))
                .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
        return dp;
    }

    // --- Action-only routes (SecurityPolicy.None + requiredAction) ---

    @Nested
    @DisplayName("Action-only route (SecurityPolicy.None + requiredAction)")
    class ActionOnly {

        @Test
        @DisplayName("permit → 200 (ctx.next), one event permitted=true PERMITTED")
        void actionOnly_permit_noJakartaPolicy() {
            Authorizer authorizer = authorizerReturning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.None(), Optional.of(CONTENT_READ));
            assertNotNull(handler, "action-only route must install a handler even for SecurityPolicy.None");
            handler.handle(rc);

            verify(rc).next();
            verify(rc, never()).fail(anyInt());
            assertEquals(1, events.size(), "action-only permit must emit exactly one event");
            assertTrue(events.get(0).decision().permitted());
            assertEquals(AuthzReasonCodes.PERMITTED, events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("deny → 403, one event permitted=false ACTION_NOT_ALLOWED")
        void actionOnly_deny_noJakartaPolicy() {
            Authorizer authorizer =
                    authorizerReturning(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(new SecurityPolicy.None(), Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "action-only deny must emit exactly one event");
            assertFalse(events.get(0).decision().permitted());
            assertEquals(
                    AuthzReasonCodes.ACTION_NOT_ALLOWED,
                    events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("safeAttributes: actionSatisfied=true, actionEvaluated=true, rolesSatisfied=true (no role gate)")
        void actionOnly_safeAttributes() {
            Authorizer authorizer = authorizerReturning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(new SecurityPolicy.None(), Optional.of(CONTENT_READ))
                    .handle(rc);

            assertEquals(1, events.size());
            Map<String, Object> attrs = events.get(0).decision().safeAttributes();
            assertEquals(Boolean.TRUE, attrs.get("rolesSatisfied"), "no role gate → rolesSatisfied=true");
            assertEquals(Boolean.TRUE, attrs.get("actionSatisfied"));
            assertEquals(Boolean.TRUE, attrs.get("actionEvaluated"));
        }
    }

    // --- AND-composition (SecurityPolicy.Constrained + requiredAction) ---

    @Nested
    @DisplayName("AND-composition (Constrained + requiredAction)")
    class AndComposition {

        @Test
        @DisplayName("both pass → 200, one event permitted=true")
        void rolesAllowedAndAction_bothPass_permit() {
            Authorizer authorizer = authorizerReturning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).next();
            verify(rc, never()).fail(anyInt());
            assertEquals(1, events.size(), "both-pass must emit exactly one event");
            assertTrue(events.get(0).decision().permitted());
            assertEquals(AuthzReasonCodes.PERMITTED, events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("role fails → 403, reason = role reason, actionEvaluated=false, action gate not called")
        void rolesAllowedAndAction_roleFails_deny() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING)));
            Authorizer authorizer = mock(Authorizer.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "role-fail must emit exactly one event");
            AuthorizationDecision decision = events.get(0).decision();
            assertFalse(decision.permitted());
            assertEquals(
                    AuthzReasonCodes.ROLE_MISSING,
                    decision.reasonCode(),
                    "first failing predicate is the role/scope gate");
            Map<String, Object> attrs = decision.safeAttributes();
            assertEquals(Boolean.FALSE, attrs.get("rolesSatisfied"));
            assertEquals(
                    Boolean.FALSE, attrs.get("actionEvaluated"), "action gate must not be evaluated after role fail");
            // Fail-fast: the action gate is never consulted once the role gate denies.
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("role passes, action fails → 403, reason = ACTION_NOT_ALLOWED, actionEvaluated=true")
        void rolesAllowedAndAction_rolePassActionFail_deny() {
            Authorizer authorizer =
                    authorizerReturning(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "action-fail must emit exactly one event");
            AuthorizationDecision decision = events.get(0).decision();
            assertFalse(decision.permitted());
            assertEquals(AuthzReasonCodes.ACTION_NOT_ALLOWED, decision.reasonCode());
            Map<String, Object> attrs = decision.safeAttributes();
            assertEquals(Boolean.TRUE, attrs.get("rolesSatisfied"));
            assertEquals(Boolean.FALSE, attrs.get("actionSatisfied"));
            assertEquals(Boolean.TRUE, attrs.get("actionEvaluated"));
        }

        @Test
        @DisplayName("action gate receives the resolved ActionRef")
        void rolesAllowedAndAction_actionRefThreaded() {
            Authorizer authorizer = authorizerReturning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(authorizer).authorize(argThat(req -> CONTENT_READ.value().equals(req.action())));
        }
    }

    // --- No-handler and pass-through cases ---

    @Nested
    @DisplayName("No-handler and pass-through")
    class NoHandlerAndPassThrough {

        @Test
        @DisplayName("None + empty action → null handler")
        void noPolicyNoAction_noHandlerCreated() {
            SecurityPolicyEnforcer enforcer =
                    enforcerWith(mock(AuthorizationDecisionPoint.class), mock(Authorizer.class));
            assertNull(enforcer.createHandler(new SecurityPolicy.None(), Optional.empty()));
            assertTrue(events.isEmpty(), "no handler → no event");
        }

        @Test
        @DisplayName("PermitAll + empty action → null handler, no event (no action gate to compose)")
        void permitAll_noAction_handlerPermits() {
            // PermitAll with no @RequiresAction is the established no-handler case (ADR-0114): the
            // route installs no authorization handler and emits nothing. The action-composing path is
            // entered only when a requiredAction is present; PermitAll + @RequiresAction is itself a
            // startup conflict (slice 11), so no PermitAll path ever runs the action gate.
            SecurityPolicyEnforcer enforcer =
                    enforcerWith(mock(AuthorizationDecisionPoint.class), mock(Authorizer.class));

            assertNull(enforcer.createHandler(new SecurityPolicy.PermitAll(), Optional.empty()));
            assertTrue(events.isEmpty(), "PermitAll + no action installs no handler → no event");
        }
    }

    // --- Missing / anonymous identity on the composed path ---

    @Nested
    @DisplayName("Composed path identity gating")
    class ComposedIdentityGating {

        @Test
        @DisplayName("secCtx == null → 401, one AUTHENTICATION_REQUIRED deny, neither gate evaluated")
        void composed_missingSecurityContext_deny401() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            Authorizer authorizer = mock(Authorizer.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer);

            // No SecurityContext bound — securityRuntime.current() returns null.
            RoutingContext rc = stubRoutingContext(null);
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(401);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "missing-context composed path must emit exactly one event");
            AuthorizationDecision decision = events.get(0).decision();
            assertFalse(decision.permitted());
            assertEquals(AuthzReasonCodes.AUTHENTICATION_REQUIRED, decision.reasonCode());
            // Neither gate is consulted when no identity is bound.
            verify(dp, never()).decide(any());
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("action-only route denies an anonymous identity (action gate is the real constraint)")
        void actionOnly_anonymousIdentity_deniedByActionGate() {
            // The action gate denies an anonymous caller; this proves the action gate — not a missing
            // context short-circuit — is the constraint and that anonymous does not slip through.
            Authorizer authorizer =
                    authorizerReturning(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class), authorizer);

            // An anonymous SecurityContext is bound (non-null) — the action gate must still deny it.
            RoutingContext rc = stubRoutingContext(anonymousSecCtx());
            enforcer.createHandler(new SecurityPolicy.None(), Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "anonymous action-only deny must emit exactly one event");
            assertFalse(events.get(0).decision().permitted());
            assertEquals(
                    AuthzReasonCodes.ACTION_NOT_ALLOWED,
                    events.get(0).decision().reasonCode());
            // The action gate WAS evaluated against the anonymous identity (it is the real constraint).
            verify(authorizer).authorize(argThat(req -> CONTENT_READ.value().equals(req.action())));
        }
    }

    // --- Composed role gate through the real decision point ---

    /**
     * Every other composed-path test stubs the role/scope gate, so none can tell which
     * {@link SecurityContext} reached it. These run the real {@link VertxProviderDecisionPoint}, which
     * decides from the request's own security context, and assert that the bound caller — never the
     * anonymous stand-in reserved for the missing-context short-circuit — is what the role gate
     * evaluates, what the action gate receives, and what the audit event records as the actor.
     */
    @Nested
    @DisplayName("Composed path through the real VertxProviderDecisionPoint")
    class ComposedRealDecisionPoint {

        private static final AuthenticationState NO_EVIDENCE_AUTH = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());

        /** The authenticated user {@code "alice"} holding exactly the given ROLE claims. */
        private static SecurityContext alice(String... roles) {
            Set<AuthorityClaim> claims = new HashSet<>();
            for (String role : roles) {
                claims.add(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of()));
            }
            SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "alice", Map.of()));
            return new AuthenticatedSecurityContext(
                    identity, NO_EVIDENCE_AUTH, new AuthorizationClaims(claims, Map.of()), Optional.empty());
        }

        @Test
        @DisplayName("caller holds the role → 200; role gate, action gate, and audit event all see the bound caller")
        void composed_realDecisionPoint_callerHoldsRole_permit() {
            SecurityContext caller = alice("admin");
            Authorizer authorizer = authorizerReturning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            SecurityPolicyEnforcer enforcer = enforcerWith(new VertxProviderDecisionPoint(Set.of()), authorizer);

            RoutingContext rc = stubRoutingContext(caller);
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            // The real role gate permits only if it evaluated the caller's own claims: the anonymous
            // stand-in carries no roles and would have been denied ROLE_MISSING.
            verify(rc).next();
            verify(rc, never()).fail(anyInt());
            assertEquals(1, events.size(), "exactly one combined event");
            AuthorizationDecisionEvent event = events.get(0);
            assertTrue(event.decision().permitted());
            assertEquals(Boolean.TRUE, event.decision().safeAttributes().get("rolesSatisfied"));
            assertSame(caller, event.request().securityContext(), "the audit event must record the bound caller");
            assertEquals(
                    "alice",
                    event.request().securityContext().identity().actor().id());

            ArgumentCaptor<AuthorizationRequest> actionRequest = ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authorizer).authorize(actionRequest.capture());
            assertSame(
                    caller,
                    actionRequest.getValue().securityContext(),
                    "the action gate must evaluate the bound caller");
        }

        @Test
        @DisplayName("caller lacks the role → 403 ROLE_MISSING, action gate not called, audit actor is the caller")
        void composed_realDecisionPoint_callerLacksRole_deny() {
            SecurityContext caller = alice("viewer");
            Authorizer authorizer = mock(Authorizer.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(new VertxProviderDecisionPoint(Set.of()), authorizer);

            RoutingContext rc = stubRoutingContext(caller);
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            // The real role gate denies only if the policy's required roles reached it alongside the
            // caller's claims.
            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "exactly one combined event");
            AuthorizationDecisionEvent event = events.get(0);
            assertFalse(event.decision().permitted());
            assertEquals(AuthzReasonCodes.ROLE_MISSING, event.decision().reasonCode());
            assertEquals(Boolean.FALSE, event.decision().safeAttributes().get("actionEvaluated"));
            assertSame(caller, event.request().securityContext(), "a deny must also record the bound caller");
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }
    }

    // --- Fail-closed hardening (F-W3): contract-violating gates on the composed path ---

    @Nested
    @DisplayName("Composed action-gate fails closed on a contract-violating Authorizer")
    class ComposedActionGateFailClosed {

        @Test
        @DisplayName("authorize() throws synchronously → 403, one INTERNAL_AUTHZ_ERROR deny, next not called")
        void authorizeThrowsSynchronously() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class)))
                    .thenThrow(new RuntimeException("authorizer exploded synchronously"));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a synchronous throw must emit exactly one deny event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("authorize() returns a null Future → 403, one INTERNAL_AUTHZ_ERROR deny, next not called")
        void authorizeReturnsNullFuture() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(null);
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a null future must emit exactly one deny event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("authorize() resolves to a null decision → 403, one INTERNAL_AUTHZ_ERROR deny, next not called")
        void authorizeResolvesNullDecision() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(Future.succeededFuture(null));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a null decision must emit exactly one deny event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
        }
    }

    @Nested
    @DisplayName("Composed role/scope gate fails closed on a contract-violating decision point")
    class ComposedRoleScopeGateFailClosed {

        @Test
        @DisplayName("decide() throws synchronously → 403, one INTERNAL_AUTHZ_ERROR deny, action gate not called")
        void roleScopeDecideThrowsSynchronously() {
            AuthorizationDecisionPoint dp = request -> {
                throw new RuntimeException("decision point exploded synchronously");
            };
            Authorizer authorizer = mock(Authorizer.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a synchronous throw must emit exactly one deny event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("decide() returns a null Future → 403, one INTERNAL_AUTHZ_ERROR deny, action gate not called")
        void roleScopeDecideReturnsNullFuture() {
            AuthorizationDecisionPoint dp = request -> null;
            Authorizer authorizer = mock(Authorizer.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a null future must emit exactly one deny event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName(
                "decide() resolves to a null decision → 403, one INTERNAL_AUTHZ_ERROR deny, action gate not called")
        void roleScopeDecideResolvesNullDecision() {
            AuthorizationDecisionPoint dp = request -> Future.succeededFuture(null);
            Authorizer authorizer = mock(Authorizer.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a null decision must emit exactly one deny event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }
    }

    // --- Exactly-one-event invariant ---

    @Nested
    @DisplayName("Exactly one combined event per attempt")
    class ExactlyOneEvent {

        @Test
        @DisplayName("composed path emits exactly one AuthorizationDecisionEvent")
        void emitsExactlyOneEvent_composedPath() {
            Authorizer authorizer = authorizerReturning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            assertEquals(1, events.size(), "exactly one combined event for the composed permit path");
        }

        @Test
        @DisplayName("event request records the route and the action gate is composed into the single decision")
        void singleEventCarriesCombinedDecision() {
            Authorizer authorizer =
                    authorizerReturning(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
            SecurityPolicyEnforcer enforcer = enforcerWith(permittingDecisionPoint(), authorizer);

            RoutingContext rc = stubRoutingContext(stubSecCtx());
            enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(CONTENT_READ))
                    .handle(rc);

            assertEquals(1, events.size());
            AuthorizationRequest emitted = events.get(0).request();
            assertEquals("/api/test", emitted.resource().id(), "combined event must record the real route path");
        }
    }
}

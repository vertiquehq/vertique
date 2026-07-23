// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the enforcement layer ({@link SecurityPolicyEnforcer}) owns emission and produces
 * <strong>exactly one</strong> {@link AuthorizationDecisionEvent} per authorization attempt, while
 * the {@link AuthorizationDecisionPoint} stays a pure evaluator that emits nothing (ADR-0114).
 *
 * <p>Covers both the happy path (a pure app-provided decision point still yields exactly one
 * enforcer-emitted event) and the fail-closed short-circuits that emit nothing today
 * (`@DenyAll`, anonymous `AuthenticatedOnly`), plus the unbound-correlation sentinel fallback.
 */
class PureDecisionPointExactOnceTest {

    // --- Fixtures ---

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

    private static ContextHolder emptyHolder() {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

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

    private static SecurityContext userSecurityContext() {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.identity()).thenReturn(SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "alice", Map.of())));
        when(ctx.authorization()).thenReturn(AuthorizationClaims.empty());
        when(ctx.origin()).thenReturn(Optional.empty());
        return ctx;
    }

    private static SecurityContext anonymousSecurityContext() {
        SecurityContext ctx = mock(SecurityContext.class);
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

    /**
     * Stubs only the request/method/path on a {@link RoutingContext} (GET {@code /api/test}) without
     * touching {@code securityRuntime}. Short-circuit deny paths read the route from the context for
     * the emitted event, so tests that set {@code securityRuntime.current()} themselves still need
     * the request stubbed.
     */
    private static RoutingContext stubRoute() {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.GET);
        when(rc.request()).thenReturn(req);
        when(rc.normalizedPath()).thenReturn("/api/test");
        return rc;
    }

    // --- State ---

    private List<AuthorizationDecisionEvent> events;
    private ContextHolder holder;
    private SecurityRuntime securityRuntime;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        holder = holderWith(stubCorrelation());
        securityRuntime = mock(SecurityRuntime.class);
    }

    private SecurityPolicyEnforcer enforcerWith(AuthorizationDecisionPoint dp, ContextHolder contextHolder) {
        return new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                capturingEmitter(events),
                contextHolder,
                securityRuntime,
                Optional.empty());
    }

    // --- Tests ---

    @Test
    @DisplayName("app-style pure decision point never emits; enforcer emits exactly one event")
    void appStylePureDecisionPoint_neverEmits() {
        // An app-provided decision point that ONLY returns a decision (the contract since ADR-0114).
        AtomicBoolean decisionPointInvoked = new AtomicBoolean(false);
        AuthorizationDecisionPoint pureDp = request -> {
            decisionPointInvoked.set(true);
            // It has no emitter dependency and emits nothing.
            return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        };
        SecurityPolicyEnforcer enforcer = enforcerWith(pureDp, holder);

        RoutingContext rc = stubRoutingContext(userSecurityContext());
        enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                .handle(rc);

        assertTrue(decisionPointInvoked.get(), "pure decision point must have been consulted");
        assertEquals(1, events.size(), "enforcer must emit exactly one event, not the decision point");
        assertTrue(events.get(0).decision().permitted(), "the single event carries the permit");
    }

    @Test
    @DisplayName("permit path: observer notified exactly once")
    void enforcerEmitsExactlyOnce_permitPath() {
        AuthorizationDecisionPoint dp =
                request -> Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        SecurityPolicyEnforcer enforcer = enforcerWith(dp, holder);

        RoutingContext rc = stubRoutingContext(userSecurityContext());
        enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                .handle(rc);

        assertEquals(1, events.size(), "permit must notify the observer exactly once");
        verify(rc).next();
    }

    @Test
    @DisplayName("@DenyAll short-circuit: observer notified exactly once")
    void enforcerEmitsExactlyOnce_denyAll_shortCircuit() {
        SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class), holder);

        RoutingContext rc = stubRoute();
        when(securityRuntime.current()).thenReturn(null);
        enforcer.createHandler(new SecurityPolicy.DenyAll()).handle(rc);

        assertEquals(1, events.size(), "@DenyAll must notify the observer exactly once");
        assertEquals(AuthzReasonCodes.DENY_ALL, events.get(0).decision().reasonCode());
        verify(rc).fail(403);
    }

    @Test
    @DisplayName("AuthenticatedOnly + anonymous: observer notified exactly once")
    void enforcerEmitsExactlyOnce_authenticatedOnly_anon() {
        SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class), holder);

        // Build the anon stub before the when()-thenReturn chain to avoid nested stubbing.
        SecurityContext anon = anonymousSecurityContext();
        when(securityRuntime.current()).thenReturn(anon);
        RoutingContext rc = stubRoute();
        enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly()).handle(rc);

        assertEquals(1, events.size(), "anonymous AuthenticatedOnly must notify the observer exactly once");
        assertEquals(
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                events.get(0).decision().reasonCode());
        verify(rc).fail(401);
    }

    @Test
    @DisplayName("unbound correlation: event uses the unbound() sentinel without throwing")
    void enforcerEmits_sentinelCorrelation_whenUnbound() {
        AuthorizationDecisionPoint dp =
                request -> Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING));
        SecurityPolicyEnforcer enforcer = enforcerWith(dp, emptyHolder());

        RoutingContext rc = stubRoutingContext(userSecurityContext());
        assertDoesNotThrow(
                () -> enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                        .handle(rc));

        assertEquals(1, events.size(), "must emit exactly one event even with no correlation bound");
        AuthorizationDecisionEvent event = events.get(0);
        assertSame(
                CorrelationContext.unbound(),
                event.correlation(),
                "event must carry the unbound() sentinel when no correlation is bound");
        assertEquals(
                "unavailable", event.correlation().correlationId().value(), "sentinel correlationId is 'unavailable'");
    }
}

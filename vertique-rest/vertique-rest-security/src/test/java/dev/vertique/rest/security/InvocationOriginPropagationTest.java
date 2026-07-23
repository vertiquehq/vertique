// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving {@link SecurityPolicyEnforcer} seeds a real {@link InvocationOrigin} on every
 * {@link AuthorizationRequest} it builds for the constrained-policy REST authorization path
 * (identity-002 P2.S5b-i) — closing the gap where {@link AuthorizationRequest}'s 4-arg convenience
 * constructor left every REST-issued request with {@link InvocationOrigin#unspecified()}.
 */
class InvocationOriginPropagationTest {

    private static CorrelationContext stubCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    /**
     * Builds a {@link ContextHolder} that resolves {@code CorrelationContext} to a fixed value and,
     * when {@code origin} is non-{@code null}, resolves {@link InvocationOrigin} to it as well —
     * everything else resolves empty.
     *
     * @param correlation the correlation context to resolve
     * @param origin      the ambient invocation origin to resolve, or {@code null} to leave it
     *                    unbound (exercising the enforcer's own fallback)
     * @return the stub context holder
     */
    private static ContextHolder holderWith(CorrelationContext correlation, InvocationOrigin origin) {
        return new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    return (Optional<T>) Optional.of(correlation);
                }
                if (type == InvocationOrigin.class && origin != null) {
                    return (Optional<T>) Optional.of(origin);
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
     * Builds a {@link ContextHolder} whose {@code InvocationOrigin} resolution changes across
     * successive calls: the <strong>first</strong> call to {@code current(InvocationOrigin.class)}
     * resolves to {@code first}; every call after that resolves to {@code second} (or unbound when
     * {@code second} is {@code null}). Used to simulate the role/scope gate's async hop landing on a
     * Vert.x context whose ambient origin differs from (or is absent relative to) the one bound at
     * handler entry — the scenario a stale re-read of {@code currentOrigin()} would silently observe.
     *
     * @param correlation the correlation context to resolve on every call
     * @param first       the origin the first call resolves to (the handler-entry read)
     * @param second      the origin every subsequent call resolves to, or {@code null} for unbound
     * @return the stub context holder
     */
    private static ContextHolder changingOriginHolder(
            CorrelationContext correlation, InvocationOrigin first, InvocationOrigin second) {
        AtomicInteger callCount = new AtomicInteger(0);
        return new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    return (Optional<T>) Optional.of(correlation);
                }
                if (type == InvocationOrigin.class) {
                    InvocationOrigin value = callCount.getAndIncrement() == 0 ? first : second;
                    return (Optional<T>) Optional.ofNullable(value);
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    private static SecurityContext stubSecCtx() {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authorization()).thenReturn(AuthorizationClaims.empty());
        when(ctx.origin()).thenReturn(Optional.empty());
        return ctx;
    }

    private static RoutingContext stubRoutingContext(SecurityRuntime securityRuntime, SecurityContext boundCtx) {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.GET);
        when(rc.request()).thenReturn(req);
        when(rc.normalizedPath()).thenReturn("/api/test");
        when(securityRuntime.current()).thenReturn(boundCtx);
        when(rc.user()).thenReturn(mock(User.class));
        return rc;
    }

    private static SecurityPolicyEnforcer buildEnforcer(
            AuthorizationDecisionPoint dp, ContextHolder holder, SecurityRuntime securityRuntime) {
        return new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                holder,
                securityRuntime,
                Optional.empty());
    }

    @Test
    @DisplayName("REST enforcer seeds InvocationOrigin.of(\"rest\") on the constrained-policy AuthorizationRequest"
            + " when no ambient origin is bound")
    void restIngressSeedsRestKind() {
        AtomicReference<AuthorizationRequest> captured = new AtomicReference<>();
        AuthorizationDecisionPoint dp = request -> {
            captured.set(request);
            return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
        };
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        ContextHolder holder = holderWith(stubCorrelation(), null);
        SecurityPolicyEnforcer enforcer = buildEnforcer(dp, holder, securityRuntime);

        RoutingContext rc = stubRoutingContext(securityRuntime, stubSecCtx());
        Handler<RoutingContext> handler =
                enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
        handler.handle(rc);

        assertNotNull(captured.get(), "decision point must have been invoked");
        assertEquals(
                "rest",
                captured.get().origin().kind(),
                "the REST enforcer must seed InvocationOrigin.of(\"rest\") as the fallback when nothing"
                        + " ambient is bound");
    }

    @Test
    @DisplayName("REST enforcer reads an ambient InvocationOrigin over the \"rest\" fallback when one is bound")
    void ambientOriginOverridesRestFallback() {
        AtomicReference<AuthorizationRequest> captured = new AtomicReference<>();
        AuthorizationDecisionPoint dp = request -> {
            captured.set(request);
            return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
        };
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        ContextHolder holder = holderWith(stubCorrelation(), InvocationOrigin.of("camel"));
        SecurityPolicyEnforcer enforcer = buildEnforcer(dp, holder, securityRuntime);

        RoutingContext rc = stubRoutingContext(securityRuntime, stubSecCtx());
        Handler<RoutingContext> handler =
                enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
        handler.handle(rc);

        assertNotNull(captured.get(), "decision point must have been invoked");
        assertEquals(
                "camel",
                captured.get().origin().kind(),
                "an ambient InvocationOrigin bound on the ContextHolder must win over the \"rest\" fallback");
    }

    @Test
    @DisplayName("REST enforcer seeds InvocationOrigin.of(\"rest\") on the @RequiresAction action-gate"
            + " AuthorizationRequest too, not InvocationOrigin.unspecified()")
    void restActionGateEvaluatesWithAmbientOrigin() {
        AtomicReference<AuthorizationRequest> captured = new AtomicReference<>();
        Authorizer authorizer = new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                captured.set(request);
                return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                throw new AssertionError("the action gate must call the 1-arg authorize(AuthorizationRequest)"
                        + " overload so the request carries the ambient InvocationOrigin, not the 3-arg"
                        + " convenience overload that always seeds InvocationOrigin.unspecified()");
            }
        };
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        ContextHolder holder = holderWith(stubCorrelation(), null);
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                holder,
                securityRuntime,
                Optional.of(authorizer));

        RoutingContext rc = stubRoutingContext(securityRuntime, stubSecCtx());
        Handler<RoutingContext> handler =
                enforcer.createHandler(new SecurityPolicy.None(), Optional.of(ActionRef.parse("cms.content.read")));
        handler.handle(rc);

        assertNotNull(captured.get(), "the action gate must have been invoked");
        assertEquals(
                "rest",
                captured.get().origin().kind(),
                "the action gate's AuthorizationRequest must carry InvocationOrigin.of(\"rest\") as the fallback,"
                        + " matching the role/scope gate's origin — not InvocationOrigin.unspecified()");
    }

    @Test
    @DisplayName("action-gate AuthorizationRequest reuses the entry-captured InvocationOrigin, not a re-read that "
            + "could observe a different origin after the role/scope gate's async hop (W1 residual fix)")
    void actionGateOriginStableAcrossAsyncHop() {
        AtomicReference<AuthorizationRequest> captured = new AtomicReference<>();
        Authorizer authorizer = new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                captured.set(request);
                return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                throw new AssertionError("the action gate must call the 1-arg authorize(AuthorizationRequest)"
                        + " overload so the request carries the ambient InvocationOrigin, not the 3-arg"
                        + " convenience overload that always seeds InvocationOrigin.unspecified()");
            }
        };
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        // The ambient InvocationOrigin observed at handler entry is "camel"; a second read of the
        // holder (simulating the role/scope gate's async hop landing on a different Vert.x context)
        // would observe nothing ambient bound — falling back to "rest" — if the enforcer re-read
        // currentOrigin() at the action gate instead of reusing the entry capture.
        ContextHolder holder = changingOriginHolder(stubCorrelation(), InvocationOrigin.of("camel"), null);
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                holder,
                securityRuntime,
                Optional.of(authorizer));

        RoutingContext rc = stubRoutingContext(securityRuntime, stubSecCtx());
        Handler<RoutingContext> handler =
                enforcer.createHandler(new SecurityPolicy.None(), Optional.of(ActionRef.parse("cms.content.read")));
        handler.handle(rc);

        assertNotNull(captured.get(), "the action gate must have been invoked");
        assertEquals(
                "camel",
                captured.get().origin().kind(),
                "the action gate's AuthorizationRequest must carry the origin captured at handler entry, not a"
                        + " re-read that could observe a different (or absent) ambient origin after the"
                        + " role/scope gate's async hop");
    }
}

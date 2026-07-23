// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityPolicyEnforcer}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link SecurityPolicy.None} and {@link SecurityPolicy.PermitAll} produce {@code null} handler</li>
 *   <li>{@link SecurityPolicy.DenyAll} handler always fails with 403</li>
 *   <li>{@link SecurityPolicy.AuthenticatedOnly} handler fails with 401 when no user; calls next otherwise</li>
 *   <li>{@link SecurityPolicy.Constrained} handler routes through {@link AuthorizationDecisionPoint}</li>
 *   <li>Constrained handler: permit → {@code ctx.next()} called</li>
 *   <li>Constrained handler: deny → {@code ctx.fail(403)} called</li>
 *   <li>Constrained handler: no bound SecurityContext → {@code ctx.fail(401)} called</li>
 *   <li>Constrained handler: decision point fails → {@code ctx.fail(cause)} called</li>
 *   <li>Constrained policy with empty roles AND empty scopes → {@link IllegalStateException}</li>
 *   <li>Decision point chain: app override wins, else sync policy wrapped, else default provider</li>
 *   <li>Constructor null argument rejection</li>
 * </ul>
 */
class SecurityPolicyEnforcerTest {

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

    /**
     * A {@link ContextHolder} that returns the bound {@link CorrelationContext} only while
     * {@code boundAtEntry} is {@code true}. Flipping it to {@code false} models the documented
     * remote-PDP pattern: the async {@link AuthorizationDecisionPoint} Future completes off the
     * request's Vert.x context, where {@code current()} no longer sees the request correlation.
     */
    private static final class FlippableHolder implements ContextHolder {
        private final CorrelationContext correlation;
        private volatile boolean boundAtEntry = true;

        FlippableHolder(CorrelationContext correlation) {
            this.correlation = correlation;
        }

        @Override
        public <T> Optional<T> current(Class<T> type) {
            if (type == CorrelationContext.class && boundAtEntry) {
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

    private static SecurityContext stubSecCtx(AuthorizationClaims claims) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authorization()).thenReturn(claims);
        when(ctx.origin()).thenReturn(Optional.empty());
        return ctx;
    }

    private static SecurityContext anonymousSecurityContext() {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.identity()).thenReturn(dev.vertique.security.SecurityIdentity.anonymous());
        return ctx;
    }

    private static SecurityContext userSecurityContext(String userId) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.identity())
                .thenReturn(dev.vertique.security.SecurityIdentity.user(new dev.vertique.security.PrincipalRef(
                        dev.vertique.security.PrincipalType.USER, userId, Map.of())));
        return ctx;
    }

    private RoutingContext stubRoutingContext(SecurityContext boundCtx) {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.GET);
        when(rc.request()).thenReturn(req);
        when(rc.normalizedPath()).thenReturn("/api/test");
        if (boundCtx != null) {
            when(securityRuntime.current()).thenReturn(boundCtx);
            // Simulate a Vert.x user present for AuthenticatedOnly checks
            User user = mock(User.class);
            when(rc.user()).thenReturn(user);
        } else {
            when(securityRuntime.current()).thenReturn(null);
            when(rc.user()).thenReturn(null);
        }
        return rc;
    }

    /**
     * Stubs only the request/method/path on a {@link RoutingContext} (GET {@code /api/test}) without
     * touching {@code securityRuntime}. Short-circuit deny paths (DenyAll, AuthenticatedOnly,
     * missing-context constrained) read the route from the context for the emitted event, so even
     * tests that set {@code securityRuntime.current()} themselves need the request stubbed.
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

    private CorrelationContext correlation;
    private ContextHolder holder;
    private SecurityEventEmitter emitter;
    private SecurityRuntime securityRuntime;

    @BeforeEach
    void setUp() {
        correlation = stubCorrelation();
        holder = holderWith(correlation);
        emitter = new SecurityEventEmitter(Set.of());
        securityRuntime = mock(SecurityRuntime.class);
    }

    private SecurityPolicyEnforcer buildDefaultEnforcer() {
        return new SecurityPolicyEnforcer(
                Optional.empty(), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());
    }

    // --- None and PermitAll ---

    @Nested
    @DisplayName("None and PermitAll policies produce null handler")
    class NullHandlerPolicies {

        @Test
        @DisplayName("None policy → null handler")
        void nonePolicyReturnsNullHandler() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            assertNull(enforcer.createHandler(new SecurityPolicy.None()));
        }

        @Test
        @DisplayName("PermitAll policy → null handler")
        void permitAllPolicyReturnsNullHandler() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            assertNull(enforcer.createHandler(new SecurityPolicy.PermitAll()));
        }
    }

    // --- DenyAll ---

    @Nested
    @DisplayName("DenyAll policy")
    class DenyAllPolicy {

        @Test
        @DisplayName("DenyAll handler always calls ctx.fail(403)")
        void denyAllHandlerFails403() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            io.vertx.core.Handler<RoutingContext> handler = enforcer.createHandler(new SecurityPolicy.DenyAll());
            assertNotNull(handler, "DenyAll must produce a non-null handler");

            RoutingContext rc = stubRoute();
            handler.handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
        }
    }

    // --- AuthenticatedOnly ---

    @Nested
    @DisplayName("AuthenticatedOnly policy")
    class AuthenticatedOnlyPolicy {

        @Test
        @DisplayName("anonymous / no bound SecurityContext → ctx.fail(401)")
        void anonymousFails401() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly());

            // No SecurityContext bound — securityRuntime.current() returns null.
            when(securityRuntime.current()).thenReturn(null);
            RoutingContext rc = stubRoute();
            handler.handle(rc);

            verify(rc).fail(401);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("anonymous identity bound → ctx.fail(401)")
        void anonymousIdentityFails401() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly());

            // Build the stub context before the when()-thenReturn chain to avoid nested stubbing.
            SecurityContext anon = anonymousSecurityContext();
            when(securityRuntime.current()).thenReturn(anon);
            RoutingContext rc = stubRoute();
            handler.handle(rc);

            verify(rc).fail(401);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("non-anonymous identity bound → ctx.next() called")
        void authenticatedIdentityCallsNext() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly());

            SecurityContext authed = userSecurityContext("alice");
            when(securityRuntime.current()).thenReturn(authed);
            // The permit path now emits one event (FR-AUTHZ-050), which reads the route from the
            // context, so the request/path must be stubbed even on the permit path.
            RoutingContext rc = stubRoute();
            handler.handle(rc);

            verify(rc).next();
            verify(rc, never()).fail(anyInt());
        }
    }

    // --- Constrained policy routes through AuthorizationDecisionPoint ---

    @Nested
    @DisplayName("Constrained policy routes through AuthorizationDecisionPoint")
    class ConstrainedPolicy {

        @Test
        @DisplayName("permit decision → ctx.next() called")
        void permitDecisionCallsNext() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any())).thenReturn(Future.succeededFuture(AuthorizationDecision.permit("PERMITTED")));

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());

            SecurityContext secCtx = stubSecCtx(AuthorizationClaims.empty());
            RoutingContext rc = stubRoutingContext(secCtx);

            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
            handler.handle(rc);

            verify(dp).decide(any(AuthorizationRequest.class));
            verify(rc).next();
            verify(rc, never()).fail(anyInt());
        }

        @Test
        @DisplayName("deny decision → ctx.fail(403) called")
        void denyDecisionFails403() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any())).thenReturn(Future.succeededFuture(AuthorizationDecision.deny("ROLE_MISSING")));

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());

            SecurityContext secCtx = stubSecCtx(AuthorizationClaims.empty());
            RoutingContext rc = stubRoutingContext(secCtx);

            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
            handler.handle(rc);

            verify(dp).decide(any(AuthorizationRequest.class));
            verify(rc).fail(403);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("no bound SecurityContext → ctx.fail(401) without calling decision point")
        void noSecurityContextFails401() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());

            RoutingContext rc = stubRoutingContext(null);

            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
            handler.handle(rc);

            verify(rc).fail(401);
            verify(rc, never()).next();
            verify(dp, never()).decide(any());
        }

        @Test
        @DisplayName("decision point fails → ctx.fail(cause) called")
        void decisionPointFailurePropagatesToContext() {
            RuntimeException boom = new RuntimeException("decision point error");
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any())).thenReturn(Future.failedFuture(boom));

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());

            SecurityContext secCtx = stubSecCtx(AuthorizationClaims.empty());
            RoutingContext rc = stubRoutingContext(secCtx);

            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
            handler.handle(rc);

            verify(rc).fail(boom);
            verify(rc, never()).next();
            verify(rc, never()).fail(anyInt());
        }

        @Test
        @DisplayName("empty roles and empty scopes → IllegalStateException at handler creation")
        void emptyRolesAndScopesThrows() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            assertThrows(
                    IllegalStateException.class,
                    () -> enforcer.createHandler(new SecurityPolicy.Constrained(List.of(), List.of(), false)));
        }

        @Test
        @DisplayName("createHandler(Constrained, label) with empty constraints includes label in message")
        void emptyConstraintsIncludesLabel() {
            SecurityPolicyEnforcer enforcer = buildDefaultEnforcer();
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of(), List.of(), false), "myOperation"));
            assertTrue(ex.getMessage().contains("myOperation"), "exception message must include the label");
        }

        @Test
        @DisplayName("AuthorizationRequest passed to decision point contains policy context keys")
        void policyContextPassedToDecisionPoint() {
            AtomicReference<AuthorizationRequest> capturedRequest = new AtomicReference<>();
            AuthorizationDecisionPoint dp = request -> {
                capturedRequest.set(request);
                return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
            };

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());

            SecurityContext secCtx = stubSecCtx(AuthorizationClaims.empty());
            RoutingContext rc = stubRoutingContext(secCtx);

            io.vertx.core.Handler<RoutingContext> handler = enforcer.createHandler(
                    new SecurityPolicy.Constrained(List.of("admin"), List.of("read"), true), "testOp");
            handler.handle(rc);

            assertNotNull(capturedRequest.get(), "decision point must have been called");
            Map<String, Object> ctx = capturedRequest.get().context();
            assertEquals(List.of("admin"), ctx.get(VertxProviderDecisionPoint.CTX_REQUIRED_ROLES));
            assertEquals(List.of("read"), ctx.get(VertxProviderDecisionPoint.CTX_REQUIRED_SCOPES));
            assertEquals(Boolean.TRUE, ctx.get(VertxProviderDecisionPoint.CTX_REQUIRE_ALL_SCOPES));
        }
    }

    // --- Decision point chain selection ---

    @Nested
    @DisplayName("Decision point chain: app override wins")
    class DecisionPointChain {

        @Test
        @DisplayName("app-provided AuthorizationDecisionPoint override is selected")
        void appDecisionPointOverrideWins() {
            AuthorizationDecisionPoint override = mock(AuthorizationDecisionPoint.class);
            AuthorizationPolicy policy = mock(AuthorizationPolicy.class);

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(override),
                    Optional.of(policy),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty());

            assertSame(override, enforcer.decisionPoint(), "app override must win");
            verifyNoInteractions(policy);
        }

        @Test
        @DisplayName("app-provided AuthorizationPolicy is wrapped as SyncPolicyDecisionPoint when no override")
        void appPolicyWrappedAsSyncDecisionPoint() {
            AuthorizationPolicy policy = mock(AuthorizationPolicy.class);

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.of(policy),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty());

            assertInstanceOf(SyncPolicyDecisionPoint.class, enforcer.decisionPoint());
        }

        @Test
        @DisplayName("default VertxProviderDecisionPoint used when no override and no sync policy")
        void defaultVertxProviderDecisionPointUsed() {
            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.empty(), Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty());

            assertInstanceOf(VertxProviderDecisionPoint.class, enforcer.decisionPoint());
        }
    }

    // --- Constructor null rejection ---

    @Nested
    @DisplayName("Constructor null argument rejection")
    class ConstructorNullArguments {

        @Test
        @DisplayName("null authorizationDecisionPoint optional throws NullPointerException")
        void nullDecisionPointOptionalThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            null, Optional.empty(), Set.of(), emitter, holder, securityRuntime, Optional.empty()));
        }

        @Test
        @DisplayName("null authorizationPolicy optional throws NullPointerException")
        void nullPolicyOptionalThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(), null, Set.of(), emitter, holder, securityRuntime, Optional.empty()));
        }

        @Test
        @DisplayName("null authorizationProviders throws NullPointerException")
        void nullProvidersThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(),
                            Optional.empty(),
                            null,
                            emitter,
                            holder,
                            securityRuntime,
                            Optional.empty()));
        }

        @Test
        @DisplayName("null emitter throws NullPointerException")
        void nullEmitterThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(),
                            null,
                            holder,
                            securityRuntime,
                            Optional.empty()));
        }

        @Test
        @DisplayName("null contextHolder throws NullPointerException")
        void nullContextHolderThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(),
                            emitter,
                            null,
                            securityRuntime,
                            Optional.empty()));
        }

        @Test
        @DisplayName("null securityRuntime throws NullPointerException")
        void nullSecurityRuntimeThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(), Optional.empty(), Set.of(), emitter, holder, null, Optional.empty()));
        }

        @Test
        @DisplayName("null authorizer optional throws NullPointerException")
        void nullAuthorizerOptionalThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(), Optional.empty(), Set.of(), emitter, holder, securityRuntime, null));
        }
    }

    // --- Emission ownership (ADR-0114): exactly one event per authorization attempt ---

    @Nested
    @DisplayName("Emission ownership (ADR-0114): exactly one event per attempt")
    class EmissionOwnership {

        private final List<AuthorizationDecisionEvent> events = new ArrayList<>();

        private SecurityPolicyEnforcer enforcerWith(AuthorizationDecisionPoint dp) {
            return new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    capturingEmitter(events),
                    holder,
                    securityRuntime,
                    Optional.empty());
        }

        @Test
        @DisplayName("Constrained permit → exactly one event, permitted=true, PERMITTED")
        void permitEmitsExactlyOnce() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            assertEquals(1, events.size(), "permit must emit exactly one event");
            assertTrue(events.get(0).decision().permitted(), "event must carry the permit decision");
            assertEquals(AuthzReasonCodes.PERMITTED, events.get(0).decision().reasonCode());
            verify(rc).next();
        }

        @Test
        @DisplayName("Constrained deny → exactly one event, permitted=false, deny reason")
        void denyEmitsExactlyOnce() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING)));
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            assertEquals(1, events.size(), "deny must emit exactly one event");
            assertFalse(events.get(0).decision().permitted(), "event must carry the deny decision");
            assertEquals(AuthzReasonCodes.ROLE_MISSING, events.get(0).decision().reasonCode());
            verify(rc).fail(403);
        }

        @Test
        @DisplayName("@DenyAll short-circuit → exactly one deny event with DENY_ALL")
        void denyAllEmitsExactlyOnce() {
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class));

            RoutingContext rc = stubRoute();
            when(securityRuntime.current()).thenReturn(null);
            enforcer.createHandler(new SecurityPolicy.DenyAll()).handle(rc);

            assertEquals(1, events.size(), "@DenyAll must emit exactly one event");
            assertFalse(events.get(0).decision().permitted(), "@DenyAll event must be a deny");
            assertEquals(AuthzReasonCodes.DENY_ALL, events.get(0).decision().reasonCode());
            verify(rc).fail(403);
        }

        @Test
        @DisplayName("AuthenticatedOnly + anonymous → exactly one deny event with AUTHENTICATION_REQUIRED")
        void authenticatedOnlyAnonEmitsExactlyOnce() {
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class));

            SecurityContext anon = anonymousSecurityContext();
            when(securityRuntime.current()).thenReturn(anon);
            RoutingContext rc = stubRoute();
            enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly()).handle(rc);

            assertEquals(1, events.size(), "anonymous AuthenticatedOnly must emit exactly one event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                    events.get(0).decision().reasonCode());
            verify(rc).fail(401);
        }

        @Test
        @DisplayName("AuthenticatedOnly + authenticated → exactly one permit event (PERMITTED)")
        void authenticatedOnlyAuthenticatedEmitsExactlyOne() {
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class));

            SecurityContext authed = userSecurityContext("alice");
            when(securityRuntime.current()).thenReturn(authed);
            // The permit path emits one event (FR-AUTHZ-050), which reads the route from the context.
            RoutingContext rc = stubRoute();
            enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly()).handle(rc);

            assertEquals(1, events.size(), "authenticated AuthenticatedOnly must emit exactly one event");
            assertTrue(events.get(0).decision().permitted(), "permit-path event must be a permit");
            assertEquals(AuthzReasonCodes.PERMITTED, events.get(0).decision().reasonCode());
            verify(rc).next();
        }

        @Test
        @DisplayName("Constrained + missing SecurityContext → exactly one deny event with AUTHENTICATION_REQUIRED")
        void constrainedMissingContextEmitsExactlyOnce() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(null);
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            assertEquals(1, events.size(), "missing-context constrained must emit exactly one event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                    events.get(0).decision().reasonCode());
            verify(rc).fail(401);
            verify(dp, never()).decide(any());
        }

        @Test
        @DisplayName("Constrained + decision-point failure → exactly one deny event with INTERNAL_AUTHZ_ERROR")
        void constrainedDecisionPointFailureEmitsExactlyOnce() {
            RuntimeException boom = new RuntimeException("decision point error");
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any())).thenReturn(Future.failedFuture(boom));
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            assertEquals(1, events.size(), "decision-point failure must emit exactly one event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
            verify(rc).fail(boom);
        }

        @Test
        @DisplayName("None and PermitAll install no handler → no event")
        void noneAndPermitAllEmitNothing() {
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class));

            assertNull(enforcer.createHandler(new SecurityPolicy.None()));
            assertNull(enforcer.createHandler(new SecurityPolicy.PermitAll()));
            assertTrue(events.isEmpty(), "None/PermitAll must emit nothing");
        }

        @Test
        @DisplayName("no CorrelationContext bound → event uses the unbound() sentinel, no exception")
        void unboundCorrelationUsesSentinel() {
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING)));
            // Enforcer with an empty holder — no CorrelationContext is bound.
            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    capturingEmitter(events),
                    emptyHolder(),
                    securityRuntime,
                    Optional.empty());

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            assertDoesNotThrow(
                    () -> enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                            .handle(rc));

            assertEquals(1, events.size(), "must still emit exactly one event when correlation is unbound");
            assertSame(
                    CorrelationContext.unbound(),
                    events.get(0).correlation(),
                    "event must carry the unbound() sentinel when no correlation is bound");
        }

        @Test
        @DisplayName("AuthenticatedOnly + no bound SecurityContext (null) → one deny event, AUTHENTICATION_REQUIRED")
        void authenticatedOnlyNullContextEmitsExactlyOnce() {
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class));

            // securityRuntime.current() returns null — no SecurityContext bound at all (F4 symmetry
            // gap: the suite previously covered only the anonymous-identity branch, not the null one).
            when(securityRuntime.current()).thenReturn(null);
            RoutingContext rc = stubRoute();
            enforcer.createHandler(new SecurityPolicy.AuthenticatedOnly()).handle(rc);

            assertEquals(1, events.size(), "null-context AuthenticatedOnly must emit exactly one event");
            assertFalse(events.get(0).decision().permitted(), "must be a deny");
            assertEquals(
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                    events.get(0).decision().reasonCode());
            // The event's actor falls back to the anonymous stand-in when no context is bound.
            assertSame(
                    dev.vertique.security.PrincipalType.ANONYMOUS,
                    events.get(0).request().securityContext().identity().actor().type(),
                    "null-context deny event must carry the anonymous stand-in actor");
            verify(rc).fail(401);
        }
    }

    // --- Short-circuit route fidelity (F2) ---

    @Nested
    @DisplayName("Short-circuit deny events carry the real route, not a placeholder")
    class ShortCircuitRouteFidelity {

        private final List<AuthorizationDecisionEvent> events = new ArrayList<>();

        private SecurityPolicyEnforcer enforcerWith(AuthorizationDecisionPoint dp) {
            return new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    capturingEmitter(events),
                    holder,
                    securityRuntime,
                    Optional.empty());
        }

        @Test
        @DisplayName("@DenyAll on GET /api/admin → deny event records that method/path, not AUTHORIZE on /")
        void denyAllRecordsRealRoute() {
            SecurityPolicyEnforcer enforcer = enforcerWith(mock(AuthorizationDecisionPoint.class));

            RoutingContext rc = mock(RoutingContext.class);
            HttpServerRequest req = mock(HttpServerRequest.class);
            when(req.method()).thenReturn(HttpMethod.GET);
            when(rc.request()).thenReturn(req);
            when(rc.normalizedPath()).thenReturn("/api/admin");
            when(securityRuntime.current()).thenReturn(null);

            enforcer.createHandler(new SecurityPolicy.DenyAll()).handle(rc);

            assertEquals(1, events.size(), "@DenyAll must emit exactly one event");
            AuthorizationRequest emitted = events.get(0).request();
            assertEquals("GET", emitted.action(), "deny event must record the real HTTP method, not AUTHORIZE");
            assertEquals(
                    "/api/admin",
                    emitted.resource().id(),
                    "deny event must record the real path, not the placeholder /");
            verify(rc).fail(403);
        }
    }

    // --- Fail-closed hardening (F-W3): contract-violating decision points ---

    @Nested
    @DisplayName("Constrained path fails closed on a contract-violating decision point")
    class ConstrainedFailClosed {

        private final List<AuthorizationDecisionEvent> events = new ArrayList<>();

        private SecurityPolicyEnforcer enforcerWith(AuthorizationDecisionPoint dp) {
            return new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    capturingEmitter(events),
                    holder,
                    securityRuntime,
                    Optional.empty());
        }

        @Test
        @DisplayName("decide() throws synchronously → 403, one INTERNAL_AUTHZ_ERROR deny, next not called")
        void decideThrowsSynchronously() {
            AuthorizationDecisionPoint dp = request -> {
                throw new RuntimeException("decision point exploded synchronously");
            };
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
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
        @DisplayName("decide() returns a null Future → 403, one INTERNAL_AUTHZ_ERROR deny, next not called")
        void decideReturnsNullFuture() {
            AuthorizationDecisionPoint dp = request -> null;
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
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
        @DisplayName("decide() resolves to a null decision → 403, one INTERNAL_AUTHZ_ERROR deny, next not called")
        void decideResolvesNullDecision() {
            AuthorizationDecisionPoint dp = request -> Future.succeededFuture(null);
            SecurityPolicyEnforcer enforcer = enforcerWith(dp);

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
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

    // --- Async correlation capture (F1): correlation is captured at handler entry ---

    @Nested
    @DisplayName("Async decision points preserve the correlation bound at handler entry")
    class AsyncCorrelationCapture {

        private final List<AuthorizationDecisionEvent> events = new ArrayList<>();

        @Test
        @DisplayName("Future completes off-context (no correlation bound at completion) → event uses entry correlation")
        void asyncCompletionOffContextUsesEntryCorrelation() {
            FlippableHolder flippableHolder = new FlippableHolder(correlation);

            // Async decision point: hand back a Promise the test completes later, so completion
            // happens after the synchronous handler-entry window — exactly when an off-context
            // remote-PDP Future would resolve.
            io.vertx.core.Promise<AuthorizationDecision> promise = io.vertx.core.Promise.promise();
            AuthorizationDecisionPoint dp = request -> promise.future();

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    capturingEmitter(events),
                    flippableHolder,
                    securityRuntime,
                    Optional.empty());

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            // No event yet — the decision is still pending.
            assertTrue(events.isEmpty(), "no event must be emitted before the decision resolves");

            // Simulate the off-context completion: correlation is no longer visible via current().
            flippableHolder.boundAtEntry = false;
            promise.complete(AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING));

            assertEquals(1, events.size(), "the resolved decision must emit exactly one event");
            assertSame(
                    correlation,
                    events.get(0).correlation(),
                    "event must carry the correlation captured at handler entry, not the unbound() sentinel");
            assertNotSame(
                    CorrelationContext.unbound(),
                    events.get(0).correlation(),
                    "off-context completion must not degrade to unbound() when correlation was bound at entry");
            verify(rc).fail(403);
        }
    }
}

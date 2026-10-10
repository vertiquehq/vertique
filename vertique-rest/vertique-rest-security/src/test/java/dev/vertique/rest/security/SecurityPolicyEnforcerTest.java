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
import dev.vertique.core.correlation.UnboundCorrelationContext;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ResourceRef;
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
import java.util.concurrent.TimeUnit;
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
 *   <li>Constrained handler: decision point fails → {@code ctx.fail(403)} and one deny event; the cause is
 *       logged server-side and never handed to the failure pipeline</li>
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
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                holder,
                securityRuntime,
                Optional.empty(),
                TestResilience.shared());
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
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared());

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
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared());

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
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared());

            RoutingContext rc = stubRoutingContext(null);

            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
            handler.handle(rc);

            verify(rc).fail(401);
            verify(rc, never()).next();
            verify(dp, never()).decide(any());
        }

        @Test
        @DisplayName("decision point fails → ctx.fail(403), cause not propagated")
        void decisionPointFailureIsADeny() {
            RuntimeException boom = new RuntimeException("decision point error");
            AuthorizationDecisionPoint dp = mock(AuthorizationDecisionPoint.class);
            when(dp.decide(any())).thenReturn(Future.failedFuture(boom));

            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared());

            SecurityContext secCtx = stubSecCtx(AuthorizationClaims.empty());
            RoutingContext rc = stubRoutingContext(secCtx);

            io.vertx.core.Handler<RoutingContext> handler =
                    enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
            handler.handle(rc);

            verify(rc).fail(403);
            verify(rc, never()).next();
            verify(rc, never()).fail(any(Throwable.class));
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
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared());

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
                    Optional.empty(),
                    TestResilience.shared());

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
                    Optional.empty(),
                    TestResilience.shared());

            assertInstanceOf(SyncPolicyDecisionPoint.class, enforcer.decisionPoint());
        }

        @Test
        @DisplayName("default VertxProviderDecisionPoint used when no override and no sync policy")
        void defaultVertxProviderDecisionPointUsed() {
            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    emitter,
                    holder,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared());

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
                            null,
                            Optional.empty(),
                            Set.of(),
                            emitter,
                            holder,
                            securityRuntime,
                            Optional.empty(),
                            TestResilience.shared()));
        }

        @Test
        @DisplayName("null authorizationPolicy optional throws NullPointerException")
        void nullPolicyOptionalThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(),
                            null,
                            Set.of(),
                            emitter,
                            holder,
                            securityRuntime,
                            Optional.empty(),
                            TestResilience.shared()));
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
                            Optional.empty(),
                            TestResilience.shared()));
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
                            Optional.empty(),
                            TestResilience.shared()));
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
                            Optional.empty(),
                            TestResilience.shared()));
        }

        @Test
        @DisplayName("null securityRuntime throws NullPointerException")
        void nullSecurityRuntimeThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(),
                            emitter,
                            holder,
                            null,
                            Optional.empty(),
                            TestResilience.shared()));
        }

        @Test
        @DisplayName("null authorizer optional throws NullPointerException")
        void nullAuthorizerOptionalThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityPolicyEnforcer(
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(),
                            emitter,
                            holder,
                            securityRuntime,
                            null,
                            TestResilience.shared()));
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
                    Optional.empty(),
                    TestResilience.shared());
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
            verify(rc).fail(403);
            verify(rc, never()).fail(any(Throwable.class));
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
        @DisplayName("no CorrelationContext bound → event uses a generated joinable correlation, no exception")
        void missingCorrelationMintsGeneratedJoinKey() {
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
                    Optional.empty(),
                    TestResilience.shared());

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            assertDoesNotThrow(
                    () -> enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                            .handle(rc));

            assertEquals(1, events.size(), "must still emit exactly one event when correlation is unbound");
            CorrelationContext correlation = events.get(0).correlation();
            assertNotSame(
                    CorrelationContext.unbound(),
                    correlation,
                    "fail-closed authorization must not record the unbound sentinel as a join key");
            assertNotEquals(
                    UnboundCorrelationContext.SENTINEL_ID_VALUE,
                    correlation.requestId().value(),
                    "requestId must be a real joinable id for audit sourceEventId minting");
            assertEquals(
                    "generated:security-policy-enforcer",
                    correlation.requestId().source(),
                    "generated fallback must tag its source for observability");
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
                    Optional.empty(),
                    TestResilience.shared());
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
                    Optional.empty(),
                    TestResilience.shared());
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
                    Optional.empty(),
                    TestResilience.shared());

            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
            enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            // No event yet — the decision is still pending.
            assertTrue(events.isEmpty(), "no event must be emitted before the decision resolves");

            // Simulate the off-context completion: correlation is no longer visible via current().
            flippableHolder.boundAtEntry = false;
            promise.complete(AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING));

            // The fence settles a still-pending gate on the request's context after a short hop, so the
            // outcome is awaited; the request is failed only after the event is emitted.
            verify(rc, timeout(2_000L)).fail(403);
            assertEquals(1, events.size(), "the resolved decision must emit exactly one event");
            assertSame(
                    correlation,
                    events.get(0).correlation(),
                    "event must carry the correlation captured at handler entry, not the unbound() sentinel");
            assertNotSame(
                    CorrelationContext.unbound(),
                    events.get(0).correlation(),
                    "off-context completion must not degrade to unbound() when correlation was bound at entry");
        }
    }

    // --- Hung gates on the handler paths (REST and WebSocket): bounded by the resilience fence ---

    @Nested
    @DisplayName("Hung gates on the handler paths are bounded and fail closed")
    class HungGateOnHandlerPaths {

        private static final long HUNG_GATE_DEADLINE_MS = 100L;

        /** Comfortably larger than the deadline; the decisive signal is that a deny arrives at all. */
        private static final long DENY_WITHIN_MS = 3_000L;

        private final List<AuthorizationDecisionEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        private SecurityPolicyEnforcer enforcer(AuthorizationDecisionPoint dp, Optional<Authorizer> authorizer) {
            return enforcer(dp, authorizer, HUNG_GATE_DEADLINE_MS, TestResilience.shared());
        }

        private SecurityPolicyEnforcer enforcer(
                AuthorizationDecisionPoint dp,
                Optional<Authorizer> authorizer,
                long deadlineMs,
                Resilience resilience) {
            return new SecurityPolicyEnforcer(
                    Optional.of(dp),
                    Optional.empty(),
                    Set.of(),
                    capturingEmitter(events),
                    holder,
                    securityRuntime,
                    authorizer,
                    Optional.of(new AuthorizationGateConfig(deadlineMs)),
                    resilience);
        }

        private RoutingContext constrainedRoute() {
            return stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));
        }

        private io.vertx.core.Handler<RoutingContext> constrainedHandler(SecurityPolicyEnforcer enforcer) {
            return enforcer.createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false));
        }

        @Test
        @DisplayName("hung decision point, role/scope-only handler → 403, one INTERNAL_AUTHZ_ERROR deny")
        void hungDecisionPointOnTheRoleScopeOnlyHandler() throws Exception {
            AuthorizationDecisionPoint dp = request ->
                    io.vertx.core.Promise.<AuthorizationDecision>promise().future();
            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));

            enforcer(dp, Optional.empty())
                    .createHandler(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false))
                    .handle(rc);

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a hung gate must emit exactly one deny event");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("hung decision point, composed handler → 403, one deny, action gate not evaluated")
        void hungDecisionPointOnTheComposedHandler() throws Exception {
            AuthorizationDecisionPoint dp = request ->
                    io.vertx.core.Promise.<AuthorizationDecision>promise().future();
            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));

            enforcer(dp, Optional.of(authorizerReturningPermit()))
                    .createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(ActionRef.parse("orders.order.read")))
                    .handle(rc);

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a hung gate must emit exactly one deny event");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
            assertEquals(
                    Boolean.FALSE,
                    events.get(0).decision().safeAttributes().get("actionEvaluated"),
                    "the action gate is never reached after a role/scope timeout");
        }

        @Test
        @DisplayName("hung authorizer on the composed handler → 403, one deny, action gate evaluated")
        void hungAuthorizerOnTheComposedHandler() throws Exception {
            AuthorizationDecisionPoint permit =
                    request -> Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            Authorizer hung = new Authorizer() {
                @Override
                public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                    return io.vertx.core.Promise.<AuthorizationDecision>promise()
                            .future();
                }

                @Override
                public Future<AuthorizationDecision> authorize(
                        SecurityContext ctx, ActionRef action, ResourceRef resource) {
                    throw new UnsupportedOperationException("the handler uses the request overload only");
                }
            };
            RoutingContext rc = stubRoutingContext(stubSecCtx(AuthorizationClaims.empty()));

            enforcer(permit, Optional.of(hung))
                    .createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(ActionRef.parse("orders.order.read")))
                    .handle(rc);

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "a hung gate must emit exactly one deny event");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
            assertEquals(
                    Boolean.TRUE,
                    events.get(0).decision().safeAttributes().get("actionEvaluated"),
                    "a timed-out action gate keeps the existing audit shape: it was evaluated");
        }

        @Test
        @DisplayName("a gate that fails with another operation's resilience timeout is a deny like any failure")
        void foreignResilienceTimeoutIsADenyToo() {
            io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
            RoutingContext rc = constrainedRoute();
            constrainedHandler(enforcer(request -> pending.future(), Optional.empty()))
                    .handle(rc);
            dev.vertique.resilience.exception.ResilienceTimeoutException foreign =
                    new dev.vertique.resilience.exception.ResilienceTimeoutException("other-op:" + "0".repeat(64), 1L);

            pending.fail(foreign);

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).fail(any(Throwable.class));
            assertEquals(1, events.size(), "the failure still emits exactly one deny event");
        }

        @Test
        @DisplayName("a gate that fails with an IllegalArgumentException is a 403 deny; its message never reaches "
                + "the failure pipeline")
        void gateIllegalArgumentExceptionIsADenyNotAClientError() {
            RoutingContext rc = constrainedRoute();
            constrainedHandler(enforcer(
                            request -> Future.failedFuture(new IllegalArgumentException("pdp host db-7 refused")),
                            Optional.empty()))
                    .handle(rc);

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).fail(any(Throwable.class));
            verify(rc, never()).next();
            assertEquals(1, events.size());
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    events.get(0).decision().reasonCode());
        }

        @Test
        @DisplayName("a gate that fails with a framework UnavailableException is a 403 deny on the composed handler")
        void gateUnavailableExceptionIsADenyOnTheComposedHandler() {
            RoutingContext rc = constrainedRoute();
            enforcer(
                            request -> Future.failedFuture(
                                    new dev.vertique.core.exception.UnavailableException("policy store unavailable")),
                            Optional.of(authorizerReturningPermit()))
                    .createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(ActionRef.parse("orders.order.read")))
                    .handle(rc);

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).fail(any(Throwable.class));
            assertEquals(1, events.size());
            assertEquals(
                    Boolean.FALSE,
                    events.get(0).decision().safeAttributes().get("actionEvaluated"),
                    "a failed role/scope gate never reaches the action gate");
        }

        @Test
        @DisplayName("a gate that fails with its own plain TimeoutException is denied like a fence timeout")
        void gatesOwnTimeoutExceptionIsDeniedLikeTheFence() {
            io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
            RoutingContext rc = constrainedRoute();
            constrainedHandler(enforcer(request -> pending.future(), Optional.empty()))
                    .handle(rc);

            pending.fail(new java.util.concurrent.TimeoutException("policy client deadline"));

            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size());
        }

        @Test
        @DisplayName("a gate that completes after the deadline changes nothing: one 403, no next(), one event")
        void lateCompletionOfAnAbandonedGateChangesNothing() throws Exception {
            io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
            RoutingContext rc = constrainedRoute();
            constrainedHandler(enforcer(request -> pending.future(), Optional.empty()))
                    .handle(rc);
            verify(rc, timeout(DENY_WITHIN_MS)).fail(403);

            pending.complete(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            Thread.sleep(300L);

            verify(rc, times(1)).fail(403);
            verify(rc, never()).next();
            assertEquals(1, events.size(), "the abandoned gate must not emit a second event");
        }

        @Test
        @DisplayName("a hung handler gate is reported to the resilience observer as a timed-out execution")
        void hungHandlerGateIsReportedToTheResilienceObserver() throws Exception {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                ObservedResilienceEvents observed = new ObservedResilienceEvents();
                Resilience resilience = Resilience.create(vertx, Set.of(observed));
                RoutingContext rc = constrainedRoute();

                constrainedHandler(enforcer(
                                request -> io.vertx.core.Promise.<AuthorizationDecision>promise()
                                        .future(),
                                Optional.empty(),
                                HUNG_GATE_DEADLINE_MS,
                                resilience))
                        .handle(rc);

                verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
                assertEquals(1, observed.timeouts(DENY_WITHIN_MS));
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(DENY_WITHIN_MS, TimeUnit.MILLISECONDS);
            }
        }

        @Test
        @DisplayName("a pending handler gate is denied at once when the resilience runtime has closed")
        void pendingHandlerGateIsDeniedAtOnceWhenTheRuntimeIsClosed() throws Exception {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                Resilience resilience = Resilience.create(vertx);
                // A deadline far longer than the wait below: only the closed runtime can deny in time.
                SecurityPolicyEnforcer enforcer = enforcer(
                        request -> io.vertx.core.Promise.<AuthorizationDecision>promise()
                                .future(),
                        Optional.empty(),
                        60_000L,
                        resilience);
                resilience.close().toCompletionStage().toCompletableFuture().get(DENY_WITHIN_MS, TimeUnit.MILLISECONDS);
                RoutingContext rc = constrainedRoute();

                constrainedHandler(enforcer).handle(rc);

                verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
                assertEquals(1, events.size());
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(DENY_WITHIN_MS, TimeUnit.MILLISECONDS);
            }
        }

        @Test
        @DisplayName("a pending role/scope gate that permits before the deadline still permits: next() once")
        void pendingRoleScopeGateThatPermitsInTimeStillPermits() {
            io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
            RoutingContext rc = constrainedRoute();
            constrainedHandler(
                            enforcer(request -> pending.future(), Optional.empty(), 60_000L, TestResilience.shared()))
                    .handle(rc);

            pending.complete(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

            verify(rc, timeout(DENY_WITHIN_MS)).next();
            verify(rc, never()).fail(anyInt());
            assertEquals(1, events.size());
            assertTrue(events.get(0).decision().permitted());
        }

        @Test
        @DisplayName("a pending action gate that permits before the deadline still permits: next() once, "
                + "action evaluated")
        void pendingActionGateThatPermitsInTimeStillPermits() {
            AuthorizationDecisionPoint permit =
                    request -> Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
            Authorizer pendingAuthorizer = new Authorizer() {
                @Override
                public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                    return pending.future();
                }

                @Override
                public Future<AuthorizationDecision> authorize(
                        SecurityContext ctx, ActionRef action, ResourceRef resource) {
                    throw new UnsupportedOperationException("the handler uses the request overload only");
                }
            };
            RoutingContext rc = constrainedRoute();
            enforcer(permit, Optional.of(pendingAuthorizer), 60_000L, TestResilience.shared())
                    .createHandler(
                            new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                            Optional.of(ActionRef.parse("orders.order.read")))
                    .handle(rc);

            pending.complete(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

            verify(rc, timeout(DENY_WITHIN_MS)).next();
            verify(rc, never()).fail(anyInt());
            assertEquals(1, events.size());
            assertTrue(events.get(0).decision().permitted());
            assertEquals(Boolean.TRUE, events.get(0).decision().safeAttributes().get("actionEvaluated"));
        }

        @Test
        @DisplayName("a handler gate pending when the runtime closes is denied once; a late permit changes nothing")
        void handlerGatePendingWhenTheRuntimeClosesIsDeniedOnce() throws Exception {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                Resilience resilience = Resilience.create(vertx);
                io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
                RoutingContext rc = constrainedRoute();
                constrainedHandler(enforcer(request -> pending.future(), Optional.empty(), 60_000L, resilience))
                        .handle(rc);

                resilience.close().toCompletionStage().toCompletableFuture().get(DENY_WITHIN_MS, TimeUnit.MILLISECONDS);
                verify(rc, timeout(DENY_WITHIN_MS)).fail(403);
                pending.complete(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
                Thread.sleep(200L);

                verify(rc, times(1)).fail(403);
                verify(rc, never()).next();
                assertEquals(1, events.size());
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(DENY_WITHIN_MS, TimeUnit.MILLISECONDS);
            }
        }

        private Authorizer authorizerReturningPermit() {
            return new Authorizer() {
                @Override
                public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                    return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
                }

                @Override
                public Future<AuthorizationDecision> authorize(
                        SecurityContext ctx, ActionRef action, ResourceRef resource) {
                    throw new UnsupportedOperationException("the handler uses the request overload only");
                }
            };
        }
    }
}

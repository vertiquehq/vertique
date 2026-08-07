// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.resolver.IdentityResolutionException;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.IdentitySnapshotContext;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link IdentityResolutionMiddleware}.
 *
 * <p>Verifies the full handler pipeline: rejection draining, identity chain resolution, MDC
 * enrichment, SecurityContext binding, and lifecycle event emission.
 *
 * <p>All tests use a minimal in-process Vert.x HTTP server to exercise the handler in a real
 * routing context, including the {@link RequestContextLifecycle} dependency.
 */
@ExtendWith(VertxExtension.class)
class IdentityResolutionMiddlewareTest {

    private HttpServer server;
    private HttpClient client;

    // --- Test-local SecurityRuntime ---

    /**
     * Minimal {@link SecurityRuntime} that captures the last bound {@link SecurityContext} for
     * in-test assertion while delegating actual storage to {@link ContextValues}.
     */
    private static class CapturingSecurityRuntime implements SecurityRuntime {

        private final AtomicReference<SecurityContext> captured = new AtomicReference<>();

        @Override
        public SecurityContext current() {
            return ContextValues.current(SecurityContext.class).orElse(null);
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext ctx) {
            captured.set(ctx);
            return ContextValues.bind(SecurityContext.class, ctx);
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext ctx, boolean secure) {
            return null;
        }

        SecurityContext getCaptured() {
            return captured.get();
        }
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> sc = server != null ? server.close() : Future.succeededFuture();
        Future<?> cc = client != null ? client.close() : Future.succeededFuture();
        Future.join(sc, cc).onComplete(ar -> ctx.completeNow());
    }

    // --- Helpers ---

    private static void installLifecycle(Router router, String path) {
        router.route(path).handler(new RequestContextLifecycle());
    }

    /** A no-op stub {@link ContextHolder} that never resolves any value. */
    private static final ContextHolder EMPTY_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /** A stub {@link ContextHolder} that always returns a fixed {@link CorrelationContext}. */
    private static ContextHolder holderWith(CorrelationContext correlation) {
        return new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    return Optional.of((T) correlation);
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    // --- Constructor / startup guard ---

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Duplicate (priority, id) resolvers throw IllegalStateException at construction")
        void duplicateResolverThrows() {
            SecurityIdentityResolver r1 = new SecurityIdentityResolver() {
                @Override
                public int priority() {
                    return 50;
                }

                @Override
                public String id() {
                    return "shared-id";
                }

                @Override
                public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
                    return Future.succeededFuture(Optional.empty());
                }
            };
            SecurityIdentityResolver r2 = new SecurityIdentityResolver() {
                @Override
                public int priority() {
                    return 50;
                }

                @Override
                public String id() {
                    return "shared-id";
                }

                @Override
                public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
                    return Future.succeededFuture(Optional.empty());
                }
            };

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> new IdentityResolutionMiddleware(
                            Set.of(r1, r2),
                            Optional.of(new DefaultSecurityClaimMapper()),
                            emitter,
                            runtime,
                            EMPTY_HOLDER));
            assertTrue(
                    ex.getMessage().contains("Duplicate SecurityIdentityResolver"),
                    "message should mention duplicate: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("priority=50"), "message should include priority: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("id=shared-id"), "message should include id: " + ex.getMessage());
        }

        @Test
        @DisplayName(
                "Duplicate (priority, id) across different phases is still detected (non-adjacent after phase sort)")
        void crossPhaseDuplicateDetected() {
            // OrderedExtension sorts phase-first, so two resolvers sharing (priority, id) but
            // differing in phase are NOT adjacent once a third resolver sorts between them. The
            // (priority, id) uniqueness contract (NFR-ID-003) must still fail loudly.
            SecurityIdentityResolver systemDup = new PhasedResolver(ExtensionPhase.SYSTEM_FIRST, 50, "shared-id");
            SecurityIdentityResolver appBetween = new PhasedResolver(ExtensionPhase.APPLICATION, 10, "between");
            SecurityIdentityResolver appDup = new PhasedResolver(ExtensionPhase.APPLICATION, 50, "shared-id");

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> new IdentityResolutionMiddleware(
                            Set.of(systemDup, appBetween, appDup),
                            Optional.of(new DefaultSecurityClaimMapper()),
                            emitter,
                            runtime,
                            EMPTY_HOLDER));
            assertTrue(
                    ex.getMessage().contains("Duplicate SecurityIdentityResolver"),
                    "message should mention duplicate: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("id=shared-id"), "message should include id: " + ex.getMessage());
        }
    }

    /** A {@link SecurityIdentityResolver} stub with configurable phase, priority, and id. */
    private record PhasedResolver(ExtensionPhase phase, int prio, String resolverId)
            implements SecurityIdentityResolver {
        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return prio;
        }

        @Override
        public String id() {
            return resolverId;
        }

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
            return Future.succeededFuture(Optional.empty());
        }
    }

    // --- Anonymous path ---

    @Nested
    @DisplayName("Anonymous request (no evidence)")
    class AnonymousPath {

        @Test
        @DisplayName("Empty evidence resolves to ANONYMOUS; no CredentialAcceptedEvent emitted")
        void emptyEvidenceResolvesToAnonymous(Vertx vertx, VertxTestContext ctx) {
            SecurityEventEmitter emitter = spy(new SecurityEventEmitter(Set.of()));
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals(PrincipalType.ANONYMOUS, sc.identity().actor().type());
                assertEquals("anonymous", sc.identity().actor().id());
                assertEquals(
                        AuthMethodKind.NONE, sc.authentication().primaryMethod().normalizedKind());
                assertTrue(sc.authentication().evidence().isEmpty());

                // No CredentialAcceptedEvent for anonymous
                verify(emitter, never()).emit(any(CredentialAcceptedEvent.class));

                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }
    }

    // --- Authenticated path ---

    @Nested
    @DisplayName("Authenticated request (with evidence)")
    class AuthenticatedPath {

        @Test
        @DisplayName("Evidence present resolves identity; CredentialAcceptedEvent emitted with correct payload")
        void evidencePresentEmitsAcceptedEvent(Vertx vertx, VertxTestContext ctx) {
            CorrelationContext correlation = new CorrelationContextFactory(Optional.empty())
                    .create(
                            new dev.vertique.core.correlation.CorrelationIdentifier("req-123", "test"),
                            new dev.vertique.core.correlation.CorrelationIdentifier("cor-123", "test"));
            SecurityEventEmitter emitter = spy(new SecurityEventEmitter(Set.of()));
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();

            AtomicReference<CredentialAcceptedEvent> capturedEvent = new AtomicReference<>();
            doAnswer(inv -> {
                        capturedEvent.set(inv.getArgument(0));
                        return Future.succeededFuture();
                    })
                    .when(emitter)
                    .emit(any(CredentialAcceptedEvent.class));

            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    holderWith(correlation));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");

            // Append JWT evidence with sub attribute
            AuthMethod jwtMethod = DefaultAuthMethod.jwt();
            router.route("/test").handler(rc -> {
                AuthenticationEvidence evidence = new AuthenticationEvidence(
                        jwtMethod,
                        Optional.of("alice"),
                        Instant.now(),
                        Optional.empty(),
                        new dev.vertique.security.verification.CustomVerificationSource("test", Map.of()),
                        Map.of("sub", "alice"));
                RestAuthenticationEvidence.append(rc, evidence);
                // Set ctx.user() so claimMapper can be invoked
                ((UserContextInternal) rc.userContext())
                        .setUser(
                                User.create(new JsonObject().put("sub", "alice").put("scope", "read")));
                rc.next();
            });
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals(PrincipalType.USER, sc.identity().actor().type());
                assertEquals("alice", sc.identity().actor().id());
                assertEquals(
                        AuthMethodKind.JWT, sc.authentication().primaryMethod().normalizedKind());
                assertEquals(1, sc.authentication().evidence().size());

                verify(emitter).emit(any(CredentialAcceptedEvent.class));
                CredentialAcceptedEvent event = capturedEvent.get();
                assertNotNull(event);
                assertEquals(correlation, event.correlation());
                assertEquals("alice", event.identity().actor().id());
                assertEquals(jwtMethod, event.authentication().primaryMethod());

                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }
    }

    // Rejection-draining tests removed: the middleware no longer drains stashed rejection events.
    // Credential rejections are emitted directly by DefaultCredentialRejectionReporter because the
    // failing auth handler calls ctx.fail(...) which short-circuits this middleware. That path is
    // covered by DefaultCredentialRejectionReporterTest.

    // --- Resolver chain ordering ---

    @Nested
    @DisplayName("Resolver chain ordering")
    class ResolverChainOrdering {

        @Test
        @DisplayName("Lower-priority resolver returning non-empty result wins over higher-priority returning empty")
        void lowerPriorityResolverWins(Vertx vertx, VertxTestContext ctx) {
            PrincipalRef expectedActor = new PrincipalRef(PrincipalType.USER, "low-priority-winner", Map.of());
            SecurityIdentity expectedIdentity =
                    new SecurityIdentity(expectedActor, Optional.empty(), Optional.empty(), Optional.empty());

            // Priority 10: returns the expected identity
            SecurityIdentityResolver lowPriority = new SecurityIdentityResolver() {
                @Override
                public int priority() {
                    return 10;
                }

                @Override
                public String id() {
                    return "low";
                }

                @Override
                public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext c) {
                    return Future.succeededFuture(Optional.of(expectedIdentity));
                }
            };

            // Priority 50: returns empty — should NOT win
            SecurityIdentityResolver higherPriority = new SecurityIdentityResolver() {
                @Override
                public int priority() {
                    return 50;
                }

                @Override
                public String id() {
                    return "higher";
                }

                @Override
                public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext c) {
                    return Future.succeededFuture(Optional.empty());
                }
            };

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(lowPriority, higherPriority),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals("low-priority-winner", sc.identity().actor().id());
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Higher-priority resolver supplying a qualified id wins over the default resolver "
                + "(FR-ID-CA-012 multi-issuer escape hatch)")
        void higherPriorityQualifiedIdResolverWins(Vertx vertx, VertxTestContext ctx) {
            // A custom, higher-priority (lower number) resolver namespaces the id with an
            // issuer-scoped URN — the pattern FR-ID-CA-012 requires multi-issuer deployments to
            // install ahead of DefaultSecurityIdentityResolver.
            PrincipalRef qualifiedActor = new PrincipalRef(PrincipalType.USER, "urn:example:issuer-a:alice", Map.of());
            SecurityIdentity qualifiedIdentity =
                    new SecurityIdentity(qualifiedActor, Optional.empty(), Optional.empty(), Optional.empty());

            SecurityIdentityResolver qualifyingResolver = new SecurityIdentityResolver() {
                @Override
                public int priority() {
                    return 10;
                }

                @Override
                public String id() {
                    return "qualifying";
                }

                @Override
                public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext c) {
                    return Future.succeededFuture(Optional.of(qualifiedIdentity));
                }
            };

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(qualifyingResolver, new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            // JWT evidence with an unqualified sub=alice: if the default resolver (priority 100)
            // ran, it would resolve actor id "alice" — a different, unqualified value. Asserting
            // the qualified id below proves the higher-priority resolver's result reached the
            // downstream handler, not the default's.
            router.route("/test").handler(rc -> {
                AuthenticationEvidence evidence = new AuthenticationEvidence(
                        DefaultAuthMethod.jwt(),
                        Optional.of("alice"),
                        Instant.now(),
                        Optional.empty(),
                        new dev.vertique.security.verification.CustomVerificationSource("test", Map.of()),
                        Map.of("sub", "alice"));
                RestAuthenticationEvidence.append(rc, evidence);
                rc.next();
            });
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals(
                        "urn:example:issuer-a:alice",
                        sc.identity().actor().id(),
                        "qualified id from higher-priority resolver must win over the default resolver");
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Exhausted resolver chain falls back to anonymous")
        void exhaustedChainFallsBackToAnonymous(Vertx vertx, VertxTestContext ctx) {
            // One resolver that always returns empty
            SecurityIdentityResolver emptyResolver = context -> Future.succeededFuture(Optional.empty());

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(emptyResolver),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals(PrincipalType.ANONYMOUS, sc.identity().actor().type());
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }
    }

    // --- Identity snapshot capture (ingress seam) ---

    @Nested
    @DisplayName("Identity snapshot capture")
    class IdentitySnapshotCapturePath {

        @Test
        @DisplayName("Wired + enabled capture binds an IdentitySnapshotContext during an authenticated request")
        void bindsSnapshotWhenCaptureWired(Vertx vertx, VertxTestContext ctx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();

            // The capture binds into its own holder, independent of the middleware's contextHolder;
            // the downstream handler reads that holder to observe the in-request binding.
            SnapshotObservingHolder captureHolder = new SnapshotObservingHolder();
            IdentitySnapshotContent known = content();
            IdentitySnapshotCapture capture = new IdentitySnapshotCapture(captureHolder, live -> known, true);

            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER,
                    Optional.of(capture));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");

            // Append JWT evidence so the resolver chain yields a USER identity with primaryMethod
            // JWT — the W3 eligibility gate only captures a snapshot for a non-anonymous,
            // non-NONE-auth-method context.
            router.route("/test").handler(rc -> {
                AuthenticationEvidence evidence = new AuthenticationEvidence(
                        DefaultAuthMethod.jwt(),
                        Optional.of("alice"),
                        Instant.now(),
                        Optional.empty(),
                        new dev.vertique.security.verification.CustomVerificationSource("test", Map.of()),
                        Map.of("sub", "alice"));
                RestAuthenticationEvidence.append(rc, evidence);
                rc.next();
            });
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                Optional<IdentitySnapshotContext> bound = captureHolder.current(IdentitySnapshotContext.class);
                assertTrue(bound.isPresent(), "snapshot must be bound during the request when capture is wired");
                assertSame(known, bound.orElseThrow().content().orElseThrow());
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Wired + enabled capture binds nothing for an anonymous request (W3 eligibility gate)")
        void skipsSnapshotForAnonymousFallback(Vertx vertx, VertxTestContext ctx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();

            // The capture binds into its own holder, independent of the middleware's contextHolder;
            // the downstream handler reads that holder to observe the in-request binding.
            SnapshotObservingHolder captureHolder = new SnapshotObservingHolder();
            IdentitySnapshotContent known = content();
            IdentitySnapshotCapture capture = new IdentitySnapshotCapture(captureHolder, live -> known, true);

            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER,
                    Optional.of(capture));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                assertTrue(
                        captureHolder.current(IdentitySnapshotContext.class).isEmpty(),
                        "anonymous/NONE-auth context must not be captured (W3)");
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Absent capture Optional binds nothing snapshot-related and resolves without error")
        void noSnapshotWhenCaptureAbsent(Vertx vertx, VertxTestContext ctx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();

            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER,
                    Optional.empty());

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(middleware);
            router.route("/test").handler(rc -> {
                // Empty capture Optional means the middleware never touches the snapshot seam: the
                // request still resolves an identity and completes normally.
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }
    }

    /** An in-memory {@link ContextHolder} that binds into a map so a test can observe the binding. */
    private static final class SnapshotObservingHolder implements ContextHolder {

        private final Map<Class<?>, Object> bindings = new java.util.HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> current(Class<T> type) {
            return Optional.ofNullable((T) bindings.get(type));
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            Object previous = bindings.put(type, value);
            return () -> {
                if (previous == null) {
                    bindings.remove(type);
                } else {
                    bindings.put(type, previous);
                }
            };
        }
    }

    // --- Authorization import (Vert.x provider importer wiring) ---

    @Nested
    @DisplayName("Authorization import")
    class AuthorizationImport {

        /**
         * Builds the middleware under test with the default resolver/mapper and the given importer,
         * mirroring the Identity-snapshot-capture group's construction style but using the new
         * 7-arg constructor.
         */
        private IdentityResolutionMiddleware middleware(
                CapturingSecurityRuntime runtime, Optional<VertxAuthorizationImporter> importer) {
            return new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    runtime,
                    EMPTY_HOLDER,
                    Optional.empty(),
                    importer);
        }

        /** Pre-handler that appends JWT evidence for {@code sub} and sets a Vert.x user principal. */
        private io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> authenticateAs(
                String sub, JsonObject principal) {
            return rc -> {
                AuthenticationEvidence evidence = new AuthenticationEvidence(
                        DefaultAuthMethod.jwt(),
                        Optional.of(sub),
                        Instant.now(),
                        Optional.empty(),
                        new dev.vertique.security.verification.CustomVerificationSource("test", Map.of()),
                        Map.of("sub", sub));
                RestAuthenticationEvidence.append(rc, evidence);
                ((UserContextInternal) rc.userContext()).setUser(User.create(principal));
                rc.next();
            };
        }

        @Test
        @DisplayName("Absent importer Optional leaves the mapper-produced claims untouched")
        void importerAbsentLeavesClaimsUntouched(Vertx vertx, VertxTestContext ctx) {
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware mw = middleware(runtime, Optional.empty());

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test")
                    .handler(authenticateAs(
                            "alice", new JsonObject().put("sub", "alice").put("roles", java.util.List.of("editor"))));
            router.route("/test").handler(mw);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals(
                        Set.of(new dev.vertique.security.authz.AuthorityClaim(
                                dev.vertique.security.authz.AuthorityKind.ROLE, "editor", "", "", "", Map.of())),
                        sc.authorization().claims(),
                        "without an importer the bound claims must be exactly the mapper-produced set");
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Present importer merges provider-granted claims with vertx-provider:<id> provenance")
        void importerPresentMergesProviderClaims(Vertx vertx, VertxTestContext ctx) {
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            VertxAuthorizationImporter importer = new VertxAuthorizationImporter(
                    Set.of(grantingProvider("p1", RoleBasedAuthorization.create("provider-role"))), Set.of());
            IdentityResolutionMiddleware mw = middleware(runtime, Optional.of(importer));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test")
                    .handler(authenticateAs(
                            "alice", new JsonObject().put("sub", "alice").put("roles", java.util.List.of("editor"))));
            router.route("/test").handler(mw);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertEquals(
                        Set.of(
                                new dev.vertique.security.authz.AuthorityClaim(
                                        dev.vertique.security.authz.AuthorityKind.ROLE, "editor", "", "", "", Map.of()),
                                new dev.vertique.security.authz.AuthorityClaim(
                                        dev.vertique.security.authz.AuthorityKind.ROLE,
                                        "provider-role",
                                        "",
                                        "",
                                        "vertx-provider:p1",
                                        Map.of())),
                        sc.authorization().claims(),
                        "bound claims must be the union of mapper claims and imported provider claims");
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Importer present but request anonymous: provider never invoked, claims empty")
        void importerNotInvokedForAnonymousRequest(Vertx vertx, VertxTestContext ctx) {
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            InvocationRecordingProvider provider = new InvocationRecordingProvider();
            VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(provider), Set.of());
            IdentityResolutionMiddleware mw = middleware(runtime, Optional.of(importer));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            // No pre-auth handler: ctx.user() stays null and no evidence is appended.
            router.route("/test").handler(mw);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                assertFalse(
                        provider.invoked.get(),
                        "the authorization provider must never be consulted for an anonymous request");
                assertTrue(
                        sc.authorization().claims().isEmpty(),
                        "an anonymous request must carry no authorization claims");
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        @Test
        @DisplayName("Provider failure fails the request with UnavailableException; no SecurityContext bound")
        void importerFailureFailsRequest(Vertx vertx, VertxTestContext ctx) {
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            AuthorizationProvider failing = new AuthorizationProvider() {
                @Override
                public String getId() {
                    return "boom";
                }

                @Override
                public Future<Void> getAuthorizations(User user) {
                    return Future.failedFuture(new IllegalStateException("provider down"));
                }
            };
            VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(failing), Set.of());
            IdentityResolutionMiddleware mw = middleware(runtime, Optional.of(importer));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test")
                    .handler(authenticateAs(
                            "alice", new JsonObject().put("sub", "alice").put("roles", java.util.List.of("editor"))));
            router.route("/test").handler(mw);
            router.route("/test").handler(rc -> ctx.failNow("ctx.next() must never be called when the importer fails"));
            router.route("/test").failureHandler(rc -> {
                assertInstanceOf(
                        dev.vertique.core.exception.UnavailableException.class,
                        rc.failure(),
                        "a provider failure must fail the request with UnavailableException");
                assertNull(
                        runtime.getCaptured(), "no SecurityContext may be bound when the authorization import fails");
                rc.response().setStatusCode(500).end("failed-as-expected");
            });

            startAndSend(vertx, ctx, router, 500);
        }

        @Test
        @DisplayName("Provider failure is logged at ERROR with the UnavailableException cause naming the provider")
        void importerFailureIsLoggedWithCause(Vertx vertx, VertxTestContext ctx) {
            Logger logbackLogger = (Logger) LoggerFactory.getLogger(IdentityResolutionMiddleware.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logbackLogger.addAppender(appender);

            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            AuthorizationProvider failing = new AuthorizationProvider() {
                @Override
                public String getId() {
                    return "boom";
                }

                @Override
                public Future<Void> getAuthorizations(User user) {
                    return Future.failedFuture(new IllegalStateException("provider down"));
                }
            };
            VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(failing), Set.of());
            IdentityResolutionMiddleware mw = middleware(runtime, Optional.of(importer));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(authenticateAs("alice", new JsonObject().put("sub", "alice")));
            router.route("/test").handler(mw);
            router.route("/test").failureHandler(rc -> {
                // The middleware logs synchronously before ctx.fail(), so the event is already
                // captured when this failure handler runs. Detach before asserting so a failed
                // assertion cannot leak the appender onto later tests.
                logbackLogger.detachAppender(appender);
                appender.stop();
                List<ILoggingEvent> errors = appender.list.stream()
                        .filter(event -> event.getLevel() == Level.ERROR)
                        .toList();
                assertEquals(
                        1, errors.size(), "exactly one ERROR event must be logged for the failed import: " + errors);
                IThrowableProxy thrown = errors.get(0).getThrowableProxy();
                assertNotNull(thrown, "the ERROR event must carry the import failure as its throwable");
                assertEquals(
                        dev.vertique.core.exception.UnavailableException.class.getName(),
                        thrown.getClassName(),
                        "the logged throwable must be the importer's UnavailableException");
                assertTrue(
                        thrown.getMessage().contains("boom"),
                        "the logged UnavailableException must name the failing provider id: " + thrown.getMessage());
                rc.response().setStatusCode(500).end("failed-as-expected");
            });

            startAndSend(vertx, ctx, router, 500);
        }

        @Test
        @DisplayName("Synchronous throw in the post-resolver body is routed to ctx.fail, never propagated")
        void syncThrowInPostResolverBodyFailsRequest() {
            // Direct handle() invocation with a mocked RoutingContext: a real Router's catch-all
            // around handler invocation would mask a synchronous propagation out of handle(), and
            // the throw inside the onComplete lambda would otherwise vanish into the Vert.x context
            // exception handler, hanging the request. The changed seam is handle() itself.
            RuntimeException boom = new RuntimeException("mapper-boom");
            SecurityClaimMapper throwingMapper = claims -> {
                throw boom;
            };
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware mw = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(throwingMapper),
                    new SecurityEventEmitter(Set.of()),
                    runtime,
                    EMPTY_HOLDER,
                    Optional.empty(),
                    Optional.empty());

            io.vertx.ext.web.RoutingContext rc = mock(io.vertx.ext.web.RoutingContext.class);
            when(rc.user()).thenReturn(User.create(new JsonObject().put("sub", "alice")));

            assertDoesNotThrow(() -> mw.handle(rc), "handle() must not propagate a synchronous mapper throw");
            verify(rc).fail(boom);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("Synchronous throw from a resolver is routed to ctx.fail, never propagated")
        void resolverSyncThrowFailsRequest() {
            // Direct handle() invocation for the same reason as syncThrowInPostResolverBodyFailsRequest:
            // the Router catch-all would convert a propagated throw into ctx.fail and falsely pass.
            RuntimeException boom = new RuntimeException("resolver-sync-boom");
            SecurityIdentityResolver throwingResolver = c -> {
                throw boom;
            };
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware mw = new IdentityResolutionMiddleware(
                    Set.of(throwingResolver),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    runtime,
                    EMPTY_HOLDER,
                    Optional.empty(),
                    Optional.empty());

            io.vertx.ext.web.RoutingContext rc = mock(io.vertx.ext.web.RoutingContext.class);

            assertDoesNotThrow(() -> mw.handle(rc), "handle() must not propagate a synchronous resolver throw");
            verify(rc).fail(boom);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("SCOPE kind fidelity: jwt-claims provider excluded by the safe constructor, so a "
                + "mapper SCOPE claim is never re-imported as PERMISSION")
        void scopeKindFidelityPreservedForJwtApps(Vertx vertx, VertxTestContext ctx) {
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            // Safe 1-arg constructor: "jwt-claims" is excluded by default. The sentinel provider is
            // NOT excluded — its imported claim proves the importer actually ran, making the
            // "no PERMISSION twin" assertion non-vacuous.
            VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(
                    grantingProvider("jwt-claims", PermissionBasedAuthorization.create("read")),
                    grantingProvider("p2", RoleBasedAuthorization.create("sentinel-role"))));
            IdentityResolutionMiddleware mw = middleware(runtime, Optional.of(importer));

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test")
                    .handler(authenticateAs(
                            "alice", new JsonObject().put("sub", "alice").put("scope", "read")));
            router.route("/test").handler(mw);
            router.route("/test").handler(rc -> {
                SecurityContext sc = runtime.getCaptured();
                assertNotNull(sc);
                Set<dev.vertique.security.authz.AuthorityClaim> claims =
                        sc.authorization().claims();
                assertTrue(
                        claims.contains(new dev.vertique.security.authz.AuthorityClaim(
                                dev.vertique.security.authz.AuthorityKind.SCOPE, "read", "", "", "", Map.of())),
                        "mapper-produced (SCOPE, read) claim must survive: " + claims);
                assertTrue(
                        claims.contains(new dev.vertique.security.authz.AuthorityClaim(
                                dev.vertique.security.authz.AuthorityKind.ROLE,
                                "sentinel-role",
                                "",
                                "",
                                "vertx-provider:p2",
                                Map.of())),
                        "sentinel provider claim must be imported (proves the importer ran): " + claims);
                assertTrue(
                        claims.stream()
                                .noneMatch(c -> c.kind() == dev.vertique.security.authz.AuthorityKind.PERMISSION
                                        && "read".equals(c.value())),
                        "the excluded jwt-claims provider must not re-project SCOPE read as PERMISSION: " + claims);
                rc.response().setStatusCode(200).end("ok");
            });

            startAndSend(vertx, ctx, router, 200);
        }

        /**
         * Builds an {@link AuthorizationProvider} that grants the given authorizations into its own
         * provider bucket on invocation.
         */
        private AuthorizationProvider grantingProvider(
                String id, io.vertx.ext.auth.authorization.Authorization... grants) {
            return new AuthorizationProvider() {
                @Override
                public String getId() {
                    return id;
                }

                @Override
                public Future<Void> getAuthorizations(User user) {
                    user.authorizations().put(id, Set.of(grants));
                    return Future.succeededFuture();
                }
            };
        }
    }

    /** An {@link AuthorizationProvider} that records whether it was ever invoked. */
    private static final class InvocationRecordingProvider implements AuthorizationProvider {

        final java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public String getId() {
            return "recording";
        }

        @Override
        public Future<Void> getAuthorizations(User user) {
            invoked.set(true);
            return Future.succeededFuture();
        }
    }

    // --- Async failure propagation ---

    @Nested
    @DisplayName("Async failure propagation")
    class AsyncFailure {

        @Test
        @DisplayName("Resolver returning failed Future causes ctx.fail() to be invoked")
        void resolverFailureCausesCtxFail(Vertx vertx, VertxTestContext ctx) {
            RuntimeException cause = new RuntimeException("resolver-failed");
            SecurityIdentityResolver failingResolver = context -> Future.failedFuture(cause);

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(failingResolver),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            router.route("/test").handler(middleware);
            // Failure handler — verifies ctx.fail was called with the correct cause
            router.route("/test").failureHandler(rc -> {
                Throwable failure = rc.failure();
                assertEquals(cause, failure, "failure must be the resolver exception");
                rc.response().setStatusCode(500).end("failed-as-expected");
            });

            startAndSend(vertx, ctx, router, 500);
        }
    }

    // --- Default resolver underivable-id failure (FR-ID-CA-012) ---

    @Nested
    @DisplayName("Default resolver underivable-id failure (FR-ID-CA-012)")
    class DefaultResolverUnderivableIdFailure {

        @Test
        @DisplayName("Evidence present but no derivable id reaches the default resolver → request fails, not 200")
        void underivableIdCausesRequestFailure(Vertx vertx, VertxTestContext ctx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            // mTLS evidence with no safe attributes at all: classifies as SERVICE, but neither
            // `sub` nor `client_id`/`azp` is present, so the default resolver's id is underivable
            // (FR-ID-CA-012). Resolution must fail closed — never anonymous, never a placeholder.
            router.route("/test").handler(rc -> {
                AuthenticationEvidence evidence = new AuthenticationEvidence(
                        DefaultAuthMethod.mtls(),
                        Optional.empty(),
                        Instant.now(),
                        Optional.empty(),
                        new dev.vertique.security.verification.CustomVerificationSource("test", Map.of()),
                        Map.of());
                RestAuthenticationEvidence.append(rc, evidence);
                rc.next();
            });
            router.route("/test").handler(middleware);
            router.route("/test")
                    .handler(rc -> ctx.failNow(
                            "downstream handler must not be reached when identity resolution fails closed"));
            router.route("/test").failureHandler(rc -> {
                Throwable failure = rc.failure();
                assertInstanceOf(
                        IdentityResolutionException.class,
                        failure,
                        "failure must propagate the resolver's IdentityResolutionException");
                rc.response().setStatusCode(500).end("failed-as-expected");
            });

            startAndSend(vertx, ctx, router, 500);
        }
    }

    // --- Blank claim handling (CW-2) ---

    @Nested
    @DisplayName("Blank sub claim handling (CW-2)")
    class BlankClaimHandling {

        @Test
        @DisplayName("Blank sub claim fails as 500/IdentityResolutionException, never as a 400 with "
                + "a leaked internal validation message")
        void blankSubDoesNotLeakValidationMessage(Vertx vertx, VertxTestContext ctx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            CapturingSecurityRuntime runtime = new CapturingSecurityRuntime();
            IdentityResolutionMiddleware middleware = new IdentityResolutionMiddleware(
                    Set.of(new DefaultSecurityIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    EMPTY_HOLDER);

            Router router = Router.router(vertx);
            installLifecycle(router, "/test");
            // A validly-signed JWT can carry "sub":"" — the shipped JwtBearerSecuritySchemeHandler
            // only null-checks claims, so this evidence shape is reachable in production, not merely
            // synthetic. Mirrors the pre-handler evidence-seeding pattern used by
            // underivableIdCausesRequestFailure below.
            router.route("/test").handler(rc -> {
                AuthenticationEvidence evidence = new AuthenticationEvidence(
                        DefaultAuthMethod.jwt(),
                        Optional.empty(),
                        Instant.now(),
                        Optional.empty(),
                        new dev.vertique.security.verification.CustomVerificationSource("test", Map.of()),
                        Map.of("sub", ""));
                RestAuthenticationEvidence.append(rc, evidence);
                rc.next();
            });
            router.route("/test").handler(middleware);
            router.route("/test")
                    .handler(rc -> ctx.failNow(
                            "downstream handler must not be reached when identity resolution fails closed"));
            // Mirrors RestModule's real default exception mapping (IllegalArgumentException -> 400
            // with the raw exception message as the body; any other failure -> 500) so this
            // self-contained unit test (vertique-rest-security has no dependency on the rest-jaxrs
            // module that owns RestModule) still proves the same shape the production error pipeline
            // would produce: today the resolver throws IllegalArgumentException synchronously,
            // leaking "id must not be blank" as a 400; after the fix it must be a same-shaped
            // IdentityResolutionException / 500 as underivableIdCausesRequestFailure below.
            router.route("/test").failureHandler(rc -> {
                Throwable failure = rc.failure();
                if (failure instanceof IllegalArgumentException) {
                    rc.response().setStatusCode(400).end(String.valueOf(failure.getMessage()));
                    return;
                }
                assertInstanceOf(
                        IdentityResolutionException.class,
                        failure,
                        "failure must propagate the resolver's IdentityResolutionException, not an "
                                + "arbitrary exception type: " + failure);
                rc.response().setStatusCode(500).end("failed-as-expected");
            });

            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .onComplete(ctx.succeeding(s -> {
                        server = s;
                        (client = vertx.createHttpClient())
                                .request(io.vertx.core.http.HttpMethod.GET, s.actualPort(), "127.0.0.1", "/test")
                                .compose(req -> req.send())
                                .compose(resp -> {
                                    assertEquals(
                                            500,
                                            resp.statusCode(),
                                            "blank sub must fail resolution the same way as an underivable id "
                                                    + "(500), not surface as a 400 validation error");
                                    return resp.body().map(Object::toString);
                                })
                                .onComplete(ctx.succeeding(body -> {
                                    assertFalse(
                                            body.toLowerCase(java.util.Locale.ROOT)
                                                    .contains("must not be blank"),
                                            "response body must not leak the internal PrincipalRef/ClientRef "
                                                    + "validation message: " + body);
                                    ctx.completeNow();
                                }));
                    }));
        }
    }

    // --- Helper ---

    /**
     * Builds fixed {@link IdentitySnapshotContent} for the capture-seam tests; a stub factory returns
     * it verbatim, so its contents only need to be a valid content instance.
     *
     * @return fixed captured content
     */
    private static IdentitySnapshotContent content() {
        return new IdentitySnapshotContent(
                new PrincipalRef(PrincipalType.USER, "alice", Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                java.util.List.of(),
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
    }

    /**
     * Starts a Vert.x HTTP server with the given router, sends a GET / request, and verifies the
     * expected status code before completing the test context.
     *
     * @param vertx          the Vert.x instance
     * @param ctx            the test context
     * @param router         the router to mount
     * @param expectedStatus the expected HTTP status code
     */
    private void startAndSend(Vertx vertx, VertxTestContext ctx, Router router, int expectedStatus) {
        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            (client = vertx.createHttpClient())
                    .request(io.vertx.core.http.HttpMethod.GET, s.actualPort(), "127.0.0.1", "/test")
                    .compose(req -> req.send())
                    .compose(resp -> {
                        assertEquals(expectedStatus, resp.statusCode());
                        return resp.body();
                    })
                    .onComplete(ctx.succeeding(body -> ctx.completeNow()));
        }));
    }
}

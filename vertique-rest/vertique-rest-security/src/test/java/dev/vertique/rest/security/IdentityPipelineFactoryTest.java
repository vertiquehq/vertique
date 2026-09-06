// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link IdentityPipelineFactory}, frozen by
 * {@code contracts/identity-pipeline-factory.md} (T007).
 *
 * <p>Verifies that {@link IdentityPipelineFactory#restIdentityResolution()} wires
 * identity-snapshot capture and binds the REST {@link InvocationOrigin}, that
 * {@link IdentityPipelineFactory#identityResolutionHandler(IdentityPipelineOptions)} with
 * {@link IdentityPipelineOptions#webSocket()} does neither, that the REST middleware and the policy
 * enforcer are memoized, that {@link IdentityPipelineFactory#securityRuntime()} returns the injected
 * runtime, that the factory exposes no options-taking middleware accessor (AR-002), and — by
 * reflection — that the factory's constructor stays a superset of the two {@code @Inject}
 * constructors it must cover (AR-006 drift guard).
 *
 * <p>Expected initial result (before T007's production slice lands): compile failure — neither
 * {@link IdentityPipelineFactory} nor {@link IdentityPipelineOptions} exist yet, and
 * {@code DispatchBoundary.REST}/{@code WEBSOCKET} and {@code IdentityResolutionMiddleware.REST_ORIGIN}
 * are additive members this task also introduces.
 */
@ExtendWith(VertxExtension.class)
class IdentityPipelineFactoryTest {

    private HttpServer server;
    private WebClient client;

    /** A no-op {@link ContextHolder} used only inside {@link RecordingCapture}'s own capture. */
    private static final ContextHolder NOOP_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /**
     * Fixed content a {@link RecordingCapture} returns from its wrapped
     * {@link dev.vertique.security.IdentitySnapshotFactory}; its contents only need to be a valid
     * content instance since the test never inspects them.
     */
    private static final IdentitySnapshotContent FIXED_CONTENT = new IdentitySnapshotContent(
            new PrincipalRef(PrincipalType.USER, "alice", Map.of()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "jwt",
            Instant.parse("2026-07-01T10:15:30Z"),
            Optional.empty(),
            List.of(),
            "rest:authenticated",
            Instant.parse("2026-07-01T10:15:31Z"));

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran, mirroring
     * {@code IdentityResolutionMiddlewareTest}'s teardown.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> sc = server != null ? server.close() : Future.succeededFuture();
        sc.onComplete(ar -> ctx.completeNow());
    }

    // --- Helpers ---

    /**
     * Wraps the real, {@code final} {@link IdentitySnapshotCapture} — which cannot be subclassed —
     * with a factory function that flips {@link #invoked} whenever
     * {@link IdentitySnapshotCapture#captureFrom(SecurityContext)} actually calls it, so the test can
     * observe capture without inspecting the captured content itself.
     */
    private static final class RecordingCapture {
        private final AtomicBoolean invoked = new AtomicBoolean();
        private final IdentitySnapshotCapture capture;

        RecordingCapture() {
            this.capture = new IdentitySnapshotCapture(
                    NOOP_HOLDER,
                    live -> {
                        invoked.set(true);
                        return FIXED_CONTENT;
                    },
                    true);
        }

        boolean invoked() {
            return invoked.get();
        }

        IdentitySnapshotCapture capture() {
            return capture;
        }
    }

    /** Minimal {@link SecurityRuntime} test double that stores the last bound context. */
    private static final class StubSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext current;

        @Override
        public SecurityContext current() {
            return current;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext ctx) {
            current = ctx;
            return () -> current = null;
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext ctx, boolean secure) {
            return null;
        }
    }

    /** A {@link ContextHolder} that records the last {@link InvocationOrigin} bound into it. */
    private static ContextHolder originCapturingHolder(AtomicReference<InvocationOrigin> capturedOrigin) {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return Optional.empty();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                if (type == InvocationOrigin.class) {
                    capturedOrigin.set((InvocationOrigin) value);
                }
                return () -> {};
            }
        };
    }

    private static void installLifecycle(Router router, String path) {
        router.route(path).handler(new RequestContextLifecycle());
    }

    /**
     * Appends JWT evidence for "alice" and sets a Vert.x user principal — mirrors
     * {@code IdentityResolutionMiddlewareTest#authenticateAsAlice}, reused here in style since the
     * two test classes cannot share private fixtures.
     */
    private static void authenticateAsAlice(RoutingContext context) {
        RestAuthenticationEvidence.append(
                context,
                new AuthenticationEvidence(
                        DefaultAuthMethod.jwt(),
                        Optional.of("alice"),
                        Instant.now(),
                        Optional.empty(),
                        new CustomVerificationSource("test", Map.of()),
                        Map.of("sub", "alice")));
        ((UserContextInternal) context.userContext()).setUser(User.create(new JsonObject().put("sub", "alice")));
        context.next();
    }

    // --- TP-001 ---

    @Test
    @DisplayName(
            "REST options wire identity-snapshot capture and bind the REST origin; " + "WebSocket options do neither")
    void restOptionsWireCaptureAndWebSocketOptionsDoNot(Vertx vertx, VertxTestContext ctx) {
        RecordingCapture capture = new RecordingCapture();
        AtomicReference<InvocationOrigin> capturedOrigin = new AtomicReference<>();
        StubSecurityRuntime runtime = new StubSecurityRuntime();

        IdentityPipelineFactory factory = new IdentityPipelineFactory(
                Set.of(new DefaultSecurityIdentityResolver()),
                Optional.empty(),
                new SecurityEventEmitter(Set.of()),
                runtime,
                originCapturingHolder(capturedOrigin),
                Optional.of(capture.capture()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty());

        Router router = Router.router(vertx);
        installLifecycle(router, "/ws");
        installLifecycle(router, "/rest");
        router.route("/ws").handler(IdentityPipelineFactoryTest::authenticateAsAlice);
        router.route("/ws").handler(factory.identityResolutionHandler(IdentityPipelineOptions.webSocket()));
        router.route("/ws").handler(rc -> rc.response().end(capturedOrigin.get().kind()));
        router.route("/rest").handler(IdentityPipelineFactoryTest::authenticateAsAlice);
        router.route("/rest").handler(factory.restIdentityResolution());
        router.route("/rest")
                .handler(rc -> rc.response().end(capturedOrigin.get().kind()));

        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(httpServer -> {
                    server = httpServer;
                    // WebSocket options first: an empty capture flag stays empty until proven
                    // otherwise, and a false-negative "not invoked" reading is not confused with the
                    // REST branch never having run.
                    return client.get(httpServer.actualPort(), "127.0.0.1", "/ws")
                            .send();
                })
                .compose(wsResponse -> {
                    assertEquals(200, wsResponse.statusCode());
                    assertEquals(
                            InvocationOrigin.of(DispatchBoundary.WEBSOCKET),
                            capturedOrigin.get(),
                            "the WebSocket handler must bind a WEBSOCKET InvocationOrigin");
                    assertEquals(DispatchBoundary.WEBSOCKET, wsResponse.bodyAsString());
                    assertFalse(
                            capture.invoked(),
                            "identityResolutionHandler(webSocket()) must not invoke identity-snapshot capture");
                    return client.get(server.actualPort(), "127.0.0.1", "/rest").send();
                })
                .onComplete(ctx.succeeding(restResponse -> {
                    assertEquals(200, restResponse.statusCode());
                    assertEquals(
                            IdentityResolutionMiddleware.REST_ORIGIN,
                            capturedOrigin.get(),
                            "restIdentityResolution() must bind the REST InvocationOrigin");
                    assertEquals(DispatchBoundary.REST, restResponse.bodyAsString());
                    assertTrue(
                            capture.invoked(),
                            "restIdentityResolution() must invoke identity-snapshot capture when wired");

                    assertSame(
                            factory.restIdentityResolution(),
                            factory.restIdentityResolution(),
                            "restIdentityResolution() must be memoized");
                    assertSame(factory.policyEnforcer(), factory.policyEnforcer(), "policyEnforcer() must be memoized");
                    assertSame(
                            runtime,
                            factory.securityRuntime(),
                            "securityRuntime() must return the injected SecurityRuntime");
                    assertNoOptionsToMiddlewareAccessor();

                    ctx.completeNow();
                }));
    }

    /**
     * Reflectively asserts the factory has no public method taking an {@link IdentityPipelineOptions}
     * and returning an {@link IdentityResolutionMiddleware} — deliberate per AR-002, since a
     * middleware's {@code handle(ctx)} always binds the REST origin.
     */
    private static void assertNoOptionsToMiddlewareAccessor() {
        boolean present = Arrays.stream(IdentityPipelineFactory.class.getMethods())
                .anyMatch(m -> m.getReturnType() == IdentityResolutionMiddleware.class
                        && Arrays.asList(m.getParameterTypes()).contains(IdentityPipelineOptions.class));
        assertFalse(
                present,
                "the factory must not expose a method taking IdentityPipelineOptions and returning "
                        + "IdentityResolutionMiddleware");
    }

    // --- TP-003 ---

    @Test
    @DisplayName("identityResolutionHandler rejects the REST origin — REST goes through restIdentityResolution()")
    void identityResolutionHandlerRejectsRestOrigin() {
        IdentityPipelineFactory factory = new IdentityPipelineFactory(
                Set.of(new DefaultSecurityIdentityResolver()),
                Optional.empty(),
                new SecurityEventEmitter(Set.of()),
                new StubSecurityRuntime(),
                NOOP_HOLDER,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty());

        IllegalArgumentException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> factory.identityResolutionHandler(
                        new IdentityPipelineOptions(IdentityResolutionMiddleware.REST_ORIGIN, false)),
                "a non-REST transport must not be able to label itself rest through the options record");
        org.junit.jupiter.api.Assertions.assertTrue(
                rejected.getMessage().contains("restIdentityResolution()"), rejected.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(
                factory.identityResolutionHandler(IdentityPipelineOptions.webSocket()),
                "the websocket preset stays accepted");
    }

    @Test
    @DisplayName("Factory constructor covers every parameter of the two @Inject constructors (AR-006 drift guard)")
    void factoryConstructorCoversEveryInjectedCollaborator() {
        Set<String> factoryParams = genericParameterTypeNames(onlyPublicConstructor(IdentityPipelineFactory.class));
        Set<String> middlewareInjectParams =
                genericParameterTypeNames(injectConstructor(IdentityResolutionMiddleware.class));
        Set<String> enforcerInjectParams = genericParameterTypeNames(injectConstructor(SecurityPolicyEnforcer.class));

        Set<String> missingFromMiddleware = missing(middlewareInjectParams, factoryParams);
        assertTrue(
                missingFromMiddleware.isEmpty(),
                () -> "IdentityPipelineFactory's constructor is missing IdentityResolutionMiddleware "
                        + "@Inject parameter(s): " + missingFromMiddleware);

        Set<String> missingFromEnforcer = missing(enforcerInjectParams, factoryParams);
        assertTrue(
                missingFromEnforcer.isEmpty(),
                () -> "IdentityPipelineFactory's constructor is missing SecurityPolicyEnforcer @Inject "
                        + "parameter(s): " + missingFromEnforcer);
    }

    private static Constructor<?> onlyPublicConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getConstructors();
        assertEquals(1, constructors.length, () -> type.getName() + " must expose exactly one public constructor");
        return constructors[0];
    }

    private static Constructor<?> injectConstructor(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(Inject.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getName() + " has no @Inject constructor"));
    }

    private static Set<String> genericParameterTypeNames(Constructor<?> constructor) {
        return Arrays.stream(constructor.getGenericParameterTypes())
                .map(Type::getTypeName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> missing(Set<String> required, Set<String> available) {
        Set<String> copy = new LinkedHashSet<>(required);
        copy.removeAll(available);
        return copy;
    }
}

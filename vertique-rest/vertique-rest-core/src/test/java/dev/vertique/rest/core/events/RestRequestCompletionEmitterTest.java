// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.rest.core.capture.RestRequestCaptureCoordinator;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Component tests for {@link RestRequestCompletionEmitter}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Exactly one event emitted per successful request with correct method/path/status.</li>
 *   <li>Exactly one event emitted on mapped 500 failure; {@code failureCode} equals the exception's
 *       simple class name; {@code safeFailureMessage} is {@code null} and the raw exception message
 *       does not appear anywhere in the event.</li>
 *   <li>Exactly one event emitted for a 4xx request that matched no operation route, with
 *       {@code operationId} null because the framework recorded no route identity.</li>
 *   <li>Idempotency: the emitter's exactly-once guard collapses a doubly-mounted emitter handler to
 *       exactly one event.</li>
 *   <li>A throwing listener does not prevent other listeners from receiving the event.</li>
 *   <li>No listeners: emitter completes silently without error.</li>
 *   <li>{@link SecurityContext} and {@link CorrelationContext} are captured when bound.</li>
 * </ul>
 *
 * <p>A single {@link WebClient} is shared across all test methods via {@code @BeforeAll} to
 * avoid netty channel-pool churn under full-reactor load. Each test still creates its own
 * {@link HttpServer} (torn down in {@code @AfterEach}) because server wiring differs per test.
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * Every assertion here reads the response status or the emitted event, never the body, so these
 * tests cannot flake on that today — the raw idiom was latent, and would become a live race the
 * moment anyone asserted on the body, with no diff to hint why. A {@link WebClient} aggregates the
 * body into its {@code HttpResponse} before completing the send, so the hazard is removed by
 * construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestRequestCompletionEmitterTest {

    // --- Class-scoped resources (shared across all @Test methods) ---

    private static Vertx vertx;
    private static WebClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link WebClient} once for
     * the entire test class. vertx-junit5 injects a class-scoped {@link Vertx} into
     * {@code @BeforeAll} and keeps it alive for all test methods.
     *
     * @param v   the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(v, new WebClientOptions().setFollowRedirects(false));
        ctx.completeNow();
    }

    /**
     * Closes the per-test {@link HttpServer}. The shared {@link WebClient} is left open and
     * closed only in {@link #tearDownClass(VertxTestContext)}.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the shared {@link WebClient} after all tests in the class have run.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to chain the context
     * completion off.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    // --- Helpers ---

    /**
     * Creates an emitter with no security runtime and a plain {@link DefaultContextHolder}.
     *
     * @param listeners the listeners to notify
     * @return a new emitter instance
     */
    private static RestRequestCompletionEmitter emitter(Set<RestRequestCompletedListener> listeners) {
        return new RestRequestCompletionEmitter(Optional.empty(), new DefaultContextHolder(), listeners);
    }

    /**
     * Creates an emitter that uses the given {@link SecurityRuntime} and {@link ContextHolder}.
     *
     * @param securityRuntime the security runtime to use
     * @param holder          the context holder to use
     * @param listeners       the listeners to notify
     * @return a new emitter instance
     */
    private static RestRequestCompletionEmitter emitter(
            SecurityRuntime securityRuntime, ContextHolder holder, Set<RestRequestCompletedListener> listeners) {
        return new RestRequestCompletionEmitter(Optional.of(securityRuntime), holder, listeners);
    }

    /**
     * Creates an emitter with no security runtime, a plain {@link DefaultContextHolder}, and the
     * given listeners and coordinators.
     *
     * @param listeners    the safe listeners to notify
     * @param coordinators the capture coordinators to invoke after listeners
     * @return a new emitter instance
     */
    private static RestRequestCompletionEmitter emitterWithCoordinators(
            Set<RestRequestCompletedListener> listeners, Set<RestRequestCaptureCoordinator> coordinators) {
        return new RestRequestCompletionEmitter(Optional.empty(), new DefaultContextHolder(), listeners, coordinators);
    }

    /**
     * Builds a {@link Router} with {@link RequestContextLifecycle}, the emitter, and a lifecycle
     * barrier middleware mounted in order, plus a terminal route at {@code /test} that delegates to
     * the supplied handler.
     *
     * <p>The barrier middleware runs on every request, after {@link RequestContextLifecycle} has
     * installed its {@link RequestContextLifecycle.Handle} on the routing context. Callers await
     * {@code barrier.future()} (via {@link #awaitBarrier(Vertx, Future)}) instead of a fixed settle
     * window before asserting captured state — see {@link #barrierHandler(Promise)}.
     *
     * @param vertx   the Vert.x instance
     * @param emitter the emitter to mount
     * @param barrier the promise completed once the request's lifecycle handle has fully closed
     * @param handler the terminal route handler
     * @return the configured router
     */
    private static Router router(
            Vertx vertx, RestRequestCompletionEmitter emitter, Promise<Void> barrier, RouteHandler handler) {
        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(emitter.priority()).handler(emitter);
        router.route().handler(barrierHandler(barrier));
        router.route("/test").handler(rc -> {
            handler.handle(rc);
            if (!rc.response().ended()) {
                rc.response().setStatusCode(200).end();
            }
        });
        return router;
    }

    /**
     * Pairs a {@link Router} built by {@link #router(Vertx, RestRequestCompletionEmitter, Promise,
     * RouteHandler)} with the lifecycle barrier future it was wired against, collapsing the
     * {@link Promise} plumbing at each call site.
     *
     * @param router  the configured router
     * @param barrier the future resolving once the request's lifecycle handle has fully closed
     */
    private record RouterWithBarrier(Router router, Future<Void> barrier) {}

    /**
     * Builds a {@link Router} via {@link #router(Vertx, RestRequestCompletionEmitter, Promise,
     * RouteHandler)} and returns it paired with the barrier future to await, so call sites don't
     * need to manage the {@link Promise} directly.
     *
     * @param vertx   the Vert.x instance
     * @param emitter the emitter to mount
     * @param handler the terminal route handler
     * @return the configured router paired with its lifecycle barrier future
     */
    private static RouterWithBarrier routerWithBarrier(
            Vertx vertx, RestRequestCompletionEmitter emitter, RouteHandler handler) {
        Promise<Void> barrier = Promise.promise();
        return new RouterWithBarrier(router(vertx, emitter, barrier, handler), barrier.future());
    }

    /**
     * Starts an HTTP server on a dynamic port, stores it on the test instance for
     * {@code @AfterEach} cleanup, and returns the listening port. The shared {@link WebClient}
     * is used for all requests.
     *
     * @param router the router to attach
     * @return a {@link Future} resolving to the bound port
     */
    private Future<Integer> startServer(Router router) {
        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    return s.actualPort();
                });
    }

    /** Timeout, in milliseconds, for {@link #awaitBarrier(Vertx, Future)}. */
    private static final long BARRIER_TIMEOUT_MS = 5000;

    /**
     * Creates a middleware handler that, during the request, registers a
     * {@link RequestContextLifecycle.Handle#afterClose(Runnable)} task completing the given
     * {@code barrier}, then delegates to the next handler.
     *
     * <p>{@link RequestContextLifecycle} registers exactly one {@code ctx.addEndHandler} and, because
     * Vert.x Web fires end handlers in reverse registration order, that handler — registered first —
     * fires last. By the time that handler's cleanup runs, every other end handler's synchronous work
     * (including the completion emitter's listener dispatch) has already finished, and all
     * {@code onClose} registrations have been closed. {@code afterClose} tasks then run in FIFO order,
     * so this barrier completes only after all {@code ctx} end-handler work and all {@code onClose}
     * cleanup for the request have finished — with no settle window required. Because
     * {@code afterClose} tasks are FIFO, a task registered <em>after</em> this middleware's
     * {@code afterClose} call would run after the barrier completes: do not register {@code
     * afterClose} downstream of this middleware in these tests.
     *
     * <p><strong>Caveat — an explicit {@code completeNow()} call breaks this guarantee.</strong> The
     * "completes only after all {@code ctx} end-handler work" claim above holds only on the normal
     * response-end path. If a request handler calls
     * {@link RequestContextLifecycle.Handle#completeNow()} explicitly, {@code completeNow()} drives
     * {@code closeAll()} — and therefore this barrier's {@code afterClose} task — synchronously at
     * that moment, before the response has even ended and independently of whether the emitter's
     * {@code ctx.addEndHandler}-driven callback has run yet. On that path this barrier is burned
     * early and proves nothing about end-handler-driven work; see
     * {@code completeNowNeitherEmitsNorSuppressesCompletionEvent} for the sentinel-middleware
     * pattern used instead when a proof must survive an explicit {@code completeNow()} call.
     *
     * @param barrier the promise to complete once the request's lifecycle handle has fully closed
     * @return a handler suitable for mounting as a catch-all route after {@link RequestContextLifecycle}
     */
    private static Handler<RoutingContext> barrierHandler(Promise<Void> barrier) {
        return rc -> {
            RequestContextLifecycle.fromRoutingContext(rc).afterClose(barrier::complete);
            rc.next();
        };
    }

    /**
     * Awaits the given lifecycle barrier future, failing with a descriptive timeout message if it
     * does not complete within {@link #BARRIER_TIMEOUT_MS} milliseconds. A lifecycle defect must
     * surface as a diagnosable message naming the barrier, not an opaque class-level {@code @Timeout}.
     *
     * <p>Delegates to {@link #awaitBarrier(Vertx, Future, String)} with the label
     * {@code "lifecycle afterClose barrier"}.
     *
     * @param vertx   the Vert.x instance used to schedule the timeout timer
     * @param barrier the barrier future to await
     * @return a future that resolves once {@code barrier} completes, or fails with a descriptive
     *     timeout message if it does not complete in time
     */
    private static Future<Void> awaitBarrier(Vertx vertx, Future<Void> barrier) {
        return awaitBarrier(vertx, barrier, "lifecycle afterClose barrier");
    }

    /**
     * Awaits the given future, failing with a descriptive timeout message naming {@code label} if it
     * does not complete within {@link #BARRIER_TIMEOUT_MS} milliseconds. A defect in the awaited
     * mechanism must surface as a diagnosable message, not an opaque class-level {@code @Timeout}.
     *
     * @param vertx   the Vert.x instance used to schedule the timeout timer
     * @param barrier the future to await
     * @param label   a short description of what {@code barrier} represents, used in the timeout
     *                failure message
     * @return a future that resolves once {@code barrier} completes, or fails with a descriptive
     *     timeout message if it does not complete in time
     */
    private static Future<Void> awaitBarrier(Vertx vertx, Future<Void> barrier, String label) {
        Promise<Void> result = Promise.promise();
        long timerId = vertx.setTimer(
                BARRIER_TIMEOUT_MS,
                id -> result.tryFail(label + " did not complete within " + BARRIER_TIMEOUT_MS + "ms"));
        barrier.onComplete(ar -> {
            vertx.cancelTimer(timerId);
            if (ar.succeeded()) {
                result.tryComplete();
            } else {
                result.tryFail(ar.cause());
            }
        });
        return result.future();
    }

    /** Simple functional interface for test route handlers. */
    @FunctionalInterface
    private interface RouteHandler {
        void handle(io.vertx.ext.web.RoutingContext rc);
    }

    // --- Tests ---

    @Nested
    @DisplayName("Success path")
    class SuccessPath {

        @Test
        @DisplayName("Exactly one event emitted for a 200 response with correct method, path, and status")
        void emitsOneEventOnSuccess(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "exactly one event must be emitted");
                            RestRequestCompletedEvent event = captured.get(0);
                            assertEquals("GET", event.method());
                            assertEquals("/test", event.path());
                            assertEquals(200, event.statusCode());
                            assertNotNull(event.startTime());
                            assertNotNull(event.endTime());
                            assertNotNull(event.origin());
                        });
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("Failure path")
    class FailurePath {

        @Test
        @DisplayName("Exactly one event on ctx.fail(); failureCode = exception simple name; safeFailureMessage is null")
        void emitsOneEventOnFailureWithSafeFields(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb =
                    routerWithBarrier(vertx, em, rc -> rc.fail(500, new IllegalStateException("secret detail")));

            startServer(rb.router())
                    .compose(port -> client.post(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(500, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "exactly one event must be emitted");
                            RestRequestCompletedEvent event = captured.get(0);
                            assertEquals(500, event.statusCode());
                            assertEquals("IllegalStateException", event.failureCode());
                            // safeFailureMessage must be null — never raw exception messages
                            assertNull(event.safeFailureMessage(), "safeFailureMessage must be null");
                            // Raw exception message must not appear anywhere in the event
                            assertRawMessageAbsent(event, "secret detail");
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Verifies that the raw exception message does not appear in any string field of the event.
         *
         * @param event      the event to check
         * @param rawMessage the raw exception message that must not appear
         */
        private static void assertRawMessageAbsent(RestRequestCompletedEvent event, String rawMessage) {
            assertFieldDoesNotContain("failureCode", event.failureCode(), rawMessage);
            assertFieldDoesNotContain("safeFailureMessage", event.safeFailureMessage(), rawMessage);
            assertFieldDoesNotContain("path", event.path(), rawMessage);
            assertFieldDoesNotContain("method", event.method(), rawMessage);
            assertFieldDoesNotContain("operationId", event.operationId(), rawMessage);
            assertFieldDoesNotContain("routeTemplate", event.routeTemplate(), rawMessage);
        }

        private static void assertFieldDoesNotContain(String fieldName, String value, String forbidden) {
            if (value != null) {
                assertTrue(
                        !value.contains(forbidden),
                        fieldName + " must not contain the raw exception message '" + forbidden + "' but was: "
                                + value);
            }
        }
    }

    @Nested
    @DisplayName("Pre-operation 4xx path")
    class PreOperationPath {

        @Test
        @DisplayName("Exactly one event for a 400 response; operationId is null (no operation route matched)")
        void emitsOneEventFor4xx(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(
                    vertx, em, rc -> rc.response().setStatusCode(400).end());

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(400, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size());
                            RestRequestCompletedEvent event = captured.get(0);
                            assertEquals(400, event.statusCode());
                            // operationId is null: /test is no operation route, so no route identity was recorded
                            assertNull(event.operationId(), "operationId must be null: no operation route matched");
                        });
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        /**
         * Mounts the same {@link RestRequestCompletionEmitter} instance on two routes at the same
         * priority, so {@link RestRequestCompletionEmitter#handle(RoutingContext)} runs twice for a
         * single request. The emitter's exactly-once guard must collapse that double mount to exactly
         * one event; without the guard this test observes two.
         */
        @Test
        @DisplayName("Guard collapses double registration from a doubly-mounted emitter to one event")
        void guardPreventsDoubleEmitWhenHandlerMountedTwice(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));

            Promise<Void> barrier = Promise.promise();
            Router router = Router.router(vertx);
            router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
            router.route().order(em.priority()).handler(em);
            router.route().order(em.priority()).handler(em);
            router.route().handler(barrierHandler(barrier));
            router.route("/test").handler(rc -> rc.response().setStatusCode(200).end());

            startServer(router)
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, barrier.future());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertEquals(
                                1,
                                captured.size(),
                                "the exactly-once guard must collapse the double mount to one event"));
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("Listener isolation")
    class ListenerIsolation {

        @Test
        @DisplayName("A throwing listener does not prevent other listeners from receiving the event")
        void throwingListenerDoesNotBlockOthers(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> secondListenerCapture = new ArrayList<>();
            RestRequestCompletedListener throwing = event -> {
                throw new RuntimeException("listener-boom");
            };
            RestRequestCompletedListener capturing = secondListenerCapture::add;

            RestRequestCompletionEmitter em = emitter(Set.of(throwing, capturing));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertEquals(
                                1, secondListenerCapture.size(), "capturing listener must still receive the event"));
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("No listeners: emitter completes silently without exception")
        void noListenersCompleteSilently(VertxTestContext ctx) {
            RestRequestCompletionEmitter em = emitter(Set.of());
            // barrier unobserved: this test asserts via the drained response, not captured state
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .map((HttpResponse<Buffer> resp) -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        // No explicit drain: the WebClient has already aggregated the response body
                        // by the time send() completes, so the shared client's pooled connection is
                        // never left with an unread response.
                        return resp.body();
                    })
                    .onComplete(ctx.succeeding(body -> ctx.completeNow()));
        }
    }

    @Nested
    @DisplayName("Context capture")
    class ContextCapture {

        @Test
        @DisplayName("SecurityContext and CorrelationContext are captured when bound")
        void capturesSecurityAndCorrelationContext(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            AtomicBoolean securityContextSeen = new AtomicBoolean(false);
            AtomicBoolean correlationContextSeen = new AtomicBoolean(false);

            // Mock SecurityContext and SecurityRuntime.
            // snapshot() is a default method on the interface; Mockito returns null for default
            // methods unless explicitly stubbed. Stub it with a concrete SecurityContextSnapshot
            // so the emitter's sec.snapshot() call produces a non-null value.
            SecurityContext mockSec = mock(SecurityContext.class);
            SecurityIdentity stubIdentity =
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "test-user", Map.of()));
            AuthenticationState stubAuth = new AuthenticationState(
                    DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());
            SecurityContextSnapshot stubSnapshot =
                    new SecurityContextSnapshot(stubIdentity, stubAuth, Optional.empty());
            when(mockSec.snapshot()).thenReturn(stubSnapshot);
            when(mockSec.origin()).thenReturn(Optional.empty());
            SecurityRuntime mockRuntime = mock(SecurityRuntime.class);
            when(mockRuntime.current()).thenReturn(mockSec);

            // Use DefaultContextHolder so we can bind CorrelationContext
            DefaultContextHolder holder = new DefaultContextHolder();

            RestRequestCompletionEmitter em = emitter(mockRuntime, holder, Set.of(event -> {
                if (event.securityContextSnapshot() != null) {
                    securityContextSeen.set(true);
                }
                if (event.correlationContext() != null) {
                    correlationContextSeen.set(true);
                }
                captured.add(event);
            }));

            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {
                // Bind a CorrelationContext on the holder for this request
                CorrelationIdentifier reqId = new CorrelationIdentifier("req-1", "test");
                CorrelationIdentifier corrId = new CorrelationIdentifier("corr-1", "test");
                dev.vertique.correlation.CorrelationContextFactory factory =
                        new dev.vertique.correlation.CorrelationContextFactory(Optional.empty());
                CorrelationContext corrCtx = factory.create(reqId, corrId);
                RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
                lifecycle.onClose(holder.bind(CorrelationContext.class, corrCtx));
                rc.response().setStatusCode(200).end();
            });

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size());
                            assertTrue(securityContextSeen.get(), "SecurityContext must be captured");
                            assertTrue(correlationContextSeen.get(), "CorrelationContext must be captured");
                        });
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("Correlation snapshot immutability")
    class CorrelationSnapshotImmutability {

        /**
         * Proves that the emitter captures an immutable snapshot rather than the live
         * {@link CorrelationContext} reference. After the event is emitted we mutate the
         * mutable stub context; the event's {@code correlationContext()} must still reflect
         * the original value captured at emission time.
         */
        @Test
        @DisplayName("Mutating the live context after emission does not change the event's snapshot")
        void snapshotIsolatedFromLiveMutation(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();

            // A mutable stub whose requestId() delegate can be switched after snapshot capture.
            MutableStubCorrelationContext mutableCtx = new MutableStubCorrelationContext("req-original");

            DefaultContextHolder holder = new DefaultContextHolder();

            RestRequestCompletionEmitter em = emitter(holder, captured::add);

            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {
                // Bind the mutable stub as the live CorrelationContext for this request.
                RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
                lifecycle.onClose(holder.bind(CorrelationContext.class, mutableCtx));
                rc.response().setStatusCode(200).end();
            });

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "exactly one event must be emitted");
                            RestRequestCompletedEvent event = captured.get(0);
                            CorrelationContextSnapshot snap = event.correlationContext();
                            assertNotNull(snap, "correlationContext snapshot must be captured");

                            // Snapshot captured "req-original" at emission time.
                            assertEquals(
                                    "req-original",
                                    snap.requestId().value(),
                                    "snapshot must hold the value at emission time");

                            // Mutate the live stub AFTER emission — the snapshot must be unchanged.
                            mutableCtx.updateRequestId("req-MUTATED");
                            assertEquals(
                                    "req-original",
                                    snap.requestId().value(),
                                    "snapshot must be isolated from post-emission mutation of the live context");
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Creates an emitter with no security runtime and the given holder + single listener.
         */
        private static RestRequestCompletionEmitter emitter(
                ContextHolder holder, RestRequestCompletedListener listener) {
            return new RestRequestCompletionEmitter(Optional.empty(), holder, Set.of(listener));
        }
    }

    @Nested
    @DisplayName("WebSocket upgrade exclusion (Fix B)")
    class WebSocketUpgradeExclusion {

        /**
         * Proves the mechanism behind the WebSocket upgrade exclusion against a real router and
         * HTTP server: the emitter registers its completion callback via
         * {@code ctx.addEndHandler(...)}, a Vert.x routing-context end handler distinct from
         * {@link RequestContextLifecycle.Handle#completeNow()}, which drives only the
         * {@code Handle}'s own {@code onClose}/{@code afterClose} registrations. Calling
         * {@code completeNow()} therefore does not, by itself, fire the emitter's callback — the
         * callback still fires, exactly once, when the response actually ends via the normal
         * {@code ctx.addEndHandler} path.
         *
         * <p>{@code Handle} completion semantics in isolation (idempotency, late-registration
         * guards, {@code afterClose} ordering relative to {@code completeNow()}) are proven by
         * {@code RequestContextLifecycleTest#completeNowShouldDriveCleanupSynchronously},
         * {@code #completeNowTwiceShouldBeNoOp}, and
         * {@code #endHandlerAfterCompleteNowShouldBeNoOp}. This class proves the
         * emitter-specific consequence: {@code completeNow()} neither emits nor suppresses the
         * end-handler-driven {@link RestRequestCompletedEvent}.
         *
         * <p>A successful WebSocket 101 upgrade calls {@code lifecycle.completeNow()} because
         * Vert.x Web 5.1.2's {@code Http1xServerResponse.completeHandshake()} writes the 101
         * response without firing the normal response end handler. No test in
         * {@code vertique-rest-websocket} references {@link RestRequestCompletedEvent} or
         * {@link RestRequestCompletionEmitter}; the end-to-end real-server upgrade-exclusion proof
         * is deferred (release-triage ledger).
         *
         * <p>Cross-reference: {@code WebSocketEndpointRegistrar.handleUpgrade()} (line ~298).
         */
        @Test
        @DisplayName("completeNow() neither emits nor suppresses the end-handler-driven completion event")
        void completeNowNeitherEmitsNorSuppressesCompletionEvent(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));

            // The lifecycle barrier (barrierHandler / routerWithBarrier) is not a useful signal for
            // this test: the terminal handler below calls Handle.completeNow(), which drives
            // closeAll() — and therefore any afterClose-registered barrier — synchronously, before
            // the response even ends (see the caveat on barrierHandler's javadoc). Instead, this
            // test builds its router inline with a sentinel middleware mounted between
            // RequestContextLifecycle and the emitter, registering its own ctx.addEndHandler that
            // completes `sentinel`. Because Vert.x Web fires end handlers in reverse registration
            // order, and this sentinel middleware registers its end handler AFTER the lifecycle but
            // BEFORE the emitter, the firing order is: emitter's end handler (populates `captured`)
            // first, this sentinel's end handler second, RequestContextLifecycle's end handler last.
            // Completing `sentinel` therefore happens-after the emitter's captured.add(...) call, on
            // the same event-loop thread — giving the final assertion a real happens-before edge
            // instead of racing an early-burned lifecycle barrier. completeNow() cannot prematurely
            // satisfy `sentinel` because completeNow() drives only the Handle's own onClose/afterClose
            // registrations, never ctx end handlers.
            Promise<Void> sentinel = Promise.promise();

            Router router = Router.router(vertx);
            router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
            router.route().order(RequestContextLifecycle.ORDER + 1).handler(rc -> {
                rc.addEndHandler(v -> sentinel.complete());
                rc.next();
            });
            router.route().order(em.priority()).handler(em);
            router.route("/test").handler(rc -> {
                RequestContextLifecycle.fromRoutingContext(rc).completeNow();
                // Non-vacuous: an implementation that wired the emitter's emission to
                // Handle.completeNow() instead of ctx.addEndHandler would already have
                // populated captured by this point, before the response has even ended.
                ctx.verify(() -> assertTrue(
                        captured.isEmpty(), "completeNow() must not trigger the emitter's completion event"));
                if (!rc.response().ended()) {
                    rc.response().setStatusCode(200).end();
                }
            });

            startServer(router)
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, sentinel.future(), "sentinel end handler");
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertEquals(
                                1,
                                captured.size(),
                                "the end-handler-driven emission must still fire exactly once after the "
                                        + "response ends; completeNow() neither emits nor suppresses it"));
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("CaptureCoordinator")
    class CaptureCoordinator {

        @Test
        @DisplayName("coordinator receives the same event the safe listeners got and the live RoutingContext")
        void coordinatorReceivesEventAndRoutingContext(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> listenerCapture = new ArrayList<>();
            AtomicReference<RestRequestCompletedEvent> coordinatorEvent = new AtomicReference<>();
            AtomicReference<RoutingContext> coordinatorRc = new AtomicReference<>();

            RestRequestCaptureCoordinator coordinator = (event, rc) -> {
                coordinatorEvent.set(event);
                coordinatorRc.set(rc);
            };

            RestRequestCompletionEmitter em =
                    emitterWithCoordinators(Set.of(listenerCapture::add), Set.of(coordinator));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, listenerCapture.size(), "safe listener must receive exactly one event");
                            assertNotNull(coordinatorEvent.get(), "coordinator must receive an event");
                            assertSame(
                                    listenerCapture.get(0),
                                    coordinatorEvent.get(),
                                    "coordinator must receive the identical event object the safe listener got");
                            assertNotNull(coordinatorRc.get(), "coordinator must receive the live RoutingContext");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName(
                "a throwing coordinator does not break completion and does not prevent safe listeners from running")
        void throwingCoordinatorDoesNotBreakCompletion(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> listenerCapture = new ArrayList<>();
            RestRequestCaptureCoordinator throwing = (event, rc) -> {
                throw new RuntimeException("coordinator-boom");
            };

            RestRequestCompletionEmitter em = emitterWithCoordinators(Set.of(listenerCapture::add), Set.of(throwing));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        // response must still complete normally despite the coordinator throwing
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() ->
                                assertEquals(1, listenerCapture.size(), "safe listener must still receive the event"));
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("with no coordinators registered the emitter completes without error (pure no-op)")
        void noCoordinatorsIsNoOp(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> listenerCapture = new ArrayList<>();
            RestRequestCompletionEmitter em = emitterWithCoordinators(Set.of(listenerCapture::add), Set.of());
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() ->
                                assertEquals(1, listenerCapture.size(), "safe listener must still receive event"));
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("coordinator is invoked after the safe listener set; safe listener path unchanged")
        void coordinatorInvokedAfterSafeListeners(VertxTestContext ctx) {
            List<String> order = new ArrayList<>();

            RestRequestCompletedListener listener = event -> order.add("listener");
            RestRequestCaptureCoordinator coordinator = (event, rc) -> order.add("coordinator");

            RestRequestCompletionEmitter em = emitterWithCoordinators(Set.of(listener), Set.of(coordinator));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(
                                    List.of("listener", "coordinator"),
                                    order,
                                    "safe listener must run before coordinator");
                        });
                        ctx.completeNow();
                    }));
        }
    }

    // --- RequestCompletionScope ---

    /**
     * Helper that creates an emitter with a single completion scope and listener set.
     *
     * <p>Uses the primary constructor passing a {@code Set.of(scope)} (the new multibind API).
     *
     * @param scope     the completion scope to install
     * @param listeners the listeners to notify
     * @return a new emitter instance
     */
    private static RestRequestCompletionEmitter emitterWithScope(
            RequestCompletionScope scope, Set<RestRequestCompletedListener> listeners) {
        return new RestRequestCompletionEmitter(
                Optional.empty(), new DefaultContextHolder(), listeners, Set.of(), Set.of(scope));
    }

    /**
     * Helper that creates an emitter with a given set of completion scopes and listener set.
     *
     * @param scopes    the completion scopes to install
     * @param listeners the listeners to notify
     * @return a new emitter instance
     */
    private static RestRequestCompletionEmitter emitterWithScopes(
            Set<RequestCompletionScope> scopes, Set<RestRequestCompletedListener> listeners) {
        return new RestRequestCompletionEmitter(
                Optional.empty(), new DefaultContextHolder(), listeners, Set.of(), scopes);
    }

    @Nested
    @DisplayName("RequestCompletionScope")
    class CompletionScopeTests {

        @Test
        @DisplayName(
                "scope is open during listener dispatch: listener observes thread-local marker set by scope.open()")
        void scopeIsOpenDuringListenerDispatch(VertxTestContext ctx) {
            ThreadLocal<Boolean> marker = new ThreadLocal<>();
            AtomicBoolean markerSeenByListener = new AtomicBoolean(false);

            RequestCompletionScope scope = rc -> {
                marker.set(Boolean.TRUE);
                return () -> marker.remove();
            };

            RestRequestCompletedListener listener = event -> {
                if (Boolean.TRUE.equals(marker.get())) {
                    markerSeenByListener.set(true);
                }
            };

            RestRequestCompletionEmitter em = emitterWithScope(scope, Set.of(listener));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertTrue(
                                    markerSeenByListener.get(),
                                    "listener must observe the thread-local marker set by scope.open()");
                            // Marker must be cleared after dispatch
                            assertTrue(
                                    marker.get() == null || !Boolean.TRUE.equals(marker.get()),
                                    "thread-local marker must be cleared after close()");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("empty set scope: behavior identical to baseline (no bracket overhead)")
        void emptyScopeBehavesAsBaseline(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            // Use the convenience constructor — no scope
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() ->
                                assertEquals(1, captured.size(), "exactly one event must be emitted without a scope"));
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("scope whose open() throws: listeners still run, one WARN, no failure propagated")
        void throwingOpenDoesNotBreakListeners(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();

            RequestCompletionScope throwingScope = rc -> {
                throw new RuntimeException("simulated open failure");
            };

            RestRequestCompletionEmitter em = emitterWithScope(throwingScope, Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertEquals(
                                1,
                                captured.size(),
                                "listener must still receive the event even when scope.open() throws"));
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("scope whose close() throws: no failure propagated, listeners already ran")
        void throwingCloseDoesNotBreakDispatch(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();

            RequestCompletionScope throwingCloseScope = rc -> () -> {
                throw new RuntimeException("simulated close failure");
            };

            RestRequestCompletionEmitter em = emitterWithScope(throwingCloseScope, Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertEquals(
                                1,
                                captured.size(),
                                "listener must receive the event; scope.close() failure must not propagate"));
                        ctx.completeNow();
                    }));
        }

        /**
         * Two scopes: both open, listener sees both markers active; scopes close in reverse order
         * after dispatch (proven by a close-order recorder).
         */
        @Test
        @DisplayName("two scopes: both open before dispatch, both markers visible to listener, "
                + "closed in reverse order after dispatch")
        void twoScopesBothOpenAndCloseInReverseOrder(VertxTestContext ctx) {
            // Use thread-locals as markers to prove each scope is open during listener dispatch.
            ThreadLocal<Boolean> markerA = new ThreadLocal<>();
            ThreadLocal<Boolean> markerB = new ThreadLocal<>();
            // Record open/close order in a concurrent list (end handler runs on the event loop).
            List<String> order = new CopyOnWriteArrayList<>();

            // scopeA: sets markerA; records "openA" on open, "closeA" on close
            RequestCompletionScope scopeA = rc -> {
                markerA.set(Boolean.TRUE);
                order.add("openA");
                return () -> {
                    markerA.remove();
                    order.add("closeA");
                };
            };

            // scopeB: sets markerB; records "openB" on open, "closeB" on close
            RequestCompletionScope scopeB = rc -> {
                markerB.set(Boolean.TRUE);
                order.add("openB");
                return () -> {
                    markerB.remove();
                    order.add("closeB");
                };
            };

            AtomicBoolean markerASeenByListener = new AtomicBoolean(false);
            AtomicBoolean markerBSeenByListener = new AtomicBoolean(false);
            RestRequestCompletedListener listener = event -> {
                order.add("listener");
                if (Boolean.TRUE.equals(markerA.get())) {
                    markerASeenByListener.set(true);
                }
                if (Boolean.TRUE.equals(markerB.get())) {
                    markerBSeenByListener.set(true);
                }
            };

            // Use a LinkedHashSet-backed set that preserves insertion order so open order
            // is deterministic: A first, B second.
            Set<RequestCompletionScope> scopes = new java.util.LinkedHashSet<>();
            scopes.add(scopeA);
            scopes.add(scopeB);

            RestRequestCompletionEmitter em = emitterWithScopes(scopes, Set.of(listener));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertTrue(markerASeenByListener.get(), "scopeA must be open during listener dispatch");
                            assertTrue(markerBSeenByListener.get(), "scopeB must be open during listener dispatch");

                            // Open order: A then B (iteration order of the LinkedHashSet)
                            // Close order: B then A (reverse of open order)
                            // Listener runs after both opens
                            int idxOpenA = order.indexOf("openA");
                            int idxOpenB = order.indexOf("openB");
                            int idxListener = order.indexOf("listener");
                            int idxCloseB = order.indexOf("closeB");
                            int idxCloseA = order.indexOf("closeA");

                            assertTrue(idxOpenA >= 0, "openA must have been recorded");
                            assertTrue(idxOpenB >= 0, "openB must have been recorded");
                            assertTrue(idxListener >= 0, "listener must have been recorded");
                            assertTrue(idxCloseA >= 0, "closeA must have been recorded");
                            assertTrue(idxCloseB >= 0, "closeB must have been recorded");

                            // Both opens happen before listener
                            assertTrue(idxOpenA < idxListener, "scopeA must open before listener; order=" + order);
                            assertTrue(idxOpenB < idxListener, "scopeB must open before listener; order=" + order);

                            // Both closes happen after listener
                            assertTrue(idxListener < idxCloseA, "closeA must happen after listener; order=" + order);
                            assertTrue(idxListener < idxCloseB, "closeB must happen after listener; order=" + order);

                            // Reverse close order: B closes before A (reverse of open order A→B)
                            assertTrue(
                                    idxCloseB < idxCloseA,
                                    "scopes must close in reverse open order (closeB before closeA); order=" + order);
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * One scope's open() throws; the OTHER scope still opens and brackets dispatch; listener
         * still runs; one WARN is logged for the failing scope.
         */
        @Test
        @DisplayName("one of two scopes throws on open: the other scope still brackets dispatch, listener still runs")
        void oneOfTwoScopesThrowsOnOpen_OtherScopeStillBracketsDispatch(VertxTestContext ctx) {
            ThreadLocal<Boolean> markerGood = new ThreadLocal<>();
            AtomicBoolean markerGoodSeenByListener = new AtomicBoolean(false);
            AtomicInteger openGoodCount = new AtomicInteger(0);
            AtomicInteger closeGoodCount = new AtomicInteger(0);

            RequestCompletionScope throwingScope = rc -> {
                throw new RuntimeException("scope-open-boom");
            };

            RequestCompletionScope goodScope = rc -> {
                markerGood.set(Boolean.TRUE);
                openGoodCount.incrementAndGet();
                return () -> {
                    markerGood.remove();
                    closeGoodCount.incrementAndGet();
                };
            };

            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletedListener listener = event -> {
                if (Boolean.TRUE.equals(markerGood.get())) {
                    markerGoodSeenByListener.set(true);
                }
                captured.add(event);
            };

            // Two scopes: throwing first (via LinkedHashSet), good second
            Set<RequestCompletionScope> scopes = new java.util.LinkedHashSet<>();
            scopes.add(throwingScope);
            scopes.add(goodScope);

            RestRequestCompletionEmitter em = emitterWithScopes(scopes, Set.of(listener));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "listener must still receive the event");
                            assertTrue(
                                    markerGoodSeenByListener.get(),
                                    "good scope's marker must be visible to the listener");
                            assertEquals(1, openGoodCount.get(), "good scope must have been opened exactly once");
                            assertEquals(1, closeGoodCount.get(), "good scope must have been closed exactly once");
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * One scope's close() throws; the other scope still closes; no failure propagated.
         */
        @Test
        @DisplayName("one of two scopes throws on close: the other scope still closes, no failure propagated")
        void oneOfTwoScopesThrowsOnClose_OtherScopeStillCloses(VertxTestContext ctx) {
            AtomicInteger closedGoodCount = new AtomicInteger(0);
            AtomicInteger closedBadCount = new AtomicInteger(0);

            RequestCompletionScope throwingCloseScope = rc -> () -> {
                closedBadCount.incrementAndGet();
                throw new RuntimeException("scope-close-boom");
            };

            RequestCompletionScope goodCloseScope = rc -> () -> closedGoodCount.incrementAndGet();

            List<RestRequestCompletedEvent> captured = new ArrayList<>();

            // Both scopes inserted; order doesn't matter for this test — we just need both closes exercised
            Set<RequestCompletionScope> scopes = new java.util.LinkedHashSet<>();
            scopes.add(throwingCloseScope);
            scopes.add(goodCloseScope);

            RestRequestCompletionEmitter em = emitterWithScopes(scopes, Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "listener must still receive the event");
                            // Both close() were invoked regardless of the first one throwing
                            assertEquals(1, closedBadCount.get(), "throwing scope's close must have been called");
                            assertEquals(1, closedGoodCount.get(), "good scope's close must also have been called");
                        });
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("Wire-failure enrichment")
    class WireFailureEnrichment {

        @Test
        @DisplayName("KEY_WIRE_FAILURE marker present on an otherwise-clean completion populates wireFailureCode")
        void emitPopulatesWireFailureCodeFromMarker(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {
                rc.put(RestRequestCompletionEmitter.KEY_WIRE_FAILURE, new IllegalStateException("truncated stream"));
                rc.response().setStatusCode(200).end();
            });

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "exactly one event must be emitted");
                            assertEquals(
                                    "IllegalStateException",
                                    captured.get(0).wireFailureCode(),
                                    "marker present -> wireFailureCode set to the cause's simple class name");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("No marker and a clean end -> wireFailureCode stays null")
        void emitLeavesWireFailureCodeNullOnCleanCompletion(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {});

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "exactly one event must be emitted");
                            assertNull(
                                    captured.get(0).wireFailureCode(),
                                    "clean completion -> wireFailureCode must stay null");
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * The precedence and normalization rules exercised here are pure branches of
         * {@link RestRequestCompletionEmitter#wireFailureCode(Throwable, io.vertx.core.AsyncResult)}
         * — asserted directly against the static method rather than through a router/socket, since
         * neither input requires a live request to construct.
         */
        @Test
        @DisplayName("Marker present AND a failed end-handler result -> the marker's cause wins")
        void markerWinsOverEndHandlerFailure() {
            Throwable marker = new IllegalStateException("marker-cause");
            Future<Void> endResult = Future.failedFuture(new RuntimeException("end-handler-cause, ignored"));

            String wireFailureCode = RestRequestCompletionEmitter.wireFailureCode(marker, endResult);

            assertEquals(
                    "IllegalStateException",
                    wireFailureCode,
                    "the marker's cause must win over the end-handler failure, regardless of either message");
        }

        /**
         * Pins the connection-close normalization, and doubles as the upgrade tripwire for it.
         *
         * <p>{@code io.vertx.core.impl.NoStackTraceThrowable} is Vert.x internal API, deprecated and
         * marked for removal. The production predicate in {@code normalizeWireFailureCause} is
         * already written to survive that — it compares {@code getClass().getName()} against the
         * class name as a <em>string</em> and never imports the type — because this is a
         * catch-and-inspect case: Vert.x throws the exception at us and the framework only has to
         * recognize it, matching on class name <em>and</em> message so an unrelated exception
         * carrying "Connection closed" cannot collide.
         *
         * <p>This test is the one place that imports the type, and that is deliberate. When the
         * upgrade lands that removes it, this test stops compiling — which is the signal to revisit
         * the predicate. Without it the predicate would silently stop matching and the metric label
         * would regress from {@code ConnectionClosed} to whatever the replacement class is called,
         * with nothing failing to say so.
         *
         * <p>So on that compile error: do not delete this test to make it go away. Find what Vert.x
         * throws for a closed connection now, update the predicate and this test together, and
         * confirm the label still comes out {@code ConnectionClosed}.
         */
        @Test
        @DisplayName("NoStackTraceThrowable+\"Connection closed\" normalizes to ConnectionClosed; "
                + "StreamResetException and an unrelated same-message exception do not")
        void emitNormalizesConnectionClosedFromFailedEndHandler() {
            assertEquals(
                    "ConnectionClosed",
                    RestRequestCompletionEmitter.wireFailureCode(
                            null, Future.failedFuture(new NoStackTraceThrowable("Connection closed"))),
                    "NoStackTraceThrowable+\"Connection closed\" must normalize");
            assertEquals(
                    "StreamResetException",
                    RestRequestCompletionEmitter.wireFailureCode(
                            null, Future.failedFuture(new StreamResetException(0L))),
                    "StreamResetException must keep its own simple class name");
            assertEquals(
                    "RuntimeException",
                    RestRequestCompletionEmitter.wireFailureCode(
                            null, Future.failedFuture(new RuntimeException("Connection closed"))),
                    "an unrelated exception with the same message must not normalize (class+message predicate)");
        }

        @Test
        @DisplayName("emit(ctx, state, endResult) wiring: a failed end-handler result with no marker populates "
                + "wireFailureCode on the emitted event")
        void emitPopulatesWireFailureCodeFromFailedEndHandlerWiring(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            // No KEY_WIRE_FAILURE marker is set here — only the endResult channel carries a failure,
            // proving emit(ctx, state, endResult) actually threads that argument into the emitted event
            // (the seam markerWinsOverEndHandlerFailure above no longer exercises end-to-end).
            RouterWithBarrier rb = routerWithBarrier(
                    vertx,
                    em,
                    rc -> em.emit(
                            rc,
                            RequestCompletionRecorder.boundState(rc),
                            Future.failedFuture(new RuntimeException("client vanished"))));

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, captured.size(), "exactly one event must be emitted");
                            assertEquals(
                                    "RuntimeException",
                                    captured.get(0).wireFailureCode(),
                                    "the endResult channel must populate wireFailureCode when no marker is set");
                        });
                        ctx.completeNow();
                    }));
        }
    }

    // --- Route identity and framework-owned completion state ---
    //
    // These proofs (rest-025 TP-004 to TP-012, TP-015 and TP-016) run on a real server whose operation
    // routes sit on a sub-router mounted at /api/*, the HttpVerticle mount shape. Each identity route
    // starts with RequestCompletionRecorder.operationRouteHandler, as the JAX-RS route registrar installs
    // it, and each proof awaits the first-pass sentinel, which fires after emission, before asserting.

    /** Mount path of the sub-router that holds every operation route of these proofs. */
    private static final String API_MOUNT = "/api/*";

    /** Request header naming the request a sentinel end belongs to, when a proof sends more than one. */
    private static final String CASE_HEADER = "X-Case";

    /** Delay, in milliseconds, before {@link Reroute}'s first pass reroutes, so a re-taken start time shows. */
    private static final long REROUTE_DELAY_MS = 20;

    /** Test-local descriptor for {@code GET /items}, operation {@code listItems}. */
    private static final RestOperationDescriptor LIST_ITEMS = new TestOperation("listItems", "GET", "/items");

    /** Test-local descriptor for {@code GET /a}, operation {@code getA}. */
    private static final RestOperationDescriptor OPERATION_A = new TestOperation("getA", "GET", "/a");

    /** Test-local descriptor for {@code GET /b}, operation {@code getB}. */
    private static final RestOperationDescriptor OPERATION_B = new TestOperation("getB", "GET", "/b");

    /** Test-local descriptor for the wildcard route {@code GET /items/*}, operation {@code itemsWildcard}. */
    private static final RestOperationDescriptor WILDCARD_ITEMS = new TestOperation("itemsWildcard", "GET", "/items/*");

    /** Test-local descriptor for {@code GET /items/special}, operation {@code specialItem}. */
    private static final RestOperationDescriptor SPECIAL_ITEM =
            new TestOperation("specialItem", "GET", "/items/special");

    /** Terminal route handler that responds 200 with no body. */
    private static final Handler<RoutingContext> RESPOND_OK = RestRequestCompletionEmitterTest::respondOk;

    /** TP-012 (a) and (c): exactly one event, carrying {@code listItems}. */
    private static final ClaimExpectation ONE_LIST_ITEMS_EVENT = new ClaimExpectation(1, LIST_ITEMS);

    /**
     * TP-012 (b), T002's interim reading of an OTHER claim: the request still emits exactly one
     * {@link RestRequestCompletedEvent}, with {@code null} identity, because within the pass the operation
     * route leaves OTHER unchanged. T003's claim-based dispatch changes this expectation to "no
     * {@code RestRequestCompletedEvent}".
     */
    private static final ClaimExpectation OTHER_CLAIM_INTERIM = new ClaimExpectation(1, null);

    /** TP-012 (d): no emitter is mounted, so there is no holder and no event. */
    private static final ClaimExpectation NO_EMITTER_NO_EVENT = new ClaimExpectation(0, null);

    /** TP-012 (e): the request stays unclaimed, as with holder removal, and still emits exactly once. */
    private static final ClaimExpectation ONE_UNCLAIMED_EVENT = new ClaimExpectation(1, null);

    /**
     * Responds 200 with no body.
     *
     * @param rc the routing context to answer
     */
    private static void respondOk(RoutingContext rc) {
        rc.response().setStatusCode(200).end();
    }

    /**
     * Adds a route at {@code path} for {@code operation}'s HTTP method whose first handler is the
     * operation's identity handler, {@link RequestCompletionRecorder#operationRouteHandler}, as the JAX-RS
     * route registrar installs it.
     *
     * @param api       the sub-router to add the route to
     * @param path      the Vert.x route path, relative to the {@code /api/*} mount
     * @param operation the operation the route serves
     * @return the route, ready for its next handler
     */
    private static Route operationRoute(Router api, String path, RestOperationDescriptor operation) {
        return api.route(HttpMethod.valueOf(operation.httpMethod()), path)
                .handler(RequestCompletionRecorder.operationRouteHandler(operation));
    }

    /**
     * Adds {@code GET /items}: the {@code listItems} identity handler, then 200.
     *
     * @param api the sub-router to add the route to
     */
    private static void listItemsRoute(Router api) {
        operationRoute(api, "/items", LIST_ITEMS).handler(RESPOND_OK);
    }

    /**
     * Wires the standard root, {@link RequestContextLifecycle}, the first-pass sentinel and the emitter, in
     * front of an {@code /api/*} sub-router holding {@code apiRoutes}.
     *
     * @param emitter   the emitter to mount once
     * @param apiRoutes adds the sub-router's routes
     * @return the wired root
     */
    private static Wired subRouterWith(RestRequestCompletionEmitter emitter, Consumer<Router> apiRoutes) {
        return RootWiring.around(emitter).mountApi(apiRoutes);
    }

    /**
     * Wires a root with no {@link RequestContextLifecycle}: the first-pass sentinel at
     * {@code emitter.priority() - 1}, then the same emitter instance mounted twice at its priority (the
     * {@link Idempotency} shape), in front of an {@code /api/*} sub-router holding {@code apiRoutes}.
     *
     * @param emitter   the emitter to mount twice
     * @param apiRoutes adds the sub-router's routes
     * @return the wired root
     */
    private static Wired lifecycleFreeRoot(RestRequestCompletionEmitter emitter, Consumer<Router> apiRoutes) {
        return RootWiring.around(emitter)
                .withoutLifecycle()
                .emitterMountedTwice()
                .mountApi(apiRoutes);
    }

    /**
     * Awaits the first-pass sentinel end of the request sent without an {@code X-Case} header.
     *
     * @param wired the wired root the request was sent to
     * @return a future completing after the request's emission, or failing with a descriptive timeout
     */
    private static Future<Void> awaitFirstPassEnd(Wired wired) {
        return awaitFirstPassEnd(wired, FirstPassSentinel.UNNAMED);
    }

    /**
     * Awaits the first-pass sentinel end of the request whose {@code X-Case} header is {@code caseName}.
     *
     * @param wired    the wired root the request was sent to
     * @param caseName the request's {@code X-Case} header value
     * @return a future completing after the request's emission, or failing with a descriptive timeout
     */
    private static Future<Void> awaitFirstPassEnd(Wired wired, String caseName) {
        return awaitBarrier(
                vertx, wired.sentinel().ended(caseName), "first-pass sentinel end of request '" + caseName + "'");
    }

    /**
     * Sends {@code GET path} on the shared client, naming the request with the {@code X-Case} header.
     *
     * @param port     the server port
     * @param path     the request path
     * @param caseName the request's {@code X-Case} header value
     * @return the response future
     */
    private static Future<HttpResponse<Buffer>> sendCase(int port, String path, String caseName) {
        return client.get(port, "127.0.0.1", path)
                .putHeader(CASE_HEADER, caseName)
                .send();
    }

    /**
     * Asserts an event's route identity: {@code expected}'s {@code operationId()} and
     * {@code routeTemplate()}, or {@code null} for both when {@code expected} is {@code null}.
     *
     * @param expected the operation the event must carry, or {@code null} for no operation
     * @param event    the event to check
     * @param subject  names the request in failure messages
     */
    private static void assertIdentity(
            RestOperationDescriptor expected, RestRequestCompletedEvent event, String subject) {
        if (expected == null) {
            assertAll(
                    subject + ": no operation",
                    () -> assertNull(event.operationId(), subject + ": operationId must be null"),
                    () -> assertNull(event.routeTemplate(), subject + ": routeTemplate must be null"));
        } else {
            assertAll(
                    subject + ": operation " + expected.operationId(),
                    () -> assertEquals(expected.operationId(), event.operationId(), subject + ": operationId"),
                    () -> assertEquals(expected.routeTemplate(), event.routeTemplate(), subject + ": routeTemplate"));
        }
    }

    /**
     * Summarizes events for failure messages as {@code path -> operationId routeTemplate}.
     *
     * @param events the events to summarize
     * @return one summary line per event, in emission order
     */
    private static List<String> identities(List<RestRequestCompletedEvent> events) {
        return events.stream()
                .map(event -> event.path() + " -> " + event.operationId() + " " + event.routeTemplate())
                .toList();
    }

    /**
     * Returns the events of the request whose path is {@code path}.
     *
     * @param events every captured event
     * @param path   the request path to select
     * @return the selected events, in emission order
     */
    private static List<RestRequestCompletedEvent> eventsWithPath(List<RestRequestCompletedEvent> events, String path) {
        return events.stream().filter(event -> path.equals(event.path())).toList();
    }

    /**
     * Writes the four retired {@code rest.events.*} keys with forged values (TP-004, AC-006.1). The keys are
     * deliberate string literals: the constants that named them are removed (FR-012), and writing the keys
     * must have no effect on the event.
     *
     * @param rc the routing context to forge the keys on
     */
    private static void forgeRetiredKeys(RoutingContext rc) {
        rc.put("rest.events.operationId", "forged");
        rc.put("rest.events.routeTemplate", "/forged");
        rc.put("rest.events.startTime", Instant.EPOCH);
        rc.put("rest.events.emitted", Boolean.TRUE);
    }

    /**
     * Removes the framework's per-request completion holder from {@code data()}, through the package-private
     * holder key. FR-006 and D003: removal before an operation route matches can at most prevent the claim;
     * removal after the match has no effect, because the emitter reads only its closure-held state.
     *
     * @param rc the routing context to remove the holder from
     */
    private static void removeCompletionHolder(RoutingContext rc) {
        rc.remove(RequestCompletionRecorder.HOLDER_KEY);
    }

    /**
     * Stores another in-flight request's holder in this request's slot, through the package-private holder
     * key: the copy of AC-006.9. FR-006: a holder bound to another request counts as no holder here, so the
     * copy never changes the other request's event and leaves this request's event at most unclaimed.
     *
     * @param rc     this request's routing context
     * @param holder the other request's holder
     */
    private static void copyForeignHolder(RoutingContext rc, Object holder) {
        rc.put(RequestCompletionRecorder.HOLDER_KEY, holder);
    }

    /**
     * FR-006: the completion state is framework-owned. Writing the retired {@code rest.events.*} keys changes
     * nothing (TP-004, AC-006.1), and neither does removing the holder once the operation route matched
     * (TP-008, AC-006.4).
     */
    @Nested
    @DisplayName("Tamper resistance")
    class TamperResistance {

        /** TP-004: the retired keys neither forge the operation, move the start time, nor suppress emission. */
        @Test
        @DisplayName("Writing the retired rest.events.* keys alters neither the operation, the start time nor emission")
        void retiredKeysDoNotAlterOperationStartTimeOrEmission(VertxTestContext ctx) {
            Instant before = Instant.now();
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            Wired wired = subRouterWith(emitter(Set.of(events::add)), api -> operationRoute(api, "/items", LIST_ITEMS)
                    .handler(rc -> {
                        forgeRetiredKeys(rc);
                        respondOk(rc);
                    }));

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", "/api/items").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(
                                    1,
                                    events.size(),
                                    "exactly one event: a forged rest.events.emitted flag must not suppress emission");
                            RestRequestCompletedEvent event = events.get(0);
                            assertAll(
                                    () -> assertIdentity(LIST_ITEMS, event, "forged identity keys"),
                                    () -> assertFalse(
                                            event.startTime().isBefore(before),
                                            "a forged start time must be ignored: startTime=" + event.startTime()
                                                    + ", test start=" + before));
                        });
                        ctx.completeNow();
                    }));
        }

        /** TP-008: the emitter reads its closure-held state, never the {@code data()} holder. */
        @Test
        @DisplayName("Removing the holder after the operation route matched keeps the operation")
        void holderRemovedAfterMatchKeepsOperation(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            AtomicReference<Instant> responded = new AtomicReference<>();
            Wired wired = subRouterWith(emitter(Set.of(events::add)), api -> operationRoute(api, "/items", LIST_ITEMS)
                    .handler(rc -> {
                        removeCompletionHolder(rc);
                        respondOk(rc);
                    }));

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", "/api/items").send())
                    .compose(resp -> {
                        responded.set(Instant.now());
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, events.size(), "exactly one event");
                            RestRequestCompletedEvent event = events.get(0);
                            assertAll(
                                    () -> assertIdentity(LIST_ITEMS, event, "holder removed after the match"),
                                    () -> assertFalse(
                                            event.startTime().isAfter(responded.get()),
                                            "startTime must not be after the response: startTime=" + event.startTime()
                                                    + ", response=" + responded.get()));
                        });
                        ctx.completeNow();
                    }));
        }
    }

    /**
     * FR-003 and AC-003.1: a reroute re-enters the emitter, which reuses the request's state. The request
     * emits once, with the operation its final pass matched and the start time of its first pass (TP-005).
     */
    @Nested
    @DisplayName("Reroute")
    class Reroute {

        /** Every event the emitter published. */
        private final List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();

        /** One {@link Instant} per routing pass, taken by a ROOT handler ordered after the emitter. */
        private final List<Instant> passMarks = new CopyOnWriteArrayList<>();

        /** TP-005, one row per reroute target. */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.rest.core.events.RestRequestCompletionEmitterTest#rerouteCases")
        @DisplayName("A reroute emits once, with the final operation and the first pass's start time")
        void rerouteEmitsOnceWithFinalOperationAndFirstPassStartTime(RerouteCase rerouteCase, VertxTestContext ctx) {
            Wired wired = rerouteCase(rerouteCase.target());

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", "/api/a").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        // The sentinel registers its end handler on the first pass only: an end handler
                        // registered on the second pass would fire before the emitter's first-pass end
                        // handler, so before emission.
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, events.size(), "exactly one event: " + identities(events));
                            RestRequestCompletedEvent event = events.get(0);
                            assertAll(
                                    () -> assertEquals(2, passMarks.size(), "the ROOT pass marker must run twice"),
                                    () -> assertFalse(
                                            event.startTime().isAfter(passMarks.get(0)),
                                            "startTime must be the first pass's: startTime=" + event.startTime()
                                                    + ", pass marks=" + passMarks),
                                    () -> assertIdentity(rerouteCase.expectedOperation(), event, rerouteCase.name()));
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Builds TP-005's router. {@code GET /api/a} claims {@code getA}, then reroutes to {@code target} on a
         * one-shot {@code REROUTE_DELAY_MS} timer; {@code GET /api/b} claims {@code getB}; {@code GET
         * /api/plain} is no operation route. A ROOT handler after the emitter appends to {@link #passMarks} on
         * every pass.
         *
         * @param target the path {@code GET /api/a} reroutes to
         * @return the wired root
         */
        private Wired rerouteCase(String target) {
            return RootWiring.around(emitter(Set.of(events::add)))
                    .afterEmitter(rc -> {
                        passMarks.add(Instant.now());
                        rc.next();
                    })
                    .mountApi(api -> {
                        operationRoute(api, "/a", OPERATION_A)
                                .handler(rc -> vertx.setTimer(REROUTE_DELAY_MS, id -> rc.reroute(target)));
                        operationRoute(api, "/b", OPERATION_B).handler(RESPOND_OK);
                        api.get("/plain").handler(RESPOND_OK);
                    });
        }
    }

    /**
     * FR-023's claim transitions, observed through the event's identity: a later operation route in the same
     * pass replaces a REST claim (TP-006, AC-003.3); {@code claimForOtherTransport} is ignored once REST has
     * claimed (TP-009, AC-006.5); an OTHER claim holds within a pass and resets on a reroute, and a missing or
     * mistyped holder is a no-op (TP-012).
     */
    @Nested
    @DisplayName("Claim transitions")
    class ClaimTransitions {

        /** TP-006. */
        @Test
        @DisplayName("A later operation route in the same routing pass replaces the claim")
        void laterOperationRouteInSamePassWins(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            Wired wired = subRouterWith(emitter(Set.of(events::add)), api -> {
                // FR-023, REST(op) -> REST(op'): both routes match /items/special. The wildcard route, added
                // first, claims and passes on; the special route, matched later in the same pass, replaces it.
                operationRoute(api, "/items/*", WILDCARD_ITEMS).handler(RoutingContext::next);
                operationRoute(api, "/items/special", SPECIAL_ITEM).handler(RESPOND_OK);
            });

            startServer(wired.root())
                    .compose(port ->
                            client.get(port, "127.0.0.1", "/api/items/special").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, events.size(), "exactly one event: " + identities(events));
                            assertIdentity(SPECIAL_ITEM, events.get(0), "the later operation route in the pass");
                        });
                        ctx.completeNow();
                    }));
        }

        /** TP-009. At T002 an OTHER claim would show as {@code null} identity. */
        @Test
        @DisplayName("claimForOtherTransport after a REST claim is ignored")
        void otherTransportClaimAfterRestClaimIsIgnored(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            Wired wired = subRouterWith(emitter(Set.of(events::add)), api -> operationRoute(api, "/items", LIST_ITEMS)
                    .handler(rc -> {
                        // FR-023: claimForOtherTransport is ignored once a REST or OTHER claim exists.
                        RequestCompletionRecorder.claimForOtherTransport(rc);
                        respondOk(rc);
                    }));

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", "/api/items").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, events.size(), "exactly one event: " + identities(events));
                            assertIdentity(LIST_ITEMS, events.get(0), "REST claim, then claimForOtherTransport");
                        });
                        ctx.completeNow();
                    }));
        }

        /** TP-012, one row per named case, each on the router its named factory builds. */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.rest.core.events.RestRequestCompletionEmitterTest#claimCases")
        @DisplayName(
                "An OTHER claim holds within a pass and resets on a reroute; a missing or foreign holder is a no-op")
        void otherClaimHoldsWithinPassResetsOnRerouteAndMissingHolderIsANoOp(
                ClaimCase claimCase, VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            AtomicInteger status = new AtomicInteger();
            Wired wired = claimCase.router().apply(emitter(Set.of(events::add)));

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", claimCase.requestPath())
                            .send())
                    .compose(resp -> {
                        status.set(resp.statusCode());
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertAll(
                                claimCase.name(),
                                () -> assertEquals(200, status.get(), "the response status"),
                                () -> assertEquals(List.of(), wired.failures(), "no exception may be thrown"),
                                () -> claimCase.expected().assertOn(events, claimCase.name())));
                        ctx.completeNow();
                    }));
        }
    }

    /** AC-006.3: the emitter needs no {@link RequestContextLifecycle} (TP-007). */
    @Nested
    @DisplayName("Lifecycle-free emission")
    class LifecycleFree {

        /** TP-007. */
        @Test
        @DisplayName("Without RequestContextLifecycle a doubly-mounted emitter still emits exactly once per request")
        void emitsExactlyOnceWithoutRequestContextLifecycle(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            List<Integer> statuses = new CopyOnWriteArrayList<>();
            Wired wired =
                    lifecycleFreeRoot(emitter(Set.of(events::add)), RestRequestCompletionEmitterTest::listItemsRoute);

            startServer(wired.root())
                    .compose(port -> sendCase(port, "/api/items", "first")
                            .compose(resp -> {
                                statuses.add(resp.statusCode());
                                return awaitFirstPassEnd(wired, "first");
                            })
                            .compose(v -> sendCase(port, "/api/items", "second"))
                            .compose(resp -> {
                                statuses.add(resp.statusCode());
                                return awaitFirstPassEnd(wired, "second");
                            }))
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertAll(
                                () -> assertEquals(List.of(200, 200), statuses, "both responses"),
                                () -> assertEquals(List.of(), wired.failures(), "no request may fail"),
                                () -> {
                                    assertEquals(
                                            2, events.size(), "exactly one event per request: " + identities(events));
                                    assertListItemsEvent(events.get(0), "the first request");
                                    assertListItemsEvent(events.get(1), "the second request");
                                }));
                        ctx.completeNow();
                    }));
        }

        /**
         * Asserts one request's event carries {@code listItems}.
         *
         * @param event   the request's event
         * @param request names the request in failure messages
         */
        private void assertListItemsEvent(RestRequestCompletedEvent event, String request) {
            assertIdentity(LIST_ITEMS, event, request);
        }
    }

    /**
     * AC-006.8: a response ended on a thread other than the event loop runs the end handlers there, and the
     * emission on that thread sees the claimed operation (TP-010).
     */
    @Nested
    @DisplayName("Foreign-thread end")
    class ForeignThread {

        /** Owns the {@code foreign-end} thread; shut down before the class's server teardown. */
        private final ForeignEnd foreignEnd = new ForeignEnd();

        /**
         * Shuts the {@code foreign-end} executor down with {@code shutdownNow} and awaits its termination.
         * Nested {@code @AfterEach} methods run before the enclosing class's, so this precedes the server close.
         *
         * @throws InterruptedException if interrupted while awaiting termination
         */
        @AfterEach
        void shutDownForeignEnd() throws InterruptedException {
            assertTrue(foreignEnd.shutDown(), "the foreign-end executor must terminate");
        }

        /** TP-010. */
        @Test
        @DisplayName("An end handler on a foreign thread emits once, with the claimed operation")
        void foreignThreadEndEmitsOnceWithClaimedOperation(VertxTestContext ctx) {
            List<ObservedEmission> observed = new CopyOnWriteArrayList<>();
            Promise<Void> listenerRan = Promise.promise();
            RestRequestCompletedListener listener = event -> {
                observed.add(
                        new ObservedEmission(event, Thread.currentThread().getName(), Context.isOnEventLoopThread()));
                listenerRan.tryComplete();
            };
            Wired wired = subRouterWith(emitter(Set.of(listener)), api -> operationRoute(api, "/items", LIST_ITEMS)
                    .handler(foreignEnd::endOnForeignThread));

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", "/api/items").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitFirstPassEnd(wired);
                    })
                    .compose(v -> awaitBarrier(vertx, listenerRan.future(), "completion listener"))
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            ObservedEmission first = observed.get(0);
                            // Precondition (testing.md, "assert the thread directly"): emission ran on the
                            // thread that ended the response, off the event loop, so this proof cannot pass
                            // vacuously on an event-loop thread.
                            assertEquals(
                                    ForeignEnd.THREAD_NAME,
                                    first.threadName(),
                                    "precondition: the listener must run on the thread that ended the response");
                            assertFalse(first.onEventLoop(), "precondition: the listener must run off the event loop");
                            assertEquals(1, observed.size(), "exactly one event");
                            assertIdentity(LIST_ITEMS, first.event(), "response ended on " + first.threadName());
                        });
                        ctx.completeNow();
                    }));
        }
    }

    /**
     * FR-002 across a mount (R-004): identity recorded on a sub-router's {@link RoutingContext} wrapper reaches
     * the root's emission (TP-011).
     */
    @Nested
    @DisplayName("Sub-router")
    class SubRouter {

        /** TP-011. */
        @Test
        @DisplayName("Identity recorded on a sub-router wrapper reaches the root emission")
        void subRouterIdentityReachesRootEmission(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            AtomicReference<RoutingContext> rootContext = new AtomicReference<>();
            AtomicReference<RoutingContext> routeContext = new AtomicReference<>();
            Wired wired = RootWiring.around(emitter(Set.of(events::add)))
                    .afterEmitter(rc -> {
                        rootContext.set(rc);
                        rc.next();
                    })
                    .mountApi(api -> operationRoute(api, "/items", LIST_ITEMS).handler(rc -> {
                        routeContext.set(rc);
                        respondOk(rc);
                    }));

            startServer(wired.root())
                    .compose(port -> client.get(port, "127.0.0.1", "/api/items").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitFirstPassEnd(wired);
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            // Precondition (R-004): the operation route runs on the sub-router's own
                            // RoutingContext wrapper, not on the root context, and both share one data() map.
                            assertNotNull(rootContext.get(), "precondition: the root context was recorded");
                            assertNotNull(routeContext.get(), "precondition: the route context was recorded");
                            assertAll(
                                    "precondition (R-004)",
                                    () -> assertNotSame(
                                            rootContext.get(),
                                            routeContext.get(),
                                            "the operation route must run on a sub-router wrapper"),
                                    () -> assertSame(
                                            rootContext.get().data(),
                                            routeContext.get().data(),
                                            "the wrapper and the root context must share one data() map"));
                            assertEquals(1, events.size(), "exactly one event: " + identities(events));
                            assertIdentity(LIST_ITEMS, events.get(0), "identity recorded on the sub-router wrapper");
                        });
                        ctx.completeNow();
                    }));
        }
    }

    /**
     * AC-006.9 and FR-006: the holder is bound to the request {@code begin} created it for. A holder copied
     * from another in-flight request, A, into this request's slot, B's, never changes A's event, and leaves
     * B's event at most unclaimed, as holder removal does. The rows differ only in where the copy lands, and
     * so in which of the three binding checks meets it (TP-015).
     */
    @Nested
    @DisplayName("Holder binding")
    class HolderBinding {

        /** {@code X-Case} of request A, the other request, whose holder is copied. */
        private static final String REQUEST_A = "A";

        /** {@code X-Case} of request B, this request, whose {@code data()} receives the copy. */
        private static final String REQUEST_B = "B";

        /** Request header selecting the ROOT copy handler, by {@link CopyPoint#header()}. */
        private static final String COPY_HEADER = "X-Copy";

        /** Holds request A open and captures its holder. */
        private final HeldRequest held = new HeldRequest();

        /** Request A's response, awaited by {@link #releaseHeldRequest(VertxTestContext)}. */
        private volatile Future<HttpResponse<Buffer>> aResponse;

        /**
         * Releases request A if a failed row left it held, and awaits A's response, before the enclosing
         * class's server and client teardown runs.
         *
         * @param ctx the test context used to signal teardown completion
         */
        @AfterEach
        void releaseHeldRequest(VertxTestContext ctx) {
            held.release();
            Future<HttpResponse<Buffer>> a = aResponse;
            Future<Void> aDone = a != null ? a.mapEmpty() : Future.succeededFuture();
            awaitBarrier(vertx, aDone, "held request A's response").onComplete(ar -> ctx.completeNow());
        }

        /** TP-015, one row per place the copy lands. */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.rest.core.events.RestRequestCompletionEmitterTest#copyCases")
        @DisplayName("A copied holder leaves the other request's event unchanged and this request's at most unclaimed")
        void copiedHolderLeavesOtherRequestUnchangedAndAtMostUnclaimsThisOne(CopyCase copyCase, VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            AtomicInteger aStatus = new AtomicInteger();
            AtomicInteger bStatus = new AtomicInteger();
            Wired wired = RootWiring.around(emitter(Set.of(events::add)))
                    .beforeEmitter(copyAt(CopyPoint.BEFORE_EMITTER))
                    .afterEmitter(copyAt(CopyPoint.AFTER_EMITTER))
                    .mountApi(api -> {
                        operationRoute(api, "/a", OPERATION_A).handler(held.holdThenEnd());
                        // /a-late is held before its operation route matches, so A is still NONE while held.
                        api.get("/a-late").handler(held.holdThenNext());
                        operationRoute(api, "/a-late", OPERATION_A).handler(RESPOND_OK);
                        operationRoute(api, "/b", OPERATION_B).handler(RESPOND_OK);
                        api.get("/claim").handler(rc -> {
                            RequestCompletionRecorder.claimForOtherTransport(rc);
                            respondOk(rc);
                        });
                    });

            startServer(wired.root())
                    .compose(port -> {
                        aResponse = sendCase(port, copyCase.aPath(), REQUEST_A);
                        return awaitBarrier(vertx, held.captured(), "request A's holder capture")
                                .compose(v -> {
                                    // Preconditions, thrown to fail the chain: A's holder was captured, and A
                                    // is still in flight when B is sent.
                                    assertNotNull(held.holder(), "precondition: request A's holder must be captured");
                                    assertFalse(
                                            wired.sentinel().ended(REQUEST_A).isComplete(),
                                            "precondition: request A must still be in flight when B is sent");
                                    return client.get(port, "127.0.0.1", copyCase.bPath())
                                            .putHeader(CASE_HEADER, REQUEST_B)
                                            .putHeader(
                                                    COPY_HEADER,
                                                    copyCase.copyPoint().header())
                                            .send();
                                })
                                .compose(bResp -> {
                                    bStatus.set(bResp.statusCode());
                                    held.release();
                                    return aResponse;
                                })
                                .compose(aResp -> {
                                    aStatus.set(aResp.statusCode());
                                    return Future.all(
                                                    awaitFirstPassEnd(wired, REQUEST_A),
                                                    awaitFirstPassEnd(wired, REQUEST_B))
                                            .<Void>mapEmpty();
                                });
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            List<RestRequestCompletedEvent> aEvents = eventsWithPath(events, copyCase.aPath());
                            List<RestRequestCompletedEvent> bEvents = eventsWithPath(events, copyCase.bPath());
                            assertAll(
                                    copyCase.name(),
                                    () -> assertEquals(200, aStatus.get(), "request A's status"),
                                    () -> assertEquals(200, bStatus.get(), "request B's status"),
                                    () -> {
                                        assertEquals(
                                                1,
                                                aEvents.size(),
                                                "request A, the other request, emits exactly once: "
                                                        + identities(events));
                                        assertIdentity(OPERATION_A, aEvents.get(0), "request A, the other request");
                                    },
                                    () -> {
                                        assertEquals(
                                                1,
                                                bEvents.size(),
                                                "request B, this request, emits exactly once: " + identities(events));
                                        RestRequestCompletedEvent bEvent = bEvents.get(0);
                                        assertNotEquals(
                                                OPERATION_A.operationId(),
                                                bEvent.operationId(),
                                                "request B's event must never carry A's operationId");
                                        assertNotEquals(
                                                OPERATION_A.routeTemplate(),
                                                bEvent.routeTemplate(),
                                                "request B's event must never carry A's routeTemplate");
                                        assertIdentity(copyCase.expectedB(), bEvent, "request B, this request");
                                    });
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Returns the ROOT copy handler for {@code point}. For a request whose {@code X-Copy} header names
         * {@code point}, it copies A's captured holder into that request's slot; every other request passes.
         *
         * @param point where the handler sits relative to the emitter
         * @return the copy handler
         */
        private Handler<RoutingContext> copyAt(CopyPoint point) {
            return rc -> {
                if (point.header().equals(rc.request().getHeader(COPY_HEADER))) {
                    copyForeignHolder(rc, held.holder());
                }
                rc.next();
            };
        }
    }

    /**
     * FR-006 and the holder lookup: every read of the holder goes through {@code ctx.get(key)}, so a context
     * whose {@code data()} is {@code null}, as a mocked one can be, is a no-holder case (TP-016).
     */
    @Nested
    @DisplayName("Holder lookup")
    class HolderLookup {

        /** TP-016. */
        @Test
        @DisplayName("The recorder tolerates a mocked context whose data() is null")
        void recorderToleratesContextWithNullData() {
            // Consumers' unit tests call the recorder through mocked RoutingContexts. get(key) and request()
            // keep Mockito's default answers, which return null.
            RoutingContext ctx = mock(RoutingContext.class);
            when(ctx.data()).thenReturn(null);

            assertDoesNotThrow(
                    () -> RequestCompletionRecorder.operationRouteHandler(LIST_ITEMS)
                            .handle(ctx),
                    "the identity handler must treat a null data() as no holder");
            verify(ctx, times(1)).next();
            assertDoesNotThrow(
                    () -> RequestCompletionRecorder.claimForOtherTransport(ctx),
                    "claimForOtherTransport must treat a null data() as no holder");
        }
    }

    // --- Parameterized cases ---

    /**
     * A TP-005 row.
     *
     * @param name              the row's display name
     * @param target            the path {@code GET /api/a} reroutes to
     * @param expectedOperation the operation the event must carry, or {@code null} for none
     */
    private record RerouteCase(String name, String target, RestOperationDescriptor expectedOperation) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * TP-005's rows.
     *
     * @return (a) a reroute to another operation route, and (b) a reroute to a plain route
     */
    private static Stream<RerouteCase> rerouteCases() {
        return Stream.of(
                new RerouteCase("(a) rerouted to another operation route", "/api/b", OPERATION_B),
                new RerouteCase(
                        "(b) rerouted to a plain route: the reroute cleared the REST claim", "/api/plain", null));
    }

    /**
     * What a TP-012 row expects of the captured events.
     *
     * @param eventCount the number of events
     * @param operation  the operation the one event carries, or {@code null} for {@code null} identity
     */
    private record ClaimExpectation(int eventCount, RestOperationDescriptor operation) {

        /**
         * Asserts this expectation on the captured events.
         *
         * @param events   the captured events
         * @param caseName names the row in failure messages
         */
        void assertOn(List<RestRequestCompletedEvent> events, String caseName) {
            assertEquals(eventCount, events.size(), caseName + ": event count, events=" + identities(events));
            if (eventCount == 1) {
                RestRequestCompletedEvent event = events.get(0);
                assertNotNull(event.startTime(), caseName + ": startTime must be set");
                assertIdentity(operation, event, caseName);
            }
        }
    }

    /**
     * A TP-012 row.
     *
     * @param name        the row's display name
     * @param requestPath the path of the row's one {@code GET}
     * @param router      the named router factory, given the emitter to mount
     * @param expected    what the row expects of the captured events
     */
    private record ClaimCase(
            String name,
            String requestPath,
            Function<RestRequestCompletionEmitter, Wired> router,
            ClaimExpectation expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * TP-012's rows, (e) as two sub-cases.
     *
     * @return the rows in contract order
     */
    private static Stream<ClaimCase> claimCases() {
        return Stream.of(
                new ClaimCase(
                        "(a) control: the listItems identity route alone",
                        "/api/items",
                        RestRequestCompletionEmitterTest::listItemsAlone,
                        ONE_LIST_ITEMS_EVENT),
                new ClaimCase(
                        "(b) OTHER claimed by a ROOT handler after the emitter, then the operation route matches",
                        "/api/items",
                        RestRequestCompletionEmitterTest::otherClaimBeforeOperationRoute,
                        OTHER_CLAIM_INTERIM),
                new ClaimCase(
                        "(c) OTHER claimed, then rerouted to the operation route",
                        "/api/x",
                        RestRequestCompletionEmitterTest::otherClaimThenRerouteToOperationRoute,
                        ONE_LIST_ITEMS_EVENT),
                new ClaimCase(
                        "(d) no emitter, so no holder: the identity route calls claimForOtherTransport",
                        "/api/items",
                        RestRequestCompletionEmitterTest::noEmitter,
                        NO_EMITTER_NO_EVENT),
                new ClaimCase(
                        "(e) holder removed by a ROOT handler after the emitter",
                        "/api/items",
                        RestRequestCompletionEmitterTest::holderRemovedBeforeOperationRoute,
                        ONE_UNCLAIMED_EVENT),
                new ClaimCase(
                        "(e) holder value replaced with a String by a ROOT handler after the emitter",
                        "/api/items",
                        RestRequestCompletionEmitterTest::holderReplacedBeforeOperationRoute,
                        ONE_UNCLAIMED_EVENT));
    }

    /**
     * TP-012 (a), the control: the {@code listItems} identity route alone.
     *
     * @param emitter the emitter to mount
     * @return the wired root
     */
    private static Wired listItemsAlone(RestRequestCompletionEmitter emitter) {
        return subRouterWith(emitter, RestRequestCompletionEmitterTest::listItemsRoute);
    }

    /**
     * TP-012 (b): a ROOT handler after the emitter claims OTHER before the operation route matches. FR-023:
     * within the pass, the operation route leaves OTHER unchanged.
     *
     * @param emitter the emitter to mount
     * @return the wired root
     */
    private static Wired otherClaimBeforeOperationRoute(RestRequestCompletionEmitter emitter) {
        return RootWiring.around(emitter)
                .afterEmitter(rc -> {
                    RequestCompletionRecorder.claimForOtherTransport(rc);
                    rc.next();
                })
                .mountApi(RestRequestCompletionEmitterTest::listItemsRoute);
    }

    /**
     * TP-012 (c): {@code GET /api/x} claims OTHER, then reroutes to the {@code listItems} route.
     *
     * <p>FR-023 and ruling R5: a reroute resets every claim, OTHER included, to NONE, so the operation route
     * claims REST on the second pass; a kept OTHER claim would block that claim and leave {@code null}
     * identity. The target is an operation route because at T002 a reroute to a plain route yields
     * {@code null} identity whether OTHER is kept or reset; T003's claim-based dispatch makes that difference
     * observable.
     *
     * @param emitter the emitter to mount
     * @return the wired root
     */
    private static Wired otherClaimThenRerouteToOperationRoute(RestRequestCompletionEmitter emitter) {
        return subRouterWith(emitter, api -> {
            api.get("/x").handler(rc -> {
                RequestCompletionRecorder.claimForOtherTransport(rc);
                rc.reroute("/api/items");
            });
            listItemsRoute(api);
        });
    }

    /**
     * TP-012 (d): a router with no emitter, so no request has a holder; the identity route also calls
     * {@code claimForOtherTransport}. Both must be no-ops.
     *
     * @param emitter the emitter, deliberately not mounted
     * @return the wired root
     */
    private static Wired noEmitter(RestRequestCompletionEmitter emitter) {
        return RootWiring.around(emitter).withoutEmitter().mountApi(api -> operationRoute(api, "/items", LIST_ITEMS)
                .handler(rc -> {
                    RequestCompletionRecorder.claimForOtherTransport(rc);
                    respondOk(rc);
                }));
    }

    /**
     * TP-012 (e), removed: a ROOT handler after the emitter removes the holder before the operation route
     * matches, so the identity handler finds no holder.
     *
     * @param emitter the emitter to mount
     * @return the wired root
     */
    private static Wired holderRemovedBeforeOperationRoute(RestRequestCompletionEmitter emitter) {
        return RootWiring.around(emitter)
                .afterEmitter(rc -> {
                    removeCompletionHolder(rc);
                    rc.next();
                })
                .mountApi(RestRequestCompletionEmitterTest::listItemsRoute);
    }

    /**
     * TP-012 (e), replaced: a ROOT handler after the emitter replaces the holder's value with a
     * {@link String} before the operation route matches. A value of another type counts as no holder, with
     * no {@link ClassCastException}.
     *
     * @param emitter the emitter to mount
     * @return the wired root
     */
    private static Wired holderReplacedBeforeOperationRoute(RestRequestCompletionEmitter emitter) {
        return RootWiring.around(emitter)
                .afterEmitter(rc -> {
                    rc.put(RequestCompletionRecorder.HOLDER_KEY, "not-a-completion-state");
                    rc.next();
                })
                .mountApi(RestRequestCompletionEmitterTest::listItemsRoute);
    }

    /** Where TP-015's copy handler sits, relative to request B's emitter; selected by B's {@code X-Copy}. */
    private enum CopyPoint {
        /** A ROOT handler ordered ahead of the emitter. */
        BEFORE_EMITTER("before-emitter"),
        /** A ROOT handler ordered after the emitter. */
        AFTER_EMITTER("after-emitter");

        private final String header;

        CopyPoint(String header) {
            this.header = header;
        }

        /**
         * Returns the {@code X-Copy} header value that selects this copy point.
         *
         * @return the header value
         */
        String header() {
            return header;
        }
    }

    /**
     * A TP-015 row.
     *
     * @param name      the row's display name, naming the place it checks
     * @param aPath     request A's path, the other request
     * @param copyPoint where A's holder lands in request B's slot
     * @param bPath     request B's path, this request
     * @param expectedB the operation B's event carries, or {@code null} for an unclaimed event
     */
    private record CopyCase(
            String name, String aPath, CopyPoint copyPoint, String bPath, RestOperationDescriptor expectedB) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * TP-015's rows.
     *
     * @return the rows in contract order
     */
    private static Stream<CopyCase> copyCases() {
        return Stream.of(
                // (a) checks the emitter's re-entry detection: the copy lands before B's emitter, so B's
                // begin replaces it with B's own state, and B still claims opB.
                new CopyCase(
                        "(a) copy before B's emitter: the emitter's re-entry detection",
                        "/api/a",
                        CopyPoint.BEFORE_EMITTER,
                        "/api/b",
                        OPERATION_B),
                // (b) checks the identity handler: the copy displaces B's own holder, so B's operation route
                // finds no holder bound to B.
                new CopyCase(
                        "(b) copy after B's emitter: the identity handler",
                        "/api/a",
                        CopyPoint.AFTER_EMITTER,
                        "/api/b",
                        null),
                // (c) checks claimForOtherTransport: A is held before its operation route, so A is still
                // NONE; B's claim finds no holder bound to B.
                new CopyCase(
                        "(c) copy after B's emitter: claimForOtherTransport",
                        "/api/a-late",
                        CopyPoint.AFTER_EMITTER,
                        "/api/claim",
                        null));
    }

    // --- Route-identity fixtures ---

    /**
     * A wired root router, the first-pass sentinel to await, and the failures its root failure handler
     * recorded.
     *
     * @param root     the root router: ROOT handlers, then the {@code /api/*} sub-router
     * @param sentinel the first-pass sentinel ordered just ahead of the emitter
     * @param failures every failure the root failure handler observed, in order
     */
    private record Wired(Router root, FirstPassSentinel sentinel, List<Throwable> failures) {}

    /**
     * Builds the root router of a route-identity proof in the {@code HttpVerticle} shape: ROOT handlers in
     * priority order, the application routes on a sub-router mounted with {@code route("/api/*")
     * .subRouter(api)}, then a root failure handler that records every failure and lets the router answer it.
     *
     * <p>ROOT order: {@link RequestContextLifecycle}, unless {@link #withoutLifecycle()}; the
     * {@link #beforeEmitter} handler at {@code emitter.priority() - 2}; the {@link FirstPassSentinel} at
     * {@code emitter.priority() - 1}; the emitter at its priority, mounted once, twice
     * ({@link #emitterMountedTwice()}) or not at all ({@link #withoutEmitter()}); the {@link #afterEmitter}
     * handler at {@code emitter.priority() + 1}.
     */
    private static final class RootWiring {

        private final RestRequestCompletionEmitter emitter;
        private boolean lifecycle = true;
        private int emitterMounts = 1;
        private Handler<RoutingContext> beforeEmitter;
        private Handler<RoutingContext> afterEmitter;

        private RootWiring(RestRequestCompletionEmitter emitter) {
            this.emitter = emitter;
        }

        /**
         * Starts a wiring around {@code emitter}, whose priority anchors every other ROOT order.
         *
         * @param emitter the emitter
         * @return a new wiring
         */
        static RootWiring around(RestRequestCompletionEmitter emitter) {
            return new RootWiring(emitter);
        }

        RootWiring withoutLifecycle() {
            this.lifecycle = false;
            return this;
        }

        RootWiring withoutEmitter() {
            this.emitterMounts = 0;
            return this;
        }

        RootWiring emitterMountedTwice() {
            this.emitterMounts = 2;
            return this;
        }

        RootWiring beforeEmitter(Handler<RoutingContext> handler) {
            this.beforeEmitter = handler;
            return this;
        }

        RootWiring afterEmitter(Handler<RoutingContext> handler) {
            this.afterEmitter = handler;
            return this;
        }

        /**
         * Builds the root router, with the sub-router at {@code /api/*} holding {@code apiRoutes}.
         *
         * @param apiRoutes adds the sub-router's routes
         * @return the wired root
         */
        Wired mountApi(Consumer<Router> apiRoutes) {
            int emitterOrder = emitter.priority();
            Router root = Router.router(vertx);
            FirstPassSentinel sentinel = new FirstPassSentinel();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            if (lifecycle) {
                root.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
            }
            if (beforeEmitter != null) {
                root.route().order(emitterOrder - 2).handler(beforeEmitter);
            }
            root.route().order(emitterOrder - 1).handler(sentinel.handler());
            for (int mount = 0; mount < emitterMounts; mount++) {
                root.route().order(emitterOrder).handler(emitter);
            }
            if (afterEmitter != null) {
                root.route().order(emitterOrder + 1).handler(afterEmitter);
            }
            Router api = Router.router(vertx);
            apiRoutes.accept(api);
            root.route(API_MOUNT).subRouter(api);
            root.route().failureHandler(rc -> {
                failures.add(
                        rc.failure() != null
                                ? rc.failure()
                                : new IllegalStateException("routing failed with status " + rc.statusCode()));
                rc.next();
            });
            return new Wired(root, sentinel, failures);
        }
    }

    /**
     * The first-pass sentinel, the {@link WebSocketUpgradeExclusion} pattern: a ROOT handler ordered just
     * ahead of the emitter that registers one end handler per request, on the request's first routing pass
     * only, keyed by the request's {@code X-Case} header.
     *
     * <p>Vert.x Web fires end handlers in reverse registration order, so this end handler fires after every
     * end handler the emitter registered, on any pass, and so after emission. It registers on the first pass
     * only because an end handler registered on a later, rerouted pass would fire before an emitter end
     * handler registered on the first pass, so before emission. A reroute keeps {@code data()}, so the flag
     * set on the first pass is still there on the next.
     */
    private static final class FirstPassSentinel {

        /** Key of the {@code data()} flag that marks the sentinel's registration on the first pass. */
        private static final String REGISTERED = "test.firstPassSentinel.registered";

        /** Key of a request sent without an {@code X-Case} header. */
        static final String UNNAMED = "";

        private final Map<String, Promise<Void>> ends = new ConcurrentHashMap<>();

        /**
         * Returns the ROOT handler.
         *
         * @return the handler that registers the sentinel end handler on each request's first pass
         */
        Handler<RoutingContext> handler() {
            return rc -> {
                if (rc.get(REGISTERED) == null) {
                    rc.put(REGISTERED, Boolean.TRUE);
                    Promise<Void> end = end(caseOf(rc));
                    rc.addEndHandler(ar -> end.tryComplete());
                }
                rc.next();
            };
        }

        /**
         * Returns the sentinel end of the request named {@code caseName}.
         *
         * @param caseName the request's {@code X-Case} header value, or {@link #UNNAMED}
         * @return a future completing when the request's sentinel end handler fires
         */
        Future<Void> ended(String caseName) {
            return end(caseName).future();
        }

        private Promise<Void> end(String caseName) {
            return ends.computeIfAbsent(caseName, name -> Promise.promise());
        }

        private static String caseOf(RoutingContext rc) {
            String caseName = rc.request().getHeader(CASE_HEADER);
            return caseName != null ? caseName : UNNAMED;
        }
    }

    /**
     * Holds TP-015's request A open. Its hold handler captures the value in A's holder slot through the
     * package-private holder key, completes {@link #captured()}, and lets A continue only once
     * {@link #release()} is called, on A's own Vert.x context.
     */
    private static final class HeldRequest {

        private final Promise<Object> holderCapture = Promise.promise();
        private final Promise<Void> releaseSignal = Promise.promise();

        /**
         * Returns a hold handler that captures A's holder, then ends A with 200 once released.
         *
         * @return the hold handler
         */
        Handler<RoutingContext> holdThenEnd() {
            return rc -> hold(rc, () -> respondOk(rc));
        }

        /**
         * Returns a hold handler that captures A's holder, then continues A's routing once released.
         *
         * @return the hold handler
         */
        Handler<RoutingContext> holdThenNext() {
            return rc -> hold(rc, rc::next);
        }

        /**
         * Returns a future completing once A's holder slot has been read.
         *
         * @return the capture future
         */
        Future<Void> captured() {
            return holderCapture.future().mapEmpty();
        }

        /**
         * Returns the value read from A's holder slot.
         *
         * @return A's holder, or {@code null} when the slot was empty or not yet read
         */
        Object holder() {
            return holderCapture.future().result();
        }

        /** Lets A continue; idempotent. */
        void release() {
            releaseSignal.tryComplete();
        }

        private void hold(RoutingContext rc, Runnable continuation) {
            Context requestContext = rc.vertx().getOrCreateContext();
            holderCapture.tryComplete(rc.get(RequestCompletionRecorder.HOLDER_KEY));
            releaseSignal.future().onComplete(ar -> requestContext.runOnContext(v -> continuation.run()));
        }
    }

    /**
     * Owns TP-010's single non-Vert.x thread, named {@code foreign-end}, and ends responses on it, so Vert.x
     * runs the response end handlers there.
     */
    private static final class ForeignEnd {

        /** Name of the foreign thread. */
        static final String THREAD_NAME = "foreign-end";

        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });

        /**
         * Ends the response with 200 on the foreign thread.
         *
         * @param rc the routing context whose response to end
         */
        void endOnForeignThread(RoutingContext rc) {
            executor.execute(() -> rc.response().setStatusCode(200).end());
        }

        /**
         * Shuts the executor down with {@code shutdownNow} and awaits its termination.
         *
         * @return whether the executor terminated within {@code BARRIER_TIMEOUT_MS}
         * @throws InterruptedException if interrupted while awaiting termination
         */
        boolean shutDown() throws InterruptedException {
            executor.shutdownNow();
            return executor.awaitTermination(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * TP-010's observation of one emission: the event, and the thread its listener ran on.
     *
     * @param event       the emitted event
     * @param threadName  {@code Thread.currentThread().getName()} inside the listener
     * @param onEventLoop {@code Context.isOnEventLoopThread()} inside the listener
     */
    private record ObservedEmission(RestRequestCompletedEvent event, String threadName, boolean onEventLoop) {}

    /**
     * Test-local {@link RestOperationDescriptor}. Only the identity fields carry values: no route here is
     * secured or negotiates content, so the security policy is {@link SecurityPolicy.None} and every
     * collection is empty. T003's {@code TestOperationDescriptors} replaces it.
     *
     * @param operationId   the operation identifier
     * @param httpMethod    the HTTP method
     * @param routeTemplate the route template
     */
    private record TestOperation(String operationId, String httpMethod, String routeTemplate)
            implements RestOperationDescriptor {

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }

    // --- Test doubles ---

    /**
     * Mutable stub {@link CorrelationContext} whose {@code requestId} can be updated after
     * construction to simulate post-emission mutation of the live context.
     *
     * <p>The {@link #snapshot()} implementation delegates to {@link CorrelationContextSnapshot}
     * using the <em>current</em> {@code requestId} at call time, so a snapshot taken before
     * {@link #updateRequestId} is called will differ from one taken after — which is exactly the
     * property the immutability test verifies.
     */
    static final class MutableStubCorrelationContext implements CorrelationContext {

        private volatile CorrelationIdentifier currentRequestId;
        private final CorrelationIdentifier correlationId;

        /**
         * Constructs a stub with the given initial request-id value.
         *
         * @param requestIdValue the initial value for {@code requestId()}
         */
        MutableStubCorrelationContext(String requestIdValue) {
            this.currentRequestId = new CorrelationIdentifier(requestIdValue, "test");
            this.correlationId = new CorrelationIdentifier("corr-stub", "test");
        }

        /**
         * Replaces the live {@code requestId} to simulate an in-place mutation of the context.
         *
         * @param newValue the new request-id value
         */
        void updateRequestId(String newValue) {
            this.currentRequestId = new CorrelationIdentifier(newValue, "test");
        }

        @Override
        public CorrelationIdentifier requestId() {
            return currentRequestId;
        }

        @Override
        public CorrelationIdentifier correlationId() {
            return correlationId;
        }

        @Override
        public CorrelationIdentifier causationId() {
            return null;
        }

        @Override
        public TraceReference trace() {
            return null;
        }

        @Override
        public java.util.List<ProtocolCorrelationRef> protocolCorrelations() {
            return java.util.List.of();
        }

        @Override
        public CorrelationSessionRef session() {
            return null;
        }

        @Override
        public java.util.Map<String, String> attributes() {
            return java.util.Map.of();
        }

        @Override
        public CorrelationContextSnapshot snapshot() {
            // Captures currentRequestId at the moment snapshot() is called — not a reference
            // to the live field. This is the key property: after updateRequestId() the live
            // field changes but any previously-taken snapshot is unaffected.
            return CorrelationContextSnapshot.of(currentRequestId, correlationId);
        }
    }
}

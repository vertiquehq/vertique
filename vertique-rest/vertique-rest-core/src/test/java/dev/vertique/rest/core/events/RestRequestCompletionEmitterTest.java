// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
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
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Component tests for {@link RestRequestCompletionEmitter}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Claim dispatch: a request an operation route claimed publishes exactly one
 *       {@link RestRequestCompletedEvent}, carrying that route's operation, to the REST listeners; a
 *       request no transport claimed publishes exactly one {@link HttpRequestCompletedEvent} to the
 *       HTTP listeners; a request another transport claimed publishes nothing, and the emitter logs
 *       one DEBUG line for it, without the request path.</li>
 *   <li>Exactly one event emitted per successful request with correct method/path/status.</li>
 *   <li>Exactly one event emitted on mapped 500 failure; {@code failureCode} equals the exception's
 *       simple class name; {@code safeFailureMessage} is {@code null} and the raw exception message
 *       does not appear anywhere in the event. An {@link HttpRequestCompletedEvent} carries the same
 *       transport facts under the same rules.</li>
 *   <li>Exactly one {@link HttpRequestCompletedEvent}, and no {@link RestRequestCompletedEvent}, for a
 *       4xx request that matched no operation route, because no transport claimed it.</li>
 *   <li>Idempotency: the emitter's exactly-once guard collapses a doubly-mounted emitter handler to
 *       exactly one event.</li>
 *   <li>A throwing listener, REST or HTTP, does not prevent other listeners from receiving the
 *       event.</li>
 *   <li>Listener overloads: every listener, of either type, is called through its two-argument
 *       {@code onCompleted(event, RoutingContext)} overload with the request's root context, also on
 *       a sub-router route, where that context's {@code request()} is the route handler's; a
 *       listener implementing only the one-argument method receives each event exactly once; a
 *       listener throwing from either method is isolated.</li>
 *   <li>No listeners: emitter completes silently without error.</li>
 *   <li>{@link SecurityContext} and {@link CorrelationContext} are captured when bound.</li>
 *   <li>Completion scopes bracket the dispatch of either event type, and none opens for a request
 *       another transport claimed.</li>
 * </ul>
 *
 * <p>The {@code /test} route of the shared router helper starts with
 * {@link RequestCompletionRecorder#operationRouteHandler} for {@link #STUB}, as the JAX-RS route
 * registrar installs it on every operation route, so the suites that observe
 * {@link RestRequestCompletedEvent}s run on a request an operation route claimed. The unclaimed
 * variant of that router, and the three-route router of the claim-dispatch proofs, cover the other
 * claims.
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
     * The operation claimed by the shared router's {@code /test} route and by the claim-dispatch
     * proofs' {@code /rest} route. Its fields are arbitrary: the proofs compare it by identity only.
     */
    private static final RestOperationDescriptor STUB = new TestOperation("stubOperation", "GET", "/stub");

    /** Claim-dispatch route that the identity handler for {@link #STUB} claims for REST. */
    private static final String REST_PATH = "/rest";

    /** Claim-dispatch route that no transport claims. */
    private static final String PLAIN_PATH = "/plain";

    /** Claim-dispatch route whose handler claims the request for another transport. */
    private static final String OTHER_PATH = "/other";

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
     * Builds a {@link Router} with {@link RequestContextLifecycle}, the emitter, and a lifecycle
     * barrier middleware mounted in order, plus a terminal route at {@code /test} that delegates to
     * the supplied handler.
     *
     * <p>The {@code /test} route's first handler is {@link RequestCompletionRecorder#operationRouteHandler}
     * for {@link #STUB}, as the JAX-RS route registrar installs it on every operation route, so the
     * request is claimed for REST and publishes a {@link RestRequestCompletedEvent}. A proof that needs
     * the request unclaimed uses {@link #unclaimedRouterWithBarrier} instead.
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
        Router router = spine(vertx, emitter, barrierHandler(barrier));
        router.route("/test")
                .handler(RequestCompletionRecorder.operationRouteHandler(STUB))
                .handler(respondOkUnlessEnded(handler));
        return router;
    }

    /**
     * Builds a {@link Router} with the per-request spine mounted in order: {@link RequestContextLifecycle}
     * (so {@code barrier} can register on its handle), the emitter, then {@code barrier}.
     *
     * @param vertx   the Vert.x instance
     * @param emitter the emitter to mount
     * @param barrier the lifecycle barrier middleware
     * @return the router, ready for its routes
     */
    private static Router spine(Vertx vertx, RestRequestCompletionEmitter emitter, Handler<RoutingContext> barrier) {
        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(emitter.priority()).handler(emitter);
        router.route().handler(barrier);
        return router;
    }

    /**
     * Wraps a test route handler so that a response it left open ends with 200.
     *
     * @param handler the test route handler
     * @return the terminal route handler
     */
    private static Handler<RoutingContext> respondOkUnlessEnded(RouteHandler handler) {
        return rc -> {
            handler.handle(rc);
            if (!rc.response().ended()) {
                rc.response().setStatusCode(200).end();
            }
        };
    }

    /**
     * Builds the router of {@link #routerWithBarrier}, but with no identity handler on {@code /test}:
     * no transport claims the request, so it publishes an {@link HttpRequestCompletedEvent}.
     *
     * @param vertx   the Vert.x instance
     * @param emitter the emitter to mount
     * @param handler the terminal route handler
     * @return the configured router paired with its lifecycle barrier future
     */
    private static RouterWithBarrier unclaimedRouterWithBarrier(
            Vertx vertx, RestRequestCompletionEmitter emitter, RouteHandler handler) {
        Promise<Void> barrier = Promise.promise();
        Router router = spine(vertx, emitter, barrierHandler(barrier));
        router.route("/test").handler(respondOkUnlessEnded(handler));
        return new RouterWithBarrier(router, barrier.future());
    }

    /**
     * Builds the claim-dispatch router: the spine with {@code barriers}' per-path lifecycle barrier,
     * then three {@code GET} routes answering 200. {@code /rest} starts with the identity handler for
     * {@link #STUB}, so it is claimed for REST; {@code /plain} is claimed by no transport;
     * {@code /other} calls {@link RequestCompletionRecorder#claimForOtherTransport} first, as a
     * transport that reports the request's completion itself does.
     *
     * @param emitter  the emitter to mount
     * @param barriers the per-path lifecycle barriers
     * @return the configured router
     */
    private static Router claimRouter(RestRequestCompletionEmitter emitter, PathBarriers barriers) {
        Router router = spine(vertx, emitter, barriers.handler());
        router.get(REST_PATH)
                .handler(RequestCompletionRecorder.operationRouteHandler(STUB))
                .handler(RESPOND_OK);
        router.get(PLAIN_PATH).handler(RESPOND_OK);
        router.get(OTHER_PATH).handler(rc -> {
            RequestCompletionRecorder.claimForOtherTransport(rc);
            respondOk(rc);
        });
        return router;
    }

    /**
     * Sends {@code GET path} on the shared client and awaits the request's lifecycle barrier.
     *
     * @param port     the server port
     * @param path     the request path
     * @param barriers the per-path lifecycle barriers the router was wired with
     * @return a future resolving to the response status once the request's lifecycle has fully closed
     */
    private static Future<Integer> getAndAwait(int port, String path, PathBarriers barriers) {
        return client.get(port, "127.0.0.1", path).send().compose(resp -> barriers.await(path)
                .map(v -> resp.statusCode()));
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

    /**
     * Asserts that a {@code String} value does not contain a forbidden raw message; a {@code null}
     * value passes.
     *
     * @param fieldName names the value in the failure message
     * @param value     the value to check, or {@code null}
     * @param forbidden the raw message that must not appear
     */
    private static void assertFieldDoesNotContain(String fieldName, String value, String forbidden) {
        if (value != null) {
            assertTrue(
                    !value.contains(forbidden),
                    fieldName + " must not contain the raw exception message '" + forbidden + "' but was: " + value);
        }
    }

    /**
     * Lifecycle barriers keyed by request path, for a proof that sends one request per path, in
     * sequence: the {@link #barrierHandler(Promise)} pattern with one barrier per request.
     *
     * <p>Each barrier is an {@link RequestContextLifecycle.Handle#afterClose(Runnable)} task, so it
     * completes on the thread that ended the response, after every end handler, the emitter's
     * included. An optional probe runs in that task, on that thread, just before the barrier
     * completes.
     */
    private static final class PathBarriers {

        private final Map<String, Promise<Void>> barriers = new ConcurrentHashMap<>();
        private final Consumer<String> probe;

        /** Creates barriers with no probe. */
        PathBarriers() {
            this(path -> {});
        }

        /**
         * Creates barriers whose afterClose task first runs {@code probe}, given the request path.
         *
         * @param probe runs on the thread that ended the response, after all its end handlers
         */
        PathBarriers(Consumer<String> probe) {
            this.probe = probe;
        }

        /**
         * Returns the barrier middleware, to mount after {@link RequestContextLifecycle}.
         *
         * @return a handler registering the request's barrier, then delegating to the next handler
         */
        Handler<RoutingContext> handler() {
            return rc -> {
                String path = rc.request().path();
                RequestContextLifecycle.fromRoutingContext(rc).afterClose(() -> {
                    probe.accept(path);
                    barrier(path).complete();
                });
                rc.next();
            };
        }

        /**
         * Awaits the barrier of the request whose path is {@code path}.
         *
         * @param path the request path
         * @return a future completing once that request's lifecycle has fully closed, or failing with a
         *     descriptive timeout
         */
        Future<Void> await(String path) {
            return awaitBarrier(vertx, barrier(path).future(), "lifecycle afterClose barrier of " + path);
        }

        private Promise<Void> barrier(String path) {
            return barriers.computeIfAbsent(path, p -> Promise.promise());
        }
    }

    /**
     * Every completion event of one proof's emitter, by type, in publication order.
     *
     * @param rest the {@link RestRequestCompletedEvent}s the REST listener received
     * @param http the {@link HttpRequestCompletedEvent}s the HTTP listener received
     */
    private record Published(List<RestRequestCompletedEvent> rest, List<HttpRequestCompletedEvent> http) {

        /**
         * Returns empty captures, safe for the thread that ends the response to append to.
         *
         * @return the captures
         */
        static Published capture() {
            return new Published(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        }

        /**
         * Creates an emitter, through the {@code @Inject} constructor, whose only listeners append to
         * these captures: no security runtime and no completion scope.
         *
         * @return a new emitter instance
         */
        RestRequestCompletionEmitter emitter() {
            return new RestRequestCompletionEmitter(
                    Optional.empty(), new DefaultContextHolder(), Set.of(rest::add), Set.of(http::add), Set.of());
        }

        /**
         * Returns the events of the request whose path is {@code path}.
         *
         * @param path the request path to select
         * @return the selected events of each type, in publication order
         */
        Published ofPath(String path) {
            return new Published(
                    rest.stream().filter(event -> path.equals(event.path())).toList(),
                    http.stream().filter(event -> path.equals(event.path())).toList());
        }

        /**
         * Returns the start time of every captured event, REST events first.
         *
         * @return the start times
         */
        List<Instant> startTimes() {
            return Stream.concat(
                            rest.stream().map(RestRequestCompletedEvent::startTime),
                            http.stream().map(HttpRequestCompletedEvent::startTime))
                    .toList();
        }

        @Override
        public String toString() {
            return "REST " + identities(rest) + ", HTTP "
                    + http.stream()
                            .map(event -> event.path() + " " + event.statusCode())
                            .toList();
        }
    }

    /**
     * What one request publishes, by its claim: exactly one {@link RestRequestCompletedEvent} carrying
     * the claiming route's operation (an operation route claimed it), exactly one
     * {@link HttpRequestCompletedEvent} (no transport claimed it), or no event (another transport
     * claimed it, or no emitter is mounted).
     *
     * @param restEvents the number of REST events
     * @param httpEvents the number of HTTP events
     * @param operation  the operation the REST event carries; {@code null} when no REST event is expected
     */
    private record Expected(int restEvents, int httpEvents, RestOperationDescriptor operation) {

        /** No transport claimed the request: exactly one HTTP event, and no REST event. */
        static final Expected ONE_HTTP_EVENT = new Expected(0, 1, null);

        /** Nothing is published: no event of either type. */
        static final Expected NO_EVENT = new Expected(0, 0, null);

        /**
         * An operation route claimed the request: exactly one REST event, carrying {@code operation}, and
         * no HTTP event.
         *
         * @param operation the claiming route's operation
         * @return the expectation
         */
        static Expected oneRestEvent(RestOperationDescriptor operation) {
            return new Expected(1, 0, Objects.requireNonNull(operation, "operation"));
        }

        /**
         * Asserts this expectation on one request's captured events. The counts are asserted before any
         * event is read, and every event read has a start time.
         *
         * @param published the request's captured events
         * @param subject   names the request in failure messages
         */
        void assertOn(Published published, String subject) {
            assertEquals(
                    restEvents, published.rest().size(), subject + ": RestRequestCompletedEvent count, " + published);
            assertEquals(
                    httpEvents, published.http().size(), subject + ": HttpRequestCompletedEvent count, " + published);
            if (restEvents == 1) {
                assertIdentity(operation, published.rest().get(0), subject);
            }
            published.startTimes().forEach(startTime -> assertNotNull(startTime, subject + ": startTime must be set"));
        }
    }

    /**
     * The {@link ContextCapture} binding harness: a mocked {@link SecurityRuntime} whose current
     * context returns a concrete security snapshot and a given origin, a {@link DefaultContextHolder},
     * and a {@link CorrelationContext} binding that a route handler makes through the request's
     * lifecycle handle, so the lifecycle closes it.
     *
     * @param runtime  the mocked security runtime
     * @param snapshot the security snapshot its context returns
     * @param holder   the context holder the correlation context is bound on
     */
    private record BoundContext(
            SecurityRuntime runtime, SecurityContextSnapshot snapshot, DefaultContextHolder holder) {

        /** Request id of the bound correlation context. */
        static final String REQUEST_ID = "req-1";

        /** Correlation id of the bound correlation context. */
        static final String CORRELATION_ID = "corr-1";

        /**
         * Creates the harness.
         *
         * @param origin the origin the mocked security context returns
         * @return the harness
         */
        static BoundContext create(Optional<RequestOrigin> origin) {
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
            when(mockSec.origin()).thenReturn(origin);
            SecurityRuntime mockRuntime = mock(SecurityRuntime.class);
            when(mockRuntime.current()).thenReturn(mockSec);
            // DefaultContextHolder, so the route handler can bind a CorrelationContext.
            return new BoundContext(mockRuntime, stubSnapshot, new DefaultContextHolder());
        }

        /**
         * Binds a {@link CorrelationContext} with {@link #REQUEST_ID} and {@link #CORRELATION_ID} on the
         * holder for this request, closed through the request's lifecycle handle.
         *
         * @param rc the routing context of the request
         */
        void bindCorrelation(RoutingContext rc) {
            CorrelationIdentifier reqId = new CorrelationIdentifier(REQUEST_ID, "test");
            CorrelationIdentifier corrId = new CorrelationIdentifier(CORRELATION_ID, "test");
            dev.vertique.correlation.CorrelationContextFactory factory =
                    new dev.vertique.correlation.CorrelationContextFactory(Optional.empty());
            CorrelationContext corrCtx = factory.create(reqId, corrId);
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            lifecycle.onClose(holder.bind(CorrelationContext.class, corrCtx));
        }
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
         * Verifies that the raw exception message does not appear in any string field of the event, nor in
         * its operation's {@code operationId} and {@code routeTemplate} when it carries one.
         *
         * @param event      the event to check
         * @param rawMessage the raw exception message that must not appear
         */
        private static void assertRawMessageAbsent(RestRequestCompletedEvent event, String rawMessage) {
            assertFieldDoesNotContain("failureCode", event.failureCode(), rawMessage);
            assertFieldDoesNotContain("safeFailureMessage", event.safeFailureMessage(), rawMessage);
            assertFieldDoesNotContain("path", event.path(), rawMessage);
            assertFieldDoesNotContain("method", event.method(), rawMessage);
            RestOperationDescriptor operation = event.operation();
            if (operation != null) {
                assertFieldDoesNotContain("operation.operationId", operation.operationId(), rawMessage);
                assertFieldDoesNotContain("operation.routeTemplate", operation.routeTemplate(), rawMessage);
            }
        }
    }

    @Nested
    @DisplayName("Pre-operation 4xx path")
    class PreOperationPath {

        @Test
        @DisplayName("Exactly one HttpRequestCompletedEvent, and no RestRequestCompletedEvent, for a 400 on a route "
                + "no transport claimed")
        void emitsOneHttpEventFor4xxOnUnclaimedRoute(VertxTestContext ctx) {
            Published published = Published.capture();
            // /test carries no identity handler: no operation route matched, so no transport claimed the request
            RouterWithBarrier rb = unclaimedRouterWithBarrier(vertx, published.emitter(), rc -> rc.response()
                    .setStatusCode(400)
                    .end());

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(400, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            Expected.ONE_HTTP_EVENT.assertOn(published, "a 400 on an unclaimed route");
                            assertEquals(400, published.http().get(0).statusCode());
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
            // An operation route, as the JAX-RS route registrar builds it: the identity handler claims the
            // request for REST after both emitter mounts have run.
            router.route("/test")
                    .handler(RequestCompletionRecorder.operationRouteHandler(STUB))
                    .handler(rc -> rc.response().setStatusCode(200).end());

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

            // Mocked SecurityContext and SecurityRuntime, and a DefaultContextHolder to bind on
            BoundContext bound = BoundContext.create(Optional.empty());

            RestRequestCompletionEmitter em = emitter(bound.runtime(), bound.holder(), Set.of(event -> {
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
                bound.bindCorrelation(rc);
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

    /**
     * T003 TP-006 (FR-008): a request no transport claimed publishes one
     * {@link HttpRequestCompletedEvent} carrying the transport facts, built as for the REST event: the
     * failure's simple class name, no failure message, the wire-failure marker, the bound security,
     * origin and correlation snapshots, and empty attributes.
     */
    @Nested
    @DisplayName("HTTP event facts")
    class HttpEventFacts {

        /** A known secret, carried only by the failure's raw message; no component may contain it. */
        private static final String RAW = "raw-secret-7c1e: jdbc password=hunter2";

        /** The non-empty origin the mocked security context returns. */
        private static final RequestOrigin ORIGIN = new RequestOrigin(
                "192.0.2.10", 54321, List.of(), 0, false, "192.0.2.10", "http", "api.example.test", Optional.empty());

        /** T003 TP-006. */
        @Test
        @DisplayName("An unclaimed failed request's HttpRequestCompletedEvent carries the transport facts and no raw "
                + "message")
        void httpEventCarriesTransportFacts(VertxTestContext ctx) {
            BoundContext bound = BoundContext.create(Optional.of(ORIGIN));
            Published published = Published.capture();
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.of(bound.runtime()),
                    bound.holder(),
                    Set.of(published.rest()::add),
                    Set.of(published.http()::add),
                    Set.of());
            RouterWithBarrier rb = unclaimedRouterWithBarrier(vertx, em, rc -> {
                bound.bindCorrelation(rc);
                RequestCompletionRecorder.recordWireFailure(rc, new IOException());
                rc.fail(500, new IllegalStateException(RAW));
            });
            rb.router().errorHandler(500, rc -> rc.response().setStatusCode(500).end());

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(500, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            Expected.ONE_HTTP_EVENT.assertOn(published, "an unclaimed failed request");
                            HttpRequestCompletedEvent event = published.http().get(0);
                            assertAll(
                                    "transport facts",
                                    () -> assertEquals("GET", event.method(), "method"),
                                    () -> assertEquals("/test", event.path(), "path"),
                                    () -> assertEquals(500, event.statusCode(), "statusCode"),
                                    () -> assertEquals(
                                            "IllegalStateException",
                                            event.failureCode(),
                                            "failureCode: the failure's simple class name"),
                                    () -> assertNull(event.safeFailureMessage(), "safeFailureMessage must be null"),
                                    () -> assertEquals(
                                            "IOException",
                                            event.wireFailureCode(),
                                            "wireFailureCode: the marker's simple class name"),
                                    () -> assertSame(
                                            bound.snapshot(),
                                            event.securityContextSnapshot(),
                                            "the bound security snapshot"),
                                    () -> assertEquals(Optional.of(ORIGIN), event.origin(), "the bound origin"),
                                    () -> {
                                        CorrelationContextSnapshot correlation = event.correlationContext();
                                        assertNotNull(correlation, "the bound correlation snapshot");
                                        assertEquals(
                                                BoundContext.REQUEST_ID,
                                                correlation.requestId().value(),
                                                "the bound request id");
                                        assertEquals(
                                                BoundContext.CORRELATION_ID,
                                                correlation.correlationId().value(),
                                                "the bound correlation id");
                                    },
                                    () -> assertEquals(Map.of(), event.safeAttributes(), "safeAttributes is empty"),
                                    () -> assertThrows(
                                            UnsupportedOperationException.class,
                                            () -> event.safeAttributes().put("key", "value"),
                                            "safeAttributes is unmodifiable"),
                                    () -> assertRawMessageAbsent(event, RAW));
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Verifies that the raw exception message appears in no {@code String} component of the event, nor
         * in its {@code toString()}.
         *
         * @param event      the event to check
         * @param rawMessage the raw exception message that must not appear
         */
        private static void assertRawMessageAbsent(HttpRequestCompletedEvent event, String rawMessage) {
            assertFieldDoesNotContain("method", event.method(), rawMessage);
            assertFieldDoesNotContain("path", event.path(), rawMessage);
            assertFieldDoesNotContain("failureCode", event.failureCode(), rawMessage);
            assertFieldDoesNotContain("safeFailureMessage", event.safeFailureMessage(), rawMessage);
            assertFieldDoesNotContain("wireFailureCode", event.wireFailureCode(), rawMessage);
            assertFieldDoesNotContain("toString()", event.toString(), rawMessage);
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
         * end-handler-driven completion event. The route carries no identity handler, so no
         * transport claimed the request, as none claims a failed upgrade that ends with an HTTP
         * response, and that event is an {@link HttpRequestCompletedEvent}.
         *
         * <p>A successful WebSocket 101 upgrade calls {@code lifecycle.completeNow()} because
         * Vert.x's {@code Http1ServerResponse.completeHandshake()} writes the 101
         * response without firing the normal response end handler. The end-to-end real-server proof
         * that a successful upgrade yields no completion event is
         * {@code WebSocketCompletionClaimIT} in {@code vertique-rest-websocket}.
         *
         * <p>Cross-reference: {@code WebSocketEndpointRegistrar.handleUpgrade()}.
         */
        @Test
        @DisplayName("completeNow() neither emits nor suppresses the end-handler-driven completion event")
        void completeNowNeitherEmitsNorSuppressesCompletionEvent(VertxTestContext ctx) {
            Published published = Published.capture();
            RestRequestCompletionEmitter em = published.emitter();

            // The lifecycle barrier (barrierHandler / routerWithBarrier) is not a useful signal for
            // this test: the terminal handler below calls Handle.completeNow(), which drives
            // closeAll() — and therefore any afterClose-registered barrier — synchronously, before
            // the response even ends (see the caveat on barrierHandler's javadoc). Instead, this
            // test builds its router inline with a sentinel middleware mounted between
            // RequestContextLifecycle and the emitter, registering its own ctx.addEndHandler that
            // completes `sentinel`. Because Vert.x Web fires end handlers in reverse registration
            // order, and this sentinel middleware registers its end handler AFTER the lifecycle but
            // BEFORE the emitter, the firing order is: emitter's end handler (populates `published`)
            // first, this sentinel's end handler second, RequestContextLifecycle's end handler last.
            // Completing `sentinel` therefore happens-after the emitter's listener dispatch, on
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
            // No identity handler: like a failed upgrade, the request stays unclaimed.
            router.route("/test").handler(rc -> {
                RequestContextLifecycle.fromRoutingContext(rc).completeNow();
                // Non-vacuous: an implementation that wired the emitter's emission to
                // Handle.completeNow() instead of ctx.addEndHandler would already have
                // published an event of either type by this point, before the response has even ended.
                ctx.verify(() -> Expected.NO_EVENT.assertOn(
                        published, "completeNow() must not trigger the emitter's completion event"));
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
                        ctx.verify(() -> Expected.ONE_HTTP_EVENT.assertOn(
                                published,
                                "the end-handler-driven emission must still fire exactly once after the "
                                        + "response ends, as an HttpRequestCompletedEvent for this unclaimed "
                                        + "request; completeNow() neither emits nor suppresses it"));
                        ctx.completeNow();
                    }));
        }
    }

    /**
     * T003 TP-005 (FR-001, D002): the emitter dispatches exactly one event type, chosen by the request's
     * claim. A REST claim publishes one {@link RestRequestCompletedEvent} to the REST listeners; no claim
     * publishes one {@link HttpRequestCompletedEvent} to the HTTP listeners, each isolated from the others;
     * another transport's claim publishes nothing and logs one DEBUG line on the emitter's logger, carrying
     * the method and status but never the request path.
     *
     * <p>The DEBUG line carries no path, so it is attributed to its request by order: the three requests
     * run in sequence, and after each one's lifecycle barrier the lines logged so far are taken from the
     * appender.
     */
    @Nested
    @DisplayName("Claim dispatch")
    class ClaimDispatch {

        /** The emitter's class logger, whose events {@link #appender} records. */
        private Logger emitterLogger;

        /** The logger's level before the test, restored after it. */
        private Level previousLevel;

        /** Records every event the emitter's logger accepts. */
        private ListAppender<ILoggingEvent> appender;

        /** Attaches {@link #appender} to the emitter's logger and enables DEBUG on it. */
        @BeforeEach
        void captureEmitterLog() {
            emitterLogger = (Logger) LoggerFactory.getLogger(RestRequestCompletionEmitter.class);
            previousLevel = emitterLogger.getLevel();
            emitterLogger.setLevel(Level.DEBUG);
            appender = new ListAppender<>();
            appender.start();
            emitterLogger.addAppender(appender);
        }

        /** Detaches {@link #appender} and restores the logger's previous level. */
        @AfterEach
        void releaseEmitterLog() {
            emitterLogger.detachAppender(appender);
            appender.stop();
            emitterLogger.setLevel(previousLevel);
        }

        /**
         * No fact is built for a request another transport claimed: the emitter takes no security
         * snapshot for it, where a request no transport claimed takes one.
         */
        @Test
        @DisplayName("Another transport's claim builds no facts: the security runtime is not consulted")
        void otherTransportClaimTakesNoSecuritySnapshot(VertxTestContext ctx) {
            Published published = Published.capture();
            SecurityRuntime runtime = mock(SecurityRuntime.class);
            RestRequestCompletionEmitter em =
                    emitter(runtime, new DefaultContextHolder(), Set.of(published.rest()::add));
            PathBarriers barriers = new PathBarriers();

            startServer(claimRouter(em, barriers))
                    .compose(port -> getAndAwait(port, OTHER_PATH, barriers).compose(status -> {
                        ctx.verify(() -> {
                            assertEquals(200, status);
                            verify(runtime, never()).current();
                        });
                        // Non-vacuous: a request no transport claimed does consult the runtime.
                        return getAndAwait(port, PLAIN_PATH, barriers);
                    }))
                    .onComplete(ctx.succeeding(status -> {
                        ctx.verify(() -> verify(runtime, times(1)).current());
                        ctx.completeNow();
                    }));
        }

        /** T003 TP-005. */
        @Test
        @DisplayName("REST claim: one REST event; no claim: one HTTP event despite a throwing HTTP listener; another "
                + "transport's claim: nothing, and one DEBUG line without the path")
        void dispatchesExactlyOneEventTypeByClaim(VertxTestContext ctx) {
            Published published = Published.capture();
            Set<HttpRequestCompletedListener> httpListeners = new LinkedHashSet<>();
            httpListeners.add(event -> {
                throw new IllegalStateException("http-listener-boom");
            });
            httpListeners.add(published.http()::add);
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.empty(),
                    new DefaultContextHolder(),
                    Set.of(published.rest()::add),
                    httpListeners,
                    Set.of());
            PathBarriers barriers = new PathBarriers();
            Map<String, Integer> statuses = new ConcurrentHashMap<>();
            Map<String, List<String>> debugByPath = new ConcurrentHashMap<>();

            startServer(claimRouter(em, barriers))
                    .compose(port -> complete(port, REST_PATH, barriers, statuses, debugByPath)
                            .compose(v -> complete(port, PLAIN_PATH, barriers, statuses, debugByPath))
                            .compose(v -> complete(port, OTHER_PATH, barriers, statuses, debugByPath)))
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertAll(
                                "dispatch by claim",
                                // /rest: REST(STUB) -> one REST event carrying STUB
                                () -> Expected.oneRestEvent(STUB).assertOn(published.ofPath(REST_PATH), REST_PATH),
                                () -> assertEquals(
                                        List.of(), debugByPath.get(REST_PATH), REST_PATH + ": no DEBUG line"),
                                // /plain: NONE -> one HTTP event, delivered past the throwing HTTP listener
                                () -> Expected.ONE_HTTP_EVENT.assertOn(
                                        published.ofPath(PLAIN_PATH),
                                        PLAIN_PATH + ", after the throwing HTTP listener"),
                                () -> assertEquals(
                                        List.of(), debugByPath.get(PLAIN_PATH), PLAIN_PATH + ": no DEBUG line"),
                                // /other: OTHER -> nothing, and one DEBUG line without the path
                                () -> Expected.NO_EVENT.assertOn(published.ofPath(OTHER_PATH), OTHER_PATH),
                                () -> assertSkipLine(debugByPath.get(OTHER_PATH)),
                                // every route answers 200
                                () -> assertEquals(
                                        Map.of(REST_PATH, 200, PLAIN_PATH, 200, OTHER_PATH, 200),
                                        statuses,
                                        "every response is 200")));
                        ctx.completeNow();
                    }));
        }

        /**
         * Sends {@code GET path}, awaits its lifecycle barrier, then records its status and the DEBUG lines
         * the emitter logged while it completed.
         *
         * @param port        the server port
         * @param path        the request path
         * @param barriers    the per-path lifecycle barriers the router was wired with
         * @param statuses    receives the response status, by path
         * @param debugByPath receives the request's DEBUG lines, by path
         * @return a future completing once the request is recorded
         */
        private Future<Void> complete(
                int port,
                String path,
                PathBarriers barriers,
                Map<String, Integer> statuses,
                Map<String, List<String>> debugByPath) {
            return getAndAwait(port, path, barriers).compose(status -> {
                statuses.put(path, status);
                debugByPath.put(path, debugLines());
                return Future.<Void>succeededFuture();
            });
        }

        /**
         * Returns the formatted messages of the DEBUG events the emitter's logger recorded since the last
         * call, and forgets every recorded event.
         *
         * @return the DEBUG messages, in logging order
         */
        private List<String> debugLines() {
            List<String> lines = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            appender.list.clear();
            return lines;
        }

        /**
         * Asserts {@code /other} logged exactly one DEBUG line, naming the method and the status but not the
         * request path.
         *
         * @param lines the DEBUG lines logged while {@code /other} completed
         */
        private static void assertSkipLine(List<String> lines) {
            assertEquals(1, lines.size(), OTHER_PATH + ": exactly one DEBUG line on the emitter's logger: " + lines);
            String line = lines.get(0);
            assertAll(
                    OTHER_PATH + " DEBUG line",
                    () -> assertTrue(line.contains("GET"), "it carries the method: " + line),
                    () -> assertTrue(line.contains("200"), "it carries the status: " + line),
                    () -> assertFalse(line.contains(OTHER_PATH), "it must not carry the request path: " + line));
        }
    }

    /**
     * T003 review finding T003-R-04 (supporting): an {@link Error} thrown by an
     * {@link HttpRequestCompletedListener} is not isolated the way its exceptions are; it propagates out of
     * {@code emit}. The request is unclaimed, and its route handler drives {@code emit} directly, through the
     * seam {@code emitPopulatesWireFailureCodeFromFailedEndHandlerWiring} uses, so the {@link Error} surfaces on
     * the calling thread. The mounted emitter's end handler then finds the request already emitted.
     */
    @Nested
    @DisplayName("Listener Error propagation")
    class ListenerErrorPropagation {

        @Test
        @DisplayName("An Error thrown by an HTTP listener escapes emit as the same instance instead of being "
                + "logged and swallowed")
        void httpListenerErrorEscapesEmit(VertxTestContext ctx) {
            ListenerError listenerError = new ListenerError();
            HttpRequestCompletedListener throwing = event -> {
                throw listenerError;
            };
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.empty(), new DefaultContextHolder(), Set.of(), Set.of(throwing), Set.of());
            RouterWithBarrier rb = unclaimedRouterWithBarrier(
                    vertx,
                    em,
                    rc -> ctx.verify(() -> {
                        ListenerError escaped = assertThrowsExactly(
                                ListenerError.class,
                                () -> em.emit(rc, RequestCompletionRecorder.boundState(rc), Future.succeededFuture()),
                                "an HTTP listener's Error must escape emit");
                        assertSame(listenerError, escaped, "the listener's own Error instance escapes");
                    }));

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> ctx.completeNow()));
        }

        /** Test-local {@link Error} the HTTP listener throws. */
        private static final class ListenerError extends Error {}
    }

    /**
     * An {@link AssertionError} or a {@link LinkageError} thrown by a completion listener of either kind is
     * isolated the way its exceptions are: the listeners after it still receive the event and nothing escapes
     * {@code emit}. Every proof drives {@code emit} from the route handler, on the calling thread, so an escaping
     * {@link Error} would surface there. The listener sets are {@link LinkedHashSet}s with the throwing listener
     * first, so the capturing listener only receives the event when the dispatch loop survived.
     */
    @Nested
    @DisplayName("Listener AssertionError and LinkageError isolation")
    class ListenerErrorIsolation {

        /** The emitter's logger, as Logback sees it. */
        private Logger emitterLogger;

        /** Records every event the emitter's logger accepts. */
        private ListAppender<ILoggingEvent> appender;

        /** Attaches {@link #appender} to the emitter's logger. */
        @BeforeEach
        void captureEmitterLog() {
            emitterLogger = (Logger) LoggerFactory.getLogger(RestRequestCompletionEmitter.class);
            appender = new ListAppender<>();
            appender.start();
            emitterLogger.addAppender(appender);
        }

        /** Detaches {@link #appender}. */
        @AfterEach
        void releaseEmitterLog() {
            emitterLogger.detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("An AssertionError from a REST listener does not stop later REST listeners or escape emit")
        void restListenerAssertionErrorIsIsolated(VertxTestContext ctx) {
            assertRestListenerIsolated(new AssertionError("rest-listener-assertion"), ctx);
        }

        @Test
        @DisplayName("A NoSuchMethodError from a REST listener does not stop later REST listeners or escape emit")
        void restListenerLinkageErrorIsIsolated(VertxTestContext ctx) {
            assertRestListenerIsolated(new NoSuchMethodError("rest-listener-linkage"), ctx);
        }

        @Test
        @DisplayName("An AssertionError from an HTTP listener does not stop later HTTP listeners or escape emit")
        void httpListenerAssertionErrorIsIsolated(VertxTestContext ctx) {
            assertHttpListenerIsolated(new AssertionError("http-listener-assertion"), ctx);
        }

        @Test
        @DisplayName("A NoSuchMethodError from an HTTP listener does not stop later HTTP listeners or escape emit")
        void httpListenerLinkageErrorIsIsolated(VertxTestContext ctx) {
            assertHttpListenerIsolated(new NoSuchMethodError("http-listener-linkage"), ctx);
        }

        @Test
        @DisplayName("A LinkageError is logged at ERROR once per listener and callback, an AssertionError at WARN "
                + "every time")
        void linkageErrorIsReportedOnceAndAssertionErrorEveryTime(VertxTestContext ctx) {
            AtomicInteger delivered = new AtomicInteger();
            HttpRequestCompletedListener unusable = event -> {
                throw new NoSuchMethodError("http-listener-linkage");
            };
            HttpRequestCompletedListener asserting = event -> {
                throw new AssertionError("http-listener-assertion");
            };
            HttpRequestCompletedListener capturing = event -> delivered.incrementAndGet();
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.empty(),
                    new DefaultContextHolder(),
                    Set.of(),
                    new LinkedHashSet<>(List.of(unusable, asserting, capturing)),
                    Set.of());
            RouterWithBarrier rb = unclaimedRouterWithBarrier(
                    vertx,
                    em,
                    rc -> ctx.verify(() -> assertDoesNotThrow(
                            () -> em.emit(rc, RequestCompletionRecorder.boundState(rc), Future.succeededFuture()))));

            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test")
                            .send()
                            .compose(first ->
                                    client.get(port, "127.0.0.1", "/test").send()))
                    .onComplete(ctx.succeeding(second -> {
                        ctx.verify(() -> {
                            assertEquals(2, delivered.get(), "the later listener receives both events");
                            List<ILoggingEvent> errors = eventsAt(Level.ERROR);
                            assertEquals(
                                    1, errors.size(), "the unusable listener is reported once, not once per request");
                            String report = errors.get(0).getFormattedMessage();
                            assertTrue(
                                    report.contains("is unusable and its notifications are being lost"),
                                    "the report says the notifications are lost: " + report);
                            assertTrue(
                                    report.contains(unusable.getClass().getName()),
                                    "the report names the listener class: " + report);
                            assertEquals(
                                    2, eventsAt(Level.WARN).size(), "an AssertionError is logged for every request");
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Completes one request an operation route claimed, with a REST listener throwing {@code thrown} ahead
         * of a capturing one, and asserts that nothing escaped {@code emit} and the capturing listener received
         * the event.
         *
         * @param thrown the {@link Error} the first listener throws
         * @param ctx    the test context
         */
        private void assertRestListenerIsolated(Error thrown, VertxTestContext ctx) {
            AtomicInteger calls = new AtomicInteger();
            List<RestRequestCompletedEvent> delivered = new CopyOnWriteArrayList<>();
            RestRequestCompletedListener throwing = event -> {
                calls.incrementAndGet();
                throw thrown;
            };
            RestRequestCompletedListener capturing = delivered::add;
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.empty(),
                    new DefaultContextHolder(),
                    new LinkedHashSet<>(List.of(throwing, capturing)),
                    Set.of(),
                    Set.of());
            RouterWithBarrier rb = routerWithBarrier(
                    vertx,
                    em,
                    rc -> ctx.verify(() -> assertDoesNotThrow(
                            () -> em.emit(rc, RequestCompletionRecorder.boundState(rc), Future.succeededFuture()),
                            "a REST listener's " + thrown.getClass().getSimpleName() + " must not escape emit")));

            completeAndAssert(rb, calls, delivered, ctx);
        }

        /**
         * Completes one request no transport claimed, with an HTTP listener throwing {@code thrown} ahead of a
         * capturing one, and asserts that nothing escaped {@code emit} and the capturing listener received the
         * event.
         *
         * @param thrown the {@link Error} the first listener throws
         * @param ctx    the test context
         */
        private void assertHttpListenerIsolated(Error thrown, VertxTestContext ctx) {
            AtomicInteger calls = new AtomicInteger();
            List<HttpRequestCompletedEvent> delivered = new CopyOnWriteArrayList<>();
            HttpRequestCompletedListener throwing = event -> {
                calls.incrementAndGet();
                throw thrown;
            };
            HttpRequestCompletedListener capturing = delivered::add;
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.empty(),
                    new DefaultContextHolder(),
                    Set.of(),
                    new LinkedHashSet<>(List.of(throwing, capturing)),
                    Set.of());
            RouterWithBarrier rb = unclaimedRouterWithBarrier(
                    vertx,
                    em,
                    rc -> ctx.verify(() -> assertDoesNotThrow(
                            () -> em.emit(rc, RequestCompletionRecorder.boundState(rc), Future.succeededFuture()),
                            "an HTTP listener's " + thrown.getClass().getSimpleName() + " must not escape emit")));

            completeAndAssert(rb, calls, delivered, ctx);
        }

        /**
         * Sends one request through {@code rb}'s router and asserts the response, that the throwing listener
         * was reached, and that the listener after it received exactly one event.
         *
         * @param rb        the router and its lifecycle barrier
         * @param calls     the number of times the throwing listener was called
         * @param delivered the events the capturing listener received
         * @param ctx       the test context
         */
        private void completeAndAssert(
                RouterWithBarrier rb, AtomicInteger calls, List<?> delivered, VertxTestContext ctx) {
            startServer(rb.router())
                    .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                    .compose(resp -> {
                        ctx.verify(() -> assertEquals(200, resp.statusCode()));
                        return awaitBarrier(vertx, rb.barrier());
                    })
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(1, calls.get(), "the throwing listener must have been reached");
                            assertEquals(1, delivered.size(), "the listener after it must still receive the event");
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * Returns the events the emitter logged at {@code level}.
         *
         * @param level the level to keep
         * @return the matching events, in logging order
         */
        private List<ILoggingEvent> eventsAt(Level level) {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .toList();
        }
    }

    /**
     * rest-025 T004's TP-001 to TP-004 (FR-009, AC-009.1 to AC-009.3): the listener overloads. The emitter reaches
     * every listener, of either type, through {@code onCompleted(event, RoutingContext)}, whose default delegates to
     * {@code onCompleted(event)}. A listener implementing only the one-argument method receives each event exactly
     * once (TP-001). A listener overriding both methods gets only the two-argument call, with the request's root
     * context (TP-002), also on a sub-router route, where that context's {@code request()} is the route handler's
     * (TP-003). A throwing listener of either type, throwing from either method, is isolated (TP-004).
     *
     * <p>Every proof builds its emitter through {@link #overloadEmitter}, with each listener set a
     * {@link LinkedHashSet}, and runs on {@link #overloadRouter}: the spine with per-path lifecycle barriers, a ROOT
     * handler after the emitter that records each request's root context, {@code /claimed}, whose operation route
     * claims the request for {@link #CLAIMED_OPERATION}, and the plain {@code /unclaimed}. Every route handler records
     * its own context and {@code request()}, then answers 200. Captured state is read only after the request's
     * lifecycle barrier. {@link #appender} records the emitter's WARN lines for TP-004.
     */
    @Nested
    @DisplayName("Listener overloads")
    class ListenerOverloads {

        /** Route whose operation route claims the request for {@link #CLAIMED_OPERATION}: it publishes a REST event. */
        private static final String CLAIMED = "/claimed";

        /** Route that no transport claims: it publishes an HTTP event. */
        private static final String UNCLAIMED = "/unclaimed";

        /** Mount path of TP-003's sub-router. */
        private static final String SUB_MOUNT = "/sub/*";

        /** {@link #CLAIMED}, served by TP-003's sub-router. */
        private static final String SUB_CLAIMED = "/sub" + CLAIMED;

        /** {@link #UNCLAIMED}, served by TP-003's sub-router. */
        private static final String SUB_UNCLAIMED = "/sub" + UNCLAIMED;

        /** The operation of every {@code /claimed} route; the proofs compare it by identity only. */
        private static final RestOperationDescriptor CLAIMED_OPERATION =
                new TestOperation("claimedOperation", "GET", "/claimed");

        /** Message of the exception TP-004's throwing listeners throw. */
        private static final String BOOM = "listener-boom";

        /** The emitter's class logger, whose events {@link #appender} records. */
        private Logger emitterLogger;

        /** The logger's level before the test, restored after it. */
        private Level previousLevel;

        /** Records every event the emitter's logger accepts. */
        private ListAppender<ILoggingEvent> appender;

        /** Attaches {@link #appender} to the emitter's logger and enables WARN on it. */
        @BeforeEach
        void captureEmitterLog() {
            emitterLogger = (Logger) LoggerFactory.getLogger(RestRequestCompletionEmitter.class);
            previousLevel = emitterLogger.getLevel();
            emitterLogger.setLevel(Level.WARN);
            appender = new ListAppender<>();
            appender.start();
            emitterLogger.addAppender(appender);
        }

        /** Detaches {@link #appender} and restores the logger's previous level. */
        @AfterEach
        void releaseEmitterLog() {
            emitterLogger.detachAppender(appender);
            appender.stop();
            emitterLogger.setLevel(previousLevel);
        }

        /** TP-001 (AC-009.1): both listener types stay lambda-compatible. */
        @Test
        @DisplayName("A listener implementing only the one-argument method receives each event exactly once")
        void oneArgumentOnlyListenerReceivesEachEventOnce(VertxTestContext ctx) {
            Published delivered = Published.capture();
            RestRequestCompletedListener restLambda = event -> delivered.rest().add(event);
            HttpRequestCompletedListener httpLambda = event -> delivered.http().add(event);

            claimedThenUnclaimed(restLambda, httpLambda, new Recorded()).onComplete(ctx.succeeding(statuses -> {
                ctx.verify(() -> assertAll(
                        "one REST event for " + CLAIMED + ", one HTTP event for " + UNCLAIMED,
                        () -> assertEquals(
                                Map.of(CLAIMED, 200, UNCLAIMED, 200), statuses, "fixture: every response is 200"),
                        () -> assertEquals(
                                List.of(CLAIMED),
                                delivered.rest().stream()
                                        .map(RestRequestCompletedEvent::path)
                                        .toList(),
                                "the REST lambda's events, by path: exactly one, for " + CLAIMED),
                        () -> assertEquals(
                                List.of(UNCLAIMED),
                                delivered.http().stream()
                                        .map(HttpRequestCompletedEvent::path)
                                        .toList(),
                                "the HTTP lambda's events, by path: exactly one, for " + UNCLAIMED),
                        () -> {
                            assertEquals(1, delivered.rest().size(), "REST events, before reading the operation");
                            assertSame(
                                    CLAIMED_OPERATION,
                                    delivered.rest().get(0).operation(),
                                    "the REST event carries the claimed operation");
                        }));
                ctx.completeNow();
            }));
        }

        /** TP-002 (AC-009.2): only the two-argument call, with the root context, for both listener types. */
        @Test
        @DisplayName("A listener overriding both methods gets only the two-argument call, with the request's root "
                + "context")
        void twoArgumentOverrideIsCalledInsteadOfOneArgumentWithRootContext(VertxTestContext ctx) {
            RestOverloadCounter restCounter = new RestOverloadCounter();
            HttpOverloadCounter httpCounter = new HttpOverloadCounter();
            Recorded recorded = new Recorded();

            claimedThenUnclaimed(restCounter, httpCounter, recorded).onComplete(ctx.succeeding(statuses -> {
                ctx.verify(() -> assertAll(
                        "only the two-argument method is called, with the root context",
                        () -> assertEquals(
                                Map.of(CLAIMED, 200, UNCLAIMED, 200), statuses, "fixture: every response is 200"),
                        () -> assertOnlyTwoArgumentCallWithRootContext(
                                restCounter, recorded.rootContext(CLAIMED), "the REST listener, " + CLAIMED),
                        () -> assertOnlyTwoArgumentCallWithRootContext(
                                httpCounter, recorded.rootContext(UNCLAIMED), "the HTTP listener, " + UNCLAIMED)));
                ctx.completeNow();
            }));
        }

        /** TP-003 (AC-009.2 across a mount, R-004). */
        @Test
        @DisplayName("On a sub-router route the listener gets the root context, whose request() is the route "
                + "handler's request()")
        void subRouterRouteListenerGetsRootContextSharingTheRouteRequest(VertxTestContext ctx) {
            RestOverloadCounter restCounter = new RestOverloadCounter();
            HttpOverloadCounter httpCounter = new HttpOverloadCounter();
            Recorded recorded = new Recorded();
            PathBarriers barriers = new PathBarriers();
            Router router =
                    overloadRouter(overloadEmitter(inOrder(restCounter), inOrder(httpCounter)), barriers, recorded);
            mountClaimedAndUnclaimedSubRouter(router, recorded);

            startServer(router)
                    .compose(port -> completeInOrder(port, barriers, SUB_CLAIMED, SUB_UNCLAIMED))
                    .onComplete(ctx.succeeding(statuses -> {
                        ctx.verify(() -> {
                            assertEquals(
                                    Map.of(SUB_CLAIMED, 200, SUB_UNCLAIMED, 200),
                                    statuses,
                                    "fixture: every response is 200");
                            assertRanOnSubRouterWrapper(recorded, SUB_CLAIMED);
                            assertRanOnSubRouterWrapper(recorded, SUB_UNCLAIMED);
                            assertAll(
                                    "the listener's context is the request's root context, and its request() is the "
                                            + "request() the sub-router route handler received",
                                    () -> assertRootContextSharingRouteRequest(
                                            restCounter, recorded, SUB_CLAIMED, "the REST listener, " + SUB_CLAIMED),
                                    () -> assertRootContextSharingRouteRequest(
                                            httpCounter,
                                            recorded,
                                            SUB_UNCLAIMED,
                                            "the HTTP listener, " + SUB_UNCLAIMED),
                                    () -> {
                                        RestRequestCompletedEvent event = restCounter.event();
                                        assertNotNull(event, "the REST listener's two-argument call received an event");
                                        assertSame(
                                                CLAIMED_OPERATION,
                                                event.operation(),
                                                "the REST event carries the sub-router route's operation");
                                    });
                        });
                        ctx.completeNow();
                    }));
        }

        /** TP-004 (AC-009.3), one row per listener type and throwing method. */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.rest.core.events.RestRequestCompletionEmitterTest#isolationCases")
        @DisplayName("A throwing listener of either type, throwing from either method, is isolated: the later "
                + "listener still runs, the response is unaffected, and one WARN is logged")
        void throwingListenerIsIsolatedForEitherTypeAndOverload(IsolationCase isolationCase, VertxTestContext ctx) {
            ThrowingListener throwing = isolationCase.throwingListener().apply(isolationCase.throwsFromOverride());
            List<Object> delivered = new CopyOnWriteArrayList<>();
            PathBarriers barriers = new PathBarriers();
            RestRequestCompletionEmitter emitter =
                    overloadEmitter(throwing.restListeners(delivered), throwing.httpListeners(delivered));

            startServer(overloadRouter(emitter, barriers, new Recorded()))
                    .compose(port -> getAndAwait(port, isolationCase.path(), barriers))
                    .onComplete(ctx.succeeding(status -> {
                        ctx.verify(() -> assertIsolated(isolationCase, throwing, delivered, status));
                        ctx.completeNow();
                    }));
        }

        /**
         * Creates the emitter of these proofs, their single construction site: no security runtime, a plain
         * {@link DefaultContextHolder}, the given REST and HTTP listener sets, which the emitter iterates as given,
         * and no completion scope.
         *
         * @param restListeners the REST listeners
         * @param httpListeners the HTTP listeners
         * @return a new emitter instance
         */
        private static RestRequestCompletionEmitter overloadEmitter(
                Set<RestRequestCompletedListener> restListeners, Set<HttpRequestCompletedListener> httpListeners) {
            return new RestRequestCompletionEmitter(
                    Optional.empty(), new DefaultContextHolder(), restListeners, httpListeners, Set.of());
        }

        /**
         * Returns {@code listeners} as a {@link LinkedHashSet}, in the given order.
         *
         * @param listeners the listeners
         * @param <T>       the listener type
         * @return the listeners, in the given order
         */
        @SafeVarargs
        private static <T> Set<T> inOrder(T... listeners) {
            return new LinkedHashSet<>(List.of(listeners));
        }

        /**
         * Builds the proofs' router: the spine ({@link RequestContextLifecycle}, the emitter, then {@code barriers}'
         * per-path lifecycle barrier), a ROOT handler after the emitter that stores each request's root context in
         * {@code recorded}, then {@code GET /claimed}, whose operation route claims the request for
         * {@link #CLAIMED_OPERATION}, and the plain {@code GET /unclaimed}.
         *
         * @param emitter  the emitter to mount
         * @param barriers the per-path lifecycle barriers
         * @param recorded receives what the ROOT and route handlers saw
         * @return the configured router
         */
        private static Router overloadRouter(
                RestRequestCompletionEmitter emitter, PathBarriers barriers, Recorded recorded) {
            Router router = spine(vertx, emitter, barriers.handler());
            router.route().handler(rc -> {
                recorded.root(rc);
                rc.next();
            });
            addClaimedAndUnclaimedRoutes(router, recorded);
            return router;
        }

        /**
         * Mounts a sub-router at {@code /sub/*} holding {@code GET /claimed} and {@code GET /unclaimed}, as
         * {@link #overloadRouter} builds them on the root: TP-003's {@code /sub/claimed} and {@code /sub/unclaimed}.
         *
         * @param router   the root router to mount the sub-router on
         * @param recorded receives what the sub-router's route handlers saw
         */
        private static void mountClaimedAndUnclaimedSubRouter(Router router, Recorded recorded) {
            Router sub = Router.router(vertx);
            addClaimedAndUnclaimedRoutes(sub, recorded);
            router.route(SUB_MOUNT).subRouter(sub);
        }

        /**
         * Adds {@code GET /claimed}, whose first handler is the operation route's identity handler for
         * {@link #CLAIMED_OPERATION}, and the plain {@code GET /unclaimed}. Each route's handler records its own
         * context and {@code request()} in {@code recorded}, then answers 200.
         *
         * @param router   the router to add the routes to
         * @param recorded receives what the route handlers saw
         */
        private static void addClaimedAndUnclaimedRoutes(Router router, Recorded recorded) {
            router.get(CLAIMED)
                    .handler(RequestCompletionRecorder.operationRouteHandler(CLAIMED_OPERATION))
                    .handler(recorded::routeThenRespondOk);
            router.get(UNCLAIMED).handler(recorded::routeThenRespondOk);
        }

        /**
         * TP-001's and TP-002's two requests: serves {@link #overloadRouter} over {@code restListener} and
         * {@code httpListener}, each the only listener of its type, then completes {@code GET /claimed} and
         * {@code GET /unclaimed}, in that order, each awaited through its lifecycle barrier.
         *
         * @param restListener the only REST listener
         * @param httpListener the only HTTP listener
         * @param recorded     receives what the ROOT and route handlers saw
         * @return the response statuses, by path, once both requests' lifecycles have closed
         */
        private Future<Map<String, Integer>> claimedThenUnclaimed(
                RestRequestCompletedListener restListener,
                HttpRequestCompletedListener httpListener,
                Recorded recorded) {
            PathBarriers barriers = new PathBarriers();
            Router router =
                    overloadRouter(overloadEmitter(inOrder(restListener), inOrder(httpListener)), barriers, recorded);
            return startServer(router).compose(port -> completeInOrder(port, barriers, CLAIMED, UNCLAIMED));
        }

        /**
         * Completes {@code GET first}, then {@code GET second}, each awaited through its lifecycle barrier.
         *
         * @param port     the server port
         * @param barriers the per-path lifecycle barriers the router was wired with
         * @param first    the first request's path
         * @param second   the second request's path
         * @return the response statuses, by path, once both requests' lifecycles have closed
         */
        private static Future<Map<String, Integer>> completeInOrder(
                int port, PathBarriers barriers, String first, String second) {
            Map<String, Integer> statuses = new ConcurrentHashMap<>();
            return getAndAwait(port, first, barriers)
                    .compose(status -> {
                        statuses.put(first, status);
                        return getAndAwait(port, second, barriers);
                    })
                    .map(status -> {
                        statuses.put(second, status);
                        return statuses;
                    });
        }

        /**
         * Asserts TP-002 on one listener: the emitter called its two-argument method once, never its one-argument
         * method, and passed the request's root context. The counts are asserted before the identity.
         *
         * @param counter the listener
         * @param root    the root context the ROOT recording handler stored for the listener's request
         * @param subject names the listener and its request in failure messages
         */
        private static void assertOnlyTwoArgumentCallWithRootContext(
                OverloadCounter<?> counter, RoutingContext root, String subject) {
            assertAll(
                    subject,
                    () -> assertEquals(
                            0,
                            counter.oneArgCalls(),
                            "oneArgCalls: the emitter never calls the one-argument method directly"),
                    () -> assertEquals(1, counter.twoArgCalls(), "twoArgCalls: the emitter calls the overload once"),
                    () -> {
                        assertNotNull(root, "fixture: the ROOT recording handler stored the request's root context");
                        assertSame(root, counter.context(), "the two-argument call receives the root context");
                    });
        }

        /**
         * TP-003's fixture guard, which checks the fixture, not the contract: the route handler of {@code path} ran on
         * a sub-router's own {@link RoutingContext} wrapper, not on the request's root context (R-004).
         *
         * @param recorded what the ROOT and route handlers saw
         * @param path     the request path
         */
        private static void assertRanOnSubRouterWrapper(Recorded recorded, String path) {
            RoutingContext root = recorded.rootContext(path);
            RoutingContext route = recorded.routeContext(path);
            assertNotNull(
                    root, "fixture, not the contract: the ROOT recording handler stored the root context of " + path);
            assertNotNull(route, "fixture, not the contract: the route handler of " + path + " recorded its context");
            assertNotSame(
                    root,
                    route,
                    "fixture, not the contract: " + path + " must run on a sub-router wrapper, not the root context");
        }

        /**
         * Asserts TP-003 on one listener: its two-argument method was called once, with the request's root context,
         * whose {@code request()} is the {@code request()} the sub-router route handler received. The count is asserted
         * before the identities, and the received context is checked for {@code null} before it is read.
         *
         * @param counter  the listener
         * @param recorded what the ROOT and route handlers saw
         * @param path     the listener's request path
         * @param subject  names the listener and its request in failure messages
         */
        private static void assertRootContextSharingRouteRequest(
                OverloadCounter<?> counter, Recorded recorded, String path, String subject) {
            assertAll(
                    subject,
                    () -> assertEquals(1, counter.twoArgCalls(), "twoArgCalls: the emitter calls the overload once"),
                    () -> assertSame(
                            recorded.rootContext(path),
                            counter.context(),
                            "the listener's context is the request's root context"),
                    () -> {
                        RoutingContext received = counter.context();
                        assertNotNull(received, "the two-argument call received a context");
                        assertSame(
                                recorded.routeRequest(path),
                                received.request(),
                                "that context's request() is the request() the sub-router route handler received");
                    });
        }

        /**
         * Asserts TP-004's five outcomes for one row. Reaching it means the request's lifecycle barrier completed, so
         * the response lifecycle ended normally.
         *
         * @param isolationCase the row
         * @param throwing      the row's throwing listener, first in its set
         * @param delivered     the events the capturing listener after it received
         * @param status        the response status the client received
         */
        private void assertIsolated(
                IsolationCase isolationCase, ThrowingListener throwing, List<Object> delivered, int status) {
            List<String> warnings = emitterWarnings();
            assertAll(
                    isolationCase.name(),
                    () -> assertEquals(200, status, "the client receives the route handler's 200"),
                    () -> assertEquals(
                            1, throwing.throwingMethodCalls(), "the throwing method is invoked exactly once"),
                    () -> {
                        if (throwing.throwsFromOverride()) {
                            assertEquals(
                                    0,
                                    throwing.oneArgCalls(),
                                    "the one-argument method of a listener throwing from its override is never "
                                            + "invoked");
                        }
                    },
                    () -> assertEquals(1, delivered.size(), "the later, capturing listener receives exactly one event"),
                    () -> assertEquals(1, warnings.size(), "exactly one WARN on the emitter's logger: " + warnings),
                    () -> assertWarningNamesListenerAndFailureButNotPath(isolationCase, warnings));
        }

        /**
         * Asserts the row's single WARN starts with the failed listener type's name, carries the thrower's message,
         * and does not carry the request path.
         *
         * @param isolationCase the row
         * @param warnings      the emitter's WARN messages
         */
        private static void assertWarningNamesListenerAndFailureButNotPath(
                IsolationCase isolationCase, List<String> warnings) {
            if (warnings.size() != 1) {
                return; // the count assertion reports it
            }
            String warning = warnings.get(0);
            String expectedPrefix = isolationCase.path().equals(CLAIMED)
                    ? "RestRequestCompletedListener failed: "
                    : "HttpRequestCompletedListener failed: ";
            assertAll(
                    "the WARN: " + warning,
                    () -> assertTrue(warning.startsWith(expectedPrefix), "it starts with " + expectedPrefix),
                    () -> assertTrue(warning.contains(BOOM), "it carries the thrower's message " + BOOM),
                    () -> assertFalse(warning.contains(isolationCase.path()), "it must not carry the request path"));
        }

        /**
         * Returns the formatted messages of the WARN events the emitter's logger recorded.
         *
         * @return the WARN messages, in logging order
         */
        private List<String> emitterWarnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        /**
         * What the proofs' handlers saw, by request path: each request's root context, stored by the ROOT recording
         * handler, and the context and {@code request()} its route handler received. Written on the thread that ran
         * the handler, read after the request's lifecycle barrier.
         */
        private static final class Recorded {

            private final Map<String, RoutingContext> rootContexts = new ConcurrentHashMap<>();
            private final Map<String, RoutingContext> routeContexts = new ConcurrentHashMap<>();
            private final Map<String, HttpServerRequest> routeRequests = new ConcurrentHashMap<>();

            /**
             * The ROOT recording handler's step: stores {@code rc} as its request's root context.
             *
             * @param rc the root routing context
             */
            void root(RoutingContext rc) {
                rootContexts.put(rc.request().path(), rc);
            }

            /**
             * A route handler: records its own context and {@code request()}, then answers 200.
             *
             * @param rc the routing context the route handler received
             */
            void routeThenRespondOk(RoutingContext rc) {
                String path = rc.request().path();
                routeContexts.put(path, rc);
                routeRequests.put(path, rc.request());
                respondOk(rc);
            }

            /**
             * Returns the root context of the request whose path is {@code path}.
             *
             * @param path the request path
             * @return the root context, or {@code null} when none was stored
             */
            RoutingContext rootContext(String path) {
                return rootContexts.get(path);
            }

            /**
             * Returns the context the route handler of {@code path} received.
             *
             * @param path the request path
             * @return the route handler's context, or {@code null} when none was recorded
             */
            RoutingContext routeContext(String path) {
                return routeContexts.get(path);
            }

            /**
             * Returns the {@code request()} the route handler of {@code path} received.
             *
             * @param path the request path
             * @return the route handler's request, or {@code null} when none was recorded
             */
            HttpServerRequest routeRequest(String path) {
                return routeRequests.get(path);
            }
        }

        /**
         * TP-002's and TP-003's counting double for a listener overriding both methods: it counts the calls of each,
         * and records the event and the context the two-argument call received.
         *
         * @param <E> the event type
         */
        private abstract static class OverloadCounter<E> {

            private final AtomicInteger oneArgCalls = new AtomicInteger();
            private final AtomicInteger twoArgCalls = new AtomicInteger();
            private final AtomicReference<E> event = new AtomicReference<>();
            private final AtomicReference<RoutingContext> context = new AtomicReference<>();

            /** Counts a call of the one-argument method. */
            void countOneArgumentCall() {
                oneArgCalls.incrementAndGet();
            }

            /**
             * Counts a call of the two-argument method and records its arguments.
             *
             * @param received       the event
             * @param routingContext the context
             */
            void recordTwoArgumentCall(E received, RoutingContext routingContext) {
                twoArgCalls.incrementAndGet();
                event.set(received);
                context.set(routingContext);
            }

            int oneArgCalls() {
                return oneArgCalls.get();
            }

            int twoArgCalls() {
                return twoArgCalls.get();
            }

            /**
             * Returns the event the two-argument method received.
             *
             * @return the event, or {@code null} when it was never called
             */
            E event() {
                return event.get();
            }

            /**
             * Returns the context the two-argument method received.
             *
             * @return the context, or {@code null} when it was never called
             */
            RoutingContext context() {
                return context.get();
            }
        }

        /** {@link OverloadCounter} for {@link RestRequestCompletedListener}. */
        private static final class RestOverloadCounter extends OverloadCounter<RestRequestCompletedEvent>
                implements RestRequestCompletedListener {

            @Override
            public void onCompleted(RestRequestCompletedEvent event) {
                countOneArgumentCall();
            }

            @Override
            public void onCompleted(RestRequestCompletedEvent event, RoutingContext routingContext) {
                recordTwoArgumentCall(event, routingContext);
            }
        }

        /** {@link OverloadCounter} for {@link HttpRequestCompletedListener}. */
        private static final class HttpOverloadCounter extends OverloadCounter<HttpRequestCompletedEvent>
                implements HttpRequestCompletedListener {

            @Override
            public void onCompleted(HttpRequestCompletedEvent event) {
                countOneArgumentCall();
            }

            @Override
            public void onCompleted(HttpRequestCompletedEvent event, RoutingContext routingContext) {
                recordTwoArgumentCall(event, routingContext);
            }
        }

        /**
         * TP-004's throwing double: it counts the calls of each of its methods and throws
         * {@code new RuntimeException("listener-boom")} from one of them, after counting. With
         * {@link #throwsFromOverride()} it throws from its two-argument override, and its one-argument method only
         * counts. Without it, it throws from its one-argument method, and its two-argument method runs the
         * interface's default through {@code super}, so it is reached exactly as a listener implementing only the
         * one-argument method is.
         */
        private abstract static class ThrowingListener {

            private final boolean throwsFromOverride;
            private final AtomicInteger oneArgCalls = new AtomicInteger();
            private final AtomicInteger twoArgCalls = new AtomicInteger();

            ThrowingListener(boolean throwsFromOverride) {
                this.throwsFromOverride = throwsFromOverride;
            }

            boolean throwsFromOverride() {
                return throwsFromOverride;
            }

            int oneArgCalls() {
                return oneArgCalls.get();
            }

            /**
             * Returns the calls of the method this listener throws from.
             *
             * @return the two-argument calls with {@link #throwsFromOverride()}, the one-argument calls without
             */
            int throwingMethodCalls() {
                return throwsFromOverride ? twoArgCalls.get() : oneArgCalls.get();
            }

            /** The one-argument method: counts, then throws unless this listener throws from its override. */
            void oneArgumentCall() {
                oneArgCalls.incrementAndGet();
                if (!throwsFromOverride) {
                    throw new RuntimeException(BOOM);
                }
            }

            /** The two-argument override, reached only with {@link #throwsFromOverride()}: counts, then throws. */
            void twoArgumentOverrideCall() {
                twoArgCalls.incrementAndGet();
                throw new RuntimeException(BOOM);
            }

            /**
             * Returns the REST listener set of this row: this listener, then one appending each event it receives to
             * {@code delivered}, for a REST listener; empty otherwise.
             *
             * @param delivered receives the capturing listener's events
             * @return the REST listeners, in dispatch order
             */
            abstract Set<RestRequestCompletedListener> restListeners(List<Object> delivered);

            /**
             * Returns the HTTP listener set of this row: this listener, then one appending each event it receives to
             * {@code delivered}, for an HTTP listener; empty otherwise.
             *
             * @param delivered receives the capturing listener's events
             * @return the HTTP listeners, in dispatch order
             */
            abstract Set<HttpRequestCompletedListener> httpListeners(List<Object> delivered);
        }

        /** {@link ThrowingListener} for {@link RestRequestCompletedListener}. */
        private static final class ThrowingRestListener extends ThrowingListener
                implements RestRequestCompletedListener {

            ThrowingRestListener(boolean throwsFromOverride) {
                super(throwsFromOverride);
            }

            @Override
            public void onCompleted(RestRequestCompletedEvent event) {
                oneArgumentCall();
            }

            @Override
            public void onCompleted(RestRequestCompletedEvent event, RoutingContext routingContext) {
                if (throwsFromOverride()) {
                    twoArgumentOverrideCall();
                } else {
                    RestRequestCompletedListener.super.onCompleted(event, routingContext);
                }
            }

            @Override
            Set<RestRequestCompletedListener> restListeners(List<Object> delivered) {
                RestRequestCompletedListener capturing = delivered::add;
                return inOrder(this, capturing);
            }

            @Override
            Set<HttpRequestCompletedListener> httpListeners(List<Object> delivered) {
                return inOrder();
            }
        }

        /** {@link ThrowingListener} for {@link HttpRequestCompletedListener}. */
        private static final class ThrowingHttpListener extends ThrowingListener
                implements HttpRequestCompletedListener {

            ThrowingHttpListener(boolean throwsFromOverride) {
                super(throwsFromOverride);
            }

            @Override
            public void onCompleted(HttpRequestCompletedEvent event) {
                oneArgumentCall();
            }

            @Override
            public void onCompleted(HttpRequestCompletedEvent event, RoutingContext routingContext) {
                if (throwsFromOverride()) {
                    twoArgumentOverrideCall();
                } else {
                    HttpRequestCompletedListener.super.onCompleted(event, routingContext);
                }
            }

            @Override
            Set<RestRequestCompletedListener> restListeners(List<Object> delivered) {
                return inOrder();
            }

            @Override
            Set<HttpRequestCompletedListener> httpListeners(List<Object> delivered) {
                HttpRequestCompletedListener capturing = delivered::add;
                return inOrder(this, capturing);
            }
        }
    }

    /**
     * A TP-004 row (rest-025 T004): the throwing listener's type, by the request that reaches it, and the method it
     * throws from.
     *
     * @param name               the row's display name
     * @param path               the request path: {@code /claimed} publishes a REST event, {@code /unclaimed} an
     *                           HTTP event
     * @param throwingListener   creates the row's throwing listener, of that event's type, given
     *                           {@code throwsFromOverride}
     * @param throwsFromOverride whether the listener throws from its two-argument override rather than from its
     *                           one-argument method
     */
    private record IsolationCase(
            String name,
            String path,
            Function<Boolean, ListenerOverloads.ThrowingListener> throwingListener,
            boolean throwsFromOverride) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * TP-004's rows: {REST on {@code /claimed}, HTTP on {@code /unclaimed}} × {throws from the one-argument method,
     * throws from the two-argument override}.
     *
     * @return the rows in contract order
     */
    private static Stream<IsolationCase> isolationCases() {
        return Stream.of(
                new IsolationCase(
                        "REST, one-argument throws",
                        ListenerOverloads.CLAIMED,
                        ListenerOverloads.ThrowingRestListener::new,
                        false),
                new IsolationCase(
                        "REST, two-argument override throws",
                        ListenerOverloads.CLAIMED,
                        ListenerOverloads.ThrowingRestListener::new,
                        true),
                new IsolationCase(
                        "HTTP, one-argument throws",
                        ListenerOverloads.UNCLAIMED,
                        ListenerOverloads.ThrowingHttpListener::new,
                        false),
                new IsolationCase(
                        "HTTP, two-argument override throws",
                        ListenerOverloads.UNCLAIMED,
                        ListenerOverloads.ThrowingHttpListener::new,
                        true));
    }

    // --- RequestCompletionScope ---

    /**
     * Helper that creates an emitter with a single completion scope and listener set.
     *
     * <p>Uses the {@code @Inject} constructor with no HTTP listeners and {@code Set.of(scope)} as the
     * completion scopes.
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
     * <p>Uses the {@code @Inject} constructor with no HTTP listeners.
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

        /** T003 TP-007: the {@code /rest} request's log, the scope bracketing the REST listener. */
        private static final List<String> REST_BRACKETED = List.of("open", "rest(marker)", "close");

        /** T003 TP-007: the {@code /plain} request's log, the scope bracketing the HTTP listener. */
        private static final List<String> HTTP_BRACKETED = List.of("open", "http(marker)", "close");

        /**
         * T003 TP-007: the {@code /other} request's log. Scopes bracket the dispatch of an event, and none
         * is dispatched for another transport's claim, so no scope opens (ruling R2).
         */
        private static final List<String> NOTHING_OPENED = List.of();

        /**
         * T003 TP-007 (FR-010): the scope brackets the dispatch of either event type, and none opens for a
         * request another transport claimed. Each request's log is keyed by its path: the scope logs
         * {@code open} and {@code close}, each listener its type and whether the scope's thread-local
         * marker was set. The marker is read again on the thread that ran the dispatch, after every end
         * handler, and removed there.
         */
        @Test
        @DisplayName("scopes bracket the dispatch of the REST and the HTTP event, and none opens for another "
                + "transport's claim")
        void scopeBracketsBothEventTypes(VertxTestContext ctx) {
            ThreadLocal<Boolean> marker = new ThreadLocal<>();
            Map<String, List<String>> logs = new ConcurrentHashMap<>();
            Function<String, List<String>> log = path -> logs.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>());
            Map<String, Boolean> markerSetAfterDispatch = new ConcurrentHashMap<>();

            RequestCompletionScope scope = rc -> {
                String path = rc.request().path();
                log.apply(path).add("open");
                marker.set(Boolean.TRUE);
                return () -> {
                    log.apply(path).add("close");
                    marker.remove();
                };
            };
            RestRequestCompletedListener restListener =
                    event -> log.apply(event.path()).add("rest" + markerTag(marker));
            HttpRequestCompletedListener httpListener =
                    event -> log.apply(event.path()).add("http" + markerTag(marker));
            RestRequestCompletionEmitter em = new RestRequestCompletionEmitter(
                    Optional.empty(),
                    new DefaultContextHolder(),
                    Set.of(restListener),
                    Set.of(httpListener),
                    Set.of(scope));
            PathBarriers barriers = new PathBarriers(path -> {
                try {
                    markerSetAfterDispatch.put(path, marker.get() != null);
                } finally {
                    marker.remove();
                }
            });

            startServer(claimRouter(em, barriers))
                    .compose(port -> getAndAwait(port, REST_PATH, barriers)
                            .compose(status -> getAndAwait(port, PLAIN_PATH, barriers))
                            .compose(status -> getAndAwait(port, OTHER_PATH, barriers)))
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> assertAll(
                                "scope bracket by claim",
                                () -> assertEquals(
                                        REST_BRACKETED,
                                        logs.getOrDefault(REST_PATH, List.of()),
                                        REST_PATH + ": the scope brackets the REST listener"),
                                () -> assertEquals(
                                        HTTP_BRACKETED,
                                        logs.getOrDefault(PLAIN_PATH, List.of()),
                                        PLAIN_PATH + ": the scope brackets the HTTP listener"),
                                () -> assertEquals(
                                        NOTHING_OPENED,
                                        logs.getOrDefault(OTHER_PATH, List.of()),
                                        OTHER_PATH + ": no scope opens, because nothing is dispatched"),
                                () -> assertEquals(
                                        Map.of(REST_PATH, false, PLAIN_PATH, false, OTHER_PATH, false),
                                        markerSetAfterDispatch,
                                        "the marker is cleared after each request")));
                        ctx.completeNow();
                    }));
        }

        /**
         * Returns {@code (marker)} when the scope's thread-local marker is set on the calling thread, and
         * {@code (no marker)} otherwise.
         *
         * @param marker the scope's thread-local marker
         * @return the log suffix
         */
        private static String markerTag(ThreadLocal<Boolean> marker) {
            return Boolean.TRUE.equals(marker.get()) ? "(marker)" : "(no marker)";
        }
    }

    @Nested
    @DisplayName("Wire-failure enrichment")
    class WireFailureEnrichment {

        @Test
        @DisplayName("Framework-owned wire-failure marker on an otherwise-clean completion populates wireFailureCode")
        void emitPopulatesWireFailureCodeFromMarker(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {
                RequestCompletionRecorder.recordWireFailure(rc, new IllegalStateException("truncated stream"));
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

        @Test
        @DisplayName("recordWireFailure is first-writer-wins: a second cause does not replace the first")
        void recordWireFailureFirstWriterWins(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> captured = new ArrayList<>();
            RestRequestCompletionEmitter em = emitter(Set.of(captured::add));
            RouterWithBarrier rb = routerWithBarrier(vertx, em, rc -> {
                RequestCompletionRecorder.recordWireFailure(rc, new IllegalStateException("first"));
                RequestCompletionRecorder.recordWireFailure(rc, new IOException("second-ignored"));
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
                                    "the first recorded cause must win");
                        });
                        ctx.completeNow();
                    }));
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
            // No framework-owned wire-failure marker is set here — only the endResult channel carries a
            // failure, proving emit(ctx, state, endResult) actually threads that argument into the
            // emitted event (the seam markerWinsOverEndHandlerFailure above no longer exercises
            // end-to-end). The shared router's identity handler has already claimed /test for REST when
            // this handler runs, so the direct emit publishes a RestRequestCompletedEvent.
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
    // These proofs (rest-025 T002's TP-004 to TP-012, TP-015 and TP-016) run on a real server whose
    // operation routes sit on a sub-router mounted at /api/*, the HttpVerticle mount shape. Each identity
    // route starts with RequestCompletionRecorder.operationRouteHandler, as the JAX-RS route registrar
    // installs it, and each proof awaits the first-pass sentinel, which fires after emission, before
    // asserting. With T003's claim dispatch, a request no operation route claimed publishes an
    // HttpRequestCompletedEvent instead of a RestRequestCompletedEvent with null identity, and a request
    // another transport claimed publishes nothing; the proofs whose requests can end that way capture both
    // event types and state their outcome as an Expected.

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

    /** TP-012 (a) and (c): exactly one REST event, carrying {@code listItems}. */
    private static final Expected ONE_LIST_ITEMS_EVENT = Expected.oneRestEvent(LIST_ITEMS);

    /**
     * TP-012 (b): within the pass the operation route leaves OTHER unchanged, so the request stays claimed
     * by another transport and publishes no rest-core event. This replaces T002's interim reading, one
     * {@link RestRequestCompletedEvent} with {@code null} identity, now that T003 dispatches by claim.
     */
    private static final Expected OTHER_CLAIM_NO_EVENT = Expected.NO_EVENT;

    /** TP-012 (d): no emitter is mounted, so there is no holder and no event. */
    private static final Expected NO_EMITTER_NO_EVENT = Expected.NO_EVENT;

    /**
     * TP-012 (e): the request stays unclaimed, as with holder removal, and still publishes exactly once: one
     * {@link HttpRequestCompletedEvent}.
     */
    private static final Expected ONE_UNCLAIMED_EVENT = Expected.ONE_HTTP_EVENT;

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
     * Asserts an event's route identity: its {@code operation()} is the {@code expected} instance. It never
     * dereferences the event's operation. A request without route identity publishes no
     * {@link RestRequestCompletedEvent}: {@link Expected} states that outcome.
     *
     * @param expected the operation the event must carry
     * @param event    the event to check
     * @param subject  names the request in failure messages
     */
    private static void assertIdentity(
            RestOperationDescriptor expected, RestRequestCompletedEvent event, String subject) {
        assertSame(expected, event.operation(), subject + ": operation " + expected.operationId());
    }

    /**
     * Summarizes events for failure messages as {@code path -> operationId routeTemplate}, or
     * {@code path -> null} for an event without an operation. It never renders the operation through its
     * {@code toString}.
     *
     * @param events the events to summarize
     * @return one summary line per event, in emission order
     */
    private static List<String> identities(List<RestRequestCompletedEvent> events) {
        return events.stream()
                .map(event -> {
                    RestOperationDescriptor operation = event.operation();
                    return event.path() + " -> "
                            + (operation == null ? "null" : operation.operationId() + " " + operation.routeTemplate());
                })
                .toList();
    }

    /**
     * Writes the four retired {@code rest.events.*} keys with forged values (TP-004, AC-006.1), plus the
     * retired wire-failure key removed by GH-648. The keys are deliberate string literals: the constants
     * that named them are removed, and writing the keys must have no effect on the event.
     *
     * @param rc the routing context to forge the keys on
     */
    private static void forgeRetiredKeys(RoutingContext rc) {
        rc.put("rest.events.operationId", "forged");
        rc.put("rest.events.routeTemplate", "/forged");
        rc.put("rest.events.startTime", Instant.EPOCH);
        rc.put("rest.events.emitted", Boolean.TRUE);
        rc.put("vertique.rest.core.events.wireFailure", new IOException("forged-wire-failure"));
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
                                                    + ", test start=" + before),
                                    () -> assertNull(
                                            event.wireFailureCode(),
                                            "a forged retired wireFailure key must not populate wireFailureCode"));
                        });
                        ctx.completeNow();
                    }));
        }

        /**
         * GH-648: writing the retired public wire-failure key cannot forge a truncated-response signature
         * onto a clean 200, and cannot suppress a real framework-recorded classification.
         */
        @Test
        @DisplayName("Forging the retired wireFailure RoutingContext key neither sets nor suppresses wireFailureCode")
        void forgedWireFailureKeyNeitherSetsNorSuppressesWireFailureCode(VertxTestContext ctx) {
            List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();
            Wired wired = subRouterWith(emitter(Set.of(events::add)), api -> {
                operationRoute(api, "/clean", LIST_ITEMS).handler(rc -> {
                    rc.put("vertique.rest.core.events.wireFailure", new IllegalStateException("forged"));
                    respondOk(rc);
                });
                operationRoute(api, "/real", OPERATION_A).handler(rc -> {
                    rc.put("vertique.rest.core.events.wireFailure", new IllegalStateException("forged-first"));
                    RequestCompletionRecorder.recordWireFailure(rc, new IOException("real-wire-failure"));
                    respondOk(rc);
                });
            });

            startServer(wired.root())
                    .compose(port -> sendCase(port, "/api/clean", "clean")
                            .compose(resp -> {
                                ctx.verify(() -> assertEquals(200, resp.statusCode()));
                                return awaitFirstPassEnd(wired, "clean");
                            })
                            .compose(v -> sendCase(port, "/api/real", "real"))
                            .compose(resp -> {
                                ctx.verify(() -> assertEquals(200, resp.statusCode()));
                                return awaitFirstPassEnd(wired, "real");
                            }))
                    .onComplete(ctx.succeeding(v -> {
                        ctx.verify(() -> {
                            assertEquals(2, events.size(), "exactly one event per request");
                            RestRequestCompletedEvent clean = events.stream()
                                    .filter(e -> "/api/clean".equals(e.path()))
                                    .findFirst()
                                    .orElseThrow();
                            RestRequestCompletedEvent real = events.stream()
                                    .filter(e -> "/api/real".equals(e.path()))
                                    .findFirst()
                                    .orElseThrow();
                            assertNull(
                                    clean.wireFailureCode(),
                                    "forging the retired key alone must leave wireFailureCode null");
                            assertEquals(
                                    "IOException",
                                    real.wireFailureCode(),
                                    "a forged retired key must not suppress a real framework-recorded cause");
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
     * emits once, with the start time of its first pass, as its final pass's claim decides: a
     * {@link RestRequestCompletedEvent} with the operation the final pass matched, or an
     * {@link HttpRequestCompletedEvent} when the final pass matched no operation route (TP-005).
     */
    @Nested
    @DisplayName("Reroute")
    class Reroute {

        /** Every event the emitter published, of either type. */
        private final Published published = Published.capture();

        /** One {@link Instant} per routing pass, taken by a ROOT handler ordered after the emitter. */
        private final List<Instant> passMarks = new CopyOnWriteArrayList<>();

        /** TP-005, one row per reroute target. */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.rest.core.events.RestRequestCompletionEmitterTest#rerouteCases")
        @DisplayName("A reroute emits once, as the final pass's claim decides, with the first pass's start time")
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
                            rerouteCase.expected().assertOn(published, rerouteCase.name());
                            Instant startTime = published.startTimes().get(0);
                            assertAll(
                                    () -> assertEquals(2, passMarks.size(), "the ROOT pass marker must run twice"),
                                    () -> assertFalse(
                                            startTime.isAfter(passMarks.get(0)),
                                            "startTime must be the first pass's: startTime=" + startTime
                                                    + ", pass marks=" + passMarks));
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
            return RootWiring.around(published.emitter())
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
     * FR-023's claim transitions, observed through the published event: a later operation route in the same
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

        /** TP-009. An effective OTHER claim would publish no event at all. */
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
            Published published = Published.capture();
            AtomicInteger status = new AtomicInteger();
            Wired wired = claimCase.router().apply(published.emitter());

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
                                () -> claimCase.expected().assertOn(published, claimCase.name())));
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
     * B at most unclaimed, as holder removal does, so publishing an {@link HttpRequestCompletedEvent}. The
     * rows differ only in where the copy lands, and so in which of the three binding checks meets it
     * (TP-015).
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
            Published published = Published.capture();
            AtomicInteger aStatus = new AtomicInteger();
            AtomicInteger bStatus = new AtomicInteger();
            Wired wired = RootWiring.around(published.emitter())
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
                            Published aEvents = published.ofPath(copyCase.aPath());
                            Published bEvents = published.ofPath(copyCase.bPath());
                            assertAll(
                                    copyCase.name(),
                                    () -> assertEquals(200, aStatus.get(), "request A's status"),
                                    () -> assertEquals(200, bStatus.get(), "request B's status"),
                                    () -> Expected.oneRestEvent(OPERATION_A)
                                            .assertOn(aEvents, "request A, the other request, emits exactly once"),
                                    () -> {
                                        for (RestRequestCompletedEvent bEvent : bEvents.rest()) {
                                            assertNotSame(
                                                    OPERATION_A,
                                                    bEvent.operation(),
                                                    "request B's event must never carry A's operation");
                                        }
                                        copyCase.expectedB()
                                                .assertOn(bEvents, "request B, this request, emits exactly once");
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
     * @param name     the row's display name
     * @param target   the path {@code GET /api/a} reroutes to
     * @param expected what the request publishes, as the final pass's claim decides
     */
    private record RerouteCase(String name, String target, Expected expected) {
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
                new RerouteCase(
                        "(a) rerouted to another operation route", "/api/b", Expected.oneRestEvent(OPERATION_B)),
                new RerouteCase(
                        "(b) rerouted to a plain route: the reroute cleared the REST claim, so no transport claimed "
                                + "the request",
                        "/api/plain",
                        Expected.ONE_HTTP_EVENT));
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
            String name, String requestPath, Function<RestRequestCompletionEmitter, Wired> router, Expected expected) {
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
                        OTHER_CLAIM_NO_EVENT),
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
     * claims REST on the second pass; a kept OTHER claim would block that claim, and the request would
     * publish no event. The target is an operation route, as at T002, where a reroute to a plain route could
     * not tell a kept OTHER claim from a reset one; it also shows the second pass claims REST.
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
     * matches, so the identity handler finds no holder and the request stays unclaimed.
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
     * no {@link ClassCastException}, and the request stays unclaimed.
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
     * @param expectedB what B publishes: a REST event carrying its own operation, or an HTTP event when B is
     *                  left unclaimed
     */
    private record CopyCase(String name, String aPath, CopyPoint copyPoint, String bPath, Expected expectedB) {
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
                        Expected.oneRestEvent(OPERATION_B)),
                // (b) checks the identity handler: the copy displaces B's own holder, so B's operation route
                // finds no holder bound to B, and B stays unclaimed.
                new CopyCase(
                        "(b) copy after B's emitter: the identity handler",
                        "/api/a",
                        CopyPoint.AFTER_EMITTER,
                        "/api/b",
                        Expected.ONE_HTTP_EVENT),
                // (c) checks claimForOtherTransport: A is held before its operation route, so A is still
                // NONE; B's claim finds no holder bound to B, and B stays unclaimed.
                new CopyCase(
                        "(c) copy after B's emitter: claimForOtherTransport",
                        "/api/a-late",
                        CopyPoint.AFTER_EMITTER,
                        "/api/claim",
                        Expected.ONE_HTTP_EVENT));
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

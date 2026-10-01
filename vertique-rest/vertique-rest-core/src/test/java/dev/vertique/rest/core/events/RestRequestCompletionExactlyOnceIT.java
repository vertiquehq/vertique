// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig.InvalidValuePolicy;
import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.BadRequestException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test proving that {@link RestRequestCompletionEmitter} fires <em>exactly one</em>
 * completion event per request across all success and failure paths (FR-AUD-300, PRD-AUD-002 §21): a
 * {@link RestRequestCompletedEvent} for a request an operation route claimed, and an
 * {@link HttpRequestCompletedEvent} for a request no transport claimed.
 *
 * <p>This is a real-server IT (Maven Failsafe, {@code *IT.java}). Each test drives a live
 * {@code HttpServer} bound on port 0 with a fully ordered middleware stack:
 * <ol>
 *   <li>{@link RequestContextLifecycle} — owns holder scope and LIFO end-handler ordering</li>
 *   <li>{@link RestRequestCompletionEmitter} — the SUT (ORDER + 5), built through its {@code @Inject}
 *       constructor with one capturing listener of each event type</li>
 *   <li>{@link CorrelationIngressMiddleware} — configured with strict REJECT policy (ORDER + 10)</li>
 * </ol>
 *
 * <p>Routes under test, each operation route starting with
 * {@link RequestCompletionRecorder#operationRouteHandler} for a stub named after the route, as the
 * JAX-RS route registrar installs it:
 * <ul>
 *   <li>{@code GET /ok} — 200 success path</li>
 *   <li>{@code GET /bad} — explicit 400 response (client-error / validation-style path)</li>
 *   <li>{@code GET /boom} — {@code ctx.fail(500, ex)} exception path; asserts {@code failureCode}
 *       equals the exception's simple class name and the raw message does not leak into the event</li>
 *   <li>{@code GET /denied} — 401 auth-rejection-style path</li>
 *   <li>(no route for {@code GET /nope}) — 404 pre-operation failure; asserts exactly one
 *       {@link HttpRequestCompletedEvent} and no {@link RestRequestCompletedEvent}</li>
 *   <li>Correlation REJECT path — a request carrying an invalid {@code X-Request-Id} header value
 *       triggers the REJECT policy in {@link CorrelationIngressMiddleware} before any operation route
 *       claims it; asserts exactly one {@link HttpRequestCompletedEvent} is still emitted because the
 *       emitter registers its end handler <em>before</em> the correlation middleware can short-circuit
 *       the request</li>
 * </ul>
 *
 * <p>Aggregate assertion: REST events plus HTTP events == total requests (no duplicates, no misses).
 *
 * <p>Note: the real {@link CorrelationIngressMiddleware} is used for the REJECT scenario (not a
 * substitute) because it is constructable without Dagger using the same factory helpers already
 * established by {@code CorrelationIngressMiddlewareTest}.
 *
 * <p>A single {@link WebClient} is shared across all test methods via {@code @BeforeAll} to avoid
 * netty channel-pool churn under full-reactor load. Each test still creates its own
 * {@code HttpServer} (torn down in {@code @AfterEach}) because route wiring differs per test.
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * Both request helpers here only drained the body to keep the pooled connection clean and projected
 * the status code, so they cannot flake on that today — the raw idiom was latent, and would become a
 * live race the moment anyone asserted on the body, with no diff to hint why. A {@link WebClient}
 * aggregates the body into its {@code HttpResponse} before completing the send, so the drain step
 * disappears and the hazard is removed by construction rather than by every author remembering an
 * idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestRequestCompletionExactlyOnceIT {

    // --- Invalid correlation header value that triggers the REJECT policy ---
    // Spaces are outside the allow-list [A-Za-z0-9._~:/+=\-]+ so this value fails
    // CorrelationHeaderValidator.isValidHeaderValue and causes InvalidValuePolicy.REJECT to fire.
    private static final String INVALID_CORRELATION_VALUE = "bad value with spaces";

    // --- Raw exception message that must NOT appear in the emitted event ---
    private static final String BOOM_RAW_MESSAGE = "super secret upstream detail";

    // --- Operation stubs, one per operation route, each named after its route ---

    /** Identity of {@code GET /ok}. */
    private static final RestOperationDescriptor OK_OPERATION = new TestOperation("ok", "GET", "/ok");

    /** Identity of {@code GET /bad}. */
    private static final RestOperationDescriptor BAD_OPERATION = new TestOperation("bad", "GET", "/bad");

    /** Identity of {@code GET /boom}. */
    private static final RestOperationDescriptor BOOM_OPERATION = new TestOperation("boom", "GET", "/boom");

    /** Identity of {@code GET /denied}. */
    private static final RestOperationDescriptor DENIED_OPERATION = new TestOperation("denied", "GET", "/denied");

    // --- Class-scoped resources (shared across all @Test methods) ---

    private static WebClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the shared {@link WebClient} once for the entire test class.
     *
     * @param vertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx vertx) {
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
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
    static void tearDownClient(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    /**
     * Closes the per-test {@link HttpServer}. The shared {@link WebClient} is left open and closed
     * only in {@link #tearDownClient(VertxTestContext)}.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    // --- Setup helpers ---

    /**
     * Builds the shared context holder so the emitter and the correlation middleware see the same
     * duplicated-context values within a single request.
     *
     * @return a fresh {@link DefaultContextHolder}
     */
    private static DefaultContextHolder newHolder() {
        return new DefaultContextHolder();
    }

    /**
     * Constructs a {@link CorrelationIngressMiddleware} with strict REJECT policy so that a
     * request carrying an invalid {@code X-Request-Id} header value is rejected with 400.
     *
     * @param holder the context holder shared with the emitter
     * @return the configured middleware
     */
    private static CorrelationIngressMiddleware newRejectCorrelationMiddleware(DefaultContextHolder holder) {
        CorrelationIngressConfig rejectConfig = new CorrelationIngressConfig(
                "X-Request-Id", "X-Correlation-Id", "X-Causation-Id", true, false, false, InvalidValuePolicy.REJECT);
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContextMutator mutator = new CorrelationContextMutator(holder);
        return new CorrelationIngressMiddleware(
                holder, factory, mutator, rejectConfig, Set.of(), Set.of(), Optional.empty());
    }

    /**
     * Builds the full middleware stack and all routes, wires one capturing listener of each event
     * type into an emitter built through the {@code @Inject} constructor, and returns a future that
     * resolves to the bound HTTP port.
     *
     * <p>Routes, each operation route starting with its stub's
     * {@link RequestCompletionRecorder#operationRouteHandler identity handler}:
     * <ul>
     *   <li>{@code GET /ok} — {@link #OK_OPERATION}, responds 200</li>
     *   <li>{@code GET /bad} — {@link #BAD_OPERATION}, responds 400</li>
     *   <li>{@code GET /boom} — {@link #BOOM_OPERATION}, calls {@code ctx.fail(500, ex)} with a known raw
     *       message</li>
     *   <li>{@code GET /denied} — {@link #DENIED_OPERATION}, responds 401</li>
     *   <li>(no route for /nope — will produce 404)</li>
     * </ul>
     * A trivial failure handler maps any {@code ctx.fail()} call to a 500 response so the HTTP
     * transaction completes and the end handlers fire.
     *
     * @param vertx      the Vert.x instance
     * @param restEvents thread-safe list into which the REST listener appends each event
     * @param httpEvents thread-safe list into which the HTTP listener appends each event
     * @return a future resolving to the server's actual TCP port
     */
    private Future<Integer> startServer(
            Vertx vertx, List<RestRequestCompletedEvent> restEvents, List<HttpRequestCompletedEvent> httpEvents) {
        DefaultContextHolder holder = newHolder();

        RestRequestCompletedListener restListener = restEvents::add;
        HttpRequestCompletedListener httpListener = httpEvents::add;
        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.empty(), holder, Set.of(restListener), Set.of(httpListener), Set.of());

        CorrelationIngressMiddleware correlationMiddleware = newRejectCorrelationMiddleware(holder);

        Router router = Router.router(vertx);

        // 1. RequestContextLifecycle — owns holder scope, fires last in LIFO end-handler order
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());

        // 2. RestRequestCompletionEmitter — SUT (ORDER + 5 = Integer.MIN_VALUE + 5)
        router.route().order(emitter.priority()).handler(emitter);

        // 3. CorrelationIngressMiddleware — strict REJECT (ORDER + 10 = Integer.MIN_VALUE + 10)
        //    Runs after the emitter so the emitter's end handler is always registered, even for
        //    requests that are short-circuited here.
        router.route().order(correlationMiddleware.priority()).handler(correlationMiddleware);

        // --- Application routes: each claims its request for REST first, as the route registrar does ---
        router.get("/ok")
                .handler(RequestCompletionRecorder.operationRouteHandler(OK_OPERATION))
                .handler(rc -> rc.response().setStatusCode(200).end());

        router.get("/bad")
                .handler(RequestCompletionRecorder.operationRouteHandler(BAD_OPERATION))
                .handler(rc -> rc.response().setStatusCode(400).end());

        router.get("/boom")
                .handler(RequestCompletionRecorder.operationRouteHandler(BOOM_OPERATION))
                .handler(rc -> rc.fail(500, new IllegalStateException(BOOM_RAW_MESSAGE)));

        router.get("/denied")
                .handler(RequestCompletionRecorder.operationRouteHandler(DENIED_OPERATION))
                .handler(rc -> rc.response().setStatusCode(401).end());

        // --- Failure handler: maps ctx.fail() calls to HTTP responses so end handlers fire ---
        router.errorHandler(500, rc -> {
            if (!rc.response().ended()) {
                rc.response().setStatusCode(500).end();
            }
        });
        // Also handle 400 failures emitted by the correlation REJECT path
        router.errorHandler(400, rc -> {
            if (!rc.response().ended()) {
                rc.response().setStatusCode(400).end();
            }
        });

        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    return s.actualPort();
                });
    }

    /**
     * Sends a single GET request to the given path on the given port and returns a future that
     * resolves to the HTTP response status code. The {@link WebClient} aggregates the response body
     * before completing the send, so the shared client's pooled connection is never left with an
     * unread response and no explicit drain step is needed.
     *
     * @param port the server port
     * @param path the request path
     * @return a future resolving to the HTTP status code
     */
    private Future<Integer> get(int port, String path) {
        return client.get(port, "127.0.0.1", path).send().map(resp -> resp.statusCode());
    }

    /**
     * Sends a single GET request to the given path with an extra HTTP header and returns a future
     * that resolves to the HTTP response status code. As in {@link #get(int, String)} the
     * {@link WebClient} aggregates the response body before completing the send, so no explicit
     * drain step is needed.
     *
     * @param port        the server port
     * @param path        the request path
     * @param headerName  the header to add
     * @param headerValue the header value
     * @return a future resolving to the HTTP status code
     */
    private Future<Integer> getWithHeader(int port, String path, String headerName, String headerValue) {
        return client.get(port, "127.0.0.1", path)
                .putHeader(headerName, headerValue)
                .send()
                .map(resp -> resp.statusCode());
    }

    // --- Polling helpers ---

    /** Settle window after the expected count is reached, to let any erroneous extra event surface. */
    private static final long SETTLE_MS = 50;

    /**
     * Waits until {@code restEvents} and {@code httpEvents} together hold at least {@code expected}
     * events, then waits one short settle window before completing. The unbounded poll removes the
     * load-dependent miss-flake (a fixed pre-assert delay was too short under CPU contention, so
     * events arrived after the assertion and shifted index-based checks). The trailing
     * {@link #SETTLE_MS} settle is a bounded proof, not an unconditional one: it only catches a
     * duplicate event emitted within {@link #SETTLE_MS} (50 ms) of the threshold being reached — a
     * duplicate emitted later escapes this check entirely. The causal-barrier pattern in
     * {@code RestRequestCompletionEmitterTest} (await {@code afterClose} rather than a settle window)
     * is the stronger form of this proof; prefer it if this helper is ever replaced. The class-level
     * {@code @Timeout} is the upper bound.
     *
     * <p>The wait counts both event types, so a request emitting the other type than a test expects
     * still ends the wait and fails on the test's assertions rather than on the timeout.
     *
     * @param vertx      the Vert.x instance
     * @param restEvents the list being populated by the REST listener
     * @param httpEvents the list being populated by the HTTP listener
     * @param expected   the number of events, of either type, to wait for
     * @return a future that completes once the two lists together reach {@code expected} and the
     *         settle elapses
     */
    private static Future<Void> awaitCaptured(
            Vertx vertx,
            List<RestRequestCompletedEvent> restEvents,
            List<HttpRequestCompletedEvent> httpEvents,
            int expected) {
        Promise<Void> promise = Promise.promise();
        pollCaptured(vertx, restEvents, httpEvents, expected, promise);
        return promise.future();
    }

    /**
     * Recursive polling step: once the two lists together reach {@code expected}, schedule one settle
     * timer and then complete; otherwise a 10 ms timer fires the next poll iteration.
     *
     * @param vertx      the Vert.x instance
     * @param restEvents the list being populated by the REST listener
     * @param httpEvents the list being populated by the HTTP listener
     * @param expected   the minimum number of events, of either type, required
     * @param promise    the promise to complete after the threshold is reached and the settle elapses
     */
    private static void pollCaptured(
            Vertx vertx,
            List<RestRequestCompletedEvent> restEvents,
            List<HttpRequestCompletedEvent> httpEvents,
            int expected,
            Promise<Void> promise) {
        if (restEvents.size() + httpEvents.size() >= expected) {
            // Local transient settle — give any erroneous extra event a window to surface
            vertx.setTimer(SETTLE_MS, id -> promise.complete());
        } else {
            // Local transient poll — lifetime bound to this single test's wait window
            vertx.setTimer(10, id -> pollCaptured(vertx, restEvents, httpEvents, expected, promise));
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("Exactly one event emitted for the 200 success path (GET /ok)")
    void exactlyOneEventOnSuccess(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();

        startServer(vertx, restEvents, httpEvents)
                .compose(port -> get(port, "/ok"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(200, status));
                    return awaitCaptured(vertx, restEvents, httpEvents, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(List.of(), httpEvents, "no HTTP event: /ok's operation route claimed the request");
                        assertEquals(1, restEvents.size(), "exactly one event must be emitted for /ok");
                        RestRequestCompletedEvent event = restEvents.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/ok", event.path());
                        assertEquals(200, event.statusCode());
                        assertNotNull(event.startTime());
                        assertNotNull(event.endTime());
                        assertNull(event.failureCode(), "no failure on 200");
                        assertNull(event.safeFailureMessage(), "no failure message on 200");
                        assertSame(OK_OPERATION, event.operation(), "the event carries /ok's operation instance");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Exactly one event emitted for the 400 client-error path (GET /bad)")
    void exactlyOneEventOn400(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();

        startServer(vertx, restEvents, httpEvents)
                .compose(port -> get(port, "/bad"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(400, status));
                    return awaitCaptured(vertx, restEvents, httpEvents, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(
                                List.of(), httpEvents, "no HTTP event: /bad's operation route claimed the request");
                        assertEquals(1, restEvents.size(), "exactly one event must be emitted for /bad");
                        RestRequestCompletedEvent event = restEvents.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/bad", event.path());
                        assertEquals(400, event.statusCode());
                        // No ctx.fail() was called — failureCode comes from ctx.failure() which is null
                        assertNull(event.failureCode(), "failureCode must be null when response was set directly");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Exactly one event on ctx.fail(500, ex); failureCode = exception simple name; raw message absent")
    void exactlyOneEventOnBoom(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();

        startServer(vertx, restEvents, httpEvents)
                .compose(port -> get(port, "/boom"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(500, status));
                    return awaitCaptured(vertx, restEvents, httpEvents, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(
                                List.of(), httpEvents, "no HTTP event: /boom's operation route claimed the request");
                        assertEquals(1, restEvents.size(), "exactly one event must be emitted for /boom");
                        RestRequestCompletedEvent event = restEvents.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/boom", event.path());
                        assertEquals(500, event.statusCode());
                        assertEquals(
                                "IllegalStateException",
                                event.failureCode(),
                                "failureCode must be the exception's simple class name");
                        assertNull(event.safeFailureMessage(), "safeFailureMessage must be null (§10.3)");
                        // The raw exception message must not appear anywhere in the event
                        assertRawMessageAbsent(event, BOOM_RAW_MESSAGE);
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Exactly one event emitted for the 401 auth-rejection-style path (GET /denied)")
    void exactlyOneEventOn401(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();

        startServer(vertx, restEvents, httpEvents)
                .compose(port -> get(port, "/denied"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(401, status));
                    return awaitCaptured(vertx, restEvents, httpEvents, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(
                                List.of(), httpEvents, "no HTTP event: /denied's operation route claimed the request");
                        assertEquals(1, restEvents.size(), "exactly one event must be emitted for /denied");
                        RestRequestCompletedEvent event = restEvents.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/denied", event.path());
                        assertEquals(401, event.statusCode());
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Exactly one HTTP event and no REST event for a 404 (no matching route, GET /nope)")
    void exactlyOneHttpEventAndNoRestEventOn404(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();

        startServer(vertx, restEvents, httpEvents)
                .compose(port -> get(port, "/nope"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(404, status));
                    return awaitCaptured(vertx, restEvents, httpEvents, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertAll(
                                "/nope matched no operation route, so no transport claimed it",
                                () -> assertEquals(
                                        1,
                                        httpEvents.size(),
                                        "exactly one HTTP event must be emitted for /nope: " + httpEvents),
                                () -> assertEquals(List.of(), restEvents, "no REST event for /nope"));
                        HttpRequestCompletedEvent event = httpEvents.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/nope", event.path());
                        assertEquals(404, event.statusCode());
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "Exactly one event emitted for a correlation REJECT (invalid X-Request-Id, real CorrelationIngressMiddleware)")
    void exactlyOneEventOnCorrelationReject(Vertx vertx, VertxTestContext ctx) {
        // The emitter registers its end handler at ORDER+5, before CorrelationIngressMiddleware
        // at ORDER+10. So the emitter's end handler is registered even when correlation rejects
        // the request. The rejection happens at ROOT, before /ok's route could claim the request,
        // so the one event is an HttpRequestCompletedEvent. The CorrelationContext may be null on
        // the event since the middleware calls ctx.fail() immediately after binding the generated
        // context, but the important invariant is that exactly one event is emitted.
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();

        startServer(vertx, restEvents, httpEvents)
                // Send a request with an X-Request-Id value that contains spaces, which are outside
                // the allow-list [A-Za-z0-9._~:/+=\-]+. With InvalidValuePolicy.REJECT this causes
                // a 400 short-circuit inside CorrelationIngressMiddleware.
                .compose(port -> getWithHeader(port, "/ok", "X-Request-Id", INVALID_CORRELATION_VALUE))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(400, status, "correlation REJECT must respond 400"));
                    return awaitCaptured(vertx, restEvents, httpEvents, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertAll(
                                "the REJECT happens at ROOT, before any operation route claims the request",
                                () -> assertEquals(
                                        1,
                                        httpEvents.size(),
                                        "exactly one HTTP event must be emitted even when correlation middleware "
                                                + "rejects: " + httpEvents),
                                () -> assertEquals(List.of(), restEvents, "no REST event for the rejected request"));
                        HttpRequestCompletedEvent event = httpEvents.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/ok", event.path());
                        assertEquals(400, event.statusCode());
                        // failureCode is present because CorrelationIngressMiddleware calls
                        // ctx.fail(400, new BadRequestException(...))
                        assertEquals(
                                BadRequestException.class.getSimpleName(),
                                event.failureCode(),
                                "failureCode must identify the exception that caused the REJECT");
                    });
                    ctx.completeNow();
                }));
    }

    /**
     * rest-025 T003 TP-014: the four operation routes each yield one {@link RestRequestCompletedEvent}
     * carrying its route's stub, and {@code /nope} and the correlation-rejected {@code /ok} each yield
     * one {@link HttpRequestCompletedEvent}, so the two event types together count the six requests.
     *
     * @param vertx the class-scoped Vert.x instance
     * @param ctx   the test context
     */
    @Test
    @DisplayName("Aggregate: REST events + HTTP events == total requests across all paths (no duplicates, no misses)")
    void aggregateTotalEventsEqualsRequests(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
        List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();
        int requests = 6;
        List<Integer> expectedRestStatuses = List.of(200, 400, 500, 401);
        List<RestOperationDescriptor> expectedRestOperations =
                List.of(OK_OPERATION, BAD_OPERATION, BOOM_OPERATION, DENIED_OPERATION);
        List<Integer> expectedHttpStatuses = List.of(404, 400);
        List<String> expectedHttpPaths = List.of("/nope", "/ok");

        startServer(vertx, restEvents, httpEvents)
                .compose(port ->
                        // Fire all six request scenarios sequentially so each captured list's order is
                        // deterministic and the index-based assertions are meaningful.
                        get(port, "/ok")
                                .compose(v -> get(port, "/bad"))
                                .compose(v -> get(port, "/boom"))
                                .compose(v -> get(port, "/denied"))
                                .compose(v -> get(port, "/nope"))
                                .compose(v -> getWithHeader(port, "/ok", "X-Request-Id", INVALID_CORRELATION_VALUE))
                                .map(port))
                .compose(port -> awaitCaptured(vertx, restEvents, httpEvents, requests))
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> assertAll(
                            "aggregate: REST " + restSummary(restEvents) + ", HTTP " + httpSummary(httpEvents),
                            () -> assertEquals(
                                    requests,
                                    restEvents.size() + httpEvents.size(),
                                    "REST events plus HTTP events must equal the six requests: no duplicates, "
                                            + "no misses"),
                            () -> assertEquals(
                                    expectedRestStatuses,
                                    restEvents.stream()
                                            .map(RestRequestCompletedEvent::statusCode)
                                            .toList(),
                                    "REST event statuses, in order: /ok, /bad, /boom, /denied"),
                            () -> assertOperations(expectedRestOperations, restEvents),
                            () -> assertEquals(
                                    expectedHttpStatuses,
                                    httpEvents.stream()
                                            .map(HttpRequestCompletedEvent::statusCode)
                                            .toList(),
                                    "HTTP event statuses, in order: /nope, the rejected /ok"),
                            () -> assertEquals(
                                    expectedHttpPaths,
                                    httpEvents.stream()
                                            .map(HttpRequestCompletedEvent::path)
                                            .toList(),
                                    "HTTP event paths, in order: /nope, the rejected /ok"),
                            () -> {
                                // No event of either type may carry the raw boom message
                                for (RestRequestCompletedEvent event : restEvents) {
                                    assertRawMessageAbsent(event, BOOM_RAW_MESSAGE);
                                }
                                for (HttpRequestCompletedEvent event : httpEvents) {
                                    assertRawMessageAbsent(event, BOOM_RAW_MESSAGE);
                                }
                            }));
                    ctx.completeNow();
                }));
    }

    // --- Assertion helpers ---

    /**
     * Asserts that {@code events} are exactly one event per {@code expected} operation, in order, each
     * carrying that operation instance. The count is checked before any event is indexed, and no
     * event's operation is dereferenced.
     *
     * @param expected the operations the events must carry, in order
     * @param events   the captured REST events
     */
    private static void assertOperations(
            List<RestOperationDescriptor> expected, List<RestRequestCompletedEvent> events) {
        assertEquals(expected.size(), events.size(), "one REST event per operation route: " + restSummary(events));
        for (int i = 0; i < expected.size(); i++) {
            RestRequestCompletedEvent event = events.get(i);
            assertSame(
                    expected.get(i),
                    event.operation(),
                    "REST event " + i + " (" + event.path() + ") must carry its route's operation instance");
        }
    }

    /**
     * Summarizes REST events for failure messages as {@code path status -> operationId routeTemplate},
     * or {@code path status -> null} for an event without an operation.
     *
     * @param events the events to summarize
     * @return one summary entry per event, in emission order
     */
    private static List<String> restSummary(List<RestRequestCompletedEvent> events) {
        return events.stream()
                .map(event -> {
                    RestOperationDescriptor operation = event.operation();
                    return event.path() + " " + event.statusCode() + " -> "
                            + (operation == null ? "null" : operation.operationId() + " " + operation.routeTemplate());
                })
                .toList();
    }

    /**
     * Summarizes HTTP events for failure messages as {@code path status}.
     *
     * @param events the events to summarize
     * @return one summary entry per event, in emission order
     */
    private static List<String> httpSummary(List<HttpRequestCompletedEvent> events) {
        return events.stream()
                .map(event -> event.path() + " " + event.statusCode())
                .toList();
    }

    /**
     * Verifies that the raw exception message does not appear in any string field of the REST event,
     * nor in its operation's {@code operationId} and {@code routeTemplate} when it carries one.
     *
     * @param event      the event to inspect
     * @param rawMessage the forbidden substring
     */
    private static void assertRawMessageAbsent(RestRequestCompletedEvent event, String rawMessage) {
        assertFieldDoesNotContain("failureCode", event.failureCode(), rawMessage);
        assertFieldDoesNotContain("safeFailureMessage", event.safeFailureMessage(), rawMessage);
        assertFieldDoesNotContain("wireFailureCode", event.wireFailureCode(), rawMessage);
        assertFieldDoesNotContain("path", event.path(), rawMessage);
        assertFieldDoesNotContain("method", event.method(), rawMessage);
        RestOperationDescriptor operation = event.operation();
        if (operation != null) {
            assertFieldDoesNotContain("operation.operationId", operation.operationId(), rawMessage);
            assertFieldDoesNotContain("operation.routeTemplate", operation.routeTemplate(), rawMessage);
        }
        assertSafeAttributesDoNotContain(event.safeAttributes(), rawMessage);
    }

    /**
     * Verifies that the raw exception message does not appear in any string field of the HTTP event.
     *
     * @param event      the event to inspect
     * @param rawMessage the forbidden substring
     */
    private static void assertRawMessageAbsent(HttpRequestCompletedEvent event, String rawMessage) {
        assertFieldDoesNotContain("failureCode", event.failureCode(), rawMessage);
        assertFieldDoesNotContain("safeFailureMessage", event.safeFailureMessage(), rawMessage);
        assertFieldDoesNotContain("wireFailureCode", event.wireFailureCode(), rawMessage);
        assertFieldDoesNotContain("path", event.path(), rawMessage);
        assertFieldDoesNotContain("method", event.method(), rawMessage);
        assertSafeAttributesDoNotContain(event.safeAttributes(), rawMessage);
    }

    /**
     * Verifies that no string value of an event's {@code safeAttributes} contains the raw exception
     * message.
     *
     * @param safeAttributes the event's safe attributes
     * @param rawMessage     the forbidden substring
     */
    private static void assertSafeAttributesDoNotContain(Map<String, Object> safeAttributes, String rawMessage) {
        for (Map.Entry<String, Object> entry : safeAttributes.entrySet()) {
            if (entry.getValue() instanceof String s) {
                assertFieldDoesNotContain("safeAttributes[" + entry.getKey() + "]", s, rawMessage);
            }
        }
    }

    /**
     * Asserts that {@code value} does not contain {@code forbidden}, or is {@code null}.
     *
     * @param fieldName the field name for the assertion message
     * @param value     the value to check; {@code null} passes
     * @param forbidden the substring that must not appear
     */
    private static void assertFieldDoesNotContain(String fieldName, String value, String forbidden) {
        if (value != null) {
            assertTrue(
                    !value.contains(forbidden),
                    fieldName + " must not contain the raw exception message '" + forbidden + "' but was: " + value);
        }
    }
}

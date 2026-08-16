// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig.InvalidValuePolicy;
import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
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
 * {@link RestRequestCompletedEvent} per handled request across all success and failure paths
 * (FR-AUD-300, PRD-AUD-002 §21).
 *
 * <p>This is a real-server IT (Maven Failsafe, {@code *IT.java}). Each test drives a live
 * {@code HttpServer} bound on port 0 with a fully ordered middleware stack:
 * <ol>
 *   <li>{@link RequestContextLifecycle} — owns holder scope and LIFO end-handler ordering</li>
 *   <li>{@link RestRequestCompletionEmitter} — the SUT (ORDER + 5)</li>
 *   <li>{@link CorrelationIngressMiddleware} — configured with strict REJECT policy (ORDER + 10)</li>
 * </ol>
 *
 * <p>Routes under test:
 * <ul>
 *   <li>{@code GET /ok} — 200 success path</li>
 *   <li>{@code GET /bad} — explicit 400 response (client-error / validation-style path)</li>
 *   <li>{@code GET /boom} — {@code ctx.fail(500, ex)} exception path; asserts {@code failureCode}
 *       equals the exception's simple class name and the raw message does not leak into the event</li>
 *   <li>{@code GET /denied} — 401 auth-rejection-style path</li>
 *   <li>(no route for {@code GET /nope}) — 404 pre-operation failure; asserts {@code operationId}
 *       is {@code null}</li>
 *   <li>Correlation REJECT path — a request carrying an invalid {@code X-Request-Id} header value
 *       triggers the REJECT policy in {@link CorrelationIngressMiddleware}; asserts exactly one
 *       event is still emitted because the emitter registers its end handler <em>before</em> the
 *       correlation middleware can short-circuit the request</li>
 * </ul>
 *
 * <p>Aggregate assertion: total events == total requests (no duplicates, no misses).
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
        client = WebClient.create(vertx);
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
     * Builds the full middleware stack and all routes, wires a capturing listener, and returns a
     * future that resolves to the bound HTTP port.
     *
     * <p>Routes:
     * <ul>
     *   <li>{@code GET /ok} — responds 200</li>
     *   <li>{@code GET /bad} — responds 400</li>
     *   <li>{@code GET /boom} — calls {@code ctx.fail(500, ex)} with a known raw message</li>
     *   <li>{@code GET /denied} — responds 401</li>
     *   <li>(no route for /nope — will produce 404)</li>
     * </ul>
     * A trivial failure handler maps any {@code ctx.fail()} call to a 500 response so the HTTP
     * transaction completes and the end handlers fire.
     *
     * @param vertx    the Vert.x instance
     * @param captured thread-safe list into which the listener appends each event
     * @return a future resolving to the server's actual TCP port
     */
    private Future<Integer> startServer(Vertx vertx, List<RestRequestCompletedEvent> captured) {
        DefaultContextHolder holder = newHolder();

        RestRequestCompletionEmitter emitter =
                new RestRequestCompletionEmitter(Optional.empty(), holder, Set.of(captured::add));

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

        // --- Application routes ---
        router.get("/ok").handler(rc -> rc.response().setStatusCode(200).end());

        router.get("/bad").handler(rc -> rc.response().setStatusCode(400).end());

        router.get("/boom").handler(rc -> rc.fail(500, new IllegalStateException(BOOM_RAW_MESSAGE)));

        router.get("/denied").handler(rc -> rc.response().setStatusCode(401).end());

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
     * Waits until {@code captured} holds at least {@code expected} events, then waits one short
     * settle window before completing. The unbounded poll removes the load-dependent miss-flake
     * (a fixed pre-assert delay was too short under CPU contention, so events arrived after the
     * assertion and shifted index-based checks). The trailing {@link #SETTLE_MS} settle is a
     * bounded proof, not an unconditional one: it only catches a duplicate event emitted within
     * {@link #SETTLE_MS} (50 ms) of the threshold being reached — a duplicate emitted later escapes
     * this check entirely. The causal-barrier pattern in {@code RestRequestCompletionEmitterTest}
     * (await {@code afterClose} rather than a settle window) is the stronger form of this proof;
     * prefer it if this helper is ever replaced. The class-level {@code @Timeout} is the upper
     * bound.
     *
     * @param vertx    the Vert.x instance
     * @param captured the list being populated by the event listener
     * @param expected the number of events to wait for
     * @return a future that completes once {@code captured.size() >= expected} and the settle elapses
     */
    private static Future<Void> awaitCaptured(Vertx vertx, List<RestRequestCompletedEvent> captured, int expected) {
        Promise<Void> promise = Promise.promise();
        pollCaptured(vertx, captured, expected, promise);
        return promise.future();
    }

    /**
     * Recursive polling step: once the list reaches {@code expected}, schedule one settle timer and
     * then complete; otherwise a 10 ms timer fires the next poll iteration.
     *
     * @param vertx    the Vert.x instance
     * @param captured the list being populated by the event listener
     * @param expected the minimum number of events required
     * @param promise  the promise to complete after the threshold is reached and the settle elapses
     */
    private static void pollCaptured(
            Vertx vertx, List<RestRequestCompletedEvent> captured, int expected, Promise<Void> promise) {
        if (captured.size() >= expected) {
            // Local transient settle — give any erroneous extra event a window to surface
            vertx.setTimer(SETTLE_MS, id -> promise.complete());
        } else {
            // Local transient poll — lifetime bound to this single test's wait window
            vertx.setTimer(10, id -> pollCaptured(vertx, captured, expected, promise));
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("Exactly one event emitted for the 200 success path (GET /ok)")
    void exactlyOneEventOnSuccess(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                .compose(port -> get(port, "/ok"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(200, status));
                    return awaitCaptured(vertx, captured, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(1, captured.size(), "exactly one event must be emitted for /ok");
                        RestRequestCompletedEvent event = captured.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/ok", event.path());
                        assertEquals(200, event.statusCode());
                        assertNotNull(event.startTime());
                        assertNotNull(event.endTime());
                        assertNull(event.failureCode(), "no failure on 200");
                        assertNull(event.safeFailureMessage(), "no failure message on 200");
                        // operationId is null because no OperationIdCaptureContributor is wired
                        assertNull(event.operationId(), "operationId must be null without the contributor");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Exactly one event emitted for the 400 client-error path (GET /bad)")
    void exactlyOneEventOn400(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                .compose(port -> get(port, "/bad"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(400, status));
                    return awaitCaptured(vertx, captured, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(1, captured.size(), "exactly one event must be emitted for /bad");
                        RestRequestCompletedEvent event = captured.get(0);
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
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                .compose(port -> get(port, "/boom"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(500, status));
                    return awaitCaptured(vertx, captured, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(1, captured.size(), "exactly one event must be emitted for /boom");
                        RestRequestCompletedEvent event = captured.get(0);
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
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                .compose(port -> get(port, "/denied"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(401, status));
                    return awaitCaptured(vertx, captured, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(1, captured.size(), "exactly one event must be emitted for /denied");
                        RestRequestCompletedEvent event = captured.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/denied", event.path());
                        assertEquals(401, event.statusCode());
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Exactly one event with null operationId for a 404 (no matching route, GET /nope)")
    void exactlyOneEventOn404WithNullOperationId(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                .compose(port -> get(port, "/nope"))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(404, status));
                    return awaitCaptured(vertx, captured, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(1, captured.size(), "exactly one event must be emitted for /nope");
                        RestRequestCompletedEvent event = captured.get(0);
                        assertEquals("GET", event.method());
                        assertEquals("/nope", event.path());
                        assertEquals(404, event.statusCode());
                        assertNull(
                                event.operationId(), "operationId must be null for a 404 — no operation dispatch ran");
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
        // the request. The CorrelationContext may be null on the event since the middleware calls
        // ctx.fail() immediately after binding the generated context, but the important invariant
        // is that exactly one event is emitted.
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                // Send a request with an X-Request-Id value that contains spaces, which are outside
                // the allow-list [A-Za-z0-9._~:/+=\-]+. With InvalidValuePolicy.REJECT this causes
                // a 400 short-circuit inside CorrelationIngressMiddleware.
                .compose(port -> getWithHeader(port, "/ok", "X-Request-Id", INVALID_CORRELATION_VALUE))
                .compose(status -> {
                    ctx.verify(() -> assertEquals(400, status, "correlation REJECT must respond 400"));
                    return awaitCaptured(vertx, captured, 1);
                })
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(
                                1,
                                captured.size(),
                                "exactly one event must be emitted even when correlation middleware rejects");
                        RestRequestCompletedEvent event = captured.get(0);
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

    @Test
    @DisplayName("Aggregate: total events == total requests across all paths (no duplicates, no misses)")
    void aggregateTotalEventsEqualsRequests(Vertx vertx, VertxTestContext ctx) {
        List<RestRequestCompletedEvent> captured = new CopyOnWriteArrayList<>();

        startServer(vertx, captured)
                .compose(port ->
                        // Fire all six request scenarios sequentially so the captured list order is
                        // deterministic and the total-count assertion is meaningful.
                        get(port, "/ok")
                                .compose(v -> get(port, "/bad"))
                                .compose(v -> get(port, "/boom"))
                                .compose(v -> get(port, "/denied"))
                                .compose(v -> get(port, "/nope"))
                                .compose(v -> getWithHeader(port, "/ok", "X-Request-Id", INVALID_CORRELATION_VALUE))
                                .map(port))
                .compose(port -> awaitCaptured(vertx, captured, 6))
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        assertEquals(
                                6,
                                captured.size(),
                                "aggregate: total events must equal total requests (6) — no duplicates, no misses");

                        // Verify status codes in the order fired
                        assertEquals(200, captured.get(0).statusCode(), "/ok -> 200");
                        assertEquals(400, captured.get(1).statusCode(), "/bad -> 400");
                        assertEquals(500, captured.get(2).statusCode(), "/boom -> 500");
                        assertEquals(401, captured.get(3).statusCode(), "/denied -> 401");
                        assertEquals(404, captured.get(4).statusCode(), "/nope -> 404");
                        assertEquals(400, captured.get(5).statusCode(), "correlation REJECT -> 400");

                        // No event may have the raw boom message
                        for (RestRequestCompletedEvent event : captured) {
                            assertRawMessageAbsent(event, BOOM_RAW_MESSAGE);
                        }
                    });
                    ctx.completeNow();
                }));
    }

    // --- Assertion helpers ---

    /**
     * Verifies that the raw exception message does not appear in any string field of the event.
     *
     * @param event      the event to inspect
     * @param rawMessage the forbidden substring
     */
    private static void assertRawMessageAbsent(RestRequestCompletedEvent event, String rawMessage) {
        assertFieldDoesNotContain("failureCode", event.failureCode(), rawMessage);
        assertFieldDoesNotContain("safeFailureMessage", event.safeFailureMessage(), rawMessage);
        assertFieldDoesNotContain("path", event.path(), rawMessage);
        assertFieldDoesNotContain("method", event.method(), rawMessage);
        assertFieldDoesNotContain("operationId", event.operationId(), rawMessage);
        assertFieldDoesNotContain("routeTemplate", event.routeTemplate(), rawMessage);
        // Also check safeAttributes values
        for (Map.Entry<String, Object> entry : event.safeAttributes().entrySet()) {
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

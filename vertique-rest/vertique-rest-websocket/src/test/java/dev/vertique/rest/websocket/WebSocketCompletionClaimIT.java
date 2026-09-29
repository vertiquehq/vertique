// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.IdentityPipelineFactory;
import dev.vertique.rest.security.SecurityClaimMapper;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves which rest-core completion events a WebSocket upgrade produces when the rest-core completion
 * emitter runs as ROOT middleware in front of a {@link WebSocketMount}.
 *
 * <p>A successful upgrade produces no completion event of either type, neither at the 101 nor after the
 * client closes the connection; the connection is observed through {@link OnOpen} and {@link OnClose}. An
 * upgrade that authentication rejects with 401 never upgrades, and completes as an ordinary HTTP request
 * with exactly one {@link HttpRequestCompletedEvent} and no {@link RestRequestCompletedEvent}.
 *
 * <p><strong>Harness.</strong> One server serves the whole class. Its root router follows {@code
 * HttpVerticle}'s ROOT order: {@link RequestContextLifecycle}, the {@link CompletionBarrier}, the {@link
 * RestRequestCompletionEmitter} with one recording REST listener and one recording HTTP listener, then the
 * WebSocket mount's sub-router. The mount comes from a {@link WebSocketMount.Factory} wired with the
 * security stubs of {@link WebSocketSecurityPipelineIT}, and serves {@link OpenLifecycleEndpoint} and the
 * {@code @Authorized} {@link SecuredEndpoint}.
 *
 * <p><strong>Barrier.</strong> The barrier registers its end handler on a request's first routing pass,
 * after the lifecycle's and before the emitter's. Vert.x Web runs end handlers once, in reverse
 * registration order, so once the barrier has recorded a request the emitter has dispatched every event it
 * will produce for it. The server is one {@link HttpServer} started once, so its connections share one
 * event loop: a barrier request sent after an observation is handled after every end handler that the
 * observation could have triggered. Barrier requests are WebSocket connects to unmatched paths. Each fails
 * its handshake with 404 and produces one {@link HttpRequestCompletedEvent}, which also shows that the
 * emitter and the HTTP listener are live. Every "no event" assertion waits for a barrier, never for a fixed
 * sleep.
 *
 * <p><strong>Late close.</strong> Whether Vert.x runs an upgraded request's routing end handlers when the
 * connection later closes is observed, not assumed. The upgrade-and-close test prints the outcome, (a) the
 * end handlers ran after the close or (b) none ran, followed by the barrier's entry and completion records,
 * to its standard output. It asserts no event for the upgraded request under either outcome.
 *
 * <p><strong>Waits and assertions.</strong> Every wait is bounded and never throws: it reports whether its
 * condition held, and the assertions report the rest. One wait lasts at most {@link #WAIT_BOUND}, and all
 * waits of one test share {@link #WAIT_BUDGET}, below the class timeout, so a stall fails an assertion
 * instead of timing the test out. Each test ends in one {@code assertAll} over named blocks; each block
 * checks counts before identities, so a missing value fails its block instead of throwing.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketCompletionClaimIT {

    private static final String OPEN_PATH = "/ws/claim-open";
    private static final String SECURED_PATH = "/ws/claim-secured";
    private static final String BARRIER_OPEN_PATH = "/ws/barrier-open";
    private static final String BARRIER_CLOSE_PATH = "/ws/barrier-close";

    private static final int NOT_FOUND = 404;
    private static final int UNAUTHORIZED = 401;

    /** The longest any single wait may last. */
    private static final Duration WAIT_BOUND = Duration.ofSeconds(5);

    /** The total all waits of one test may last, from the test's start; below the 20 s class timeout. */
    private static final Duration WAIT_BUDGET = Duration.ofSeconds(15);

    /** The emitter's context holder: it binds nothing and resolves nothing. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    private static final CompletionBarrier BARRIER = new CompletionBarrier();

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;

    private final long waitDeadlineNanos = System.nanoTime() + WAIT_BUDGET.toNanos();

    // --- BeforeAll / AfterAll / BeforeEach ---

    /**
     * Builds the WebSocket mount through {@link WebSocketMount.Factory}, mounts its sub-router behind the
     * root middleware in {@code HttpVerticle}'s ROOT order, and starts the shared server on {@code
     * 127.0.0.1:0}.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx the test context used for async startup
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        SecurityEventEmitter securityEvents = new SecurityEventEmitter(Set.of());

        // The security pipeline's holder: a stub correlation context, and a bind that keeps nothing.
        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext stubCorrelation = correlationFactory.create(
                new CorrelationIdentifier("test-req", "test"), new CorrelationIdentifier("test-cor", "test"));
        ContextHolder pipelineContextHolder = new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    return (Optional<T>) Optional.of(stubCorrelation);
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };

        IdentityPipelineFactory identityPipelineFactory = new IdentityPipelineFactory(
                Set.<SecurityIdentityResolver>of(new WebSocketSecurityPipelineIT.EvidenceBasedIdentityResolver()),
                Optional.<SecurityClaimMapper>of(new DefaultSecurityClaimMapper()),
                securityEvents,
                securityRuntime,
                pipelineContextHolder,
                Optional.empty(),
                Optional.empty(),
                Optional.<AuthorizationDecisionPoint>of(new WebSocketSecurityPipelineIT.RoleCheckDecisionPoint()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty());

        WebSocketMount.Factory factory = new WebSocketMount.Factory(
                new WebSocketMessageCodec(),
                Optional.of(identityPipelineFactory),
                Set.<RouteAuthHandler>of(new WebSocketSecurityPipelineIT.StubBearerAuthHandler()),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.empty(),
                NO_OP_CONTEXT_HOLDER,
                Set.of(new RecordingRestListener()),
                Set.of(new RecordingHttpListener()),
                Set.of());

        WebSocketMount mount = factory.create("/*", Set.<Object>of(new OpenLifecycleEndpoint(), new SecuredEndpoint()));
        mount.createRouter(vertx)
                .compose(wsRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").handler(new RequestContextLifecycle());
                    root.route("/*").handler(BARRIER);
                    root.route("/*").handler(emitter);
                    root.route("/*").subRouter(wsRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    port = s.actualPort();
                    ctx.completeNow();
                }));
    }

    /**
     * Closes the shared server and WebSocket client, joined.
     *
     * @param ctx the test context used for async teardown
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> s = server != null ? server.close() : Future.succeededFuture();
        Future<?> c = wsClient != null ? wsClient.close() : Future.succeededFuture();
        Future.join(s, c).onComplete(ctx.succeeding(ar -> ctx.completeNow()));
    }

    /** Clears the recorded events and the barrier, and replaces the endpoint latches and counter. */
    @BeforeEach
    void resetRecords() {
        RecordingRestListener.reset();
        RecordingHttpListener.reset();
        BARRIER.reset();
        OpenLifecycleEndpoint.reset();
        SecuredEndpoint.reset();
    }

    // --- Tests ---

    /**
     * A successful upgrade produces no completion event at the 101, and none after the client closes the
     * socket. Two barrier requests, one after {@code @OnOpen} and one after {@code @OnClose}, show the
     * emitter live at both points.
     */
    @Test
    @DisplayName("a successful upgrade and a later client close produce no rest-core completion event")
    void successfulUpgradeAndClientCloseProduceNoCompletionEvent() {
        // Given: the shared server, its events, barrier and latches reset.
        // When, step 1: an upgrade without credentials to the open endpoint succeeds.
        WebSocket ws = upgradeOpen();
        try {
            // Step 2: once @OnOpen has run, a barrier request fails with 404 and the barrier records it.
            boolean opened = ws != null && awaitLatch("@OnOpen on " + OPEN_PATH, OpenLifecycleEndpoint.opened);
            BarrierOutcome barrierOpen = barrier(BARRIER_OPEN_PATH);
            RecordedEvents afterUpgrade = RecordedEvents.snapshot();
            List<String> completedAfterUpgrade = BARRIER.completed();

            // Step 3: the client closes the socket, and the server runs @OnClose.
            boolean serverClosed = closeAndAwaitServerClose(ws);

            // Step 4: a second barrier request fails with 404 and the barrier records it.
            BarrierOutcome barrierClose = barrier(BARRIER_CLOSE_PATH);
            RecordedEvents afterClose = RecordedEvents.snapshot();

            reportLateCloseOutcome(ws != null, serverClosed, barrierClose.recorded(), completedAfterUpgrade);

            // Then: no event of either type for the upgraded request at either point; exactly one HTTP
            // event per barrier request; and no REST event at either point.
            assertAll(
                    "successful upgrade and client close",
                    block("witness", () -> {
                        assertNotNull(ws, "the upgrade of " + OPEN_PATH + " must succeed");
                        assertTrue(opened, "@OnOpen must run for " + OPEN_PATH);
                        assertTrue(serverClosed, "@OnClose must run after the client closes " + OPEN_PATH);
                        assertTrue(
                                BARRIER.entered().contains(OPEN_PATH),
                                () -> "the barrier must see the upgrade request; entered " + BARRIER.entered());
                        assertBarrierRequest(barrierOpen, BARRIER_OPEN_PATH);
                        assertBarrierRequest(barrierClose, BARRIER_CLOSE_PATH);
                    }),
                    block(
                            "after upgrade (step 2)",
                            () -> assertAll(
                                    () -> assertEquals(
                                            List.of(),
                                            afterUpgrade.eventsForPath(OPEN_PATH),
                                            "rest-core events for " + OPEN_PATH + " after the upgrade"),
                                    () -> assertExactlyOneHttpEventAndNoRestEvent(
                                            afterUpgrade, BARRIER_OPEN_PATH, NOT_FOUND))),
                    block(
                            "after close (step 4)",
                            () -> assertAll(
                                    () -> assertEquals(
                                            List.of(),
                                            afterClose.eventsForPath(OPEN_PATH),
                                            "rest-core events for " + OPEN_PATH + " after the client close"),
                                    () -> assertExactlyOneHttpEventAndNoRestEvent(
                                            afterClose, BARRIER_OPEN_PATH, NOT_FOUND),
                                    () -> assertExactlyOneHttpEventAndNoRestEvent(
                                            afterClose, BARRIER_CLOSE_PATH, NOT_FOUND))));
        } finally {
            if (ws != null && !ws.isClosed()) {
                ws.close();
            }
        }
    }

    /**
     * An upgrade that authentication rejects with 401 is never upgraded, so nothing claims it: it
     * completes as an ordinary HTTP request with exactly one HTTP event and no REST event.
     */
    @Test
    @DisplayName("an upgrade rejected with 401 produces exactly one HTTP completion event and no REST event")
    void failedUpgradeProducesExactlyOneHttpEvent() {
        // Given: the shared server, reset.
        // When: an upgrade without an Authorization header to the secured endpoint, which the stub bearer
        // authentication handler fails with 401 before the upgrade handler runs.
        boolean rejected = connectExpectingFailure(SECURED_PATH);
        boolean recorded = awaitBarrier(SECURED_PATH);
        RecordedEvents events = RecordedEvents.snapshot();

        // Then: the handshake failed, @OnOpen never ran, and the request produced one HTTP event with
        // status 401 and no REST event.
        assertAll(
                "upgrade rejected with 401",
                block("witness", () -> {
                    assertTrue(rejected, "the upgrade of " + SECURED_PATH + " without credentials must fail");
                    assertTrue(recorded, "the barrier must record " + SECURED_PATH);
                }),
                block(
                        "@OnOpen",
                        () -> assertEquals(0, SecuredEndpoint.opened.get(), "@OnOpen invocations on " + SECURED_PATH)),
                block("events", () -> assertExactlyOneHttpEventAndNoRestEvent(events, SECURED_PATH, UNAUTHORIZED)));
    }

    // --- Steps ---

    /**
     * Upgrades to {@link #OPEN_PATH} without credentials.
     *
     * @return the client socket, or {@code null} when the upgrade failed or did not complete in time
     */
    private WebSocket upgradeOpen() {
        Future<WebSocket> connect = connect(OPEN_PATH);
        WebSocket ws = await("the upgrade of " + OPEN_PATH, connect);
        if (ws == null) {
            // An upgrade that completes after the wait gave up is still closed.
            connect.onSuccess(late -> late.close());
        }
        return ws;
    }

    /**
     * Sends one barrier request: a WebSocket connect to an unmatched {@code path}, which must fail its
     * handshake with 404, then waits for the barrier to record it.
     *
     * @param path the unmatched path to connect to
     * @return whether the connect failed and whether the barrier recorded the request
     */
    private BarrierOutcome barrier(String path) {
        boolean connectFailed = connectExpectingFailure(path);
        boolean recorded = awaitBarrier(path);
        return new BarrierOutcome(connectFailed, recorded);
    }

    /**
     * Closes the client socket, then waits for the server's {@code @OnClose} on {@link #OPEN_PATH}.
     *
     * @param ws the client socket, or {@code null} when the upgrade failed
     * @return whether the server ran {@code @OnClose} within the bound
     */
    private boolean closeAndAwaitServerClose(WebSocket ws) {
        if (ws == null) {
            return false;
        }
        await("the client close of " + OPEN_PATH, ws.close());
        return awaitLatch("@OnClose on " + OPEN_PATH, OpenLifecycleEndpoint.closed);
    }

    /**
     * Connects to {@code path} without credentials, expecting the handshake to fail. A socket the
     * handshake should not have produced is closed, even if it arrives after the wait.
     *
     * @param path the path to connect to
     * @return whether the connect failed within the bound
     */
    private boolean connectExpectingFailure(String path) {
        Future<WebSocket> connect = connect(path);
        connect.onSuccess(unexpected -> unexpected.close());
        Future<Boolean> failed = connect.transform(result -> Future.succeededFuture(result.failed()));
        return Boolean.TRUE.equals(await("the connect to " + path, failed));
    }

    private static Future<WebSocket> connect(String path) {
        return wsClient.connect(
                new WebSocketConnectOptions().setHost("127.0.0.1").setPort(port).setURI(path));
    }

    // --- Bounded, non-throwing waits ---

    /** The next wait's bound: at most {@link #WAIT_BOUND}, and never past the test's wait budget. */
    private long nextWaitNanos() {
        long remaining = waitDeadlineNanos - System.nanoTime();
        return Math.max(0, Math.min(WAIT_BOUND.toNanos(), remaining));
    }

    /**
     * Waits for {@code signal} within the next wait's bound.
     *
     * @return its value, or {@code null} when it failed or did not complete in time
     */
    private <T> T await(String what, Future<T> signal) {
        try {
            return signal.toCompletionStage().toCompletableFuture().get(nextWaitNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportUnfinishedWait(what, interrupted);
        } catch (ExecutionException | TimeoutException unfinished) {
            reportUnfinishedWait(what, unfinished);
        }
        return null;
    }

    /**
     * Waits for {@code latch} within the next wait's bound.
     *
     * @return whether it reached zero in time
     */
    private boolean awaitLatch(String what, CountDownLatch latch) {
        try {
            if (latch.await(nextWaitNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
            reportUnfinishedWait(what, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportUnfinishedWait(what, interrupted);
        }
        return false;
    }

    /**
     * Waits until the barrier has recorded {@code path} as completed, then drains the Vert.x context of
     * every request it saw once.
     *
     * @return whether the barrier recorded it and every drain ran within its bound
     */
    private boolean awaitBarrier(String path) {
        boolean recorded = BARRIER.awaitCompleted(path, nextWaitNanos());
        boolean drained = BARRIER.drain(this::nextWaitNanos);
        if (!recorded || !drained) {
            System.err.println("barrier wait for " + path + " fell short: completed " + BARRIER.completed()
                    + ", drained " + drained);
        }
        return recorded && drained;
    }

    private static void reportUnfinishedWait(String what, Throwable cause) {
        System.err.println(
                "bounded wait for " + what + " ended without a result" + (cause != null ? ": " + cause : ""));
    }

    // --- Evidence ---

    /**
     * Prints which late-close outcome occurred for {@link #OPEN_PATH}, then the barrier's records. The
     * outcome is decided only when the upgrade, the close and the barrier after it were all observed.
     */
    private static void reportLateCloseOutcome(
            boolean upgraded, boolean serverClosed, boolean barrierCloseRecorded, List<String> completedAfterUpgrade) {
        List<String> entered = BARRIER.entered();
        List<String> completed = BARRIER.completed();
        String outcome;
        if (!upgraded) {
            outcome = "undetermined: the upgrade of " + OPEN_PATH + " did not succeed";
        } else if (!entered.contains(OPEN_PATH)) {
            outcome = "undetermined: the barrier never saw " + OPEN_PATH;
        } else if (completedAfterUpgrade.contains(OPEN_PATH)) {
            outcome = "neither (a) nor (b): the routing end handlers for " + OPEN_PATH + " ran before the client close";
        } else if (completed.contains(OPEN_PATH)) {
            outcome = "(a) the routing end handlers ran for " + OPEN_PATH + " after the client close";
        } else if (!serverClosed || !barrierCloseRecorded) {
            outcome = "undetermined: the client close or the barrier request after it was not observed";
        } else {
            outcome = "(b) no routing end handler ran for " + OPEN_PATH + " after the client close";
        }
        System.out.println("late-close outcome: " + outcome);
        System.out.println("barrier entered: " + entered + "; completed: " + completed);
    }

    // --- Assertions ---

    /**
     * Names one block of a test's single {@code assertAll}: a failure inside it names the block, and the
     * other blocks still run.
     */
    private static Executable block(String name, Executable assertions) {
        return () -> {
            try {
                assertions.execute();
            } catch (AssertionError failure) {
                throw new AssertionError(name + " block: " + failure.getMessage(), failure);
            }
        };
    }

    /** Asserts that {@code outcome}'s connect to {@code path} failed its handshake and was recorded. */
    private static void assertBarrierRequest(BarrierOutcome outcome, String path) {
        assertTrue(outcome.connectFailed(), "the barrier connect to " + path + " must fail its handshake");
        assertTrue(outcome.recorded(), "the barrier must record " + path);
    }

    /**
     * Asserts that {@code events} holds exactly one HTTP event with {@code path} and {@code status}, and no
     * REST event.
     */
    private static void assertExactlyOneHttpEventAndNoRestEvent(RecordedEvents events, String path, int status) {
        assertAll(
                "exactly one HTTP event for " + path + " and no REST event",
                () -> {
                    List<HttpRequestCompletedEvent> http = events.httpEventsForPath(path);
                    assertEquals(1, http.size(), () -> "HTTP events for " + path + " among " + events.httpSummaries());
                    assertEquals(status, http.get(0).statusCode(), () -> "status of the HTTP event for " + path);
                },
                () -> assertEquals(List.of(), events.restSummaries(), "REST listener events"));
    }

    private static String summary(HttpRequestCompletedEvent event) {
        return "HTTP " + event.method() + " " + event.path() + " " + event.statusCode()
                + (event.wireFailureCode() != null ? " wire=" + event.wireFailureCode() : "");
    }

    private static String summary(RestRequestCompletedEvent event) {
        return "REST " + event.method() + " " + event.path() + " " + event.statusCode();
    }

    // --- Records ---

    /** One barrier request's outcome: whether its connect failed, and whether the barrier recorded it. */
    private record BarrierOutcome(boolean connectFailed, boolean recorded) {}

    /** Both listeners' events at one moment. */
    private record RecordedEvents(List<RestRequestCompletedEvent> rest, List<HttpRequestCompletedEvent> http) {

        static RecordedEvents snapshot() {
            return new RecordedEvents(RecordingRestListener.events(), RecordingHttpListener.events());
        }

        /** The events of either type with {@code path}, as summaries. */
        List<String> eventsForPath(String path) {
            Stream<String> restMatches =
                    rest.stream().filter(event -> event.path().equals(path)).map(WebSocketCompletionClaimIT::summary);
            Stream<String> httpMatches = httpEventsForPath(path).stream().map(WebSocketCompletionClaimIT::summary);
            return Stream.concat(restMatches, httpMatches).toList();
        }

        List<HttpRequestCompletedEvent> httpEventsForPath(String path) {
            return http.stream().filter(event -> event.path().equals(path)).toList();
        }

        List<String> httpSummaries() {
            return http.stream().map(WebSocketCompletionClaimIT::summary).toList();
        }

        List<String> restSummaries() {
            return rest.stream().map(WebSocketCompletionClaimIT::summary).toList();
        }
    }

    // --- Fixtures ---

    /**
     * Test-only ROOT handler between {@link RequestContextLifecycle} and the completion emitter. On a
     * request's first routing pass only, marked by a test-local data key, it records the request's path and
     * Vert.x context as an entry, and registers one end handler that records the path as completed.
     *
     * <p>Its end handler is registered after the lifecycle's and before the emitter's, so it runs after the
     * emitter's. Once it has recorded a request as completed, the emitter has dispatched every event it will
     * produce for it. An entry without a completion shows that the end handler was registered but never ran.
     * {@link #drain} runs one marker task on each recorded context, so work already queued on the server's
     * event loop has run before the test counts.
     */
    static final class CompletionBarrier implements Handler<RoutingContext> {
        private static final String REGISTERED_KEY = "test.barrier.registered";

        private final List<String> entered = new CopyOnWriteArrayList<>();
        private final List<Context> requestContexts = new CopyOnWriteArrayList<>();
        private final List<String> completed = new ArrayList<>();

        @Override
        public void handle(RoutingContext context) {
            if (context.get(REGISTERED_KEY) == null) {
                context.put(REGISTERED_KEY, Boolean.TRUE);
                String path = context.request().path();
                entered.add(path);
                Context requestContext = Vertx.currentContext();
                if (requestContext != null) {
                    requestContexts.add(requestContext);
                }
                context.addEndHandler(ignored -> recordCompleted(path));
            }
            context.next();
        }

        private synchronized void recordCompleted(String path) {
            completed.add(path);
            notifyAll();
        }

        /** The paths whose first routing pass the barrier saw, in order. */
        List<String> entered() {
            return List.copyOf(entered);
        }

        /** The paths whose end handlers ran, in order. */
        synchronized List<String> completed() {
            return List.copyOf(completed);
        }

        /**
         * Waits until {@code path} has been recorded as completed.
         *
         * @return whether it was, within {@code timeoutNanos}
         */
        synchronized boolean awaitCompleted(String path, long timeoutNanos) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (!completed.contains(path)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        /**
         * Runs one marker task on each recorded request context, and waits for each within {@code
         * timeoutNanos}.
         *
         * @return whether every marker ran in time
         */
        boolean drain(LongSupplier timeoutNanos) {
            for (Context requestContext : requestContexts) {
                CompletableFuture<Void> marker = new CompletableFuture<>();
                requestContext.runOnContext(ignored -> marker.complete(null));
                try {
                    marker.get(timeoutNanos.getAsLong(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException | TimeoutException unfinished) {
                    return false;
                }
            }
            return true;
        }

        synchronized void reset() {
            entered.clear();
            requestContexts.clear();
            completed.clear();
        }
    }

    /** Records every {@link RestRequestCompletedEvent} the emitter dispatches. */
    static final class RecordingRestListener implements RestRequestCompletedListener {
        private static final List<RestRequestCompletedEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public void onCompleted(RestRequestCompletedEvent event) {
            EVENTS.add(event);
        }

        static List<RestRequestCompletedEvent> events() {
            return List.copyOf(EVENTS);
        }

        static void reset() {
            EVENTS.clear();
        }
    }

    /** Records every {@link HttpRequestCompletedEvent} the emitter dispatches. */
    static final class RecordingHttpListener implements HttpRequestCompletedListener {
        private static final List<HttpRequestCompletedEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public void onCompleted(HttpRequestCompletedEvent event) {
            EVENTS.add(event);
        }

        static List<HttpRequestCompletedEvent> events() {
            return List.copyOf(EVENTS);
        }

        static void reset() {
            EVENTS.clear();
        }
    }

    /** Endpoint at {@link #OPEN_PATH} without security; counts {@code @OnOpen} and {@code @OnClose}. */
    @WebSocketEndpoint(OPEN_PATH)
    static final class OpenLifecycleEndpoint {

        /** Counted down by {@code @OnOpen}; replaced before each test. */
        static volatile CountDownLatch opened = new CountDownLatch(1);

        /** Counted down by {@code @OnClose}; replaced before each test. */
        static volatile CountDownLatch closed = new CountDownLatch(1);

        static void reset() {
            opened = new CountDownLatch(1);
            closed = new CountDownLatch(1);
        }

        /**
         * Counts the open.
         *
         * @param session the WebSocket session
         */
        @OnOpen
        public void onOpen(WebSocketSession session) {
            opened.countDown();
        }

        /**
         * Counts the close.
         *
         * @param session the WebSocket session
         */
        @OnClose
        public void onClose(WebSocketSession session) {
            closed.countDown();
        }
    }

    /** Endpoint at {@link #SECURED_PATH} that requires authentication; counts {@code @OnOpen}. */
    @WebSocketEndpoint(SECURED_PATH)
    @Authorized
    static final class SecuredEndpoint {

        /** {@code @OnOpen} invocations; reset before each test. */
        static final AtomicInteger opened = new AtomicInteger();

        static void reset() {
            opened.set(0);
        }

        /**
         * Counts the open.
         *
         * @param session the WebSocket session
         */
        @OnOpen
        public void onOpen(WebSocketSession session) {
            opened.incrementAndGet();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link HealthCheckHandler} in isolation by mounting it on a lightweight
 * {@link Router} attached to a real HTTP server. Each test starts its own server on a free
 * loopback port and closes it explicitly in {@code @AfterEach}; a single {@link WebClient}
 * is shared across the class. testing.md requires both: relying on extension teardown to
 * reclaim sockets is what surfaces under load as a misrouted response.
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached,
 * so under load {@code body()} can succeed with zero bytes while the status code is correct — which
 * this class, decoding every body as JSON, would surface as a spurious decode failure. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>Aggregation: all-UP, all-DOWN, mixed, empty check set
 *   <li>Data inclusion: data field present vs. absent in per-check JSON
 *   <li>Error handling: failed futures, synchronous exceptions, message-less and hostile
 *       throwables, a check returning no future at all, and results whose data cannot be
 *       serialized — including data too deeply nested for the encoder, which degrades to the
 *       terse response-boundary fallback rather than a per-check entry
 *   <li>Response boundary: a synchronous {@link Error} from {@code name()} or {@code check()} is
 *       answered with the terse fallback instead of escaping into a router-generated 500, and a
 *       failure arriving after the head is committed aborts the response rather than completing it
 *   <li>Sibling isolation: one misbehaving check must never starve the others or suppress the
 *       HTTP response entirely, including when rendering its data overflows the stack
 *   <li>Continuity: a probe answered by the fallback must leave the server able to answer the next
 *   <li>Configurable timeout: slow check exceeding custom timeout is reported DOWN
 * </ul>
 *
 * <p>The class carries an explicit {@link Timeout} because every regression in this area
 * manifests as a hang — a response that is never written. Without it a broken handler blocks the
 * suite instead of failing it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
class HealthCheckHandlerTest {

    /**
     * The body the handler writes when the aggregated response cannot be rendered. Duplicated from
     * the handler deliberately: asserting against its private constant would prove only that the
     * constant equals itself.
     */
    private static final String TERSE_FALLBACK_BODY = "{\"status\":\"DOWN\",\"checks\":[]}";

    /**
     * Nesting depth that survives {@link JsonObject#mapFrom(Object)} but breaks
     * {@link JsonObject#encode()}, whose 1000-level write limit is a Jackson constant. Shared by
     * every fixture that needs data the aggregate encoder rejects, so the two cannot drift apart.
     */
    private static final int ENCODER_BREAKING_DEPTH = 1001;

    // --- Class-scoped resources (shared across all @Test methods, including @Nested) ---

    private static Vertx vertx;
    private static WebClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link WebClient} once for the
     * entire test class. A client per request accumulates netty channel pools that are never
     * reclaimed until the JVM exits, and an unbound client can never be closed at all.
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
     * Closes the per-test {@link HttpServer} explicitly rather than leaving it to extension
     * teardown.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the shared {@link WebClient} after all tests in the class have run — before the
     * extension-owned {@link Vertx} instance is closed, which happens only once every
     * {@code @AfterAll} method has run.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to await here.
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

    // --- Test HealthCheck implementations ---

    static class UpCheck implements HealthCheck {
        private final String name;

        UpCheck(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    static class DownCheck implements HealthCheck {
        private final String name;
        private final String error;

        DownCheck(String name, String error) {
            this.name = name;
            this.error = error;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.down(error));
        }
    }

    static class UpCheckWithData implements HealthCheck {
        @Override
        public String name() {
            return "detailed";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up(Map.of("pool.active", 2, "pool.idle", 8)));
        }
    }

    static class FailingCheck implements HealthCheck {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new RuntimeException("connection refused"));
        }
    }

    static class ThrowingCheck implements HealthCheck {
        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public Future<HealthCheckResult> check() {
            throw new RuntimeException("unexpected error");
        }
    }

    /**
     * A {@link HealthCheck} that never completes, used to verify that a custom timeout fires
     * before the check finishes.
     */
    static class SlowCheck implements HealthCheck {

        @Override
        public String name() {
            return "slow";
        }

        @Override
        public Future<HealthCheckResult> check() {
            // Return a promise that is never completed — the timeout must fire
            return Promise.<HealthCheckResult>promise().future();
        }
    }

    /**
     * A {@link HealthCheck} whose future fails with a throwable carrying no message.
     *
     * <p>The {@link IllegalStateException} is constructed deliberately without a message rather
     * than provoking a "natural" failure: on Java 21 a spontaneous {@code NullPointerException}
     * carries a JEP 358 helpful message, which would silently defuse the null-message path this
     * fixture exists to exercise.
     */
    static class NoMessageFailingCheck implements HealthCheck {
        @Override
        public String name() {
            return "failing-nomsg";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new IllegalStateException());
        }
    }

    /**
     * A {@link HealthCheck} that throws synchronously with a throwable carrying no message. See
     * {@link NoMessageFailingCheck} for why the exception is constructed without a message.
     */
    static class NoMessageThrowingCheck implements HealthCheck {
        @Override
        public String name() {
            return "throwing-nomsg";
        }

        @Override
        public Future<HealthCheckResult> check() {
            throw new IllegalStateException();
        }
    }

    /**
     * A {@link HealthCheck} whose {@link #name()} throws a bare {@link Error}.
     *
     * <p>{@code start} converts only an {@link Exception} into a per-check {@code DOWN} entry, so an
     * {@code Error} escapes the synchronous start path and must be absorbed by the response
     * boundary, degrading the whole probe to the terse fallback body.
     */
    static class ErrorThrowingNameCheck implements HealthCheck {
        @Override
        public String name() {
            throw new Error("name blew up");
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    /**
     * A {@link HealthCheck} whose {@link #check()} throws a bare {@link Error}. See
     * {@link ErrorThrowingNameCheck} for why an {@code Error} reaches the response boundary rather
     * than a per-check entry.
     */
    static class ErrorThrowingCheck implements HealthCheck {
        @Override
        public String name() {
            return "error-throwing";
        }

        @Override
        public Future<HealthCheckResult> check() {
            throw new Error("check blew up");
        }
    }

    /**
     * A {@link HealthCheck} whose {@link #name()} throws.
     *
     * <p>The handler resolves a name exactly once, when it starts the check, and falls back to the
     * check's class name if that throws — so this fixture pins both halves of that contract: the
     * fallback name in the entry, and the sibling entries surviving alongside it.
     */
    static class ThrowingNameCheck implements HealthCheck {
        @Override
        public String name() {
            throw new IllegalStateException("name blew up");
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    /**
     * A healthy {@link HealthCheck} that completes only after a delay, used to prove that a
     * sibling check still lands in the aggregated response when another check misbehaves.
     */
    static class DelayedUpCheck implements HealthCheck {
        private final Vertx vertx;
        private final String name;
        private final long delayMs;

        /**
         * Creates a delayed healthy check.
         *
         * @param vertx   the Vert.x instance used to schedule the completion
         * @param name    the check name reported in the response
         * @param delayMs how long to wait before completing UP, in milliseconds
         */
        DelayedUpCheck(Vertx vertx, String name, long delayMs) {
            this.vertx = vertx;
            this.name = name;
            this.delayMs = delayMs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            Promise<HealthCheckResult> promise = Promise.promise();
            // One-shot timer bound to this single check invocation; it completes the promise and
            // is not retained.
            vertx.setTimer(delayMs, id -> promise.complete(HealthCheckResult.up()));
            return promise.future();
        }
    }

    /**
     * A healthy {@link HealthCheck} whose diagnostic data cannot be rendered as JSON.
     *
     * <p>A bare {@link Object} has no bean properties, so {@link JsonObject#mapFrom(Object)}
     * rejects it with {@code "No serializer found for class java.lang.Object"} (verified
     * empirically against the Jackson mapper Vert.x uses). Replacing the value with anything
     * serializable silently defuses this fixture.
     */
    static class UnserializableDataCheck implements HealthCheck {
        @Override
        public String name() {
            return "unserializable";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up(Map.of("bad", new Object())));
        }
    }

    /** A throwable whose {@link #getMessage()} itself throws. */
    static final class HostileMessageException extends RuntimeException {

        @Override
        public String getMessage() {
            throw new RuntimeException("getMessage exploded");
        }
    }

    /**
     * A {@link HealthCheck} failing with a {@link HostileMessageException}, so that any code
     * reaching for {@code cause.getMessage()} blows up in turn.
     */
    static class HostileMessageCheck implements HealthCheck {
        @Override
        public String name() {
            return "hostile";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new HostileMessageException());
        }
    }

    /**
     * A healthy {@link HealthCheck} whose diagnostic data refers to itself.
     *
     * <p>A cycle among {@code Map}/{@code Collection} values makes {@link JsonObject#mapFrom(Object)}
     * recurse until the thread's stack is exhausted, raising a raw {@link StackOverflowError} rather
     * than an {@link Exception} (verified empirically; a cycle between POJOs is wrapped by Jackson as
     * an {@link IllegalArgumentException} and was already caught). A cycle is used rather than a fixed
     * nesting depth because the depth at which the stack overflows varies with the thread's stack size
     * and would flake across machines.
     */
    static class SelfReferentialDataCheck implements HealthCheck {
        @Override
        public String name() {
            return "self-referential";
        }

        @Override
        public Future<HealthCheckResult> check() {
            Map<String, Object> data = new HashMap<>();
            data.put("self", data);
            return Future.succeededFuture(HealthCheckResult.up(data));
        }
    }

    /**
     * A healthy {@link HealthCheck} whose diagnostic data is an acyclic map nested deeper than the
     * 1000-level write limit Jackson enforces.
     *
     * <p>{@link JsonObject#mapFrom(Object)} does <em>not</em> enforce that limit but
     * {@link JsonObject#encode()} does, so the failure surfaces while the aggregated body is being
     * encoded — after per-check rendering has already succeeded.
     *
     * <p>Unlike a stack overflow the 1000-level limit is a library constant rather than a
     * machine-dependent threshold, but <em>reaching</em> it is not unconditional:
     * {@link JsonObject#mapFrom(Object)} must first survive
     * {@value HealthCheckHandlerTest#ENCODER_BREAKING_DEPTH} recursive frames, which needs a thread
     * stack of at least 1 MB — the JVM default on every supported platform. Under a smaller
     * {@code -Xss} the fixture takes the interior {@link StackOverflowError} path instead and
     * renders an ordinary per-check entry; the assertions below then fail loudly with the message
     * that says exactly this, rather than passing for the wrong reason.
     */
    static class DeeplyNestedDataCheck implements HealthCheck {

        @Override
        public String name() {
            return "deeply-nested";
        }

        @Override
        public Future<HealthCheckResult> check() {
            Map<String, Object> current = new HashMap<>();
            current.put("leaf", "value");
            for (int i = 0; i < ENCODER_BREAKING_DEPTH; i++) {
                Map<String, Object> parent = new HashMap<>();
                parent.put("nested", current);
                current = parent;
            }
            return Future.succeededFuture(HealthCheckResult.up(current));
        }
    }

    /**
     * A healthy {@link HealthCheck} that poisons only its first invocation: the first request gets
     * data nested past the encoder's limit, every later request gets ordinary data.
     *
     * <p>Used to prove that answering a poisoned probe leaves the handler and its server usable —
     * the fallback path must not end the connection in a state that strands the next request. The
     * poisoned data shares {@link DeeplyNestedDataCheck}'s stack-size precondition.
     *
     * <p>The counter is atomic because the server is created outside a verticle, so the two probes
     * can land on different event loops with no happens-before edge between the first probe's write
     * and the second probe's read.
     */
    static class PoisonedOnceCheck implements HealthCheck {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String name() {
            return "poisoned-once";
        }

        @Override
        public Future<HealthCheckResult> check() {
            if (invocations.getAndIncrement() > 0) {
                return Future.succeededFuture(HealthCheckResult.up(Map.of("recovered", true)));
            }
            Map<String, Object> current = new HashMap<>();
            current.put("leaf", "value");
            for (int i = 0; i < ENCODER_BREAKING_DEPTH; i++) {
                Map<String, Object> parent = new HashMap<>();
                parent.put("nested", current);
                current = parent;
            }
            return Future.succeededFuture(HealthCheckResult.up(current));
        }
    }

    /**
     * A {@link HealthCheck} that violates the SPI by returning {@code null} instead of a future.
     */
    static class NullReturningCheck implements HealthCheck {
        @Override
        public String name() {
            return "null-future";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return null;
        }
    }

    // --- Helpers ---

    /**
     * Returns the first check entry carrying the given name, or {@code null} when absent.
     *
     * @param checks the {@code checks} array from the response body
     * @param name   the check name to look for
     * @return the matching entry, or {@code null}
     */
    private static JsonObject checkNamed(JsonArray checks, String name) {
        return findCheck(checks, name::equals);
    }

    /**
     * Returns the first check entry whose name differs from the given one, or {@code null} when
     * every entry matches. Used when a check's name is unknown because producing it threw.
     *
     * @param checks the {@code checks} array from the response body
     * @param name   the check name to exclude
     * @return the first non-matching entry, or {@code null}
     */
    private static JsonObject checkNotNamed(JsonArray checks, String name) {
        return findCheck(checks, candidate -> !name.equals(candidate));
    }

    /**
     * Returns the first check entry whose name satisfies the given predicate, or {@code null} when
     * none does.
     *
     * @param checks       the {@code checks} array from the response body
     * @param nameMatches  predicate applied to each entry's {@code name}
     * @return the first matching entry, or {@code null}
     */
    private static JsonObject findCheck(JsonArray checks, Predicate<String> nameMatches) {
        for (int i = 0; i < checks.size(); i++) {
            JsonObject check = checks.getJsonObject(i);
            if (nameMatches.test(check.getString("name"))) {
                return check;
            }
        }
        return null;
    }

    /**
     * Starts an HTTP server with the given {@link HealthCheckHandler} mounted at {@code /health}
     * on a random free port and returns the port it is listening on.
     *
     * @param vertx   the Vert.x instance
     * @param handler the handler under test
     * @return a future completing with the actual server port
     */
    private Future<Integer> startServer(Vertx vertx, HealthCheckHandler handler) {
        Router router = Router.router(vertx);
        router.get("/health").handler(handler);
        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    return s.actualPort();
                });
    }

    /**
     * Sends a GET request to {@code /health} on the given port and returns a
     * {@link JsonObject} enriched with an extra {@code _statusCode} field carrying
     * the HTTP response status.
     *
     * @param vertx the Vert.x instance
     * @param port  the server port
     * @return a future completing with the parsed response body
     */
    private Future<JsonObject> request(Vertx vertx, int port) {
        return client.get(port, "127.0.0.1", "/health").send().map(response -> {
            JsonObject json = new JsonObject(response.body());
            json.put("_statusCode", response.statusCode());
            return json;
        });
    }

    /**
     * One probe response captured verbatim: the HTTP status and the body exactly as written.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body, byte-for-byte
     */
    private record RawResponse(int statusCode, String body) {}

    /**
     * Sends a GET request to {@code /health} on the given port without parsing the body, so that a
     * response the handler never wrote — a router-generated 500, say — fails an assertion instead
     * of failing the JSON parse.
     *
     * @param vertx the Vert.x instance
     * @param port  the server port
     * @return a future completing with the raw status and body
     */
    private Future<RawResponse> requestRaw(Vertx vertx, int port) {
        return client.get(port, "127.0.0.1", "/health")
                .send()
                .map(response -> new RawResponse(response.statusCode(), response.bodyAsString()));
    }

    // --- Aggregation ---

    @Nested
    class Aggregation {

        @Test
        @DisplayName("returns 200 UP with empty checks")
        void emptyChecks(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of());
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    assertEquals("UP", json.getString("status"));
                    assertTrue(json.getJsonArray("checks").isEmpty());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 200 UP when all checks pass")
        void allChecksPass(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new UpCheck("db"), new UpCheck("cache")));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    assertEquals("UP", json.getString("status"));
                    assertEquals(2, json.getJsonArray("checks").size());
                    JsonArray checks = json.getJsonArray("checks");
                    for (int i = 0; i < checks.size(); i++) {
                        assertEquals("UP", checks.getJsonObject(i).getString("status"));
                    }
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 503 DOWN when any check fails")
        void anyCheckFails(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new DownCheck("db", "timeout")));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("db", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 503 DOWN with all results when mix of UP and DOWN")
        void mixedChecks(VertxTestContext ctx) {
            HealthCheckHandler handler =
                    new HealthCheckHandler(Set.of(new UpCheck("cache"), new DownCheck("database", "timeout")));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(2, checks.size());
                    boolean hasCache = false;
                    boolean hasDatabase = false;
                    for (int i = 0; i < checks.size(); i++) {
                        String name = checks.getJsonObject(i).getString("name");
                        if ("cache".equals(name)) hasCache = true;
                        if ("database".equals(name)) hasDatabase = true;
                    }
                    assertTrue(hasCache, "cache check should be present");
                    assertTrue(hasDatabase, "database check should be present");
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Data inclusion ---

    @Nested
    class DataInclusion {

        @Test
        @DisplayName("includes data field when check result has data")
        void includesData(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new UpCheckWithData()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("detailed", check.getString("name"));
                    assertEquals("UP", check.getString("status"));
                    assertNotNull(check.getJsonObject("data"));
                    assertEquals(2, check.getJsonObject("data").getInteger("pool.active"));
                    assertEquals(8, check.getJsonObject("data").getInteger("pool.idle"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("omits data field when check result has no data")
        void omitsDataWhenEmpty(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new UpCheck("simple")));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("simple", check.getString("name"));
                    assertNull(check.getJsonObject("data"), "data field should be absent when empty");
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Error handling ---

    @Nested
    class ErrorHandling {

        @Test
        @DisplayName("check returning failed future is reported as DOWN")
        void failedFuture(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new FailingCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("failing", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("check throwing exception synchronously is reported as DOWN")
        void throwingCheck(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new ThrowingCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("throwing", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("failed future with a message-less throwable is DOWN with its class name")
        void failedFutureWithNullMessageIsDown(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new NoMessageFailingCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(1, checks.size());
                    JsonObject check = checks.getJsonObject(0);
                    assertEquals("failing-nomsg", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                    JsonObject data = check.getJsonObject("data");
                    assertNotNull(data, "a message-less failure must still carry a diagnostic error entry");
                    assertEquals("java.lang.IllegalStateException", data.getString("error"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("synchronous throw of a message-less throwable is DOWN with its class name")
        void throwingCheckWithNullMessageIsDown(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new NoMessageThrowingCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(1, checks.size());
                    JsonObject check = checks.getJsonObject(0);
                    assertEquals("throwing-nomsg", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                    JsonObject data = check.getJsonObject("data");
                    assertNotNull(data, "a message-less failure must still carry a diagnostic error entry");
                    assertEquals("java.lang.IllegalStateException", data.getString("error"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("check whose data cannot be serialized is reported DOWN, not swallowed")
        void unserializableCheckDataIsReportedDown(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new UnserializableDataCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(1, checks.size());
                    JsonObject check = checks.getJsonObject(0);
                    assertEquals("unserializable", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("throwable whose getMessage() throws is reported DOWN with its class name")
        void hostileGetMessageIsReportedDown(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new HostileMessageCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    JsonObject check = checkNamed(checks, "hostile");
                    assertNotNull(check, "the hostile check must appear in the response");
                    assertEquals("DOWN", check.getString("status"));
                    JsonObject data = check.getJsonObject("data");
                    assertNotNull(data, "an unreadable message must fall back to a diagnostic error entry");
                    assertEquals(HostileMessageException.class.getName(), data.getString("error"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("check returning a null future is reported DOWN")
        void nullReturningCheckIsReportedDown(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new NullReturningCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = checkNamed(json.getJsonArray("checks"), "null-future");
                    assertNotNull(check, "a check that returns no future must still be reported");
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("an Error thrown by name() is answered by the terse boundary fallback")
        void syncErrorFromNameStillAnswers(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new ErrorThrowingNameCheck()));
            startServer(vertx, handler).compose(p -> requestRaw(vertx, p)).onComplete(ctx.succeeding(response -> {
                ctx.verify(() -> {
                    // An Error escapes start(), which converts only Exceptions, so it must be
                    // absorbed by the handler's own boundary. A 500 here means the throw escaped
                    // handle() and the router answered instead.
                    assertEquals(503, response.statusCode());
                    assertEquals(TERSE_FALLBACK_BODY, response.body());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("an Error thrown by check() is answered by the terse boundary fallback")
        void syncErrorFromCheckStillAnswers(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new ErrorThrowingCheck()));
            startServer(vertx, handler).compose(p -> requestRaw(vertx, p)).onComplete(ctx.succeeding(response -> {
                ctx.verify(() -> {
                    assertEquals(503, response.statusCode());
                    assertEquals(TERSE_FALLBACK_BODY, response.body());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("data nested deeper than the encoder allows still yields a DOWN response")
        void deeplyNestedCheckDataStillAnswers(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new DeeplyNestedDataCheck()));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    // Only the response envelope is asserted: this failure surfaces while the whole
                    // body is being encoded, so it trips the response-boundary fallback rather than
                    // per-check rendering, and the fallback deliberately answers with no entries.
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    assertEquals(
                            new JsonArray(),
                            json.getJsonArray("checks"),
                            "the deep-data fixture must fail at aggregate encode (the boundary fallback), not at"
                                    + " per-check mapFrom — a non-empty checks array means this fixture no longer"
                                    + " reaches the response boundary");
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("a probe answered by the fallback leaves the next probe unaffected")
        void serverStillAnswersAfterAPoisonedProbe(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new PoisonedOnceCheck()));
            startServer(vertx, handler)
                    .compose(port -> request(vertx, port).compose(poisoned -> {
                        ctx.verify(() -> {
                            assertEquals(
                                    503, poisoned.getInteger("_statusCode"), "the first probe must trip the fallback");
                            // An empty checks array is what distinguishes the response-boundary
                            // fallback from a per-check DOWN entry: if the fixture ever failed at
                            // mapFrom instead, it would render an ordinary entry and this test
                            // would no longer exercise the fallback it exists to prove.
                            assertEquals(
                                    new JsonArray(),
                                    poisoned.getJsonArray("checks"),
                                    "the first probe must be answered by the boundary fallback, which names no check");
                        });
                        return request(vertx, port);
                    }))
                    .onComplete(ctx.succeeding(json -> {
                        ctx.verify(() -> {
                            assertEquals(200, json.getInteger("_statusCode"));
                            assertEquals("UP", json.getString("status"));
                            JsonObject check = checkNamed(json.getJsonArray("checks"), "poisoned-once");
                            assertNotNull(check, "the recovered check must be rendered normally");
                            assertEquals("UP", check.getString("status"));
                            assertEquals(true, check.getJsonObject("data").getBoolean("recovered"));
                        });
                        ctx.completeNow();
                    }));
        }
    }

    // --- Response boundary fallback ---

    /**
     * Verifies the fallback branch that runs once the response head is already committed. That
     * branch is unreachable through the HTTP surface — {@code sendResponse} encodes the whole body
     * before it touches the response — so it is driven through a stubbed {@link HttpServerResponse}
     * whose write throws after the head went out.
     */
    @Nested
    class ResponseBoundaryFallback {

        @Test
        @DisplayName("the deep-data fixture fails at aggregate encode, not at per-check conversion")
        void deepDataFixtureFailsAtAggregateEncode() {
            // Makes the fixture's precondition executable instead of prose. Both deep-data tests
            // assert an empty checks array, which the interior StackOverflowError path also
            // produces — so without this, a stack too small to reach the encoder's limit would let
            // them pass through the wrong failure mode. Asserting the two steps separately names
            // which path the fixture must take: mapFrom survives, encode rejects.
            Map<String, Object> data = new HashMap<>();
            data.put("leaf", "value");
            for (int i = 0; i < ENCODER_BREAKING_DEPTH; i++) {
                Map<String, Object> parent = new HashMap<>();
                parent.put("nested", data);
                data = parent;
            }
            Map<String, Object> nested = data;

            JsonObject converted = assertDoesNotThrow(
                    () -> JsonObject.mapFrom(nested),
                    "the fixture must survive per-check conversion — if this overflows, the stack is"
                            + " too small and the deep-data tests are exercising the interior guard");
            assertThrows(
                    EncodeException.class,
                    () -> new JsonObject()
                            .put("checks", new JsonArray().add(converted))
                            .encode(),
                    "the fixture must be rejected by the aggregate encode — that is the response"
                            + " boundary the deep-data tests exist to reach");
        }

        @Test
        @DisplayName("a write failure after the head is committed aborts the response, never completes it")
        void committedResponseIsAbortedNotCompleted() {
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            // The head is already on the wire as 200 and the body write then fails: exactly the
            // state the committed-head branch exists for.
            when(response.end(anyString())).thenThrow(new IllegalStateException("response already committed"));
            when(response.closed()).thenReturn(false);
            when(response.ended()).thenReturn(false);
            when(response.headWritten()).thenReturn(true);
            when(response.reset()).thenReturn(Future.succeededFuture());

            RoutingContext ctx = mock(RoutingContext.class);
            when(ctx.response()).thenReturn(response);

            new HealthCheckHandler(Set.of()).handle(ctx);

            // Completing a committed 200 would report the probe healthy on a rendering failure —
            // Kubernetes reads the status and ignores the body. reset() gives the client a terminal
            // error event instead.
            verify(response).reset();
            verify(response, never()).end();
        }
    }

    // --- Sibling isolation ---

    /**
     * Verifies that one misbehaving check never starves its siblings: the aggregated response is
     * still written, still contains every check, and healthy siblings are still reported UP.
     */
    @Nested
    class SiblingIsolation {

        @Test
        @DisplayName("a failing check does not suppress a slower sibling's UP result")
        void siblingSurvivesAFailedCheck(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(
                    Set.of(new NoMessageFailingCheck(), new DelayedUpCheck(vertx, "delayed-sibling", 100L)));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(2, checks.size(), "both checks must be reported");
                    JsonObject sibling = checkNamed(checks, "delayed-sibling");
                    assertNotNull(sibling, "the delayed sibling must be present");
                    assertEquals("UP", sibling.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("a check whose name() throws does not starve a slower sibling")
        void throwingNameCheckDoesNotStarveSiblings(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(
                    Set.of(new ThrowingNameCheck(), new DelayedUpCheck(vertx, "delayed-sibling", 100L)));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(2, checks.size(), "both checks must be reported");
                    JsonObject sibling = checkNamed(checks, "delayed-sibling");
                    assertNotNull(sibling, "the delayed sibling must be present");
                    assertEquals("UP", sibling.getString("status"));
                    JsonObject unnamed = checkNotNamed(checks, "delayed-sibling");
                    assertNotNull(unnamed, "the check with the throwing name must still be reported");
                    String fallbackName = unnamed.getString("name");
                    assertNotNull(fallbackName, "an unobtainable name must fall back to some placeholder");
                    assertFalse(fallbackName.isBlank(), "the fallback name must not be blank");
                    assertEquals("DOWN", unnamed.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("a check whose data is self-referential does not starve a slower sibling")
        void selfReferentialCheckDataIsReportedDown(VertxTestContext ctx) {
            HealthCheckHandler handler = new HealthCheckHandler(
                    Set.of(new SelfReferentialDataCheck(), new DelayedUpCheck(vertx, "delayed-sibling", 100L)));
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(2, checks.size(), "both checks must be reported");
                    JsonObject poisoned = checkNamed(checks, "self-referential");
                    assertNotNull(poisoned, "the check with unrenderable data must still be reported");
                    assertEquals("DOWN", poisoned.getString("status"));
                    JsonObject sibling = checkNamed(checks, "delayed-sibling");
                    assertNotNull(sibling, "the delayed sibling must be present");
                    assertEquals("UP", sibling.getString("status"));
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Configurable timeout ---

    @Nested
    class ConfigurableTimeout {

        @Test
        @DisplayName("check that exceeds custom timeout is reported as DOWN")
        void slowCheckExceedingTimeoutIsDown(VertxTestContext ctx) {
            // SlowCheck never completes; handler timeout is 1 s — check must be reported DOWN.
            HealthCheckHandler handler = new HealthCheckHandler(Set.of(new SlowCheck()), 1L);
            startServer(vertx, handler).compose(p -> request(vertx, p)).onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("slow", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
 * loopback port and closes it explicitly in {@code @AfterEach}; a single {@link HttpClient}
 * is shared across the class. testing.md requires both: relying on extension teardown to
 * reclaim sockets is what surfaces under load as a misrouted response.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>Aggregation: all-UP, all-DOWN, mixed, empty check set
 *   <li>Data inclusion: data field present vs. absent in per-check JSON
 *   <li>Error handling: failed futures, synchronous exceptions, message-less and hostile
 *       throwables, and results whose data cannot be serialized
 *   <li>Sibling isolation: one misbehaving check must never starve the others or suppress the
 *       HTTP response entirely
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

    // --- Class-scoped resources (shared across all @Test methods, including @Nested) ---

    private static Vertx vertx;
    private static HttpClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link HttpClient} once for the
     * entire test class. A client per request accumulates netty channel pools that are never
     * reclaimed until the JVM exits.
     *
     * @param v   the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = v.createHttpClient();
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
     * Closes the shared {@link HttpClient} after all tests in the class have run.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
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
     * A {@link HealthCheck} whose {@link #name()} throws. The handler calls {@code name()} on both
     * the success and the recovery path, so a throwing name defeats the recovery itself.
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

    // --- Helpers ---

    /**
     * Returns the first check entry carrying the given name, or {@code null} when absent.
     *
     * @param checks the {@code checks} array from the response body
     * @param name   the check name to look for
     * @return the matching entry, or {@code null}
     */
    private static JsonObject checkNamed(JsonArray checks, String name) {
        for (int i = 0; i < checks.size(); i++) {
            JsonObject check = checks.getJsonObject(i);
            if (name.equals(check.getString("name"))) {
                return check;
            }
        }
        return null;
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
        for (int i = 0; i < checks.size(); i++) {
            JsonObject check = checks.getJsonObject(i);
            if (!name.equals(check.getString("name"))) {
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
        return client.request(HttpMethod.GET, port, "127.0.0.1", "/health")
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(body -> {
                    JsonObject json = new JsonObject(body);
                    json.put("_statusCode", resp.statusCode());
                    return json;
                }));
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

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link HealthCheckHandler} in isolation by mounting it on a lightweight
 * {@link Router} attached to a real HTTP server. Each test starts its own server on
 * a free port and tears it down implicitly when the Vert.x instance is closed by the
 * extension.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>Aggregation: all-UP, all-DOWN, mixed, empty check set
 *   <li>Data inclusion: data field present vs. absent in per-check JSON
 *   <li>Error handling: failed futures and synchronous exceptions
 *   <li>Configurable timeout: slow check exceeding custom timeout is reported DOWN
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class HealthCheckHandlerTest {

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

    // --- Helpers ---

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
        return vertx.createHttpServer().requestHandler(router).listen(0).map(server -> server.actualPort());
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
        return vertx.createHttpClient()
                .request(HttpMethod.GET, port, "localhost", "/health")
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
        void emptyChecks(Vertx vertx, VertxTestContext ctx) {
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
        void allChecksPass(Vertx vertx, VertxTestContext ctx) {
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
        void anyCheckFails(Vertx vertx, VertxTestContext ctx) {
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
        void mixedChecks(Vertx vertx, VertxTestContext ctx) {
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
        void includesData(Vertx vertx, VertxTestContext ctx) {
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
        void omitsDataWhenEmpty(Vertx vertx, VertxTestContext ctx) {
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
        void failedFuture(Vertx vertx, VertxTestContext ctx) {
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
        void throwingCheck(Vertx vertx, VertxTestContext ctx) {
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
    }

    // --- Configurable timeout ---

    @Nested
    class ConfigurableTimeout {

        @Test
        @DisplayName("check that exceeds custom timeout is reported as DOWN")
        void slowCheckExceedingTimeoutIsDown(Vertx vertx, VertxTestContext ctx) {
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

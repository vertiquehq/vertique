// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link ManagementVerticle} health endpoint behavior including
 * check aggregation logic, HTTP status codes, and error handling for
 * both liveness and readiness probes.
 */
@ExtendWith(VertxExtension.class)
class ManagementVerticleTest {

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

    // --- Helpers ---

    /**
     * Deploys the verticle (which must be configured with {@code port = 0}) and issues a GET
     * request against {@code path} on the actual port the management server bound to.
     *
     * <p>This avoids the bind-close-rebind TOCTOU race that an {@code ServerSocket(0)}-style
     * "find a free port" helper would introduce on CI. {@link ManagementVerticle} stores the
     * resolved port in the shared local map under {@code management.port}.
     *
     * @param vertx    the Vert.x instance
     * @param verticle the verticle to deploy (must be configured with {@code port = 0})
     * @param path     the request path
     * @return a future completing with the parsed response body
     */
    private Future<JsonObject> deployAndRequest(Vertx vertx, ManagementVerticle verticle, String path) {
        return vertx.deployVerticle(verticle).compose(id -> {
            int boundPort = (int) vertx.sharedData().getLocalMap("vertique").get("management.port");
            return request(vertx, boundPort, path);
        });
    }

    /**
     * Sends a GET request to the management server and returns a JsonObject
     * with an extra {@code _statusCode} field carrying the HTTP response status.
     *
     * @param vertx the Vert.x instance
     * @param port  the management server port
     * @param path  the request path
     * @return a future completing with the parsed response body
     */
    private Future<JsonObject> request(Vertx vertx, int port, String path) {
        return request(vertx, port, "localhost", path);
    }

    /**
     * Sends a GET request to the management server on an explicit host and returns a JsonObject
     * with an extra {@code _statusCode} field carrying the HTTP response status.
     *
     * @param vertx the Vert.x instance
     * @param port  the management server port
     * @param host  the host to connect to
     * @param path  the request path
     * @return a future completing with the parsed response body
     */
    private Future<JsonObject> request(Vertx vertx, int port, String host, String path) {
        return vertx.createHttpClient()
                .request(HttpMethod.GET, port, host, path)
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(body -> {
                    JsonObject json = new JsonObject(body);
                    json.put("_statusCode", resp.statusCode());
                    return json;
                }));
    }

    // --- Liveness endpoint ---

    @Nested
    class LivenessEndpoint {

        @Test
        @DisplayName("returns 200 UP with empty liveness checks")
        void emptyChecks(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/live").onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    assertEquals("UP", json.getString("status"));
                    assertTrue(json.getJsonArray("checks").isEmpty());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 200 UP with single passing liveness check")
        void passingCheck(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(new UpCheck("process")), Set.of(), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/live").onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    assertEquals("UP", json.getString("status"));
                    JsonArray checks = json.getJsonArray("checks");
                    assertEquals(1, checks.size());
                    JsonObject check = checks.getJsonObject(0);
                    assertEquals("process", check.getString("name"));
                    assertEquals("UP", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 503 DOWN when any liveness check fails")
        void failingCheck(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(new DownCheck("process", "OOM")), Set.of(), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/live").onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("process", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Readiness endpoint ---

    @Nested
    class ReadinessEndpoint {

        @Test
        @DisplayName("returns 200 UP with empty readiness checks")
        void emptyChecks(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/ready").onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    assertEquals("UP", json.getString("status"));
                    assertTrue(json.getJsonArray("checks").isEmpty());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 200 UP with passing readiness check including data")
        void passingCheckWithData(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(), Set.of(new UpCheckWithData()), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/ready").onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(200, json.getInteger("_statusCode"));
                    assertEquals("UP", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("detailed", check.getString("name"));
                    assertEquals("UP", check.getString("status"));
                    assertNotNull(check.getJsonObject("data"));
                    assertEquals(2, check.getJsonObject("data").getInteger("pool.active"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 503 DOWN when any readiness check fails")
        void failingCheck(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle = new ManagementVerticle(
                    Set.of(), Set.of(new DownCheck("database", "connection refused")), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/ready").onComplete(ctx.succeeding(json -> {
                ctx.verify(() -> {
                    assertEquals(503, json.getInteger("_statusCode"));
                    assertEquals("DOWN", json.getString("status"));
                    JsonObject check = json.getJsonArray("checks").getJsonObject(0);
                    assertEquals("database", check.getString("name"));
                    assertEquals("DOWN", check.getString("status"));
                    assertEquals(
                            "connection refused", check.getJsonObject("data").getString("error"));
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("returns 503 DOWN with all results when mix of UP and DOWN")
        void mixedChecks(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle = new ManagementVerticle(
                    Set.of(), Set.of(new UpCheck("cache"), new DownCheck("database", "timeout")), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/ready").onComplete(ctx.succeeding(json -> {
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

    // --- Error handling ---

    @Nested
    class ErrorHandling {

        @Test
        @DisplayName("check returning failed future is reported as DOWN")
        void failedFuture(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(), Set.of(new FailingCheck()), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/ready").onComplete(ctx.succeeding(json -> {
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
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(), Set.of(new ThrowingCheck()), config, Set.of());
            deployAndRequest(vertx, verticle, "/health/ready").onComplete(ctx.succeeding(json -> {
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

    // --- Disabled management ---

    @Nested
    class DisabledManagement {

        @Test
        @DisplayName("disabled verticle starts successfully without binding port")
        void disabledVerticle(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config =
                    ManagementConfig.builder().port(9999).enabled(false).build();
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of());
            vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
                // Verify port is NOT bound by attempting to connect — the request must fail
                vertx.createHttpClient()
                        .request(HttpMethod.GET, 9999, "localhost", "/health/live")
                        .compose(req -> req.send())
                        .onComplete(ctx.failing(cause -> ctx.completeNow()));
            }));
        }
    }

    // --- Host binding ---

    @Nested
    @DisplayName("HostBinding")
    class HostBinding {

        @Test
        @DisplayName("host defaults to the wildcard bind address")
        void hostDefaultsToWildcard() {
            ManagementConfig config = ManagementConfig.builder().port(0).build();

            assertEquals("0.0.0.0", config.host());
        }

        @Test
        @DisplayName("pinned loopback host serves health endpoint on 127.0.0.1")
        void pinnedHostServesOnLoopback(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config =
                    ManagementConfig.builder().port(0).host("127.0.0.1").build();
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of());

            vertx.deployVerticle(verticle)
                    .compose(id -> {
                        int boundPort =
                                (int) vertx.sharedData().getLocalMap("vertique").get("management.port");
                        return request(vertx, boundPort, "127.0.0.1", "/health/live");
                    })
                    .onComplete(ctx.succeeding(json -> {
                        ctx.verify(() -> {
                            assertEquals(200, json.getInteger("_statusCode"));
                            assertEquals("UP", json.getString("status"));
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("unresolvable host fails deployment")
        void invalidHostFailsDeployment(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder()
                    .port(0)
                    .host("999.invalid.example")
                    .build();
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of());

            vertx.deployVerticle(verticle).onComplete(ctx.failing(cause -> ctx.completeNow()));
        }
    }

    // --- Endpoint contributors ---

    /**
     * Test {@link ManagementEndpointContributor} implementations used across contributor tests.
     */
    static class CustomRouteContributor implements ManagementEndpointContributor {
        private final String path;
        private final String responseBody;

        CustomRouteContributor(String path, String responseBody) {
            this.path = path;
            this.responseBody = responseBody;
        }

        @Override
        public void contribute(Router router) {
            router.get(path).handler(ctx -> ctx.response().end(responseBody));
        }
    }

    /** A contributor whose {@link #contribute} throws a {@link RuntimeException}. */
    static class ThrowingContributor implements ManagementEndpointContributor {
        @Override
        public void contribute(Router router) {
            throw new RuntimeException("contributor boom");
        }
    }

    /** A contributor that records its invocation index for ordering assertions. */
    static class RecordingContributor implements ManagementEndpointContributor {
        private final String name;
        private final int priority;
        private final List<String> invocationOrder;

        RecordingContributor(String name, int priority, List<String> invocationOrder) {
            this.name = name;
            this.priority = priority;
            this.invocationOrder = invocationOrder;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public void contribute(Router router) {
            invocationOrder.add(name);
        }
    }

    /**
     * A contributor that mounts a handler on {@code /health/live} to verify that the health
     * route is registered first and wins on path conflicts.
     */
    static class HealthPathContributor implements ManagementEndpointContributor {
        @Override
        public void contribute(Router router) {
            router.get("/health/live").handler(ctx -> ctx.response().end("{\"status\":\"CONTRIBUTOR\"}"));
        }
    }

    @Nested
    @DisplayName("EndpointContributors")
    class EndpointContributors {

        @Test
        @DisplayName("contributor mounts custom route; health route still works")
        void customRouteAndHealthStillWork(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementEndpointContributor contributor = new CustomRouteContributor("/custom", "{\"ok\":true}");
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of(contributor));

            vertx.deployVerticle(verticle)
                    .compose(id -> {
                        int port =
                                (int) vertx.sharedData().getLocalMap("vertique").get("management.port");
                        return request(vertx, port, "/custom")
                                .compose(customResp -> {
                                    ctx.verify(() -> {
                                        assertEquals(200, customResp.getInteger("_statusCode"));
                                        assertTrue(customResp.getBoolean("ok"));
                                    });
                                    return request(vertx, port, "/health/live");
                                })
                                .map(healthResp -> {
                                    ctx.verify(() -> {
                                        assertEquals(200, healthResp.getInteger("_statusCode"));
                                        assertEquals("UP", healthResp.getString("status"));
                                    });
                                    return healthResp;
                                });
                    })
                    .onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
        }

        @Test
        @DisplayName("contributors are invoked in OrderedExtension.comparator() order")
        void contributorsInvocationOrder(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            List<String> order = new ArrayList<>();
            ManagementEndpointContributor first = new RecordingContributor("first", 1, order);
            ManagementEndpointContributor second = new RecordingContributor("second", 2, order);
            // Pass in reverse order to prove sorting occurs
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of(second, first));

            vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
                ctx.verify(() -> {
                    assertEquals(List.of("first", "second"), order);
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("throwing contributor fails management server startup")
        void throwingContributorFailsStartup(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(), Set.of(), config, Set.of(new ThrowingContributor()));

            vertx.deployVerticle(verticle).onComplete(ctx.failing(cause -> {
                ctx.verify(() -> {
                    assertEquals("contributor boom", cause.getMessage());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("contributor mounting /health/live does not shadow the real health route")
        void contributorDoesNotShadowHealthRoute(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config = ManagementConfig.builder().port(0).build();
            ManagementVerticle verticle =
                    new ManagementVerticle(Set.of(), Set.of(), config, Set.of(new HealthPathContributor()));

            vertx.deployVerticle(verticle)
                    .compose(id -> {
                        int port =
                                (int) vertx.sharedData().getLocalMap("vertique").get("management.port");
                        return request(vertx, port, "/health/live");
                    })
                    .onComplete(ctx.succeeding(json -> {
                        ctx.verify(() -> {
                            assertEquals(200, json.getInteger("_statusCode"));
                            assertEquals("UP", json.getString("status"));
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("contributor is not invoked when management is disabled")
        void contributorNotInvokedWhenDisabled(Vertx vertx, VertxTestContext ctx) {
            ManagementConfig config =
                    ManagementConfig.builder().port(9998).enabled(false).build();
            AtomicBoolean invoked = new AtomicBoolean(false);
            ManagementEndpointContributor contributor = router -> invoked.set(true);
            ManagementVerticle verticle = new ManagementVerticle(Set.of(), Set.of(), config, Set.of(contributor));

            vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
                ctx.verify(() -> assertFalse(invoked.get(), "contributor must not be invoked when disabled"));
                ctx.completeNow();
            }));
        }
    }
}

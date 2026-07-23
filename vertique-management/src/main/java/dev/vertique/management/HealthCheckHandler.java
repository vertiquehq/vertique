// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Vert.x route handler that runs a set of {@link HealthCheck} instances concurrently, applies a
 * per-check timeout, and writes the aggregated result as a JSON response.
 *
 * <p>Response format:
 *
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "checks": [
 *     { "name": "db", "status": "UP", "data": { ... } }
 *   ]
 * }
 * }</pre>
 *
 * <p>The overall status is {@code UP} (HTTP 200) only when every individual check is {@code UP}.
 * Any failed or timed-out check yields {@code DOWN} (HTTP 503). An empty check set is treated as
 * {@code UP} with an empty checks array.
 *
 * <p>Each check runs with a configurable per-check timeout (default
 * {@value #DEFAULT_CHECK_TIMEOUT_SECONDS} seconds). Synchronous exceptions thrown by
 * {@link HealthCheck#check()} are caught and reported as {@code DOWN}.
 */
public class HealthCheckHandler implements Handler<RoutingContext> {

    private static final long DEFAULT_CHECK_TIMEOUT_SECONDS = 5L;

    private final Set<HealthCheck> checks;
    private final long checkTimeoutSeconds;

    /**
     * Creates a new handler for the given set of health checks using the default
     * {@value #DEFAULT_CHECK_TIMEOUT_SECONDS}-second per-check timeout.
     *
     * <p>A defensive unmodifiable copy of {@code checks} is taken at construction time, so
     * subsequent mutations to the caller's set have no effect.
     *
     * @param checks the health checks to run on each request; must not be {@code null}
     */
    public HealthCheckHandler(Set<HealthCheck> checks) {
        this(checks, DEFAULT_CHECK_TIMEOUT_SECONDS);
    }

    /**
     * Creates a new handler for the given set of health checks with a configurable per-check
     * timeout.
     *
     * <p>A defensive unmodifiable copy of {@code checks} is taken at construction time, so
     * subsequent mutations to the caller's set have no effect.
     *
     * @param checks              the health checks to run on each request; must not be {@code null}
     * @param checkTimeoutSeconds the per-check timeout in seconds; must be positive
     */
    public HealthCheckHandler(Set<HealthCheck> checks, long checkTimeoutSeconds) {
        if (checkTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("checkTimeoutSeconds must be positive, got: " + checkTimeoutSeconds);
        }
        this.checks = Set.copyOf(checks);
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }

    /**
     * Handles a health probe request by running all checks concurrently and writing the aggregated
     * JSON response.
     *
     * @param ctx the routing context for the incoming HTTP request
     */
    @Override
    public void handle(RoutingContext ctx) {
        if (checks.isEmpty()) {
            sendResponse(ctx, HealthStatus.UP, new JsonArray());
            return;
        }

        List<Future<JsonObject>> checkFutures = new ArrayList<>();
        for (HealthCheck check : checks) {
            Future<JsonObject> future;
            try {
                future = check.check()
                        .timeout(checkTimeoutSeconds, TimeUnit.SECONDS)
                        .map(result -> toJson(check.name(), result))
                        .otherwise(cause -> toJson(check.name(), HealthCheckResult.down(cause.getMessage())));
            } catch (Exception e) {
                future = Future.succeededFuture(toJson(check.name(), HealthCheckResult.down(e.getMessage())));
            }
            checkFutures.add(future);
        }

        Future.all(checkFutures).onComplete(ar -> {
            JsonArray checksArray = new JsonArray();
            boolean allUp = true;
            for (Future<JsonObject> f : checkFutures) {
                JsonObject checkJson = f.result();
                checksArray.add(checkJson);
                if (!HealthStatus.UP.name().equals(checkJson.getString("status"))) {
                    allUp = false;
                }
            }
            sendResponse(ctx, allUp ? HealthStatus.UP : HealthStatus.DOWN, checksArray);
        });
    }

    private JsonObject toJson(String name, HealthCheckResult result) {
        JsonObject json =
                new JsonObject().put("name", name).put("status", result.status().name());
        if (!result.data().isEmpty()) {
            json.put("data", JsonObject.mapFrom(result.data()));
        }
        return json;
    }

    private void sendResponse(RoutingContext ctx, HealthStatus status, JsonArray checks) {
        int statusCode = status == HealthStatus.UP ? 200 : 503;
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                        .put("status", status.name())
                        .put("checks", checks)
                        .encode());
    }
}

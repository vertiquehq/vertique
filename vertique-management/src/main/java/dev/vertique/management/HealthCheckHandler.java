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
 *
 * <p><b>Invariant:</b> {@link #handle(RoutingContext)} writes exactly one response, and the
 * {@code checks} array carries exactly one entry per check in the set — for every check that
 * returns or throws an {@link Exception}. A check that fails, times out, throws from
 * {@link HealthCheck#check()} or {@link HealthCheck#name()}, produces data that cannot be
 * serialized, or fails with a throwable whose {@link Throwable#getMessage()} throws, still
 * contributes a {@code DOWN} entry and never suppresses a sibling's entry or the response itself.
 *
 * <p>Two hazards remain, and are deliberately not absorbed: a contributor that throws an
 * {@link Error} propagates rather than being laundered into a {@code DOWN} status (a probe must
 * not report "unhealthy" for an {@code OutOfMemoryError} and carry on), and a contributor that
 * blocks the event loop indefinitely inside {@code check()}, {@code name()}, or
 * {@code Throwable#getMessage()} can still stall the probe — the per-check timeout bounds a
 * pending future, not a thread that never yields.
 */
public class HealthCheckHandler implements Handler<RoutingContext> {

    private static final long DEFAULT_CHECK_TIMEOUT_SECONDS = 5L;

    private final Set<HealthCheck> checks;
    private final long checkTimeoutSeconds;

    /**
     * One check's in-flight invocation: its name, resolved exactly once up front, paired with the
     * raw result future.
     *
     * <p>The future carries the {@link HealthCheckResult} as the check produced it — never a
     * pre-rendered JSON view and never a per-check recovery. Keeping failures raw is what lets the
     * aggregation observe them: a per-check {@code otherwise} could itself throw and strand the
     * response.
     *
     * @param name   the check's name, or its class name when {@link HealthCheck#name()} threw
     * @param result the raw, possibly failed, result of invoking the check
     */
    private record CheckExecution(String name, Future<HealthCheckResult> result) {}

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

    // --- Request handling ---

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

        List<CheckExecution> executions = checks.stream().map(this::start).toList();

        // join, not all: all is fail-fast, so a single failed check would complete the aggregation
        // while its siblings are still pending and they would render as DOWN. join waits for every
        // constituent to settle, whatever its outcome.
        Future.join(executions.stream().map(CheckExecution::result).toList()).onComplete(ar -> {
            JsonArray checksArray = new JsonArray();
            boolean allUp = true;
            for (CheckExecution execution : executions) {
                JsonObject checkJson = render(execution);
                checksArray.add(checkJson);
                if (!HealthStatus.UP.name().equals(checkJson.getString("status"))) {
                    allUp = false;
                }
            }
            sendResponse(ctx, allUp ? HealthStatus.UP : HealthStatus.DOWN, checksArray);
        });
    }

    // --- Check execution ---

    /**
     * Starts one check: resolves its name exactly once and converts a synchronous throw into a
     * failed future, so every outcome reaches the aggregation as a settled future.
     *
     * @param check the check to invoke
     * @return the started execution
     */
    private CheckExecution start(HealthCheck check) {
        String name;
        try {
            name = check.name();
        } catch (Exception e) {
            // A check that cannot even name itself is unusable: report it DOWN under its class
            // name. name() is never called again — calling it on a rendering path is what
            // previously defeated the recovery and suppressed the whole response.
            return new CheckExecution(check.getClass().getName(), Future.failedFuture(e));
        }
        try {
            return new CheckExecution(name, check.check().timeout(checkTimeoutSeconds, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new CheckExecution(name, Future.failedFuture(e));
        }
    }

    // --- Rendering ---

    /**
     * Renders one settled execution as its JSON entry. This method never throws an
     * {@link Exception}: a failed check, an unserializable data map, and a hostile
     * {@link Throwable#getMessage()} all degrade to a {@code DOWN} entry.
     *
     * @param execution the settled execution to render
     * @return the check's JSON entry
     */
    private JsonObject render(CheckExecution execution) {
        Future<HealthCheckResult> result = execution.result();
        if (result.succeeded()) {
            try {
                return toJson(execution.name(), result.result());
            } catch (Exception e) {
                // The check reported a status but its diagnostic data cannot be rendered as JSON;
                // report the rendering failure instead of dropping the entry.
                return downJson(execution.name(), e);
            }
        }
        return downJson(execution.name(), result.cause());
    }

    /**
     * Builds the {@code DOWN} JSON entry describing a failure.
     *
     * <p>{@link HealthCheckResult#down(Throwable)} is total for any failure that throws an
     * {@link Exception} from {@link Throwable#getMessage()}, so no fallback is needed here.
     *
     * @param name  the check's already-resolved name
     * @param cause the failure to describe
     * @return a {@code DOWN} entry carrying the failure's message, or its class name when the
     *         message is unavailable or unreadable
     */
    private JsonObject downJson(String name, Throwable cause) {
        return toJson(name, HealthCheckResult.down(cause));
    }

    /**
     * Renders a single check result as its JSON entry, omitting {@code data} when the result
     * carries none.
     *
     * @param name   the check's name
     * @param result the check's result
     * @return the check's JSON entry
     */
    private JsonObject toJson(String name, HealthCheckResult result) {
        JsonObject json =
                new JsonObject().put("name", name).put("status", result.status().name());
        if (!result.data().isEmpty()) {
            json.put("data", JsonObject.mapFrom(result.data()));
        }
        return json;
    }

    /**
     * Writes the aggregated probe response: HTTP 200 for {@code UP}, HTTP 503 for {@code DOWN}.
     *
     * @param ctx    the routing context for the incoming HTTP request
     * @param status the aggregated status
     * @param checks the per-check entries
     */
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

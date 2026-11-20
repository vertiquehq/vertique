// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckAggregator;
import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthReport;
import dev.vertique.core.health.HealthStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x route handler that evaluates a set of {@link HealthCheck} instances through a
 * {@link HealthCheckAggregator} and writes the resulting {@link HealthReport} as a JSON response.
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
 * <p>The overall status is {@code UP} (HTTP 200) only when every individual entry is {@code UP}.
 * Any {@code DOWN} entry yields {@code DOWN} (HTTP 503). An empty check set is {@code UP} with an
 * empty checks array.
 *
 * <p>Which checks run, how long each may take, and how a failed, slow, misnamed or throwing check is
 * reported are the aggregator's rules, applied here with the configured per-check timeout (default
 * {@value HealthCheckAggregator#DEFAULT_CHECK_TIMEOUT_SECONDS} seconds). This handler owns only the
 * HTTP layer on top of them: rendering each entry, choosing the status code, and answering when the
 * report itself cannot be rendered.
 *
 * <p><b>Invariant:</b> {@link #handle(RoutingContext)} writes exactly one response for every
 * outcome that terminates — every {@link Throwable} a contributor raises is absorbed and answered —
 * but it promises nothing for the aggregator's two non-terminating hazards, where contributor code
 * never yields and no outcome exists to answer with.
 *
 * <p>The response degrades in two steps:
 *
 * <ul>
 *   <li><b>Attributable failure</b> — a failure pinned on one check yields a {@code DOWN} entry for
 *       that check and leaves every sibling's entry intact. The aggregator attributes a failed or
 *       timed-out future and an {@link Exception} thrown by {@link HealthCheck#check()} or
 *       {@link HealthCheck#name()}; this handler additionally attributes diagnostic data that cannot
 *       be rendered as JSON — including data whose graph is cyclic or too deep for the serializer,
 *       which exhausts the stack rather than raising an exception. A rendered entry can therefore be
 *       {@code DOWN} where the report's own entry was {@code UP}, which is why the HTTP status is
 *       decided from the rendered entries, not from {@link HealthReport#status()}.
 *   <li><b>Unattributable failure</b> — an {@link Error} from a contributor, which the aggregator
 *       does not treat as one check's diagnosable status, or a failure that surfaces only while the
 *       aggregated body is being written, yields a terse {@code 503 {"status":"DOWN","checks":[]}}
 *       and an {@code ERROR} log carrying the cause. The boundary absorbs any {@link Throwable},
 *       {@code Error}s included, because a probe that cannot answer is indistinguishable to an
 *       orchestrator from a dead process. The log is the only record of what went wrong: a throw
 *       escaping the aggregation callback is swallowed by Vert.x rather than propagated, so it would
 *       otherwise leave the probe hanging silently.
 * </ul>
 */
@Slf4j
public class HealthCheckHandler implements Handler<RoutingContext> {

    /**
     * The body written when the aggregated response cannot be rendered. It is a constant so that
     * producing it cannot itself fail on the path that exists to answer a rendering failure.
     */
    private static final String FALLBACK_BODY = "{\"status\":\"DOWN\",\"checks\":[]}";

    private final Set<HealthCheck> checks;
    private final HealthCheckAggregator aggregator;

    /**
     * Creates a new handler for the given set of health checks using the default
     * {@value HealthCheckAggregator#DEFAULT_CHECK_TIMEOUT_SECONDS}-second per-check timeout.
     *
     * <p>A defensive unmodifiable copy of {@code checks} is taken at construction time, so
     * subsequent mutations to the caller's set have no effect.
     *
     * @param checks the health checks to run on each request; must not be {@code null}
     */
    public HealthCheckHandler(Set<HealthCheck> checks) {
        this(checks, HealthCheckAggregator.DEFAULT_CHECK_TIMEOUT_SECONDS);
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
     * @throws IllegalArgumentException if {@code checkTimeoutSeconds} is zero or negative
     */
    public HealthCheckHandler(Set<HealthCheck> checks, long checkTimeoutSeconds) {
        this.aggregator = new HealthCheckAggregator(checkTimeoutSeconds);
        this.checks = Set.copyOf(checks);
    }

    // --- Request handling ---

    /**
     * Handles a health probe request by evaluating all checks through the aggregator and writing
     * the aggregated JSON response.
     *
     * @param ctx the routing context for the incoming HTTP request
     */
    @Override
    public void handle(RoutingContext ctx) {
        // The whole body sits inside the boundary, not just the aggregation callback: an Error from
        // a contributor's name() or check() propagates synchronously out of evaluate and would
        // otherwise escape into vertx-web and be answered as a generic router 500. When this fires
        // mid-stream, any sibling check already started is simply abandoned — its future settles
        // into a callback nobody reads.
        try {
            aggregator.evaluate(checks).onComplete(ar -> {
                try {
                    if (ar.failed()) {
                        // The aggregator could not assemble the report — an Error raised while a
                        // failure was being described. Not one check's status; answer at the boundary.
                        answerWithFallback(ctx, ar.cause());
                        return;
                    }
                    JsonArray checksArray = new JsonArray();
                    boolean allUp = true;
                    for (HealthReport.Entry entry : ar.result().checks()) {
                        JsonObject checkJson = render(entry);
                        checksArray.add(checkJson);
                        if (!HealthStatus.UP.name().equals(checkJson.getString("status"))) {
                            allUp = false;
                        }
                    }
                    sendResponse(ctx, allUp ? HealthStatus.UP : HealthStatus.DOWN, checksArray);
                } catch (Throwable failure) {
                    answerWithFallback(ctx, failure);
                }
            });
        } catch (Throwable failure) {
            // The aggregation may complete inline, so this can run after the callback above already
            // answered. That cannot double-answer: answerWithFallback re-checks closed()/ended().
            answerWithFallback(ctx, failure);
        }
    }

    /**
     * Answers a probe whose aggregated response could not be rendered, and records why.
     *
     * <p>This is the one boundary that owns the "always answers" invariant, so it catches everything
     * an interior guard let through. Rethrowing is not an option: the aggregation completes through a
     * contextless composite, so a throw escaping its completion callback reaches no Vert.x exception
     * handler and is never logged — the probe would simply hang. The {@code ERROR} log is still the
     * whole replacement for that rethrow, but it runs only once the write has been <em>initiated</em>
     * — {@code end} returns a future, so the response is already handed to the transport by then.
     * That ordering keeps an application-supplied appender off the path that builds and submits the
     * answer. It does not make the answer independent of a blocking appender: {@code end} hands the
     * bytes to the transport, which flushes them inline only while the socket is writable, so under
     * backpressure a blocking appender can still delay delivery. Sequencing the log on the write's
     * completion future is deliberately avoided, because the {@code ERROR} line would then be lost
     * whenever that future never settles — and it is the only record that the failure happened at
     * all.
     *
     * @param ctx     the routing context for the incoming HTTP request
     * @param failure the failure that prevented the aggregated response from being rendered
     */
    private void answerWithFallback(RoutingContext ctx, Throwable failure) {
        try {
            HttpServerResponse response = ctx.response();
            if (!response.closed() && !response.ended()) {
                if (!response.headWritten()) {
                    response.setStatusCode(503)
                            .putHeader("content-type", "application/json")
                            .end(FALLBACK_BODY)
                            .onFailure(t -> logQuietly("Health probe fallback response failed", t));
                } else {
                    // Head committed but the body never completed. The status can no longer be
                    // changed, so completing the response would report whatever the head already
                    // says — a 200 when every check was UP — and an orchestrator reads the status
                    // and ignores the body, so a rendering failure would surface as "healthy".
                    // reset() aborts instead, giving the client a terminal error event. Kept as
                    // defense in depth: it is unreachable while sendResponse encodes the body
                    // before the first mutation of the response.
                    response.reset().onFailure(t -> logQuietly("Health probe fallback reset failed", t));
                }
            }
        } catch (Throwable writeFailure) {
            logQuietly("Health probe fallback response failed", writeFailure);
        }
        logQuietly("Health probe rendering failed; answering DOWN", failure);
    }

    /**
     * Logs at {@code ERROR} without letting the logging itself fail the caller.
     *
     * @param message the message to log
     * @param failure the failure to attach
     */
    private void logQuietly(String message, Throwable failure) {
        try {
            log.error(message, failure);
        } catch (Throwable ignored) {
            // An appender is application-supplied, so a throwing one must not fail this probe. A
            // blocking one is a different matter: it still stalls the event loop after the answer
            // has been handed to the transport, which delays delivery whenever the socket is not
            // immediately writable. Nothing here bounds it — the same hazard family as the
            // aggregator's non-terminating hazards.
        }
    }

    // --- Rendering ---

    /**
     * Renders one report entry as its JSON entry, degrading rather than propagating whatever the
     * check's data does to the serializer. An unserializable data map yields a {@code DOWN} entry. A
     * {@link Throwable} that is not an {@code Exception} — other than the {@link StackOverflowError}
     * guarded explicitly below — reaches the response boundary instead, where it degrades the whole
     * probe to the terse fallback. That split is the policy: an {@code Error} is not a diagnosable
     * per-check condition, so it is answered at the boundary that owns the "always answers"
     * invariant rather than dressed up as one check's status.
     *
     * <p>Keeping this guard here rather than only at the response boundary is what preserves
     * per-check fidelity — a single poisoned check degrades its own entry, not the whole response
     * and its siblings' entries with it.
     *
     * @param entry the report entry to render
     * @return the check's JSON entry
     */
    private JsonObject render(HealthReport.Entry entry) {
        try {
            return toJson(entry.name(), entry.result());
        } catch (Exception e) {
            // The check reported a status but its diagnostic data cannot be rendered as JSON;
            // report the rendering failure instead of dropping the entry.
            return toJson(entry.name(), HealthCheckResult.down(e));
        } catch (StackOverflowError e) {
            // JsonObject.mapFrom recurses into Map/Collection values, so data whose graph is
            // cyclic or extremely deep exhausts the stack instead of raising an Exception.
            // (A cycle between POJOs is wrapped by Jackson as an IllegalArgumentException and
            // is already handled above.) The stack unwinds fully before this runs, so the entry
            // is rendered on a healthy stack.
            return toJson(entry.name(), HealthCheckResult.down(e));
        }
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
        // Encode before touching the response. JsonObject.encode enforces the serializer's nesting
        // limit that JsonObject.mapFrom does not, so it can still reject data that every per-check
        // rendering accepted. Failing before the first mutation hands the boundary fallback an
        // untouched response instead of one already carrying a status code and a header.
        String body = new JsonObject()
                .put("status", status.name())
                .put("checks", checks)
                .encode();
        int statusCode = status == HealthStatus.UP ? 200 : 503;
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(body);
    }
}

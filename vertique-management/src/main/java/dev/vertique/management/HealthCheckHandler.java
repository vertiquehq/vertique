// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

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
 * <p><b>Invariant:</b> {@link #handle(RoutingContext)} writes exactly one response for every
 * outcome that terminates — every {@link Throwable} a contributor raises is absorbed and answered —
 * but it promises nothing for the two non-terminating hazards described below, where contributor
 * code never yields and no outcome exists to answer with.
 *
 * <p>The response degrades in two steps:
 *
 * <ul>
 *   <li><b>Attributable failure</b> — a failure the handler can pin on one check yields a
 *       {@code DOWN} entry for that check and leaves every sibling's entry intact. This covers a
 *       failed or timed-out future, an {@link Exception} thrown by {@link HealthCheck#check()} or
 *       {@link HealthCheck#name()} — an {@code Error} from either is not treated as one check's
 *       diagnosable status and degrades to the unattributable fallback below — a failure whose
 *       {@link Throwable#getMessage()} throws, and diagnostic data that cannot be rendered as JSON
 *       — including data whose graph is cyclic or too deep for the serializer, which exhausts the
 *       stack rather than raising an exception.
 *   <li><b>Unattributable failure</b> — a failure that surfaces only while the aggregated body is
 *       being written, so no single check owns it, yields a terse
 *       {@code 503 {"status":"DOWN","checks":[]}} and an {@code ERROR} log carrying the cause. The
 *       boundary absorbs any {@link Throwable}, {@code Error}s included, because a probe that
 *       cannot answer is indistinguishable to an orchestrator from a dead process. The log is the
 *       only record of what went wrong: a throw escaping the aggregation callback is swallowed by
 *       Vert.x rather than propagated, so it would otherwise leave the probe hanging silently.
 * </ul>
 *
 * <p>Two hazards remain and are deliberately not absorbed. Both originate in contributor code this
 * handler invokes, and neither can be bounded by the per-check timeout, which bounds a pending
 * future rather than a thread that never yields. A contributor that blocks the event loop
 * indefinitely inside {@code check()}, {@code name()}, or {@code Throwable#getMessage()} stalls the
 * probe with no future left to time out. A contributor {@link Future} that reports itself failed
 * while returning a {@code null} {@link Future#cause()} breaks the {@code Future} contract and
 * stalls Vert.x's own aggregation before this handler regains control.
 */
@Slf4j
public class HealthCheckHandler implements Handler<RoutingContext> {

    private static final long DEFAULT_CHECK_TIMEOUT_SECONDS = 5L;

    /**
     * The body written when the aggregated response cannot be rendered. It is a constant so that
     * producing it cannot itself fail on the path that exists to answer a rendering failure.
     */
    private static final String FALLBACK_BODY = "{\"status\":\"DOWN\",\"checks\":[]}";

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
        // The whole body sits inside the boundary, not just the aggregation callback: start()
        // converts only an Exception, so an Error from a contributor's name() or check() would
        // otherwise escape into vertx-web and be answered as a generic router 500. When this fires
        // mid-stream, any sibling check already started is simply abandoned — its future settles
        // into a callback nobody reads.
        try {
            if (checks.isEmpty()) {
                sendResponse(ctx, HealthStatus.UP, new JsonArray());
                return;
            }

            List<CheckExecution> executions = checks.stream().map(this::start).toList();

            // join, not all: all is fail-fast, so a single failed check would complete the
            // aggregation while its siblings are still pending and they would render as DOWN. join
            // waits for every constituent to settle, whatever its outcome.
            Future.join(executions.stream().map(CheckExecution::result).toList())
                    .onComplete(ar -> {
                        try {
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
                        } catch (Throwable failure) {
                            answerWithFallback(ctx, failure);
                        }
                    });
        } catch (Throwable failure) {
            // Future.join may complete inline, so this can run after the callback above already
            // answered. That cannot double-answer: answerWithFallback re-checks closed()/ended().
            answerWithFallback(ctx, failure);
        }
    }

    /**
     * Answers a probe whose aggregated response could not be rendered, and records why.
     *
     * <p>This is the one boundary that owns the "always answers" invariant, so it catches everything
     * an interior guard let through. Rethrowing is not an option: {@code Future.join} builds a
     * contextless composite, so a throw escaping its completion callback reaches no Vert.x exception
     * handler and is never logged — the probe would simply hang. The {@code ERROR} log is still the
     * whole replacement for that rethrow, but it runs only once the write has been <em>initiated</em>
     * — {@code end} returns a future, so the response is already handed to the transport by then.
     * That ordering keeps an application-supplied appender off the path that builds and submits the
     * answer. It does not make the answer independent of a blocking appender: {@code end} hands the
     * bytes to the transport, which flushes them inline only while the socket is writable, so under
     * backpressure a blocking appender can still delay delivery. Sequencing the log on the write's
     * completion future
     * is deliberately avoided, because the {@code ERROR} line would then be lost whenever that
     * future never settles — and it is the only record that the failure happened at all.
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
            // immediately writable. Nothing here bounds it — the same hazard family as the residual
            // hazards named in the class javadoc.
        }
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
     * Renders one settled execution as its JSON entry, degrading rather than propagating whatever
     * the check produced. A failed check, an unserializable data map, and a
     * {@link Throwable#getMessage()} that throws an {@link Exception} each yield a {@code DOWN}
     * entry. A {@link Throwable} that is not an {@code Exception} — other than the
     * {@link StackOverflowError} guarded explicitly below — reaches the response boundary instead,
     * where it degrades the whole probe to the terse fallback. That split is the policy: an
     * {@code Error} is not a diagnosable per-check condition, so it is answered at the boundary
     * that owns the "always answers" invariant rather than dressed up as one check's status.
     *
     * <p>Keeping these guards here rather than only at the response boundary is what preserves
     * per-check fidelity — a single poisoned check degrades its own entry, not the whole response
     * and its siblings' entries with it.
     *
     * <p>A failure that reports itself failed while carrying a {@code null} {@link Future#cause()}
     * is handled defensively rather than as a supported case: no current {@code Future}
     * implementation reaches this method in that state, because Vert.x's own aggregation stalls on
     * it first. The guard exists because this is the seam that would observe the state if that ever
     * changed.
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
            } catch (StackOverflowError e) {
                // JsonObject.mapFrom recurses into Map/Collection values, so data whose graph is
                // cyclic or extremely deep exhausts the stack instead of raising an Exception.
                // (A cycle between POJOs is wrapped by Jackson as an IllegalArgumentException and
                // is already handled above.) The stack unwinds fully before this runs, so the entry
                // is rendered on a healthy stack.
                return downJson(execution.name(), e);
            }
        }
        Throwable cause = result.cause();
        if (cause == null) {
            // A third-party Future implementation may report a failure with no cause at all, and
            // this method reads succeeded()/cause() straight off that object. down(Throwable) is
            // strict on null by contract — an absent failure object is a caller bug, not a
            // diagnosable state — so report DOWN with no diagnostic rather than relax it.
            //
            // Today no such future reaches here: Future.join reads the same two accessors while
            // settling its composite and never completes when the cause is null, so the probe
            // stalls upstream of this branch (measured against vertx-core 5.1.2). The guard is kept
            // because this is the seam that would observe the state if that ever changed.
            return toJson(execution.name(), HealthCheckResult.down());
        }
        return downJson(execution.name(), cause);
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

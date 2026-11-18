// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpBodyLimitException.Direction;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.internal.FutureInternal;
import io.vertx.ext.web.client.impl.HttpContext;
import java.util.concurrent.TimeoutException;

/**
 * Governance interceptor that follows each exchange of an {@link AttemptScope}: it refuses a request
 * once the scope has ended and records the Vert.x request and response so the scope can reset them.
 *
 * <p>Per-exchange state lives in the {@link HttpContext}, never on this instance.
 */
final class ExchangeTracker implements Handler<HttpContext<?>> {

    private static final String EXCHANGE = "vertique.exchange";

    private final AttemptScope scope;
    private final Vertx vertx;
    private final long timeoutMs;
    private final long maxResponseBytes;

    /**
     * Creates a tracker.
     *
     * @param scope the scope whose exchanges it follows
     * @param vertx the Vert.x instance that runs the per-exchange timer
     * @param timeoutMs the timeout of each exchange in milliseconds, {@code 0} when the scope's owner
     *     bounds the attempt itself
     * @param maxResponseBytes the most response body bytes a declared {@code Content-Length} may
     *     announce, {@code 0} for no bound
     */
    ExchangeTracker(AttemptScope scope, Vertx vertx, long timeoutMs, long maxResponseBytes) {
        this.scope = scope;
        this.vertx = vertx;
        this.timeoutMs = timeoutMs;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Resets the exchange of a context because its response body went over the bound; the call fails
     * with the body-limit failure.
     */
    static void overrun(HttpContext<?> ctx, long limit) {
        AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
        if (exchange != null) {
            exchange.reset(new HttpBodyLimitException(Direction.RESPONSE, limit));
        }
    }

    /** Returns the exchange a context belongs to, or {@code null} when it is not tracked. */
    static AttemptScope.Exchange exchangeOf(HttpContext<?> ctx) {
        return ctx.get(EXCHANGE);
    }

    @Override
    public void handle(HttpContext<?> ctx) {
        HttpClientInterceptors interceptors = scope.interceptors();
        switch (ctx.phase()) {
            case PREPARE_REQUEST -> {
                if (ctx.get(EXCHANGE) == null) {
                    AttemptScope.Exchange exchange = scope.begin();
                    if (exchange == null) {
                        ctx.fail(new IllegalStateException(AttemptScope.ENDED));
                        return;
                    }
                    ctx.set(EXCHANGE, exchange);
                    exchange.method(ctx.request().method().name());
                    if (!interceptors.isEmpty()) {
                        exchange.snapshot(interceptors.describe(ctx.request(), exchange.callId()));
                        exchange.target(interceptors.targetOf(ctx.request()));
                    }
                    if (timeoutMs > 0) {
                        startTimer(ctx, exchange);
                    }
                }
            }
            case CREATE_REQUEST -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                RequestOptions options = ctx.requestOptions();
                if (exchange != null && options != null && !interceptors.isEmpty()) {
                    exchange.target(interceptors.target(options.isSsl(), options.getHost(), options.getPort()));
                }
            }
            case SEND_REQUEST -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null && !exchange.attach(ctx.clientRequest())) {
                    refuse(ctx, exchange, ctx.clientRequest());
                    return;
                }
            }
            case RECEIVE_RESPONSE -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null && !exchange.attach(ctx.clientResponse())) {
                    refuse(ctx, exchange, ctx.clientResponse().request());
                    return;
                }
                if (exchange != null && !interceptors.isEmpty()) {
                    exchange.head(headOf(ctx.clientResponse()));
                }
                if (exchange != null && declaresTooMuch(ctx.clientResponse())) {
                    Throwable cause = new HttpBodyLimitException(Direction.RESPONSE, maxResponseBytes);
                    ctx.clientResponse().request().reset(0, cause);
                    ctx.fail(cause);
                    return;
                }
            }
            case DISPATCH_RESPONSE -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null) {
                    exchange.respond();
                }
            }
            case FAILURE -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null) {
                    exchange.fail(ctx.failure());
                }
            }
            default -> {
                // phases without tracking
            }
        }
        ctx.next();
    }

    /** The head of a response as the interceptors see it, or {@code null} for a status they cannot be shown. */
    private static HttpClientResponseHead headOf(HttpClientResponse response) {
        int status = response.statusCode();
        if (status < 100 || status > 599) {
            return null;
        }
        return new HttpClientResponseHead(status, response.statusMessage(), response.headers());
    }

    /** Whether the response head declares a body longer than the bound; a bodiless answer never does. */
    private boolean declaresTooMuch(HttpClientResponse response) {
        if (maxResponseBytes <= 0 || response.request().getMethod() == HttpMethod.HEAD) {
            return false;
        }
        int status = response.statusCode();
        if (status < 200 || status == 204 || status == 304) {
            return false;
        }
        String declared = response.getHeader("Content-Length");
        if (declared == null) {
            return false;
        }
        try {
            return Long.parseLong(declared.trim()) > maxResponseBytes;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /**
     * Starts the exchange's own timer. The timer fires on the context that owns the {@link HttpContext}
     * and not on the thread that created the request, so expiry never races the exchange's phases.
     */
    private void startTimer(HttpContext<?> ctx, AttemptScope.Exchange exchange) {
        Context owner = ctx.future() instanceof FutureInternal<?> future ? future.context() : null;
        long timerId = vertx.setTimer(timeoutMs, id -> {
            if (owner != null) {
                owner.runOnContext(ignored -> expire(ctx, exchange));
            } else {
                expire(ctx, exchange);
            }
        });
        exchange.onFinish(() -> vertx.cancelTimer(timerId));
    }

    /**
     * The exchange's own timer fired. Once a request exists it is reset with the timeout and Vert.x
     * fails the context; before that the context is failed here, so every path fails it exactly once.
     */
    private static void expire(HttpContext<?> ctx, AttemptScope.Exchange exchange) {
        boolean requestExists = exchange.hasRequest();
        if (!exchange.expire()) {
            return;
        }
        Throwable cause = new TimeoutException(CallExecution.TIMED_OUT);
        if (!requestExists) {
            ctx.fail(cause);
            return;
        }
        exchange.reset(cause);
    }

    /**
     * Refuses a request or response of an exchange that must not go on. An expired exchange already
     * failed its context, or does so through the reset; an exchange of an ended scope is failed here.
     */
    private static void refuse(HttpContext<?> ctx, AttemptScope.Exchange exchange, HttpClientRequest request) {
        if (exchange.isExpired()) {
            request.reset(0, new TimeoutException(CallExecution.TIMED_OUT));
            return;
        }
        Throwable cause = new IllegalStateException(AttemptScope.ENDED);
        request.reset(0, cause);
        ctx.fail(cause);
    }
}

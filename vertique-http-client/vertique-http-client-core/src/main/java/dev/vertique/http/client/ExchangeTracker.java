// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
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

    /**
     * Creates a tracker.
     *
     * @param scope the scope whose exchanges it follows
     * @param vertx the Vert.x instance that runs the per-exchange timer
     * @param timeoutMs the timeout of each exchange in milliseconds, {@code 0} when the scope's owner
     *     bounds the attempt itself
     */
    ExchangeTracker(AttemptScope scope, Vertx vertx, long timeoutMs) {
        this.scope = scope;
        this.vertx = vertx;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void handle(HttpContext<?> ctx) {
        switch (ctx.phase()) {
            case PREPARE_REQUEST -> {
                if (ctx.get(EXCHANGE) == null) {
                    AttemptScope.Exchange exchange = scope.begin();
                    if (exchange == null) {
                        ctx.fail(new IllegalStateException(AttemptScope.ENDED));
                        return;
                    }
                    ctx.set(EXCHANGE, exchange);
                    if (timeoutMs > 0) {
                        startTimer(ctx, exchange);
                    }
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
            }
            case DISPATCH_RESPONSE, FAILURE -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null) {
                    exchange.finish();
                }
            }
            default -> {
                // phases without tracking
            }
        }
        ctx.next();
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

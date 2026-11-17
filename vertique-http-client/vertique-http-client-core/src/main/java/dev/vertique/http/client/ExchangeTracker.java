// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Handler;
import io.vertx.ext.web.client.impl.HttpContext;

/**
 * Governance interceptor that follows each exchange of an {@link AttemptScope}: it refuses a request
 * once the scope has ended and records the Vert.x request and response so the scope can reset them.
 *
 * <p>Per-exchange state lives in the {@link HttpContext}, never on this instance.
 */
final class ExchangeTracker implements Handler<HttpContext<?>> {

    private static final String EXCHANGE = "vertique.exchange";

    private final AttemptScope scope;

    ExchangeTracker(AttemptScope scope) {
        this.scope = scope;
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
                }
            }
            case SEND_REQUEST -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null && !exchange.attach(ctx.clientRequest())) {
                    Throwable cause = new IllegalStateException(AttemptScope.ENDED);
                    ctx.clientRequest().reset(0, cause);
                    ctx.fail(cause);
                    return;
                }
            }
            case RECEIVE_RESPONSE -> {
                AttemptScope.Exchange exchange = ctx.get(EXCHANGE);
                if (exchange != null && !exchange.attach(ctx.clientResponse())) {
                    Throwable cause = new IllegalStateException(AttemptScope.ENDED);
                    ctx.clientResponse().request().reset(0, cause);
                    ctx.fail(cause);
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
}

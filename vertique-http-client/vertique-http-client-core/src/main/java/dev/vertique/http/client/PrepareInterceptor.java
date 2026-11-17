// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import java.util.Map;

/**
 * Governance interceptor for the request-preparation phase: it refuses relative requests of a client
 * without a base URL, applies the request header rules to what the caller set and then adds the
 * client's default headers.
 */
final class PrepareInterceptor implements Handler<HttpContext<?>> {

    /** Host a relative request carries when the client has no base URL; it is never connected to. */
    static final String NO_BASE_HOST = "no-base-url.invalid";

    /** Failure message of a relative request on a client without a base URL. */
    static final String NO_BASE_URL = "The request is relative but the client has no base URL";

    private final ClientSettings settings;

    PrepareInterceptor(ClientSettings settings) {
        this.settings = settings;
    }

    @Override
    public void handle(HttpContext<?> ctx) {
        if (ctx.phase() != ClientPhase.PREPARE_REQUEST) {
            ctx.next();
            return;
        }
        HttpRequest<?> request = ctx.request();
        if (NO_BASE_HOST.equals(request.host())) {
            ctx.fail(new IllegalStateException(NO_BASE_URL));
            return;
        }
        MultiMap headers = request.headers();
        if (settings.validateHeaders()) {
            String violation = HeaderRules.violation(headers);
            if (violation != null) {
                ctx.fail(new IllegalArgumentException(violation));
                return;
            }
        }
        for (Map.Entry<String, String> header : settings.defaultHeaders().entrySet()) {
            if (!headers.contains(header.getKey())) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        ctx.next();
    }
}

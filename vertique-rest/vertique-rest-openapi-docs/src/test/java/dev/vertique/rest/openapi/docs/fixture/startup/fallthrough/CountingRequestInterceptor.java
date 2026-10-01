// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.fallthrough;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;

/** A {@link RequestInterceptor} that counts its {@code onRequest} and {@code beforeRequest} calls and changes nothing. */
public final class CountingRequestInterceptor implements RequestInterceptor {

    private final FallThroughCounters counters;

    /**
     * Creates the interceptor.
     *
     * @param counters the counters it increments
     */
    public CountingRequestInterceptor(FallThroughCounters counters) {
        this.counters = counters;
    }

    @Override
    public void onRequest(RoutingContext rc) {
        counters.countOnRequest();
    }

    @Override
    public Future<Void> beforeRequest(RoutingContext rc) {
        counters.countBeforeRequest();
        return Future.succeededFuture();
    }
}

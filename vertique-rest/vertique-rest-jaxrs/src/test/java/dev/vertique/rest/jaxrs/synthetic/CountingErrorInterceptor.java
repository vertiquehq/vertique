// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link ErrorInterceptor} that counts, per request path, how many times it observed a
 * JAX-RS-routed failure. Only a resource route dispatches through this interceptor — a synthetic
 * operation's route deliberately bypasses it.
 */
public final class CountingErrorInterceptor implements ErrorInterceptor {

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    @Override
    public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
        counts.computeIfAbsent(rc.normalizedPath(), key -> new AtomicInteger()).incrementAndGet();
        return Future.succeededFuture(throwable);
    }

    /**
     * Returns how many times a failure for {@code path} was observed.
     *
     * @param path the normalized request path
     * @return the observed failure count, {@code 0} if none
     */
    public int count(String path) {
        AtomicInteger counter = counts.get(path);
        return counter != null ? counter.get() : 0;
    }
}

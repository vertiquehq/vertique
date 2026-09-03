// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.aop.Cacheable;
import dev.vertique.ratelimit.aop.RateLimited;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Handler fixture whose outcomes make the five-aspect composition observable. */
public class CompositionProbeServiceHandler implements ServiceHandler<CompositionProbeService> {

    private static final String MISS_SUCCESS_KEY = "miss-success";
    private static final String BREAKER_KEY = "breaker";

    private final ConcurrentMap<String, AtomicInteger> invocationsByKey = new ConcurrentHashMap<>();

    /** Creates the composition probe handler. */
    @Inject
    public CompositionProbeServiceHandler() {}

    /**
     * Applies the frozen outer-to-inner composition: quota, cache, resilience, then the handler.
     * The {@code miss-success} key fails twice before succeeding; the {@code breaker} key always
     * fails so the first logical call opens its circuit and the next call is rejected.
     *
     * @param key probe key
     * @return a failed future for the deterministic failure keys, otherwise a probe result
     */
    @Cacheable(
            name = "composition",
            key = {"0"},
            identity = CacheIdentity.NONE)
    @RateLimited(policy = "composition", subject = RateLimitSubject.NONE)
    @Resilient
    @CircuitBreaker(maxFailures = 1, resetTimeoutMs = 60_000)
    @Retry(maxRetries = 2, delayMs = 1, maxDelayMs = 1)
    public Future<CompositionProbeResult> probe(String key) {
        int invocation = invocationsByKey
                .computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (BREAKER_KEY.equals(key) || (MISS_SUCCESS_KEY.equals(key) && invocation <= 2)) {
            return Future.failedFuture("composition probe failure");
        }
        return Future.succeededFuture(new CompositionProbeResult(key, invocation));
    }

    /**
     * Returns the number of concrete handler invocations for a key.
     *
     * @param key probe key
     * @return concrete invocation count
     */
    public int attemptsFor(String key) {
        AtomicInteger attempts = invocationsByKey.get(key);
        return attempts == null ? 0 : attempts.get();
    }
}

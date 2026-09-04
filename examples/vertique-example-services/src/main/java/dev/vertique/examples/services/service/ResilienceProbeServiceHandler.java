// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Handler whose transient failures make named retry-policy precedence observable. */
public class ResilienceProbeServiceHandler implements ServiceHandler<ResilienceProbeService> {

    private final ConcurrentMap<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();

    /** Creates the probe handler. */
    @Inject
    public ResilienceProbeServiceHandler() {}

    /**
     * Fails the first four attempts for each key, then succeeds. The named {@code probe} retry
     * tier supplies five retries; without that tier the inline declaration supplies only two.
     *
     * @param key probe key
     * @return a failed future during the transient phase, then a successful result
     */
    @Resilient(policy = "probe")
    @Retry(maxRetries = 2, delayMs = 1, maxDelayMs = 1)
    public Future<String> probe(String key) {
        int attempt = attemptsByKey
                .computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (attempt < 5) {
            return Future.failedFuture("transient failure");
        }
        return Future.succeededFuture("ok:" + key);
    }

    /**
     * Returns the number of invocations observed for a key.
     *
     * @param key probe key
     * @return invocation count
     */
    public int attemptsFor(String key) {
        AtomicInteger attempts = attemptsByKey.get(key);
        return attempts == null ? 0 : attempts.get();
    }
}

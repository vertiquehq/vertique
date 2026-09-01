// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.redis.RedisClientShutdownStep;
import io.vertx.core.Future;
import java.util.Objects;

/**
 * Fences the CLUSTERED rate-limit runtime ahead of the shared Redis client close.
 *
 * <p>Application teardown runs {@link ApplicationShutdownStep}s in the <em>reverse</em> of {@link
 * dev.vertique.core.lifecycle.LifecycleOrdered#comparator()} startup order (see {@code
 * dev.vertique.application.VertiqueApplicationHandle#teardown()}) — so an {@link
 * LifecyclePhase#INFRA} step at a priority above {@link RedisClientShutdownStep#SHUTDOWN_PRIORITY}
 * runs <em>before</em> the shared Redis client closes. This mirrors {@code
 * vertique-cache-redis}'s {@code RedisCleanupLifecycle}, which fences the cache family's own Redis
 * consumers the same way.
 *
 * <p>Without this fence, {@code vertique-rate-limit-core}'s {@code RateLimitCoreModule}-contributed
 * {@link LifecyclePhase#CONFIGURE} shutdown step would close {@link RateLimiters} only
 * <em>after</em> the shared Redis client has already closed: {@code CONFIGURE} sorts ahead of
 * {@code INFRA} in startup order and therefore runs <em>after</em> it once that order is reversed
 * for teardown, letting an in-flight {@code consume(...)} race the client close.
 *
 * <p>{@link RateLimiters#close()} is idempotent, so whichever of this step or the core module's
 * {@code CONFIGURE}-phase step runs first actually closes the runtime; the other observes the same,
 * already-succeeded close future and becomes a no-op.
 */
final class RedisRateLimitFenceLifecycle implements ApplicationShutdownStep {

    private final RateLimiters rateLimiters;

    /**
     * Creates the fence step bound to the application-scoped rate-limit runtime.
     *
     * @param rateLimiters the runtime this step closes ahead of the shared Redis client
     */
    RedisRateLimitFenceLifecycle(RateLimiters rateLimiters) {
        this.rateLimiters = Objects.requireNonNull(rateLimiters, "rateLimiters");
    }

    /**
     * Returns the infrastructure lifecycle phase.
     *
     * @return {@link LifecyclePhase#INFRA}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.INFRA;
    }

    /**
     * Returns the priority that places this fence ahead of the shared Redis client close during
     * reverse-order teardown.
     *
     * @return {@link RedisClientShutdownStep#SHUTDOWN_PRIORITY} + 1
     */
    @Override
    public int priority() {
        return RedisClientShutdownStep.SHUTDOWN_PRIORITY + 1;
    }

    /**
     * Closes the rate-limit runtime, fencing any in-flight {@code consume(...)} before the shared
     * Redis client can close underneath it.
     *
     * @return {@link RateLimiters}'s idempotent close future
     */
    @Override
    public Future<Void> stop() {
        return rateLimiters.close();
    }
}

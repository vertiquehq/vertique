// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.redis.RedisClientShutdownStep;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Objects;
import java.util.function.Supplier;

/** Orders cleanup deregistration ahead of closing the shared Redis client registry. */
final class RedisCleanupLifecycle implements ApplicationShutdownStep {
    private final RedisCleanupJob job;
    private final Supplier<Future<Void>> unregister;
    private final Supplier<Future<Void>> closeRedis;

    /**
     * Creates the cleanup shutdown step with explicit lifecycle operation seams.
     *
     * @param job the cleanup job owned by this lifecycle step
     * @param unregister operation that stops cleanup dispatch registration
     * @param closeRedis operation that closes the shared Redis clients
     */
    RedisCleanupLifecycle(RedisCleanupJob job, Supplier<Future<Void>> unregister, Supplier<Future<Void>> closeRedis) {
        this.job = Objects.requireNonNull(job, "job");
        this.unregister = Objects.requireNonNull(unregister, "unregister");
        this.closeRedis = Objects.requireNonNull(closeRedis, "closeRedis");
    }

    /**
     * Returns the infrastructure lifecycle phase used for Redis-owned cleanup.
     *
     * @return {@link LifecyclePhase#INFRA}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.INFRA;
    }

    /**
     * Runs cleanup deregistration before the shared Redis close operation.
     *
     * @return a future completing after both ordered operations complete
     */
    @Override
    public Future<Void> stop() {
        Promise<Void> completion = Promise.promise();
        invoke(unregister).onComplete(unregisterResult -> {
            invoke(closeRedis).onComplete(closeResult -> {
                if (unregisterResult.failed()) {
                    completion.fail(unregisterResult.cause());
                } else if (closeResult.failed()) {
                    completion.fail(closeResult.cause());
                } else {
                    completion.complete();
                }
            });
        });
        return completion.future();
    }

    /** Invokes a lifecycle operation while converting synchronous failures into failed futures. */
    private static Future<Void> invoke(Supplier<Future<Void>> operation) {
        try {
            Future<Void> result = operation.get();
            return result == null ? Future.failedFuture("Lifecycle operation returned null") : result;
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    /**
     * Places this step ahead of the shared Redis client close during reverse teardown ordering.
     *
     * @return the priority immediately above the shared Redis close step
     */
    @Override
    public int priority() {
        return RedisClientShutdownStep.SHUTDOWN_PRIORITY + 1;
    }
}

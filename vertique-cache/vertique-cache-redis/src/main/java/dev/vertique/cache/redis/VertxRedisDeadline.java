// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.redis.RedisDeadline;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;

/** Package-private adapter from the provider deadline boundary to {@link RedisDeadline}. */
final class VertxRedisDeadline implements RedisDeadlineBoundary {
    private final Vertx vertx;

    VertxRedisDeadline(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public <T> Future<T> withDeadline(Future<T> backend, Duration deadline) {
        return RedisDeadline.withDeadline(vertx, backend, deadline);
    }
}

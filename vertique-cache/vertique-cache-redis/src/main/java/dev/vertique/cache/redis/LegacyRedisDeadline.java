// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Package-private deadline fallback retained for the established direct constructor. */
final class LegacyRedisDeadline implements RedisDeadlineBoundary {
    @Override
    public <T> Future<T> withDeadline(Future<T> backend, Duration deadline) {
        return backend.timeout(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }
}

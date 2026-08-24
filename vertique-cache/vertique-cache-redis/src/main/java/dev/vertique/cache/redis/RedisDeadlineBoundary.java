// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import io.vertx.core.Future;
import java.time.Duration;

/** Package-private deadline boundary that keeps timeout control deterministic in provider tests. */
@FunctionalInterface
interface RedisDeadlineBoundary {
    /**
     * Applies the configured asynchronous backend deadline.
     *
     * @param backend the backend operation to fence
     * @param deadline the positive timeout
     * @param <T> the backend result type
     * @return the deadline-fenced future
     */
    <T> Future<T> withDeadline(Future<T> backend, Duration deadline);
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.Response;
import java.util.List;

/** Package-private asynchronous command boundary for the Redis cache provider. */
interface RedisCommandClient {
    /**
     * Reads one Redis key.
     *
     * @param key the Redis key
     * @return the asynchronous Redis response
     */
    Future<Response> get(String key);

    /**
     * Executes a Redis {@code SET} command represented as command arguments.
     *
     * @param command the command arguments, excluding the command name
     * @return the asynchronous Redis response
     */
    Future<Response> set(List<String> command);

    /**
     * Executes a Redis {@code DEL} command represented as command arguments.
     *
     * @param command the command arguments, excluding the command name
     * @return the asynchronous Redis response
     */
    Future<Response> del(List<String> command);
}

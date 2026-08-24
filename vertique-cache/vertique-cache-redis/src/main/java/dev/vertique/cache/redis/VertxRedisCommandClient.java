// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import java.util.List;

/** Package-private adapter from the Vert.x Redis API to the provider command boundary. */
final class VertxRedisCommandClient implements RedisCommandClient {
    private final RedisAPI redis;

    private VertxRedisCommandClient(RedisAPI redis) {
        this.redis = redis;
    }

    static RedisCommandClient from(RedisClientRegistry clients, String connection) {
        return new VertxRedisCommandClient(RedisAPI.api(clients.client(connection)));
    }

    @Override
    public Future<Response> get(String key) {
        return redis.get(key);
    }

    @Override
    public Future<Response> set(List<String> command) {
        return redis.set(command);
    }

    @Override
    public Future<Response> del(List<String> command) {
        return redis.del(command);
    }
}

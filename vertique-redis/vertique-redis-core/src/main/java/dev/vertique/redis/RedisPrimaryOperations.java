// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisCluster;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.util.List;
import java.util.Objects;

/** Shared asynchronous seam for commands that must run on every Redis primary. */
public final class RedisPrimaryOperations {
    private final RedisCluster redis;

    /**
     * Constructs the primary-operation seam over a Redis cluster client.
     *
     * @param redis the cluster-capable client used to fan out commands to primary nodes
     */
    RedisPrimaryOperations(RedisCluster redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    /**
     * Scans every Redis primary with the supplied command arguments.
     *
     * @param args the Redis SCAN arguments
     * @return the unchanged response list from the cluster operation
     */
    public Future<List<Response>> scan(Object... args) {
        return redis.onAllMasterNodes(Request.cmd(Command.SCAN, args));
    }

    /**
     * Unlinks the supplied keys on every Redis primary.
     *
     * @param keys the Redis keys to unlink
     * @return the unchanged response list from the cluster operation
     */
    public Future<List<Response>> unlink(Object... keys) {
        return redis.onAllMasterNodes(Request.cmd(Command.UNLINK, keys));
    }
}

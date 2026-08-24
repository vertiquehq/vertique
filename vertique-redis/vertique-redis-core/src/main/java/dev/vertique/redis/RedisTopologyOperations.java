// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import io.vertx.core.Future;
import java.util.List;

/** Minimal asynchronous topology-aware Redis maintenance boundary. */
public interface RedisTopologyOperations {

    /**
     * Returns the current Redis primary nodes.
     *
     * @return the asynchronously discovered primary nodes
     */
    Future<List<RedisPrimaryNode>> primaryNodes();

    /**
     * Scans one primary node using a cursor owned by the caller.
     *
     * @param node the primary node to scan
     * @param cursor the opaque node-local cursor
     * @param count the requested scan count
     * @return the asynchronous scan page
     */
    Future<RedisScanPage> scan(RedisPrimaryNode node, String cursor, int count);

    /**
     * Unlinks keys from one primary node.
     *
     * @param node the primary node receiving the command
     * @param keys the keys to unlink
     * @return the asynchronous number of unlinked keys
     */
    Future<Long> unlink(RedisPrimaryNode node, List<String> keys);
}

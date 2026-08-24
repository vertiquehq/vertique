// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Internal Lettuce adapter for per-primary Redis maintenance commands. */
final class LettuceRedisTopologyOperations implements RedisTopologyOperations {
    private final RedisClusterClient client;
    private final Object connectionLock = new Object();
    private CompletableFuture<StatefulRedisClusterConnection<String, String>> connection;

    LettuceRedisTopologyOperations(RedisClusterClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Future<List<RedisPrimaryNode>> primaryNodes() {
        return adapt(connection().thenApply(cluster -> cluster.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM))
                .map(node -> new RedisPrimaryNode(node.getNodeId()))
                .toList()));
    }

    @Override
    public Future<RedisScanPage> scan(RedisPrimaryNode node, String cursor, int count) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(cursor, "cursor");
        if (count < 1) {
            return Future.failedFuture("count must be positive");
        }
        return adapt(connection().thenCompose(cluster -> cluster.getConnectionAsync(node.id())
                .thenCompose(primary -> scan(primary, cursor, count))));
    }

    @Override
    public Future<Long> unlink(RedisPrimaryNode node, List<String> keys) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            return Future.succeededFuture(0L);
        }
        return adapt(connection().thenCompose(cluster -> cluster.getConnectionAsync(node.id())
                .thenCompose(primary -> primary.async().unlink(keys.toArray(String[]::new)))));
    }

    private static CompletionStage<RedisScanPage> scan(
            StatefulRedisConnection<String, String> primary, String cursor, int count) {
        return primary.async()
                .scan(ScanCursor.of(cursor), ScanArgs.Builder.limit(count))
                .thenApply(result -> new RedisScanPage(result.getCursor(), result.getKeys(), result.isFinished()));
    }

    private CompletableFuture<StatefulRedisClusterConnection<String, String>> connection() {
        synchronized (connectionLock) {
            if (connection == null) {
                try {
                    // Lettuce requires the topology view to be initialized before async connect.
                    client.getPartitions();
                    connection = client.connectAsync(io.lettuce.core.codec.StringCodec.UTF8);
                } catch (RuntimeException failure) {
                    connection = CompletableFuture.failedFuture(failure);
                }
            }
            return connection;
        }
    }

    private static <T> Future<T> adapt(CompletionStage<T> stage) {
        Promise<T> promise = Promise.promise();
        stage.whenComplete((value, failure) -> {
            if (failure != null) {
                promise.fail(failure);
            } else {
                promise.complete(value);
            }
        });
        return promise.future();
    }
}

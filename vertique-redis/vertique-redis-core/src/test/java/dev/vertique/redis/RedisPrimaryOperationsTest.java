// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisCluster;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Characterizes the minimal asynchronous shared seam for commands on every Redis primary. */
class RedisPrimaryOperationsTest {

    @Test
    @DisplayName("SCAN fans out through RedisCluster and preserves one response per primary")
    void scanFansOutToEveryPrimaryAndPreservesTraversalOrder() {
        // Given: a deterministic topology response in primary traversal order.
        Response primaryA = response("primary-a");
        Response primaryB = response("primary-b");
        Response primaryC = response("primary-c");
        FakeRedisCluster cluster = FakeRedisCluster.successful(primaryA, primaryB, primaryC);
        RedisPrimaryOperations operations = new RedisPrimaryOperations(cluster.client());

        // When: the shared seam issues a SCAN operation.
        Future<List<Response>> result = operations.scan("0", "MATCH", "cache:v1:*", "COUNT", 100);

        // Then: the Vert.x topology call receives SCAN and returns each primary response unchanged.
        assertTrue(result.succeeded());
        assertEquals(1, cluster.onAllMasterNodesCalls());
        assertEquals(Command.SCAN, cluster.requests().getFirst().command());
        assertEquals(List.of(primaryA, primaryB, primaryC), result.result());
    }

    @Test
    @DisplayName("UNLINK fans out through RedisCluster and preserves one response per primary")
    void unlinkFansOutToEveryPrimaryAndPreservesTraversalOrder() {
        // Given: a deterministic topology response in primary traversal order.
        Response primaryA = response("primary-a");
        Response primaryB = response("primary-b");
        FakeRedisCluster cluster = FakeRedisCluster.successful(primaryA, primaryB);
        RedisPrimaryOperations operations = new RedisPrimaryOperations(cluster.client());

        // When: the shared seam issues an UNLINK operation.
        Future<List<Response>> result = operations.unlink("cache:v1:old-a", "cache:v1:old-b");

        // Then: the Vert.x topology call receives UNLINK and returns one response for each primary.
        assertTrue(result.succeeded());
        assertEquals(1, cluster.onAllMasterNodesCalls());
        assertEquals(Command.UNLINK, cluster.requests().getFirst().command());
        assertEquals(List.of(primaryA, primaryB), result.result());
    }

    @Test
    @DisplayName("a primary operation failure propagates without replacing the original cause")
    void primaryOperationFailurePropagatesWithoutSubstitution() {
        // Given: the topology layer fails while traversing primaries.
        IllegalStateException failure = new IllegalStateException("primary operation failed");
        FakeRedisCluster cluster = FakeRedisCluster.failed(failure);
        RedisPrimaryOperations operations = new RedisPrimaryOperations(cluster.client());

        // When: the shared seam issues SCAN.
        Future<List<Response>> result = operations.scan("0", "MATCH", "cache:v1:*");

        // Then: the returned future fails with the exact topology failure.
        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertEquals(1, cluster.onAllMasterNodesCalls());
    }

    private static Response response(String value) {
        return (Response) Proxy.newProxyInstance(
                Response.class.getClassLoader(),
                new Class<?>[] {Response.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> value;
                    case "hashCode" -> value.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("unexpected Response method: " + method);
                });
    }

    private static final class FakeRedisCluster implements InvocationHandler {
        private final Future<List<Response>> result;
        private final List<Request> requests = new ArrayList<>();

        private FakeRedisCluster(Future<List<Response>> result) {
            this.result = result;
        }

        static FakeRedisCluster successful(Response... responses) {
            return new FakeRedisCluster(Future.succeededFuture(List.of(responses)));
        }

        static FakeRedisCluster failed(Throwable failure) {
            return new FakeRedisCluster(Future.failedFuture(failure));
        }

        RedisCluster client() {
            return (RedisCluster) Proxy.newProxyInstance(
                    RedisCluster.class.getClassLoader(), new Class<?>[] {RedisCluster.class}, this);
        }

        List<Request> requests() {
            return List.copyOf(requests);
        }

        int onAllMasterNodesCalls() {
            return requests.size();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "onAllMasterNodes" -> {
                    requests.add((Request) args[0]);
                    yield result;
                }
                case "toString" -> "fake-redis-cluster";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("unexpected RedisCluster method: " + method);
            };
        }
    }
}

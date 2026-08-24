// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.redis.RedisPrimaryNode;
import dev.vertique.redis.RedisScanPage;
import dev.vertique.redis.RedisTopologyOperations;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Characterizes the policy-neutral topology-aware Redis maintenance seam. */
class RedisTopologyOperationsContractTest {
    private static final RedisPrimaryNode NODE_A = new RedisPrimaryNode("primary-a");
    private static final RedisPrimaryNode NODE_B = new RedisPrimaryNode("primary-b");

    @Test
    @DisplayName("primaryNodes exposes stable primary identities")
    void primaryNodesExposeStableNodes() throws Exception {
        RecordingTopology topology = new RecordingTopology();

        List<RedisPrimaryNode> first = await(topology.primaryNodes());
        List<RedisPrimaryNode> second = await(topology.primaryNodes());

        assertEquals(List.of(NODE_A, NODE_B), first);
        assertEquals(first, second);
        assertEquals(List.of("primary-a", "primary-b"), first.stream().map(RedisPrimaryNode::id).toList());
    }

    @Test
    @DisplayName("scan preserves independent opaque cursor and page state per node")
    void scanCarriesIndependentCursorAndPagePerNode() throws Exception {
        RecordingTopology topology = new RecordingTopology();
        RedisScanPage nodeAFirst = new RedisScanPage("a-next", List.of("a-1"), false);
        RedisScanPage nodeBFirst = new RedisScanPage("0", List.of("b-1"), true);
        RedisScanPage nodeASecond = new RedisScanPage("0", List.of("a-2"), true);
        topology.pages.put(new ScanRequest(NODE_A, "0", 128), nodeAFirst);
        topology.pages.put(new ScanRequest(NODE_B, "0", 128), nodeBFirst);
        topology.pages.put(new ScanRequest(NODE_A, "a-next", 128), nodeASecond);

        RedisScanPage actualAFirst = await(topology.scan(NODE_A, "0", 128));
        RedisScanPage actualBFirst = await(topology.scan(NODE_B, "0", 128));
        RedisScanPage actualASecond = await(topology.scan(NODE_A, actualAFirst.cursor(), 128));

        assertEquals(nodeAFirst, actualAFirst);
        assertEquals(nodeBFirst, actualBFirst);
        assertEquals(nodeASecond, actualASecond);
        assertEquals(
                List.of(
                        new ScanRequest(NODE_A, "0", 128),
                        new ScanRequest(NODE_B, "0", 128),
                        new ScanRequest(NODE_A, "a-next", 128)),
                topology.scanRequests);
    }

    @Test
    @DisplayName("unlink targets the supplied primary node and exact key list")
    void unlinkTargetsSuppliedNode() throws Exception {
        RecordingTopology topology = new RecordingTopology();

        assertEquals(2L, await(topology.unlink(NODE_A, List.of("a-1", "a-2"))));
        assertEquals(1L, await(topology.unlink(NODE_B, List.of("b-1"))));

        assertEquals(
                List.of(
                        new UnlinkRequest(NODE_A, List.of("a-1", "a-2")),
                        new UnlinkRequest(NODE_B, List.of("b-1"))),
                topology.unlinkRequests);
    }

    @Test
    @DisplayName("topology operation failures propagate without translation")
    void failuresPropagate() throws Exception {
        RuntimeException primaryFailure = new RuntimeException("primary discovery failed");
        RuntimeException scanFailure = new RuntimeException("scan failed");
        RuntimeException unlinkFailure = new RuntimeException("unlink failed");
        RecordingTopology topology = new RecordingTopology();
        topology.primaryNodesFailure = primaryFailure;
        topology.scanFailure = scanFailure;
        topology.unlinkFailure = unlinkFailure;

        Future<List<RedisPrimaryNode>> primaryNodes = topology.primaryNodes();
        Future<RedisScanPage> scan = topology.scan(NODE_A, "0", 128);
        Future<Long> unlink = topology.unlink(NODE_A, List.of("key"));

        assertTrue(primaryNodes.failed());
        assertTrue(scan.failed());
        assertTrue(unlink.failed());
        assertSame(primaryFailure, primaryNodes.cause());
        assertSame(scanFailure, scan.cause());
        assertSame(unlinkFailure, unlink.cause());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private record ScanRequest(RedisPrimaryNode node, String cursor, int count) {}

    private record UnlinkRequest(RedisPrimaryNode node, List<String> keys) {}

    private static final class RecordingTopology implements RedisTopologyOperations {
        private final List<RedisPrimaryNode> nodes = List.of(NODE_A, NODE_B);
        private final Map<ScanRequest, RedisScanPage> pages = new HashMap<>();
        private final List<ScanRequest> scanRequests = new ArrayList<>();
        private final List<UnlinkRequest> unlinkRequests = new ArrayList<>();
        private RuntimeException primaryNodesFailure;
        private RuntimeException scanFailure;
        private RuntimeException unlinkFailure;

        @Override
        public Future<List<RedisPrimaryNode>> primaryNodes() {
            return primaryNodesFailure == null
                    ? Future.succeededFuture(List.copyOf(nodes))
                    : Future.failedFuture(primaryNodesFailure);
        }

        @Override
        public Future<RedisScanPage> scan(RedisPrimaryNode node, String cursor, int count) {
            if (scanFailure != null) {
                return Future.failedFuture(scanFailure);
            }
            ScanRequest request = new ScanRequest(node, cursor, count);
            scanRequests.add(request);
            RedisScanPage page = pages.get(request);
            return page == null ? Future.failedFuture("missing scripted scan page: " + request) : Future.succeededFuture(page);
        }

        @Override
        public Future<Long> unlink(RedisPrimaryNode node, List<String> keys) {
            if (unlinkFailure != null) {
                return Future.failedFuture(unlinkFailure);
            }
            unlinkRequests.add(new UnlinkRequest(node, List.copyOf(keys)));
            return Future.succeededFuture((long) keys.size());
        }
    }
}

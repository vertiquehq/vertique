// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisCluster;
import io.vertx.redis.client.RedisClusterConnectOptions;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Verifies shared Redis client identity, lazy ownership, close lifecycle, and secret hygiene. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RedisClientLifecycleTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(2);
    private static final String PROFILE_NAME = "primary";

    @Test
    @DisplayName("same profile shares one client and closes it idempotently")
    void sameProfileSharesClientAndClosesIdempotently() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisClientRegistry lazyRegistry =
                new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile())));

        try {
            // Given: a validated profile.
            Map<String, Redis> createdClients = clientsOf(lazyRegistry);

            // When: two consumers request the same profile.
            Redis first = lazyRegistry.client(PROFILE_NAME);
            Redis second = lazyRegistry.client(PROFILE_NAME);

            // Then: both consumers receive one shared client and no connection was requested.
            assertSame(first, second, "one client must be reused for a profile");
            assertEquals(1, createdClients.size(), "only one client must be registered");
            await(lazyRegistry.close());

            // Given: a separate registry contains a deterministic fake with an observable close.
            RedisClientRegistry registry =
                    new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile())));
            Map<String, Redis> fakeClients = clientsOf(registry);
            RecordingRedis fakeClient = new RecordingRedis(PROFILE_NAME, new ArrayList<>());
            fakeClients.put(PROFILE_NAME, fakeClient);

            // When: application shutdown is requested twice.
            await(registry.close());
            await(registry.close());

            // Then: client close is idempotent.
            assertEquals(1, fakeClient.closeCalls(), "repeated shutdown must not close a client twice");
        } finally {
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("client construction is deferred until a profile is requested")
    void clientConstructionIsLazy() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisClientRegistry registry = new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile())));

        try {
            Map<String, Redis> clients = clientsOf(registry);

            // Given: startup has constructed the registry but no consumer has requested Redis.
            assertTrue(clients.isEmpty(), "startup must not construct a Redis client");

            // When: the profile is first requested.
            Redis client = registry.client(PROFILE_NAME);

            // Then: exactly one client is constructed at the request boundary.
            assertSame(client, clients.get(PROFILE_NAME));
            assertEquals(1, clients.size());
        } finally {
            await(registry.close());
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("primary operations reuse one cluster client and registry close handles it")
    void primaryOperationsReuseOneClusterClientAndRegistryCloseHandlesIt() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisClientRegistry registry = new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile())));

        try {
            // When: the same profile requests its primary-operation seam twice.
            RedisPrimaryOperations first = registry.primaryOperations(PROFILE_NAME);
            RedisPrimaryOperations second = registry.primaryOperations(PROFILE_NAME);
            Map<String, Redis> clusterClients = clusterClientsOf(registry);

            // Then: one cluster-capable client and seam are reused without connecting to Redis.
            assertSame(first, second, "one primary-operation seam must be reused for a profile");
            assertEquals(1, clusterClients.size(), "only one cluster client must be registered");
            Redis clusterClient = clusterClients.get(PROFILE_NAME);
            assertNotNull(clusterClient, "the primary-operation client must be registered");
            assertDoesNotThrow(() -> RedisCluster.create(clusterClient), "the client must be cluster-capable");

            // When: application shutdown is requested twice.
            Future<Void> firstClose = registry.close();
            Future<Void> secondClose = registry.close();

            // Then: registry-owned cluster client close uses the shared idempotent future.
            assertSame(firstClose, secondClose, "registry close must return one shared future");
            await(firstClose);
        } finally {
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("cluster connect options preserve profile endpoints and copied connection settings")
    void clusterConnectOptionsPreserveProfileEndpointsAndConnectionSettings() {
        RedisConnectionConfig profile = new RedisConnectionConfig(
                PROFILE_NAME,
                List.of("redis://redis-a:6379", "redis://redis-b:6379"),
                "cache-user",
                "redis-password",
                false,
                500,
                8,
                100);
        RedisOptions options = new RedisOptions()
                .setUser(profile.username())
                .setPassword(profile.passwordSecret())
                .setMaxNestedArrays(17)
                .setProtocolNegotiation(false)
                .setMaxWaitingHandlers(23);

        // When: the cluster-connect callback copies the client options for the profile.
        RedisClusterConnectOptions connectOptions =
                RedisClientRegistry.copyClusterConnectOptions(options, profile.endpoints());

        // Then: profile seed endpoints and connection settings are retained in the callback options.
        assertEquals(profile.endpoints(), connectOptions.getEndpoints());
        assertEquals(options.getUser(), connectOptions.getUser());
        assertEquals(options.getPassword(), connectOptions.getPassword());
        assertEquals(options.getMaxNestedArrays(), connectOptions.getMaxNestedArrays());
        assertEquals(options.isProtocolNegotiation(), connectOptions.isProtocolNegotiation());
        assertEquals(options.getMaxWaitingHandlers(), connectOptions.getMaxWaitingHandlers());
    }

    @Test
    @DisplayName("startup credential material is redacted from diagnostics")
    void startupCredentialResolutionDoesNotExposeSecretMaterial() {
        String secret = "redis-password-SENTINEL-7f19";
        RedisConnectionConfig profile = new RedisConnectionConfig(
                PROFILE_NAME, List.of("redis://redis:6379"), "cache-user", secret, false, 500, 8, 100);

        // Then: any startup diagnostic representation must redact the secret value.
        assertFalse(profile.toString().contains(secret), "startup diagnostics must not log secret material");
    }

    @Test
    @DisplayName("created profile clients close in order and only once")
    void closeIsOrderedAndIdempotent() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisConnectionConfig replica = new RedisConnectionConfig(
                "replica", List.of("redis://redis-replica:6379"), null, null, false, 500, 8, 100);
        RedisClientRegistry registry =
                new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile(), replica)));
        List<String> closeOrder = new ArrayList<>();
        Map<String, Redis> clients = clientsOf(registry);
        Map<String, Redis> clusterClients = clusterClientsOf(registry);
        clients.put(PROFILE_NAME, new RecordingRedis(PROFILE_NAME, closeOrder));
        clients.put("replica", new RecordingRedis("replica", closeOrder));
        clusterClients.put(PROFILE_NAME, new RecordingRedis(PROFILE_NAME + "-cluster", closeOrder));
        clusterClients.put("replica", new RecordingRedis("replica-cluster", closeOrder));

        try {
            // When: application shutdown is requested twice.
            await(registry.close());
            await(registry.close());

            // Then: profile clients close in profile order and repeated shutdown is a no-op.
            assertEquals(List.of(PROFILE_NAME, PROFILE_NAME + "-cluster", "replica", "replica-cluster"), closeOrder);
        } finally {
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("registry shutdown leaves the host-owned Vertx instance open")
    void closeDoesNotCloseHostVertx() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisClientRegistry registry = new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile())));
        clientsOf(registry).put(PROFILE_NAME, new RecordingRedis(PROFILE_NAME, new ArrayList<>()));

        try {
            await(registry.close());

            CountDownLatch hostStillOpen = new CountDownLatch(1);
            vertx.runOnContext(ignored -> hostStillOpen.countDown());
            assertTrue(
                    hostStillOpen.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "registry shutdown must not close host-owned Vertx");
        } finally {
            await(vertx.close());
        }
    }

    private static RedisConnectionConfig profile() {
        return new RedisConnectionConfig(PROFILE_NAME, List.of("redis://redis:6379"), null, null, false, 500, 8, 100);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Redis> clientsOf(RedisClientRegistry registry) throws ReflectiveOperationException {
        Field clients = RedisClientRegistry.class.getDeclaredField("clients");
        clients.setAccessible(true);
        return (Map<String, Redis>) clients.get(registry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Redis> clusterClientsOf(RedisClientRegistry registry)
            throws ReflectiveOperationException {
        Field clients = RedisClientRegistry.class.getDeclaredField("clusterClients");
        clients.setAccessible(true);
        return (Map<String, Redis>) clients.get(registry);
    }

    private static void await(Future<Void> future) throws Exception {
        future.toCompletionStage().toCompletableFuture().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static final class RecordingRedis implements Redis {
        private final String name;
        private final List<String> closeOrder;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private RecordingRedis(String name, List<String> closeOrder) {
            this.name = name;
            this.closeOrder = closeOrder;
        }

        @Override
        public Future<RedisConnection> connect() {
            return Future.failedFuture("test fake does not connect");
        }

        @Override
        public Future<Void> close() {
            closeCalls.incrementAndGet();
            closeOrder.add(name);
            return Future.succeededFuture();
        }

        @Override
        public Future<Response> send(Request request) {
            return Future.failedFuture("test fake does not send Redis commands");
        }

        @Override
        public Future<List<Response>> batch(List<Request> requests) {
            return Future.failedFuture("test fake does not send Redis commands");
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }
}

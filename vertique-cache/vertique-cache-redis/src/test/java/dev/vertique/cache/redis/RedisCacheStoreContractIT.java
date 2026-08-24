// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.REDIS_CONFIG;
import static dev.vertique.cache.redis.RedisTestFixtures.REGION;
import static dev.vertique.cache.redis.RedisTestFixtures.await;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static dev.vertique.cache.redis.RedisTestFixtures.profiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.redis.RedisClientRegistry;
import dev.vertique.redis.RedisConnectionConfig;
import dev.vertique.redis.RedisConnectionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Verifies the Redis provider contract against the pinned Redis server image. */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RedisCacheStoreContractIT {
    private static final String REDIS_IMAGE =
            "redis:7.2.4-alpine@sha256:c8bb255c3559b3e458766db810aa7b3c7af1235b204cfdb304e79ff388fe1a5a";
    private static final CacheKey KEY = new CacheKey(REGION, "NONE", "alice");
    private static final CacheKey OTHER_KEY = new CacheKey(REGION, "NONE", "bob");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static RedisClientRegistry registry;
    private static RedisCacheStore store;
    private static RedisAPI commands;
    private static Vertx vertx;

    @BeforeAll
    static void setUp(Vertx hostVertx, io.vertx.junit5.VertxTestContext context) throws Exception {
        vertx = hostVertx;
        RedisConnectionConfig connection = new RedisConnectionConfig(
                "primary",
                List.of("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)),
                null,
                null,
                false,
                1_000,
                4,
                100);
        registry = new RedisClientRegistry(hostVertx, new RedisConnectionsConfig(List.of(connection)));
        store = new RedisCacheStore(
                registry, REDIS_CONFIG, cacheConfig(), profiles("vertx", new ObjectMapper()), hostVertx);
        commands = RedisAPI.api(registry.client("primary"));
        await(commands.ping(List.of()));
        context.completeNow();
    }

    @AfterEach
    void clearLogicalRegion() throws Exception {
        if (store != null) {
            await(store.clear(REGION));
        }
    }

    @AfterAll
    static void tearDown(io.vertx.junit5.VertxTestContext context) {
        if (registry == null) {
            context.completeNow();
            return;
        }
        registry.close().onComplete(result -> {
            if (result.failed()) {
                context.failNow(result.cause());
            } else {
                context.completeNow();
            }
        });
    }

    @Test
    @DisplayName("returns a miss for an absent Redis entry")
    void getMissReturnsEmpty() throws Exception {
        assertEquals(Optional.empty(), await(store.get(KEY, Profile.class)));
    }

    @Test
    @DisplayName("attaches a finite expiration to a Redis write")
    void finiteTtlExpiresEntry() throws Exception {
        await(store.put(KEY, new Profile("Alice", 3), Profile.class, Duration.ofMillis(100)));
        String generation = generationToken();
        String physicalKey = RedisCacheKey.entry(KEY, generation, REDIS_CONFIG, cacheConfig());
        Response ttl = await(commands.pttl(physicalKey));

        assertNotNull(ttl);
        assertTrue(ttl.toLong() > 0, "finite writes must use a positive Redis millisecond TTL");
        assertEquals(Optional.empty(), await(awaitMiss(KEY)));
    }

    @Test
    @DisplayName("writes with zero TTL without an expiration")
    void zeroTtlDoesNotExpire() throws Exception {
        await(store.put(KEY, new Profile("Alice", 3), Profile.class, Duration.ZERO));
        String generation = generationToken();
        String physicalKey = RedisCacheKey.entry(KEY, generation, REDIS_CONFIG, cacheConfig());
        Response ttl = await(commands.pttl(physicalKey));

        assertNotNull(ttl);
        assertEquals(-1L, ttl.toLong(), "zero-TTL writes must not attach PX expiration");
        assertEquals(Optional.of(new Profile("Alice", 3)), await(store.get(KEY, Profile.class)));
    }

    @Test
    @DisplayName("evicts only the selected physical key")
    void evictRemovesExactKey() throws Exception {
        Profile alice = new Profile("Alice", 3);
        Profile bob = new Profile("Bob", 4);
        await(store.put(KEY, alice, Profile.class, Duration.ZERO));
        await(store.put(OTHER_KEY, bob, Profile.class, Duration.ZERO));

        await(store.evict(KEY));

        assertEquals(Optional.empty(), await(store.get(KEY, Profile.class)));
        assertEquals(Optional.of(bob), await(store.get(OTHER_KEY, Profile.class)));
    }

    @Test
    @DisplayName("clear replaces the generation with an opaque token")
    void generationClearUsesOpaqueToken() throws Exception {
        await(store.put(KEY, new Profile("Alice", 3), Profile.class, Duration.ZERO));
        String before = generationToken();

        await(store.clear(REGION));

        String after = generationToken();
        assertNotEquals(before, after);
        assertTrue(!after.isBlank(), "the replacement generation must be non-blank");
        assertEquals(Optional.empty(), await(store.get(KEY, Profile.class)));
    }

    @Test
    @DisplayName("recreating generation state cannot make an old entry reachable")
    void generationKeyRecreationCannotResurrectOldEntries() throws Exception {
        await(store.put(KEY, new Profile("Alice", 3), Profile.class, Duration.ZERO));
        String oldGeneration = generationToken();
        String generationKey = RedisCacheKey.generation(REGION, REDIS_CONFIG, cacheConfig());
        String oldPhysicalKey = RedisCacheKey.entry(KEY, oldGeneration, REDIS_CONFIG, cacheConfig());

        await(commands.del(List.of(generationKey)));
        assertEquals(Optional.empty(), await(store.get(KEY, Profile.class)));

        String recreatedGeneration = generationToken();
        assertNotEquals(oldGeneration, recreatedGeneration);
        assertNotNull(await(commands.get(oldPhysicalKey)), "old physical data may remain for cleanup");
    }

    @Test
    @DisplayName("an in-flight old-generation lookup may complete old but later lookup uses new generation")
    void inFlightReadMayReturnOldValueButLaterReadUsesNewGeneration() throws Exception {
        await(store.put(KEY, new Profile("Alice", 3), Profile.class, Duration.ZERO));
        BarrierRedisCommandClient barrier =
                new BarrierRedisCommandClient(VertxRedisCommandClient.from(registry, REDIS_CONFIG.connection()));
        RedisCacheStore blockedStore = new RedisCacheStore(
                barrier,
                REDIS_CONFIG,
                cacheConfig(),
                profiles("vertx", new ObjectMapper()),
                new VertxRedisDeadline(vertx));

        Future<Optional<Object>> oldLookup = blockedStore.get(KEY, Profile.class);
        assertTrue(barrier.awaitEntryReadStarted(), "lookup must reach the controllable entry read");
        await(store.clear(REGION));
        barrier.releaseEntryRead();

        assertEquals(Optional.of(new Profile("Alice", 3)), await(oldLookup));
        assertEquals(Optional.empty(), await(store.get(KEY, Profile.class)));
    }

    @Test
    @DisplayName("round-trips an equivalent value through Redis")
    void putThenGetReturnsEquivalentValue() throws Exception {
        Profile expected = new Profile("Alice", 3);

        await(store.put(KEY, expected, Profile.class, Duration.ZERO));

        assertEquals(Optional.of(expected), await(store.get(KEY, Profile.class)));
    }

    private static String generationToken() throws Exception {
        Response response = await(commands.get(RedisCacheKey.generation(REGION, REDIS_CONFIG, cacheConfig())));
        assertNotNull(response, "Redis generation state must exist");
        return response.toString();
    }

    private static Future<Optional<Object>> awaitMiss(CacheKey key) {
        Promise<Optional<Object>> result = Promise.promise();
        pollForMiss(key, result);
        return result.future();
    }

    private static void pollForMiss(CacheKey key, Promise<Optional<Object>> result) {
        store.get(key, Profile.class).onComplete(read -> {
            if (read.failed()) {
                result.tryFail(read.cause());
            } else if (read.result().isEmpty()) {
                result.tryComplete(Optional.empty());
            } else {
                vertx.setTimer(20, ignored -> pollForMiss(key, result));
            }
        });
    }

    record Profile(String name, int score) {}

    private static final class BarrierRedisCommandClient implements RedisCommandClient {
        private final RedisCommandClient delegate;
        private final CountDownLatch entryReadStarted = new CountDownLatch(1);
        private Promise<Response> blockedRead;
        private String blockedKey;

        private BarrierRedisCommandClient(RedisCommandClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Future<Response> get(String key) {
            if (!key.endsWith(":generation")) {
                blockedKey = key;
                blockedRead = Promise.promise();
                entryReadStarted.countDown();
                return blockedRead.future();
            }
            return delegate.get(key);
        }

        @Override
        public Future<Response> set(List<String> command) {
            return delegate.set(command);
        }

        @Override
        public Future<Response> del(List<String> command) {
            return delegate.del(command);
        }

        private boolean awaitEntryReadStarted() throws InterruptedException {
            return entryReadStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseEntryRead() {
            delegate.get(blockedKey).onComplete(read -> {
                if (read.succeeded()) {
                    blockedRead.tryComplete(read.result());
                } else {
                    blockedRead.tryFail(read.cause());
                }
            });
        }
    }
}

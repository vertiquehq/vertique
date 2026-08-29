// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Verifies local bounded-store expiration, isolation, and logical-region invalidation. */
class CaffeineCacheStoreTest {
    private static final CacheRegion REGION = new CacheRegion("cache", "users", 2);
    private static final ResolvedCacheKey KEY = new ResolvedCacheKey(REGION, "i2:N", "42");

    @Test
    void finiteTtlExpiresAndZeroTtlIsPersistent() {
        AtomicLong now = new AtomicLong(100);
        CaffeineCacheStore store = new CaffeineCacheStore(
                CacheConfig.defaults(), new DefaultJsonMapperProfileRegistry(Set.of()), now::get);

        await(put(store, KEY, "temporary", String.class, Duration.ofNanos(10)));
        now.set(109);
        assertEquals(Optional.of("temporary"), await(get(store, KEY, String.class)));
        now.set(110);
        assertEquals(Optional.empty(), await(get(store, KEY, String.class)));

        await(put(store, KEY, "persistent", String.class, Duration.ZERO));
        assertEquals(Optional.of("persistent"), await(get(store, KEY, String.class)));
    }

    @Test
    void clearRemovesOnlyTheSelectedRegion() {
        CaffeineCacheStore store = new CaffeineCacheStore(CacheConfig.defaults());
        ResolvedCacheKey other = new ResolvedCacheKey(new CacheRegion("cache", "orders", 2), "i2:N", "42");
        await(put(store, KEY, "user", String.class, Duration.ZERO));
        await(put(store, other, "order", String.class, Duration.ZERO));

        await(store.clear(REGION));

        assertTrue(await(get(store, KEY, String.class)).isEmpty());
        assertEquals(Optional.of("order"), await(get(store, other, String.class)));
    }

    @Test
    void storesOneBoundPerLogicalRegion() {
        CacheConfig config =
                new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 1, 100, Map.of());
        CaffeineCacheStore store = new CaffeineCacheStore(config);
        ResolvedCacheKey secondUser = new ResolvedCacheKey(REGION, "i2:N", "43");
        ResolvedCacheKey order = new ResolvedCacheKey(new CacheRegion("cache", "orders", 2), "i2:N", "42");

        await(put(store, KEY, "user-42", String.class, Duration.ZERO));
        await(put(store, secondUser, "user-43", String.class, Duration.ZERO));
        await(put(store, order, "order-42", String.class, Duration.ZERO));

        assertTrue(await(get(store, KEY, String.class)).isEmpty());
        assertEquals(Optional.of("user-43"), await(get(store, secondUser, String.class)));
        assertEquals(Optional.of("order-42"), await(get(store, order, String.class)));
    }

    @Test
    void returnsJsonDefensiveCopiesForMutableValues() {
        CaffeineCacheStore store = new CaffeineCacheStore(CacheConfig.defaults());
        MutableValue original = new MutableValue("before", List.of("one"));

        await(put(store, KEY, original, MutableValue.class, Duration.ZERO));
        MutableValue first =
                (MutableValue) await(get(store, KEY, MutableValue.class)).orElseThrow();
        first.tags().add("changed");
        MutableValue second =
                (MutableValue) await(get(store, KEY, MutableValue.class)).orElseThrow();

        assertEquals(new MutableValue("before", List.of("one")), second);
    }

    @Test
    void disabledAndNullWritesRemainMisses() {
        CacheConfig disabled =
                new CacheConfig(false, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 10, 100, Map.of());
        CaffeineCacheStore store = new CaffeineCacheStore(disabled);

        await(put(store, KEY, "ignored", String.class, Duration.ZERO));
        await(put(store, KEY, null, String.class, Duration.ZERO));

        assertTrue(await(get(store, KEY, String.class)).isEmpty());
    }

    private static Future<Optional<Object>> get(CaffeineCacheStore store, ResolvedCacheKey key, Class<?> type) {
        return store.get(key, new CacheValueDescriptor(type, "vertx"));
    }

    private static Future<Void> put(
            CaffeineCacheStore store, ResolvedCacheKey key, Object value, Class<?> type, Duration ttl) {
        return store.put(key, new CacheValueDescriptor(type, "vertx"), value, ttl);
    }

    private static <T> T await(io.vertx.core.Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    private record MutableValue(String name, List<String> tags) {
        private MutableValue {
            tags = new java.util.ArrayList<>(tags);
        }
    }
}

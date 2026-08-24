// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.injvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Verifies local bounded-store expiration, isolation, and logical-region invalidation. */
class CaffeineCacheStoreTest {
    private static final CacheRegion REGION = new CacheRegion("cache", "users", 1);
    private static final CacheKey KEY = new CacheKey(REGION, "NONE", "42");

    @Test
    void finiteTtlExpiresAndZeroTtlIsPersistent() {
        AtomicLong now = new AtomicLong(100);
        CaffeineCacheStore store = new CaffeineCacheStore(
                CacheConfig.defaults(), new DefaultJsonMapperProfileRegistry(Set.of()), now::get);

        await(store.put(KEY, "temporary", String.class, Duration.ofNanos(10)));
        now.set(109);
        assertEquals(Optional.of("temporary"), await(store.get(KEY, String.class)));
        now.set(110);
        assertEquals(Optional.empty(), await(store.get(KEY, String.class)));

        await(store.put(KEY, "persistent", String.class, Duration.ZERO));
        assertEquals(Optional.of("persistent"), await(store.get(KEY, String.class)));
    }

    @Test
    void clearRemovesOnlyTheSelectedRegion() {
        CaffeineCacheStore store = new CaffeineCacheStore(CacheConfig.defaults());
        CacheKey other = new CacheKey(new CacheRegion("cache", "orders", 1), "NONE", "42");
        await(store.put(KEY, "user", String.class, Duration.ZERO));
        await(store.put(other, "order", String.class, Duration.ZERO));

        await(store.clear(REGION));

        assertTrue(await(store.get(KEY, String.class)).isEmpty());
        assertEquals(Optional.of("order"), await(store.get(other, String.class)));
    }

    @Test
    void storesOneBoundPerLogicalRegion() {
        CacheConfig config =
                new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 1, 100, Map.of());
        CaffeineCacheStore store = new CaffeineCacheStore(config);
        CacheKey secondUser = new CacheKey(REGION, "NONE", "43");
        CacheKey order = new CacheKey(new CacheRegion("cache", "orders", 1), "NONE", "42");

        await(store.put(KEY, "user-42", String.class, Duration.ZERO));
        await(store.put(secondUser, "user-43", String.class, Duration.ZERO));
        await(store.put(order, "order-42", String.class, Duration.ZERO));

        assertTrue(await(store.get(KEY, String.class)).isEmpty());
        assertEquals(Optional.of("user-43"), await(store.get(secondUser, String.class)));
        assertEquals(Optional.of("order-42"), await(store.get(order, String.class)));
    }

    @Test
    void returnsJsonDefensiveCopiesForMutableValues() {
        CaffeineCacheStore store = new CaffeineCacheStore(CacheConfig.defaults());
        MutableValue original = new MutableValue("before", List.of("one"));

        await(store.put(KEY, original, MutableValue.class, Duration.ZERO));
        MutableValue first =
                (MutableValue) await(store.get(KEY, MutableValue.class)).orElseThrow();
        first.tags().add("changed");
        MutableValue second =
                (MutableValue) await(store.get(KEY, MutableValue.class)).orElseThrow();

        assertEquals(new MutableValue("before", List.of("one")), second);
    }

    @Test
    void disabledAndNullWritesRemainMisses() {
        CacheConfig disabled =
                new CacheConfig(false, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 10, 100, Map.of());
        CaffeineCacheStore store = new CaffeineCacheStore(disabled);

        await(store.put(KEY, "ignored", String.class, Duration.ZERO));
        await(store.put(KEY, null, String.class, Duration.ZERO));

        assertTrue(await(store.get(KEY, String.class)).isEmpty());
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

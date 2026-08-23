// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.injvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies local bounded-store expiration and logical-region invalidation. */
class CaffeineCacheStoreTest {
    private static final CacheRegion REGION = new CacheRegion("cache", "users", 1);
    private static final CacheKey KEY = new CacheKey(REGION, "NONE", "42");

    @Test
    void finiteTtlExpiresAndZeroTtlIsPersistent() throws InterruptedException {
        CaffeineCacheStore store = new CaffeineCacheStore(CacheConfig.defaults());

        store.put(KEY, "temporary", String.class, Duration.ofMillis(1))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        Thread.sleep(5);
        assertEquals(
                Optional.empty(),
                store.get(KEY, String.class)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());

        store.put(KEY, "persistent", String.class, Duration.ZERO)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals(
                Optional.of("persistent"),
                store.get(KEY, String.class)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
    }

    @Test
    void clearRemovesOnlyTheSelectedRegion() {
        CaffeineCacheStore store = new CaffeineCacheStore(CacheConfig.defaults());
        CacheKey other = new CacheKey(new CacheRegion("cache", "orders", 1), "NONE", "42");
        store.put(KEY, "user", String.class, Duration.ZERO);
        store.put(other, "order", String.class, Duration.ZERO);

        store.clear(REGION);

        assertTrue(store.get(KEY, String.class)
                .toCompletionStage()
                .toCompletableFuture()
                .join()
                .isEmpty());
        assertEquals(
                Optional.of("order"),
                store.get(other, String.class)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
    }
}

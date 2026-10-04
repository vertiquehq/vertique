// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proof that TTL resolution rejects values above {@code maxTtlSeconds} instead of clamping them. */
class CacheTtlPolicyTest {

    private static CacheConfig config(long defaultTtl, long maxTtl, Map<String, CacheEntryConfig> caches) {
        return new CacheConfig(
                true, CacheMode.LOCAL, defaultTtl, maxTtl, "system", 1_024, 1_048_576, 10_000, 100, caches);
    }

    private static CacheBuilder builder(ProgrammaticCacheTestFixtures.RecordingStore store, CacheConfig config) {
        return CacheBuilder.forTesting(store, config, Set.of(), Set.of());
    }

    @Test
    @DisplayName("explicit TTL above maxTtlSeconds is rejected with IllegalArgumentException, not clamped")
    void explicitTtlAboveMaxIsRejected() {
        CacheBuilder builder = builder(new ProgrammaticCacheTestFixtures.RecordingStore(), config(10, 100, Map.of()));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> builder.cache("orders", String.class)
                        .identity(CacheIdentity.NONE)
                        .ttl(Duration.ofSeconds(101))
                        .build());

        assertTrue(failure.getMessage().contains("maxTtlSeconds"));
        assertTrue(failure.getMessage().contains("orders"));
    }

    @Test
    @DisplayName("the keyed definition path enforces the same TTL cap")
    void keyedTtlAboveMaxIsRejected() {
        CacheBuilder builder = builder(new ProgrammaticCacheTestFixtures.RecordingStore(), config(10, 100, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> builder.cache("orders", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ofHours(48))
                .<String>key(id -> id)
                .build());
    }

    @Test
    @DisplayName("TTL equal to maxTtlSeconds is accepted and reaches the store unchanged")
    void ttlAtMaxIsAcceptedUnchanged() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<String, String> cache = builder(store, config(10, 100, Map.of()))
                .cache("orders", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ofSeconds(100))
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("v")));

        assertEquals(Duration.ofSeconds(100), store.lastTtl);
    }

    @Test
    @DisplayName("an omitted TTL falls back to defaultTtlSeconds")
    void omittedTtlUsesDefault() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<String, String> cache = builder(store, config(10, 100, Map.of()))
                .cache("orders", String.class)
                .identity(CacheIdentity.NONE)
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("v")));

        assertEquals(Duration.ofSeconds(10), store.lastTtl);
    }

    @Test
    @DisplayName("TTL zero disables time expiration and is passed to the store as a zero duration")
    void zeroTtlDisablesExpiration() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<String, String> cache = builder(store, config(10, 100, Map.of()))
                .cache("orders", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ZERO)
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("v")));

        assertEquals(Duration.ZERO, store.lastTtl);
    }

    @Test
    @DisplayName("negative and fractional-second TTLs are rejected")
    void malformedTtlsAreRejected() {
        CacheBuilder builder = builder(new ProgrammaticCacheTestFixtures.RecordingStore(), config(10, 100, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> builder.cache("orders", String.class)
                .ttl(Duration.ofSeconds(-1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> builder.cache("orders", String.class)
                .ttl(Duration.ofMillis(1_500))
                .build());
    }

    @Test
    @DisplayName("a per-cache configuration TTL overrides the builder TTL")
    void perCacheConfigOverridesBuilderTtl() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        CacheConfig config = config(10, 100, Map.of("orders", new CacheEntryConfig(CacheMode.DEFAULT, 42, null)));
        Cache<String, String> cache = builder(store, config)
                .cache("orders", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ofSeconds(5))
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("v")));

        assertEquals(Duration.ofSeconds(42), store.lastTtl);
    }

    @Test
    @DisplayName("a per-cache configuration TTL of -1 inherits the builder TTL")
    void perCacheMinusOneInherits() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        CacheConfig config = config(10, 100, Map.of("orders", new CacheEntryConfig(CacheMode.DEFAULT, -1, null)));
        Cache<String, String> cache = builder(store, config)
                .cache("orders", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ofSeconds(5))
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("v")));

        assertEquals(Duration.ofSeconds(5), store.lastTtl);
    }

    @Test
    @DisplayName("an explicit TTL equal to a lowered ceiling still builds, while the next second is rejected")
    void boundaryIsExact() {
        CacheBuilder builder = builder(new ProgrammaticCacheTestFixtures.RecordingStore(), config(1, 1, Map.of()));

        assertDoesNotThrow(() -> builder.cache("a", String.class)
                .ttl(Duration.ofSeconds(1))
                .identity(CacheIdentity.NONE)
                .build());
        assertThrows(IllegalArgumentException.class, () -> builder.cache("b", String.class)
                .ttl(Duration.ofSeconds(2))
                .identity(CacheIdentity.NONE)
                .build());
    }

    private static <T> T await(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }
}

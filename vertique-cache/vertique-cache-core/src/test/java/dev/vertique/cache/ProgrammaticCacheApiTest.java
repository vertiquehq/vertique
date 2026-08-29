// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Direct proof of the programmatic cache API and runtime behavior. */
class ProgrammaticCacheApiTest {
    @Test
    void scalarHandleLoadsOnceAndUsesVersionTwoFraming() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        CacheBuilder builder = CacheBuilder.forTesting(
                store, CacheTestFixturesConfig.defaults(), java.util.Set.of(), java.util.Set.of());
        Cache<String, String> cache = builder.cache("products", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ofSeconds(5))
                .build();
        AtomicInteger calls = new AtomicInteger();

        assertEquals("product", await(cache.get("product-1", ignored -> Future.succeededFuture("product"))));
        assertEquals("product", await(cache.get("product-1", ignored -> {
            calls.incrementAndGet();
            return Future.succeededFuture("unexpected");
        })));

        assertEquals(0, calls.get());
        assertEquals("k2Sproduct-1", store.lastKey.selector());
        assertEquals(2, store.lastKey.region().formatVersion());
    }

    @Test
    void compositeDefinitionEvaluatesEachSelectorOnceAndIsImmutable() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        CacheBuilder builder = CacheBuilder.forTesting(
                store, CacheTestFixturesConfig.defaults(), java.util.Set.of(), java.util.Set.of());
        var base = builder.cache("products", String.class).identity(CacheIdentity.NONE);
        var cache =
                base.key("{tenant}:{product}", Query::tenant, Query::product).build();

        await(cache.get(new Query("acme", 42), ignored -> Future.succeededFuture("value")));

        assertEquals("k2Sacme:k2I42", store.lastKey.selector());
        assertThrows(IllegalArgumentException.class, () -> base.key("{left}-{right}", Query::tenant, Query::product)
                .build());
    }

    @Test
    void parameterizedTypeRefIsAccepted() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        CacheBuilder builder = CacheBuilder.forTesting(
                store, CacheTestFixturesConfig.defaults(), java.util.Set.of(), java.util.Set.of());
        Cache<String, List<String>> cache = builder.cache("values", new CacheBuilder.TypeRef<List<String>>() {})
                .identity(CacheIdentity.NONE)
                .build();

        assertEquals(List.of("value"), await(cache.get("key", ignored -> Future.succeededFuture(List.of("value")))));
    }

    private static <T> T await(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    private record Query(String tenant, int product) {}

    /** Keeps the test focused on the builder and uses the project defaults without exposing config helpers. */
    private static final class CacheTestFixturesConfig {
        private static dev.vertique.cache.config.CacheConfig defaults() {
            return dev.vertique.cache.config.CacheConfig.defaults();
        }
    }
}

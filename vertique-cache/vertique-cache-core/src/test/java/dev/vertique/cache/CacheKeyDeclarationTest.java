// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** D007 proof: component declarations are injective without a template language. */
class CacheKeyDeclarationTest {

    @Test
    void distinctComponentTuplesRenderDistinctSelectors() {
        assertNotEquals(selectorOf(input -> CacheKey.of("a-b", "c")), selectorOf(input -> CacheKey.of("a", "b-c")));
    }

    @Test
    void componentValuesCannotForgeTheRuntimeSeparator() {
        String forged = selectorOf(input -> CacheKey.of("a:b"));
        String joined = selectorOf(input -> CacheKey.of("a", "b"));
        assertNotEquals(forged, joined);
        assertFalse(forged.substring("k2S".length()).contains(":"), forged);
    }

    @Test
    void singleScalarAndOneComponentCacheKeyRenderIdentically() {
        assertEquals(selectorOf(input -> "product-1"), selectorOf(input -> CacheKey.of("product-1")));
    }

    @Test
    void cacheKeyRejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> CacheKey.of(null));
        assertThrows(NullPointerException.class, () -> CacheKey.of("a", (Object) null));
        assertThrows(NullPointerException.class, () -> CacheKey.of("a", (Object[]) null));
    }

    @Test
    void varargsMutationCannotAlterAConstructedCacheKey() {
        Object[] rest = {"b", "c"};
        CacheKey key = CacheKey.of("a", rest);
        String before = renderThrough(key);
        rest[0] = "mutated";
        rest[1] = "values";
        assertEquals(before, renderThrough(key));
    }

    @Test
    void throwingSelectorFailsOpenWithoutProviderAccess() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<String, String> cache = builder(store)
                .cache("declaration-throwing", String.class)
                .identity(CacheIdentity.NONE)
                .<String>key(input -> {
                    throw new IllegalStateException("selector failure");
                })
                .build();
        AtomicInteger loads = new AtomicInteger();

        assertEquals("loaded", await(cache.get("input", ignored -> {
            loads.incrementAndGet();
            return Future.succeededFuture("loaded");
        })));
        assertEquals(1, loads.get());
        assertEquals(0, store.getCalls);
        assertEquals(0, store.putCalls);
        assertFalse(await(cache.invalidate("input")));
    }

    @Test
    void unsupportedAndNullSelectorResultsFailOpen() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<String, String> unsupported = builder(store)
                .cache("declaration-unsupported", String.class)
                .identity(CacheIdentity.NONE)
                .<String>key(input -> new Object())
                .build();
        Cache<String, String> nullResult = builder(store)
                .cache("declaration-null", String.class)
                .identity(CacheIdentity.NONE)
                .<String>key(input -> null)
                .build();

        assertEquals("loaded", await(unsupported.get("input", ignored -> Future.succeededFuture("loaded"))));
        assertEquals("loaded", await(nullResult.get("input", ignored -> Future.succeededFuture("loaded"))));
        assertEquals(0, store.getCalls);
        assertEquals(0, store.putCalls);
    }

    @Test
    void equalComponentTuplesShareOneStoredEntry() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<Query, String> cache = builder(store)
                .cache("declaration-shared", String.class)
                .identity(CacheIdentity.NONE)
                .<Query>key(query -> CacheKey.of(query.tenant(), query.product()))
                .build();

        assertEquals("first", await(cache.get(new Query("acme", 42), ignored -> Future.succeededFuture("first"))));
        assertEquals("first", await(cache.get(new Query("acme", 42), ignored -> Future.succeededFuture("second"))));
        assertEquals("k2Sacme:k2I42", store.lastKey.selector());
    }

    @Test
    void noPublicApiAcceptsATemplateString() {
        assertTrue(Arrays.stream(CacheBuilder.Definition.class.getMethods())
                .filter(method -> method.getName().equals("key"))
                .allMatch(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[] {Function.class})));
    }

    private static String selectorOf(Function<String, Object> selector) {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        Cache<String, String> cache = builder(store)
                .cache("declaration-probe", String.class)
                .identity(CacheIdentity.NONE)
                .key(selector)
                .build();
        await(cache.get("input", ignored -> Future.succeededFuture("value")));
        return store.lastKey.selector();
    }

    private static String renderThrough(CacheKey key) {
        return selectorOf(input -> key);
    }

    private static CacheBuilder builder(ProgrammaticCacheTestFixtures.RecordingStore store) {
        return CacheBuilder.forTesting(store, dev.vertique.cache.config.CacheConfig.defaults(), Set.of(), Set.of());
    }

    private static <T> T await(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    private record Query(String tenant, int product) {}
}

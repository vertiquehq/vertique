// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import io.vertx.core.Future;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared provider-neutral CacheStore contract inherited by provider test edges. */
public abstract class CacheStoreContractTest {
    protected static final CacheRegion REGION = new CacheRegion("cache", "profiles", 1);
    protected static final CacheRegion OTHER_REGION = new CacheRegion("cache", "orders", 1);
    protected static final ResolvedCacheKey KEY = new ResolvedCacheKey(REGION, "NONE", "alice");
    protected static final ResolvedCacheKey OTHER_KEY = new ResolvedCacheKey(REGION, "NONE", "bob");
    protected static final ResolvedCacheKey OTHER_REGION_KEY = new ResolvedCacheKey(OTHER_REGION, "NONE", "order-1");

    private CacheStore store;

    /** Creates the provider fixture at the provider-specific test edge. */
    protected abstract CacheStore createStore();

    @BeforeEach
    void resetContractState() throws Exception {
        store = createStore();
        await(store.clear(REGION));
        await(store.clear(OTHER_REGION));
    }

    @Test
    @DisplayName("all providers satisfy core semantics")
    void allProvidersSatisfyCoreSemantics() throws Exception {
        assertEquals(Optional.empty(), await(store.get(KEY, descriptor(String.class))));

        await(store.put(KEY, descriptor(String.class), "alice", Duration.ZERO));
        await(store.put(OTHER_KEY, descriptor(String.class), "bob", Duration.ZERO));
        await(store.put(OTHER_REGION_KEY, descriptor(String.class), "order-1", Duration.ZERO));
        ResolvedCacheKey nullKey = new ResolvedCacheKey(REGION, "NONE", "null");
        await(store.put(nullKey, descriptor(String.class), null, Duration.ZERO));

        assertEquals(Optional.of("alice"), await(store.get(KEY, descriptor(String.class))));
        assertEquals(Optional.empty(), await(store.get(nullKey, descriptor(String.class))));

        await(store.evict(KEY));
        assertEquals(Optional.empty(), await(store.get(KEY, descriptor(String.class))));
        assertEquals(Optional.of("bob"), await(store.get(OTHER_KEY, descriptor(String.class))));

        await(store.clear(REGION));
        assertEquals(Optional.empty(), await(store.get(OTHER_KEY, descriptor(String.class))));
        assertEquals(Optional.of("order-1"), await(store.get(OTHER_REGION_KEY, descriptor(String.class))));
    }

    @Test
    @DisplayName("all providers isolate mutable values")
    void allProvidersIsolateMutableValues() throws Exception {
        MutableValue expected = new MutableValue("before", List.of("one"));
        await(store.put(KEY, descriptor(MutableValue.class), expected, Duration.ZERO));

        expected.tags().add("changed-after-put");
        MutableValue first = (MutableValue)
                await(store.get(KEY, descriptor(MutableValue.class))).orElseThrow();
        first.tags().add("changed-after-get");
        MutableValue second = (MutableValue)
                await(store.get(KEY, descriptor(MutableValue.class))).orElseThrow();

        assertEquals(new MutableValue("before", List.of("one")), second);
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("all providers respect declared types")
    void allProvidersRespectDeclaredType() throws Exception {
        Type declaredType = listOf(MutableValue.class);
        List<MutableValue> expected = List.of(new MutableValue("alice", List.of("admin")));

        await(store.put(KEY, descriptor(declaredType), expected, Duration.ZERO));

        assertEquals(Optional.of(expected), await(store.get(KEY, descriptor(declaredType))));
    }

    @Test
    @DisplayName("all providers fail open on codec failure")
    void allProvidersFailOpenOnCodecFailure() throws Exception {
        await(store.put(KEY, descriptor(String.class), "not-an-integer", Duration.ZERO));

        Future<Optional<Object>> failedOrMiss = store.get(KEY, descriptor(Integer.class));
        Optional<Object> recovered = await(failedOrMiss.recover(ignored -> Future.succeededFuture(Optional.empty())));

        assertTrue(recovered.isEmpty(), "a codec failure must never expose an arbitrarily typed value");
        assertTrue(failedOrMiss.failed() || failedOrMiss.result().isEmpty());
    }

    @Test
    @DisplayName("all providers handle repeatable eviction")
    void allProvidersHandleRepeatableEviction() throws Exception {
        await(store.put(KEY, descriptor(String.class), "alice", Duration.ZERO));
        await(store.put(OTHER_KEY, descriptor(String.class), "bob", Duration.ZERO));

        assertDoesNotThrow(() -> {
            await(store.evict(KEY));
            await(store.evict(KEY));
            await(store.clear(REGION));
            await(store.clear(REGION));
        });

        assertEquals(Optional.empty(), await(store.get(KEY, descriptor(String.class))));
        assertEquals(Optional.empty(), await(store.get(OTHER_KEY, descriptor(String.class))));
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static CacheValueDescriptor descriptor(Type type) {
        return new CacheValueDescriptor(type, "system");
    }

    private static Type listOf(Type elementType) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {elementType};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String getTypeName() {
                return List.class.getTypeName() + "<" + elementType.getTypeName() + ">";
            }
        };
    }

    protected record MutableValue(String name, List<String> tags) {
        protected MutableValue {
            tags = new ArrayList<>(tags);
        }
    }
}

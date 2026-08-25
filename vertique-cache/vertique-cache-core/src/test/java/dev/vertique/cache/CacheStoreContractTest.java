// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import io.vertx.core.Future;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared provider-neutral CacheStore contract inherited by provider test edges. */
public abstract class CacheStoreContractTest {
    protected static final CacheRegion REGION = new CacheRegion("cache", "profiles", 1);
    protected static final CacheRegion OTHER_REGION = new CacheRegion("cache", "orders", 1);
    protected static final CacheKey KEY = new CacheKey(REGION, "NONE", "alice");
    protected static final CacheKey OTHER_KEY = new CacheKey(REGION, "NONE", "bob");
    protected static final CacheKey OTHER_REGION_KEY = new CacheKey(OTHER_REGION, "NONE", "order-1");

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
    void allProvidersSatisfyCoreSemantics() throws Exception {
        assertEquals(Optional.empty(), await(store.get(KEY, String.class)));

        await(store.put(KEY, "alice", String.class, Duration.ZERO));
        await(store.put(OTHER_KEY, "bob", String.class, Duration.ZERO));
        await(store.put(OTHER_REGION_KEY, "order-1", String.class, Duration.ZERO));
        await(store.put(new CacheKey(REGION, "NONE", "null"), null, String.class, Duration.ZERO));

        assertEquals(Optional.of("alice"), await(store.get(KEY, String.class)));
        assertEquals(Optional.empty(), await(store.get(new CacheKey(REGION, "NONE", "null"), String.class)));

        await(store.evict(KEY));
        assertEquals(Optional.empty(), await(store.get(KEY, String.class)));
        assertEquals(Optional.of("bob"), await(store.get(OTHER_KEY, String.class)));

        await(store.clear(REGION));
        assertEquals(Optional.empty(), await(store.get(OTHER_KEY, String.class)));
        assertEquals(Optional.of("order-1"), await(store.get(OTHER_REGION_KEY, String.class)));
    }

    @Test
    void allProvidersIsolateMutableValues() throws Exception {
        MutableValue expected = new MutableValue("before", List.of("one"));
        await(store.put(KEY, expected, MutableValue.class, Duration.ZERO));

        expected.tags().add("changed-after-put");
        MutableValue first =
                (MutableValue) await(store.get(KEY, MutableValue.class)).orElseThrow();
        first.tags().add("changed-after-get");
        MutableValue second =
                (MutableValue) await(store.get(KEY, MutableValue.class)).orElseThrow();

        assertEquals(new MutableValue("before", List.of("one")), second);
        assertNotSame(first, second);
    }

    @Test
    void allProvidersRespectDeclaredType() throws Exception {
        Type declaredType = listOf(MutableValue.class);
        List<MutableValue> expected = List.of(new MutableValue("alice", List.of("admin")));

        await(store.put(KEY, expected, declaredType, Duration.ZERO));

        assertEquals(Optional.of(expected), await(store.get(KEY, declaredType)));
    }

    @Test
    void allProvidersFailOpenOnCodecFailure() throws Exception {
        await(store.put(KEY, "not-an-integer", String.class, Duration.ZERO));

        Future<Optional<Object>> failedOrMiss = store.get(KEY, Integer.class);
        Optional<Object> recovered = await(failedOrMiss.recover(ignored -> Future.succeededFuture(Optional.empty())));

        assertTrue(recovered.isEmpty(), "a codec failure must never expose an arbitrarily typed value");
        assertTrue(failedOrMiss.failed() || failedOrMiss.result().isEmpty());
    }

    @Test
    void allProvidersHandleRepeatableEviction() throws Exception {
        await(store.put(KEY, "alice", String.class, Duration.ZERO));
        await(store.put(OTHER_KEY, "bob", String.class, Duration.ZERO));

        assertDoesNotThrow(() -> {
            await(store.evict(KEY));
            await(store.evict(KEY));
            await(store.clear(REGION));
            await(store.clear(REGION));
        });

        assertEquals(Optional.empty(), await(store.get(KEY, String.class)));
        assertEquals(Optional.empty(), await(store.get(OTHER_KEY, String.class)));
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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

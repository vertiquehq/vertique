// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A synchronous operation resolves to LOCAL under a clustered default, and its clear addresses LOCAL. */
class SynchronousCacheModeTest {

    private final CountingStore local = new CountingStore();
    private final CountingStore clustered = new CountingStore();

    private CacheAdapterSupport support(CacheMode defaultMode) {
        CacheStoreResolver resolver = new CacheStoreResolver(
                Map.of(CacheMode.LOCAL, () -> local, CacheMode.CLUSTERED, () -> clustered),
                Map.of(CacheMode.LOCAL, "local", CacheMode.CLUSTERED, "clustered"));
        CacheConfig config =
                new CacheConfig(true, defaultMode, 60, 120, "system", 1_024, 1_048_576, 10_000, 100, Map.of());
        return new CacheAdapterSupport(new CacheBuilder(resolver, config, Set.of(), Optional.empty()));
    }

    private static CacheAdapterSupport.AdapterDefinition definition(String name, boolean synchronous) {
        return new CacheAdapterSupport.AdapterDefinition(
                name,
                String.class,
                CacheMode.DEFAULT,
                -1,
                CacheIdentity.NONE,
                AnonymousCachePolicy.BYPASS,
                input -> input,
                synchronous,
                List.of("0"));
    }

    @Test
    @DisplayName("a synchronous definition with no mode caches in the LOCAL store under a CLUSTERED default")
    void synchronousDefaultModeUsesTheLocalStore() {
        Cache<Object, Object> cache = support(CacheMode.CLUSTERED).registered(definition("profiles", true));

        cache.get("alice", key -> Future.succeededFuture("value"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(1, local.puts, "the value must be stored in the LOCAL store");
        assertEquals(0, clustered.puts + clustered.gets, "the CLUSTERED store must not be touched");
    }

    @Test
    @DisplayName("a Future definition with no mode still follows the CLUSTERED default")
    void asynchronousDefaultModeFollowsTheDefault() {
        Cache<Object, Object> cache = support(CacheMode.CLUSTERED).registered(definition("profiles", false));

        cache.get("alice", key -> Future.succeededFuture("value"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(1, clustered.puts);
        assertEquals(0, local.puts + local.gets);
    }

    @Test
    @DisplayName("a clear addresses the store of the registered definition, not the default mode")
    void clearAddressesTheRegisteredStore() {
        CacheAdapterSupport support = support(CacheMode.CLUSTERED);
        support.registered(definition("profiles", true));

        support.clearFor("profiles")
                .orElseThrow()
                .invalidateAll()
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(1, local.clears, "the clear must reach the LOCAL store the entries live in");
        assertEquals(0, clustered.clears);
        assertTrue(support.clearFor("unknown").isEmpty(), "no registered definition, no registered clear handle");
    }

    @Test
    @DisplayName("a name shared by a synchronous and a Future definition fails when they resolve to different modes")
    void sharedNameWithDifferentResolvedModesFails() {
        CacheAdapterSupport support = support(CacheMode.CLUSTERED);
        support.registered(definition("profiles", true));

        assertThrows(IllegalStateException.class, () -> support.registered(definition("profiles", false)));
    }

    private static final class CountingStore implements CacheStore {
        private final Map<String, Object> values = new HashMap<>();
        int gets;
        int puts;
        int clears;

        @Override
        public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
            gets++;
            return Future.succeededFuture(Optional.ofNullable(values.get(key.canonical())));
        }

        @Override
        public Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
            puts++;
            values.put(key.canonical(), value);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> evict(ResolvedCacheKey key) {
            values.remove(key.canonical());
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> clear(CacheRegion region) {
            clears++;
            values.clear();
            return Future.succeededFuture();
        }
    }
}

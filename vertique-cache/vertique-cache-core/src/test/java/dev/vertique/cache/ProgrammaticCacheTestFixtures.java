// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class ProgrammaticCacheTestFixtures {

    private ProgrammaticCacheTestFixtures() {}

    static final class RecordingStore implements CacheStore {
        private final Map<String, Object> values = new HashMap<>();
        int getCalls;
        int putCalls;
        Duration lastTtl;
        ResolvedCacheKey lastKey;

        @Override
        public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
            getCalls++;
            lastKey = key;
            return Future.succeededFuture(Optional.ofNullable(values.get(key.canonical())));
        }

        @Override
        public Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
            putCalls++;
            lastKey = key;
            lastTtl = ttl;
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
            values.clear();
            return Future.succeededFuture();
        }
    }
}

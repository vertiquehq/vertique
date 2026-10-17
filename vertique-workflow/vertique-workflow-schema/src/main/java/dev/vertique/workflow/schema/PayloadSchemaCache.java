// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The process-wide store of generated payload schemas, keyed by payload type name and JSON profile.
 *
 * <p>A computation runs at most once per key. A computation that throws leaves no entry behind, so a failure is
 * never cached.
 */
@Singleton
final class PayloadSchemaCache {

    private final ConcurrentHashMap<SchemaKey, CachedPayloadSchema> entries = new ConcurrentHashMap<>();

    @Inject
    PayloadSchemaCache() {}

    /**
     * Looks up an entry.
     *
     * @param key the key
     * @return the entry, or empty when none has been computed
     */
    Optional<CachedPayloadSchema> find(SchemaKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Returns the entry for {@code key}, computing and storing it first when absent.
     *
     * @param key the key
     * @param loader computes the entry; any exception it throws propagates and nothing is stored
     * @return the stored entry
     */
    CachedPayloadSchema computeIfAbsent(SchemaKey key, Function<? super SchemaKey, CachedPayloadSchema> loader) {
        return entries.computeIfAbsent(key, loader);
    }

    /**
     * Copies the current entries.
     *
     * @return an unmodifiable copy that later writes do not change
     */
    Map<SchemaKey, CachedPayloadSchema> snapshot() {
        return Map.copyOf(entries);
    }
}

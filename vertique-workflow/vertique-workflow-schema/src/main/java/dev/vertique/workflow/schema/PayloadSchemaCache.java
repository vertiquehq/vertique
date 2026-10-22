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
 * The process-wide store of generated payload schemas, keyed by the logical payload type name and JSON profile.
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
     * Looks up the entry of a payload type without naming the profile.
     *
     * <p>A process has one effective system profile, so the read side knows only the type name. The lookup scans the
     * entries, which are few: one per distinct payload type.
     *
     * @param typeName the logical name of the payload type, which is the binary class name for a class without a
     *     name annotation
     * @return the entry, or empty when none has been computed for the type
     * @throws IllegalStateException if entries for the type exist under more than one profile
     */
    Optional<CachedPayloadSchema> findByTypeName(String typeName) {
        SchemaKey found = null;
        CachedPayloadSchema schema = null;
        for (Map.Entry<SchemaKey, CachedPayloadSchema> entry : entries.entrySet()) {
            if (!entry.getKey().typeName().equals(typeName)) {
                continue;
            }
            if (found != null && !found.profileId().equals(entry.getKey().profileId())) {
                throw new IllegalStateException("payload type '" + typeName + "' has schemas under more than one"
                        + " JSON profile ('" + found.profileId().value() + "' and '"
                        + entry.getKey().profileId().value()
                        + "'); a process must use a single system profile for workflow payloads");
            }
            found = entry.getKey();
            schema = entry.getValue();
        }
        return Optional.ofNullable(schema);
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

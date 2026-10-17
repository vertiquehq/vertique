// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.json.schema.validation.JsonSchemaValidationOptions;
import dev.vertique.json.schema.validation.PatternInputGuard;
import io.vertx.core.json.JsonObject;

/** Builds and exercises cache entries the way a read of a cached validator does: guarded, in one window. */
final class Entries {

    private Entries() {}

    /**
     * Compiles {@code document} with a guard at the shared default bounds.
     *
     * @param document the schema document
     * @return the entry
     */
    static CachedPayloadSchema of(JsonObject document) {
        PatternInputGuard guard =
                new PatternInputGuard(PatternInputGuard.DEFAULT_MAX_CHARS, PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS);
        return new CachedPayloadSchema(
                document, guard.compile(document.copy(), JsonSchemaValidationOptions.draft202012()), guard);
    }

    /**
     * Validates {@code instance} with the entry's own guard inside one window.
     *
     * @param entry the cache entry
     * @param instance the instance
     * @return whether the entry's validator accepts it
     */
    static boolean accepts(CachedPayloadSchema entry, Object instance) {
        try (PatternInputGuard.Window ignored = entry.guard().openWindow()) {
            return entry.validator().validate(instance).getValid();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/**
 * Package-private helper that resolves a config key against a {@link JsonObject} tree using the
 * <em>flat-key-first</em> probe rule, with a fallback dot-path walk.
 *
 * <h2>Probe algorithm (Q5 rule)</h2>
 * <ol>
 *   <li><strong>Flat probe</strong> — {@code tree.getValue(key)} is attempted first. If the key is
 *       present in the top-level map (including dotted keys such as {@code "a.b"}) and its value is
 *       non-null, that value is returned. Flat keys outrank nested paths because system-store
 *       entries are always flat and are intended to shadow file-sourced nested values.</li>
 *   <li><strong>Dot-path walk</strong> — if the flat probe misses (key absent or JSON null), the
 *       key is split on {@code '.'} and each segment is used to descend into the tree. Each
 *       intermediate segment must resolve to a {@link JsonObject}; if any intermediate is absent,
 *       {@code null}, or a non-{@code JsonObject} type (including {@link JsonArray}), the walk
 *       returns empty. No array indexing is performed in V1.</li>
 * </ol>
 *
 * <h2>Present-null = not-found</h2>
 * <p>Both flat and nested lookups treat a JSON {@code null} value as <em>absent</em> — the returned
 * {@link Optional} is empty. Although {@link JsonObject#containsKey(String)} can distinguish
 * {@code null}-by-presence from {@code null}-by-absence, the engine treats explicit nulls the same
 * as missing entries: the lookup chain continues and the default value (if any) applies. This
 * matches merge semantics where a {@code null} config value rarely carries intentional meaning.
 *
 * <h2>Type preservation</h2>
 * <p>{@link #find(JsonObject, String)} returns the value in its natural JSON type
 * ({@link String}, {@link Integer}, {@link Long}, {@link Double}, {@link Boolean},
 * {@link JsonObject}, {@link JsonArray}). When a placeholder is the sole token in an expression,
 * the caller may use the typed value directly. In concatenation contexts, the value is converted
 * to a string via {@link #stringify(Object)}.
 *
 * <h2>NFR-CONF-002 — value redaction</h2>
 * <p>Callers must not include resolved values in logs, exception messages, or any observable
 * output. Log keys and counts only.
 *
 * @see PlaceholderParser
 */
final class TreeLookup {

    private TreeLookup() {}

    // --- Public API ---

    /**
     * Resolves {@code key} against {@code tree} using the flat-key-first probe rule followed by a
     * dot-path walk.
     *
     * <p>A JSON {@code null} value — whether stored explicitly or absent — is treated as not found
     * and returns an empty {@link Optional}. The caller cannot distinguish the two cases via this
     * method; that distinction is intentionally hidden because both cases mean "chain continues".
     *
     * @param tree the top-level config {@link JsonObject} to search; must not be {@code null}
     * @param key  the config key to resolve; must not be {@code null}
     * @return an {@link Optional} containing the resolved typed value, or empty if the key is not
     *     found (including when the stored value is JSON {@code null})
     */
    static Optional<Object> find(JsonObject tree, String key) {
        // --- Step 1: flat probe ---
        Object flat = tree.getValue(key);
        if (flat != null) {
            return Optional.of(flat);
        }
        // flat returned null — could mean absent OR present-null.
        // Either way we do NOT return here; both cases fall through to the dot-path walk
        // (present-null = not-found per the decided semantic).

        // --- Step 2: dot-path walk (only when key contains a dot) ---
        int dotIndex = key.indexOf('.');
        if (dotIndex < 0) {
            // No dot in key and flat probe returned null → not found
            return Optional.empty();
        }

        return walkPath(tree, key);
    }

    /**
     * Converts a resolved config value to its string representation for use in concatenation
     * contexts.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@link String} — returned as-is (no quoting).</li>
     *   <li>{@link JsonObject} — encoded via {@link JsonObject#encode()} (compact JSON).</li>
     *   <li>{@link JsonArray} — encoded via {@link JsonArray#encode()} (compact JSON).</li>
     *   <li>All other types (numerics, {@link Boolean}) — converted via
     *       {@link String#valueOf(Object)}.</li>
     *   <li>{@code null} — returns {@code "null"} as a defensive fallback; callers should not pass
     *       {@code null} because {@link #find(JsonObject, String)} never returns a present null in
     *       its result.</li>
     * </ul>
     *
     * @param value the resolved value to stringify; should not be {@code null} in normal use
     * @return the string representation of {@code value}; never {@code null}
     */
    static String stringify(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof JsonObject obj) {
            return obj.encode();
        }
        if (value instanceof JsonArray arr) {
            return arr.encode();
        }
        // Covers Integer, Long, Double, Boolean, and null (defensive)
        return String.valueOf(value);
    }

    // --- Private helpers ---

    /**
     * Descends through the tree following each dot-delimited segment.
     *
     * <p>The walk starts with the full tree; at each step the current segment is used to extract
     * the next level, which must be a {@link JsonObject} (except the final segment, which may be
     * any JSON value). A missing key, a JSON {@code null}, or any non-{@code JsonObject}
     * intermediate terminates the walk with an empty result.
     *
     * @param tree the top-level config tree
     * @param key  the full dot-delimited key (guaranteed to contain at least one dot)
     * @return the resolved value, or empty if the path cannot be fully descended
     */
    private static Optional<Object> walkPath(JsonObject tree, String key) {
        String[] segments = key.split("\\.", -1);
        JsonObject current = tree;

        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.getValue(segments[i]);
            if (!(next instanceof JsonObject nextObj)) {
                // absent, null, or non-JsonObject intermediate → not found
                return Optional.empty();
            }
            current = nextObj;
        }

        // Retrieve the final segment value
        String lastSegment = segments[segments.length - 1];
        Object value = current.getValue(lastSegment);
        if (value == null) {
            // absent or JSON null → not found (present-null = not-found semantic)
            return Optional.empty();
        }
        return Optional.of(value);
    }
}

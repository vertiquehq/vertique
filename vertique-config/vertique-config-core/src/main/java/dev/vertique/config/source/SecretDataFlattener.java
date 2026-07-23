// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility for flattening a nested {@code Map<String,Object>} into a flat {@code Map<String,String>}
 * with dot-joined keys under an optional prefix.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Scalar values (non-{@code Map}) are converted to strings via {@link String#valueOf(Object)}.
 *       Primitive wrappers, booleans, and numbers are all handled.</li>
 *   <li>Nested {@code Map<String,Object>} values are recursed into; inner keys are appended to the
 *       accumulated dot path (e.g. {@code "db"} → {@code "host"} becomes {@code "db.host"}).</li>
 *   <li>{@code null} values are skipped — they produce no entry in the result.</li>
 *   <li>The top-level {@code prefix} is prepended to every produced key as-is (including an empty
 *       string for no prefix). It is the <em>caller's</em> responsibility to include any trailing
 *       separator in the prefix (e.g. {@code "app."}).</li>
 *   <li>Insertion order is preserved via a {@link LinkedHashMap}.</li>
 * </ul>
 *
 * <h2>Collision</h2>
 * <p>This helper flattens a <em>single</em> map. When multiple maps must be merged (e.g. multiple
 * secret paths), the caller is responsible for merging the results and deciding the collision policy
 * (typically "later entry wins").
 *
 * <h2>Redaction</h2>
 * <p>This class never logs, inspects, or otherwise observes the values it converts. It transforms
 * structure only.
 */
public final class SecretDataFlattener {

    private SecretDataFlattener() {}

    /**
     * Flattens {@code data} into an insertion-ordered map with dot-joined keys under {@code prefix}.
     *
     * <p>Top-level keys are produced as {@code prefix + key}. Nested map keys are produced as
     * {@code prefix + outerKey + "." + innerKey + ...}. {@code null} values are silently skipped.
     * Non-map scalars are stringified via {@link String#valueOf(Object)}.
     *
     * @param data   the (possibly nested) source map to flatten; must not be {@code null}
     * @param prefix the prefix to prepend to every top-level key; {@code ""} for no prefix;
     *               must not be {@code null}
     * @return an insertion-ordered, non-null, mutable map of flattened key → string value
     */
    public static Map<String, String> flatten(Map<String, Object> data, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        flattenInto(result, data, prefix, "");
        return result;
    }

    // --- Internal helpers ---

    /**
     * Recursively flattens {@code source} into {@code target} using dot-joined keys.
     *
     * <p>Non-map values are converted to strings via {@link String#valueOf(Object)}.
     * {@code null} values are skipped. Nested maps produce dot-joined key paths.
     *
     * @param target    the map to write flattened entries into
     * @param source    the (possibly nested) source map
     * @param prefix    the per-secret prefix declared in the entry configuration
     * @param keyPrefix accumulated dot-path from recursive calls (empty at top level)
     */
    @SuppressWarnings("unchecked")
    private static void flattenInto(
            Map<String, String> target, Map<String, Object> source, String prefix, String keyPrefix) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String relKey = keyPrefix.isEmpty() ? entry.getKey() : keyPrefix + "." + entry.getKey();
            if (value instanceof Map<?, ?> nestedMap) {
                flattenInto(target, (Map<String, Object>) nestedMap, prefix, relKey);
            } else {
                target.put(prefix + relKey, String.valueOf(value));
            }
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursively wraps a JSON-compatible value tree (nested {@code Map}/{@code List}/scalar) into
 * unmodifiable views at every level.
 *
 * <p>A value-observation record's normalized tree must reject mutation anywhere in its nested
 * structure, not only at the outermost map or list, regardless of whether the tree handed to the
 * record's compact constructor was itself already immutable. This package-private helper is the one
 * place both {@link McpToolInputObservation} and {@link McpToolOutputObservation} enforce that.
 */
final class McpValueTrees {

    private McpValueTrees() {}

    /**
     * Returns a deeply unmodifiable copy of {@code source}, preserving key order.
     *
     * @param source the map to copy; must not be {@code null}
     * @return an unmodifiable map whose nested {@code Map}/{@code List} values are unmodifiable too
     */
    static Map<String, Object> deepUnmodifiableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, deepUnmodifiable(value)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns a deeply unmodifiable view of {@code value}.
     *
     * @param value the value to wrap; a {@code Map} or {@code List} is deep-copied into an
     *     unmodifiable view with every nested value wrapped the same way, and anything else (a
     *     scalar, or {@code null}) is returned unchanged
     * @return the deeply unmodifiable view
     */
    static @Nullable Object deepUnmodifiable(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, deepUnmodifiable(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepUnmodifiable(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import dev.vertique.core.config.ConfigSecretRenderer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one dotted-path traversal shared by the config mapper's secret masking
 * ({@link ConfigSecretMaskingModule}) and the parser's mask-placeholder rejection
 * ({@link DefaultConfigParser}), so the two sides cannot drift apart.
 *
 * <p>The sensitivity rule itself is not defined here: every decision delegates to
 * {@link ConfigSecretRenderer#isSensitivePath(String)}. This class only fixes how a dotted path is
 * built while walking a value, with the walk of {@link ConfigSecretRenderer#redactBag(JsonObject)}:
 * object keys extend the path with a {@code .} separator, and array or collection elements inherit
 * their parent's path (an index is not part of the key shape the runtime flattens to). One deliberate
 * difference: {@code redactBag} prints {@code "***"} for a {@code null} secret, while serialization
 * omits an absent secret, so {@link #maskBag} drops a {@code null} sensitive entry.
 */
final class ConfigSecretPaths {

    private ConfigSecretPaths() {}

    /**
     * Joins a dotted prefix and a key the way the runtime flattens configuration.
     *
     * @param prefix the dotted path accumulated so far; empty at the root
     * @param key the next key
     * @return {@code key} when the prefix is empty, otherwise {@code prefix + "." + key}
     */
    static String join(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    /**
     * Returns a copy of an open bag with every sensitive leaf masked, or the <em>same</em> instance
     * when nothing in it is sensitive.
     *
     * <p>Handles {@link Map}, {@link JsonObject}, {@link Collection} and {@link JsonArray} values and
     * recurses through any nesting of them. A sensitive entry whose value is {@code null} is dropped
     * (absent secrets are omitted); any other sensitive entry becomes {@link ConfigSecretRenderer#MASK}
     * whatever its shape, as in {@code redactBag}. Scalars and unknown types are copied as-is.
     * The input is never mutated.
     *
     * @param path the dotted path of {@code value} itself
     * @param value the bag to mask
     * @return a masked copy, or {@code value} itself when no leaf was masked
     */
    static Object maskBag(String path, Object value) {
        if (value instanceof JsonObject json) {
            Object masked = maskBag(path, json.getMap());
            return masked == json.getMap() ? json : new JsonObject(castMap(masked));
        }
        if (value instanceof JsonArray array) {
            Object masked = maskBag(path, array.getList());
            return masked == array.getList() ? array : new JsonArray(castList(masked));
        }
        if (value instanceof Map<?, ?> map) {
            boolean changed = false;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String fullKey = join(path, key);
                Object entryValue = entry.getValue();
                if (ConfigSecretRenderer.isSensitivePath(fullKey)) {
                    changed = true;
                    if (entryValue != null) {
                        copy.put(key, ConfigSecretRenderer.MASK);
                    }
                } else {
                    Object maskedValue = maskBag(fullKey, entryValue);
                    changed |= maskedValue != entryValue;
                    copy.put(key, maskedValue);
                }
            }
            return changed ? copy : value;
        }
        if (value instanceof Collection<?> collection) {
            boolean changed = false;
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object element : collection) {
                Object maskedElement = maskBag(path, element);
                changed |= maskedElement != element;
                copy.add(maskedElement);
            }
            return changed ? copy : value;
        }
        return value;
    }

    /**
     * Finds the first sensitive leaf of a configuration section whose value is the literal
     * {@link ConfigSecretRenderer#MASK}: the shape a masked dump has, which must never be fed back as
     * a secret.
     *
     * @param section the raw configuration section to scan; may be {@code null}
     * @return the dotted path of the offending leaf, or empty when the section carries no mask
     *     placeholder at a sensitive path
     */
    static Optional<String> findMaskPlaceholder(JsonObject section) {
        return section == null ? Optional.empty() : findMaskPlaceholder("", section);
    }

    private static Optional<String> findMaskPlaceholder(String prefix, JsonObject object) {
        for (String key : object.fieldNames()) {
            Optional<String> found = findMaskPlaceholder(join(prefix, key), object.getValue(key));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findMaskPlaceholder(String path, Object value) {
        if (value instanceof JsonObject nested) {
            return findMaskPlaceholder(path, nested);
        }
        if (value instanceof JsonArray array) {
            for (Object element : array) {
                Optional<String> found = findMaskPlaceholder(path, element);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (ConfigSecretRenderer.MASK.equals(value) && ConfigSecretRenderer.isSensitivePath(path)) {
            return Optional.of(path);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}

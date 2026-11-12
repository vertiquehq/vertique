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
 * {@link ConfigSecretRenderer#isSensitivePath(String)} and {@link ConfigSecretRenderer#isUriPath(String)}.
 * This class only fixes how a dotted path is built while walking a value, with the walk of
 * {@link ConfigSecretRenderer#redactBag(JsonObject)}: object keys extend the path with a {@code .}
 * separator, and array or collection elements inherit their parent's path (an index is not part of the
 * key shape the runtime flattens to). Two deliberate differences from {@code redactBag}: it prints
 * {@code "***"} for a {@code null} secret while serialization omits an absent secret, so
 * {@link #maskValue} drops a {@code null} sensitive entry; and it leaves a URL-named string leaf as
 * given, while {@link #maskValue} renders it through {@link ConfigSecretRenderer#redactUri(String)},
 * so a bag's {@code toString()} prints a URL as given where a config-mapper dump masks its credentials.
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
     * Returns the log-safe rendering of a non-secret value at {@code path}, or the <em>same</em>
     * instance when nothing in it needs masking.
     *
     * <p>Two rules apply, by dotted path. A {@link CharSequence} at a path that
     * {@link ConfigSecretRenderer#isUriPath(String) names a URL} is rendered through
     * {@link ConfigSecretRenderer#redactUri(String)} and returned only when that differs from the
     * input (compared by {@code equals}, because {@code redactUri} rebuilds any string with a query).
     * An open bag — {@link Map}, {@link JsonObject}, {@link Collection}, {@link JsonArray}, nested to
     * any depth — is copied with every sensitive entry masked and every URL-named string leaf redacted:
     * a sensitive entry whose value is {@code null} is dropped (absent secrets are omitted); any other
     * sensitive entry becomes {@link ConfigSecretRenderer#MASK} whatever its shape, as in
     * {@code redactBag}. Other scalars and unknown types are returned as-is. The caller has already
     * established that {@code path} itself is not sensitive. The input is never mutated.
     *
     * @param path the dotted path of {@code value} itself
     * @param value the value to mask
     * @return a masked rendering, or {@code value} itself when nothing was masked
     */
    static Object maskValue(String path, Object value) {
        if (value instanceof CharSequence sequence) {
            if (!ConfigSecretRenderer.isUriPath(path)) {
                return value;
            }
            String text = sequence.toString();
            String redacted = ConfigSecretRenderer.redactUri(text);
            return redacted.equals(text) ? value : redacted;
        }
        if (value instanceof JsonObject json) {
            Object masked = maskValue(path, json.getMap());
            return masked == json.getMap() ? json : new JsonObject(castMap(masked));
        }
        if (value instanceof JsonArray array) {
            Object masked = maskValue(path, array.getList());
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
                    Object maskedValue = maskValue(fullKey, entryValue);
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
                Object maskedElement = maskValue(path, element);
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

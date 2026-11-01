// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads one JSON object strictly: every key that is read is marked as known, and {@link #finish()} rejects any key
 * that was never read.
 *
 * <p>A misspelled input is rejected whatever its value is, so a typo whose value equals the default fails as
 * loudly as one that would change the run. The reader also records every value it returns, defaults included, in
 * {@link #resolved()}, so the manifest of a run shows each parameter the run used and not only those the operator
 * wrote.
 */
public final class SpecReader {

    private final JsonObject source;
    private final String path;
    private final JsonObject resolved = new JsonObject();
    private final Set<String> read = new TreeSet<>();
    private final List<SpecReader> children = new ArrayList<>();

    /**
     * Creates a reader.
     *
     * @param source the object to read
     * @param path the dotted path of the object, for messages; empty for the root
     */
    public SpecReader(JsonObject source, String path) {
        this.source = source == null ? new JsonObject() : source;
        this.path = path;
    }

    /**
     * Creates the reader of a root object.
     *
     * @param source the object to read
     * @return the reader
     */
    public static SpecReader root(JsonObject source) {
        return new SpecReader(source, "");
    }

    /**
     * Returns whether the object holds a key.
     *
     * @param key the key
     * @return {@code true} when the key is present
     */
    public boolean has(String key) {
        return source.containsKey(key);
    }

    /**
     * Reads an array of strings.
     *
     * @param key the key
     * @param fallback the value when the key is absent
     * @return the strings
     */
    public List<String> stringList(String key, List<String> fallback) {
        read.add(key);
        Object value = source.getValue(key);
        List<String> result = new ArrayList<>();
        if (value == null) {
            result.addAll(fallback);
        } else if (value instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getValue(i) instanceof String text) || text.isBlank()) {
                    throw fail(key + "[" + i + "]", "must be a non-blank string");
                }
                result.add(text);
            }
        } else {
            throw fail(key, "must be an array of strings");
        }
        resolved.put(key, new JsonArray(new ArrayList<Object>(result)));
        return result;
    }

    /**
     * Reads an optional string.
     *
     * @param key the key
     * @param fallback the value when the key is absent
     * @return the value
     */
    public String string(String key, String fallback) {
        read.add(key);
        Object value = source.getValue(key);
        String result;
        if (value == null) {
            result = fallback;
        } else if (value instanceof String text) {
            result = text;
        } else {
            throw fail(key, "must be a string");
        }
        resolved.put(key, result);
        return result;
    }

    /**
     * Reads a required string.
     *
     * @param key the key
     * @return the value
     */
    public String requiredString(String key) {
        read.add(key);
        if (!(source.getValue(key) instanceof String text) || text.isBlank()) {
            throw fail(key, "is required and must be a non-blank string");
        }
        resolved.put(key, text);
        return text;
    }

    /**
     * Reads a boolean.
     *
     * @param key the key
     * @param fallback the value when the key is absent
     * @return the value
     */
    public boolean bool(String key, boolean fallback) {
        read.add(key);
        Object value = source.getValue(key);
        boolean result;
        if (value == null) {
            result = fallback;
        } else if (value instanceof Boolean flag) {
            result = flag;
        } else {
            throw fail(key, "must be true or false");
        }
        resolved.put(key, result);
        return result;
    }

    /**
     * Reads an integer within bounds.
     *
     * @param key the key
     * @param fallback the value when the key is absent
     * @param min the least accepted value
     * @param max the greatest accepted value
     * @return the value
     */
    public long longValue(String key, long fallback, long min, long max) {
        read.add(key);
        Object value = source.getValue(key);
        long result;
        if (value == null) {
            result = fallback;
        } else if (value instanceof Integer || value instanceof Long) {
            result = ((Number) value).longValue();
        } else {
            throw fail(key, "must be a whole number");
        }
        if (result < min || result > max) {
            throw fail(key, "must be between " + min + " and " + max + " but was " + result);
        }
        resolved.put(key, result);
        return result;
    }

    /**
     * Reads an integer within bounds.
     *
     * @param key the key
     * @param fallback the value when the key is absent
     * @param min the least accepted value
     * @param max the greatest accepted value
     * @return the value
     */
    public int intValue(String key, int fallback, int min, int max) {
        return (int) longValue(key, fallback, min, max);
    }

    /**
     * Reads a number within bounds.
     *
     * @param key the key
     * @param fallback the value when the key is absent
     * @param min the least accepted value
     * @param max the greatest accepted value
     * @return the value
     */
    public double doubleValue(String key, double fallback, double min, double max) {
        read.add(key);
        Object value = source.getValue(key);
        double result;
        if (value == null) {
            result = fallback;
        } else if (value instanceof Number number) {
            result = number.doubleValue();
        } else {
            throw fail(key, "must be a number");
        }
        if (Double.isNaN(result) || result < min || result > max) {
            throw fail(key, "must be between " + min + " and " + max + " but was " + result);
        }
        resolved.put(key, result);
        return result;
    }

    /**
     * Reads a required number within bounds.
     *
     * @param key the key
     * @param min the least accepted value
     * @param max the greatest accepted value
     * @return the value
     */
    public double requiredDouble(String key, double min, double max) {
        if (!source.containsKey(key)) {
            read.add(key);
            throw fail(key, "is required");
        }
        return doubleValue(key, 0, min, max);
    }

    /**
     * Reads a required integer within bounds.
     *
     * @param key the key
     * @param min the least accepted value
     * @param max the greatest accepted value
     * @return the value
     */
    public int requiredInt(String key, int min, int max) {
        if (!source.containsKey(key)) {
            read.add(key);
            throw fail(key, "is required");
        }
        return intValue(key, 0, min, max);
    }

    /**
     * Reads an enum constant by name, ignoring case and accepting a hyphen for an underscore.
     *
     * @param key the key
     * @param type the enum type
     * @param fallback the value when the key is absent
     * @param <E> the enum type
     * @return the value
     */
    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
        String text = string(key, fallback == null ? null : fallback.name());
        if (text == null) {
            throw fail(key, "is required");
        }
        String normalized = text.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                resolved.put(key, constant.name());
                return constant;
            }
        }
        throw fail(key, "must be one of " + List.of(type.getEnumConstants()) + " but was '" + text + "'");
    }

    /**
     * Reads a nested object; an absent object reads as empty, so every key inside takes its default.
     *
     * @param key the key
     * @return the reader of the nested object
     */
    public SpecReader object(String key) {
        read.add(key);
        Object value = source.getValue(key);
        if (value != null && !(value instanceof JsonObject)) {
            throw fail(key, "must be an object");
        }
        SpecReader child = new SpecReader((JsonObject) value, join(key));
        children.add(child);
        resolved.put(key, child.resolved);
        return child;
    }

    /**
     * Reads an array of objects.
     *
     * @param key the key
     * @return one reader per element; empty when the key is absent
     */
    public List<SpecReader> objects(String key) {
        read.add(key);
        Object value = source.getValue(key);
        if (value == null) {
            resolved.put(key, new JsonArray());
            return List.of();
        }
        if (!(value instanceof JsonArray array)) {
            throw fail(key, "must be an array");
        }
        List<SpecReader> readers = new ArrayList<>();
        JsonArray resolvedArray = new JsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (!(array.getValue(i) instanceof JsonObject element)) {
                throw fail(key + "[" + i + "]", "must be an object");
            }
            SpecReader child = new SpecReader(element, join(key) + "[" + i + "]");
            children.add(child);
            readers.add(child);
            resolvedArray.add(child.resolved);
        }
        resolved.put(key, resolvedArray);
        return readers;
    }

    /**
     * Reads an object that is passed on unread, such as a configuration overlay validated elsewhere.
     *
     * @param key the key
     * @return a copy of the object; empty when the key is absent
     */
    public JsonObject rawObject(String key) {
        read.add(key);
        Object value = source.getValue(key);
        if (value != null && !(value instanceof JsonObject)) {
            throw fail(key, "must be an object");
        }
        JsonObject copy = value == null ? new JsonObject() : ((JsonObject) value).copy();
        resolved.put(key, copy);
        return copy;
    }

    /**
     * Rejects every key of this object, and of the objects read from it, that was never read.
     *
     * @throws ConfigurationException naming the first unknown input and the known ones
     */
    public void finish() {
        Set<String> unknown = new TreeSet<>(source.fieldNames());
        unknown.removeAll(read);
        if (!unknown.isEmpty()) {
            String first = unknown.iterator().next();
            throw new ConfigurationException("Unknown input " + join(first) + "; known inputs here are " + read);
        }
        for (SpecReader child : children) {
            child.finish();
        }
    }

    /**
     * Returns every value that was read, defaults included.
     *
     * @return the resolved tree; complete once the object has been read
     */
    public JsonObject resolved() {
        return resolved;
    }

    /**
     * Returns the dotted path of a key of this object.
     *
     * @param key the key
     * @return the path
     */
    public String join(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private ConfigurationException fail(String key, String message) {
        return new ConfigurationException("Input " + join(key) + " " + message);
    }
}

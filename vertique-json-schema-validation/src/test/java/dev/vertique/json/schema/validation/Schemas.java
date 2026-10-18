// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Named builders for the schema documents the tests in this package compare and compile, so a test body
 * reads as the document it proves rather than as nested {@code put} calls.
 */
final class Schemas {

    private Schemas() {}

    /**
     * Parses a JSON literal written with single quotes in place of double quotes, which keeps Java
     * string literals free of escapes.
     *
     * @param quoted the JSON text, {@code '} standing for {@code "}
     * @return the parsed object
     */
    static JsonObject json(String quoted) {
        return new JsonObject(quoted.replace('\'', '"'));
    }

    /**
     * Parses a JSON literal like {@link #json(String)} after expanding two placeholders: {@code <B>} for the
     * bound entry and {@code <P>} for the {@code propertyNames} entry a non-empty {@code patternProperties}
     * gains.
     *
     * @param quoted the JSON text, {@code '} standing for {@code "}
     * @return the parsed object
     */
    static JsonObject jsonWithEntries(String quoted) {
        return json(quoted.replace("<B>", boundEntry().encode())
                .replace("<P>", propertyNamesEntry().encode()));
    }

    /**
     * Returns a new bound entry, {@code {"format": "x-vertique-pattern-input-bound"}}.
     *
     * @return the entry
     */
    static JsonObject boundEntry() {
        return new JsonObject().put("format", PatternInputGuard.BOUND_FORMAT);
    }

    /**
     * Returns a new entry as a non-empty {@code patternProperties} gains it, {@code {"propertyNames":
     * <the bound entry>}}.
     *
     * @return the entry
     */
    static JsonObject propertyNamesEntry() {
        return new JsonObject().put("propertyNames", boundEntry());
    }

    /**
     * Returns a string schema carrying a {@code pattern}.
     *
     * @param pattern the regular expression
     * @return {@code {"type": "string", "pattern": <pattern>}}
     */
    static JsonObject stringWithPattern(String pattern) {
        return new JsonObject().put("type", "string").put("pattern", pattern);
    }

    /**
     * Returns a string schema carrying a {@code format}.
     *
     * @param format the format name
     * @return {@code {"type": "string", "format": <format>}}
     */
    static JsonObject stringWithFormat(String format) {
        return new JsonObject().put("type", "string").put("format", format);
    }

    /**
     * Returns an object schema with the given properties, in the given order.
     *
     * @param nameThenSchema alternating property names and their schemas
     * @return {@code {"type": "object", "properties": {...}}}
     */
    static JsonObject objectWith(Object... nameThenSchema) {
        JsonObject properties = new JsonObject();
        for (int index = 0; index < nameThenSchema.length; index += 2) {
            properties.put((String) nameThenSchema[index], nameThenSchema[index + 1]);
        }
        return new JsonObject().put("type", "object").put("properties", properties);
    }

    /**
     * Returns a copy of {@code node} whose {@code allOf} holds the given entries, appended to the
     * node's own {@code allOf} when it has one.
     *
     * @param node    the schema node
     * @param entries the entries to append
     * @return the copy
     */
    static JsonObject withAllOf(JsonObject node, JsonObject... entries) {
        JsonObject copy = node.copy();
        JsonArray allOf = copy.getJsonArray("allOf", new JsonArray());
        for (JsonObject entry : entries) {
            allOf.add(entry);
        }
        return copy.put("allOf", allOf);
    }
}

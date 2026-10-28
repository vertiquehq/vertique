// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.pointer.JsonPointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * INTERNAL: replaces the values at JSON Pointers with the redaction marker in a JSON document.
 *
 * <p>Pointers are RFC 6901 and are parsed by Vert.x. A pointer that does not resolve in a document, or resolves to a
 * JSON {@code null}, is ignored for that document. A pointer that is not valid fails with an
 * {@link IllegalArgumentException} that names the pointer, never a value. The input is never modified.
 */
public final class HistoryRedaction {

    /** The string a redacted value is replaced by. */
    public static final String REDACTED = "[redacted]";

    private HistoryRedaction() {
        // static helpers only
    }

    /**
     * Replaces the value at each resolvable pointer with {@value #REDACTED}.
     *
     * @param tree     the document
     * @param pointers the RFC 6901 pointers whose values are redacted
     * @return the document itself when there is nothing to redact, otherwise a copy with the values replaced
     * @throws IllegalArgumentException if a pointer is not a valid non-root JSON Pointer; the message names the pointer
     */
    public static JsonObject apply(JsonObject tree, Set<String> pointers) {
        List<JsonPointer> parsed = parse(pointers);
        if (parsed.isEmpty()) {
            return tree;
        }
        JsonObject copy = tree.copy();
        replace(copy, parsed);
        return copy;
    }

    /**
     * Replaces the value at each resolvable pointer in any JSON value: an object or an array is copied and redacted,
     * and any other value (a scalar, or {@code null}) has nothing to resolve into and is returned as it is.
     *
     * @param json     the JSON value as Vert.x represents it
     * @param pointers the RFC 6901 pointers whose values are redacted
     * @return the value itself when there is nothing to redact, otherwise a copy with the values replaced
     * @throws IllegalArgumentException if a pointer is not a valid non-root JSON Pointer; the message names the pointer
     */
    public static Object applyToValue(Object json, Set<String> pointers) {
        List<JsonPointer> parsed = parse(pointers);
        if (parsed.isEmpty()) {
            return json;
        }
        if (json instanceof JsonObject object) {
            JsonObject copy = object.copy();
            replace(copy, parsed);
            return copy;
        }
        if (json instanceof JsonArray array) {
            JsonArray copy = array.copy();
            replace(copy, parsed);
            return copy;
        }
        return json;
    }

    /**
     * The values a set of pointers resolves to in a document, for the redaction of text that may have echoed them.
     *
     * @param tree     the document
     * @param pointers the RFC 6901 pointers
     * @return the resolved non-null values, in pointer order
     * @throws IllegalArgumentException if a pointer is not a valid non-root JSON Pointer; the message names the pointer
     */
    public static List<Object> valuesAt(Object tree, Set<String> pointers) {
        List<Object> values = new ArrayList<>();
        for (JsonPointer pointer : parse(pointers)) {
            Object value = resolve(pointer, tree);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Replaces, in a text, every occurrence of the text form of the given values with {@value #REDACTED}. A scalar is
     * rendered as its string, an object or array as its JSON.
     *
     * @param text   the text, for example a sanitized reason
     * @param values the values to remove from it
     * @return the text with the values replaced
     */
    public static String redactText(String text, List<Object> values) {
        String result = text;
        for (Object value : values) {
            String rendered = value instanceof JsonObject object
                    ? object.encode()
                    : value instanceof JsonArray array ? array.encode() : String.valueOf(value);
            if (!rendered.isEmpty()) {
                result = result.replace(rendered, REDACTED);
            }
        }
        return result;
    }

    private static List<JsonPointer> parse(Set<String> pointers) {
        List<JsonPointer> parsed = new ArrayList<>();
        // a stable order makes the outcome independent of the set's iteration order
        for (String pointer : new TreeSet<>(pointers)) {
            JsonPointer one;
            try {
                one = JsonPointer.from(pointer);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Not a JSON Pointer: '" + bounded(pointer) + "'");
            }
            if (one.isRootPointer() || !pointer.startsWith("/")) {
                throw new IllegalArgumentException("Not a JSON Pointer to a value: '" + bounded(pointer) + "'");
            }
            parsed.add(one);
        }
        return parsed;
    }

    private static void replace(Object root, List<JsonPointer> pointers) {
        for (JsonPointer pointer : pointers) {
            if (resolve(pointer, root) != null) {
                pointer.writeJson(root, REDACTED, false);
            }
        }
    }

    /** Resolves a pointer, with the absence of a path as {@code null}. */
    private static Object resolve(JsonPointer pointer, Object root) {
        try {
            return pointer.queryJson(root);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String bounded(String pointer) {
        return pointer.length() <= 200 ? pointer : pointer.substring(0, 200) + "...";
    }
}

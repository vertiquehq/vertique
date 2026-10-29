// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * INTERNAL: replaces the values at JSON Pointers with the redaction marker in a JSON document.
 *
 * <p>Pointers are RFC 6901, with one extension: a segment that is exactly {@code *} matches every element of an array
 * and every member of an object at that position, so {@code /cards/*}{@code /number} redacts the number of every
 * card whatever the array shifts to. A key that is literally {@code *} cannot be named alone. A pointer that does not
 * resolve in a document, or resolves to a JSON {@code null}, is ignored for that document. A pointer that is not valid
 * fails with an {@link IllegalArgumentException} that names the pointer, never a value. The input is never modified.
 */
public final class HistoryRedaction {

    /** The string a redacted value is replaced by. */
    public static final String REDACTED = "[redacted]";

    /** The pointer segment that matches every element of an array and every member of an object. */
    public static final String WILDCARD = "*";

    private HistoryRedaction() {
        // static helpers only
    }

    /** A parsed pointer: its text for messages and its unescaped segments. */
    private record Pointer(String text, List<String> segments) {}

    /** What a walk does with each value it reaches at the end of a pointer. */
    private interface Visitor {
        void reached(JsonNode parent, Object slot, JsonNode value);
    }

    /**
     * Replaces the value at each resolvable pointer with {@value #REDACTED}.
     *
     * @param tree     the document
     * @param pointers the pointers whose values are redacted
     * @return the document itself when there is nothing to redact, otherwise a copy with the values replaced
     * @throws IllegalArgumentException if a pointer is not a valid non-root JSON Pointer; the message names the pointer
     */
    public static ObjectNode apply(ObjectNode tree, Set<String> pointers) {
        List<Pointer> parsed = parse(pointers);
        if (parsed.isEmpty()) {
            return tree;
        }
        ObjectNode copy = tree.deepCopy();
        replace(copy, parsed);
        return copy;
    }

    /**
     * Replaces the value at each resolvable pointer in any JSON value: an object or an array is copied and redacted,
     * and any other value (a scalar, or {@code null}) has nothing to resolve into and is returned as it is.
     *
     * @param json     the JSON value as Vert.x represents it
     * @param pointers the pointers whose values are redacted
     * @return the value itself when there is nothing to redact, otherwise a copy with the values replaced
     * @throws IllegalArgumentException if a pointer is not a valid non-root JSON Pointer; the message names the pointer
     */
    public static Object applyToValue(Object json, Set<String> pointers) {
        List<Pointer> parsed = parse(pointers);
        if (parsed.isEmpty()) {
            return json;
        }
        if (json instanceof JsonObject object) {
            JsonNode tree = ExactJson.read(object.encode());
            replace(tree, parsed);
            return new JsonObject(tree.toString());
        }
        if (json instanceof JsonArray array) {
            JsonNode tree = ExactJson.read(array.encode());
            replace(tree, parsed);
            return new JsonArray(tree.toString());
        }
        return json;
    }

    /**
     * The values a set of pointers resolves to in a document, for the redaction of text that may have echoed them.
     *
     * @param tree     the document
     * @param pointers the pointers
     * @return the resolved non-null values, in pointer order
     * @throws IllegalArgumentException if a pointer is not a valid non-root JSON Pointer; the message names the pointer
     */
    public static List<JsonNode> valuesAt(JsonNode tree, Set<String> pointers) {
        List<JsonNode> values = new ArrayList<>();
        for (Pointer pointer : parse(pointers)) {
            walk(tree, pointer.segments(), 0, (parent, slot, value) -> values.add(value));
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
    public static String redactText(String text, List<JsonNode> values) {
        String result = text;
        for (JsonNode value : values) {
            String rendered = value.isTextual() ? value.textValue() : value.toString();
            if (!rendered.isEmpty()) {
                result = result.replace(rendered, REDACTED);
            }
        }
        return result;
    }

    private static List<Pointer> parse(Set<String> pointers) {
        List<Pointer> parsed = new ArrayList<>();
        // a stable order makes the outcome independent of the set's iteration order
        for (String pointer : new TreeSet<>(pointers)) {
            if (!pointer.startsWith("/")) {
                throw new IllegalArgumentException(
                        pointer.isEmpty()
                                ? "Not a JSON Pointer to a value: ''"
                                : "Not a JSON Pointer: '" + bounded(pointer) + "'");
            }
            List<String> segments = new ArrayList<>();
            for (String raw : pointer.substring(1).split("/", -1)) {
                segments.add(unescape(raw, pointer));
            }
            parsed.add(new Pointer(pointer, segments));
        }
        return parsed;
    }

    /** Unescapes ~1 and ~0; the wildcard stays the one-character segment. */
    private static String unescape(String segment, String pointer) {
        if (segment.indexOf('~') < 0) {
            return segment;
        }
        StringBuilder out = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '~') {
                out.append(c);
                continue;
            }
            char next = i + 1 < segment.length() ? segment.charAt(i + 1) : ' ';
            if (next != '0' && next != '1') {
                throw new IllegalArgumentException("Not a JSON Pointer: '" + bounded(pointer) + "'");
            }
            out.append(next == '0' ? '~' : '/');
            i++;
        }
        return out.toString();
    }

    private static void replace(JsonNode root, List<Pointer> pointers) {
        for (Pointer pointer : pointers) {
            walk(root, pointer.segments(), 0, HistoryRedaction::setRedacted);
        }
    }

    private static void setRedacted(JsonNode parent, Object slot, JsonNode value) {
        if (parent instanceof ObjectNode object) {
            object.set((String) slot, TextNode.valueOf(REDACTED));
        } else if (parent instanceof ArrayNode array) {
            array.set((Integer) slot, TextNode.valueOf(REDACTED));
        }
    }

    /** Visits the non-null values the segments from {@code index} on reach below {@code node}. */
    private static void walk(JsonNode node, List<String> segments, int index, Visitor visitor) {
        String segment = segments.get(index);
        boolean last = index == segments.size() - 1;
        for (Object slot : slotsOf(node, segment)) {
            JsonNode child = node instanceof ObjectNode ? node.get((String) slot) : node.get((Integer) slot);
            if (child == null || child.isNull()) {
                continue;
            }
            if (last) {
                visitor.reached(node, slot, child);
            } else {
                walk(child, segments, index + 1, visitor);
            }
        }
    }

    /** The members of a container that a segment names: all of them for the wildcard, otherwise at most one. */
    private static List<Object> slotsOf(JsonNode node, String segment) {
        List<Object> slots = new ArrayList<>();
        if (node instanceof ObjectNode) {
            if (WILDCARD.equals(segment)) {
                for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                    slots.add(names.next());
                }
            } else {
                slots.add(segment);
            }
        } else if (node instanceof ArrayNode) {
            if (WILDCARD.equals(segment)) {
                for (int i = 0; i < node.size(); i++) {
                    slots.add(i);
                }
            } else if (isIndex(segment) && Long.parseLong(segment) < node.size()) {
                slots.add(Integer.parseInt(segment));
            }
        }
        return slots;
    }

    /** An RFC 6901 array index: digits without a leading zero, short enough for an int. */
    private static boolean isIndex(String segment) {
        if (segment.isEmpty() || segment.length() > 9) {
            return false;
        }
        if (segment.length() > 1 && segment.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) < '0' || segment.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String bounded(String pointer) {
        return pointer.length() <= 200 ? pointer : pointer.substring(0, 200) + "...";
    }
}

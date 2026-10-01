// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks the schema positions of a captured schema the way the request-validation gate walks a schema
 * for its patterns, and resolves JSON Pointers to schema positions.
 *
 * <p>Every object reached is a schema whose member names are keywords, except the value of a
 * literal keyword ({@code const}, {@code enum}, {@code default}, {@code examples}, {@code example}),
 * which is JSON data and never entered. The members of a {@code properties}, {@code
 * patternProperties}, {@code $defs}, or {@code dependentSchemas} object are names, each holding a
 * schema; every element of an array is a schema position. Any other member is entered as a schema.
 *
 * <p>A walk pointer names each member by its RFC 6901 reference token, with every control character
 * written as a backslash, {@code u}, and four upper-case hexadecimal digits; a {@code
 * patternProperties} member is named by its zero-based ordinal in that object, {@code [key-N]}, so
 * the pattern text never appears.
 */
final class SchemaPositions {

    /** The keywords whose value is JSON data rather than a schema. */
    private static final Set<String> LITERAL_KEYWORDS = Set.of("const", "enum", "default", "examples", "example");

    /** The keyword whose member names are regular expressions. */
    private static final String PATTERN_PROPERTIES = "patternProperties";

    /** The keywords whose value is an object of named schemas. */
    private static final Set<String> NAMED_MEMBER_KEYWORDS =
            Set.of("properties", PATTERN_PROPERTIES, "$defs", "dependentSchemas");

    private SchemaPositions() {}

    /** Receives each keyword member of a schema position, in document order. */
    @FunctionalInterface
    interface KeywordVisitor {

        /**
         * Visits one keyword member. The walk enters the member's value after this returns.
         *
         * @param owner the schema object holding the member
         * @param keyword the member name
         * @param value the member value
         * @param pointer the pointer of the member within the walked schema
         * @param atRoot whether the owner is the walked schema's root object
         */
        void keyword(ObjectNode owner, String keyword, JsonNode value, String pointer, boolean atRoot);
    }

    /**
     * Visits every keyword member at a schema position of a schema, depth first in document order.
     *
     * @param schema the schema to walk
     * @param visitor receives each keyword member
     */
    static void walk(JsonNode schema, KeywordVisitor visitor) {
        walk(schema, "", false, false, true, visitor);
    }

    private static void walk(
            JsonNode value,
            String pointer,
            boolean membersAreNames,
            boolean membersArePatterns,
            boolean atRoot,
            KeywordVisitor visitor) {
        if (value instanceof ObjectNode object) {
            int ordinal = 0;
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                String name = entry.getKey();
                JsonNode member = entry.getValue();
                String memberPointer =
                        pointer + "/" + (membersArePatterns ? "[key-" + ordinal + "]" : pointerToken(name));
                ordinal++;
                if (membersAreNames) {
                    walk(member, memberPointer, false, false, false, visitor);
                    continue;
                }
                visitor.keyword(object, name, member, memberPointer, atRoot);
                if (LITERAL_KEYWORDS.contains(name)) {
                    continue;
                }
                boolean named = NAMED_MEMBER_KEYWORDS.contains(name) && member.isObject();
                walk(member, memberPointer, named, named && PATTERN_PROPERTIES.equals(name), false, visitor);
            }
        } else if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                walk(array.get(index), pointer + "/" + index, false, false, false, visitor);
            }
        }
    }

    /**
     * Tells whether a JSON Pointer, given as its unescaped reference tokens, leads from a schema's
     * root to a schema position holding a schema: an object or a boolean. A pointer that ends on the
     * object of a named-member keyword (such as {@code $defs} itself), on an array, on a scalar, or
     * that passes through literal data or a missing member does not.
     *
     * @param schema the schema the pointer is read in
     * @param tokens the unescaped reference tokens, empty for the root
     * @return whether the pointer resolves to a schema
     */
    static boolean resolvesToSchema(JsonNode schema, List<String> tokens) {
        JsonNode node = schema;
        boolean membersAreNames = false;
        for (String token : tokens) {
            if (node instanceof ObjectNode object) {
                JsonNode member = object.get(token);
                if (member == null) {
                    return false;
                }
                if (membersAreNames) {
                    membersAreNames = false;
                } else if (LITERAL_KEYWORDS.contains(token)) {
                    return false;
                } else {
                    membersAreNames = NAMED_MEMBER_KEYWORDS.contains(token) && member.isObject();
                }
                node = member;
            } else if (node instanceof ArrayNode array) {
                int index = arrayIndex(token);
                if (index < 0 || index >= array.size()) {
                    return false;
                }
                node = array.get(index);
            } else {
                return false;
            }
        }
        return !membersAreNames && (node.isObject() || node.isBoolean());
    }

    /**
     * Escapes one member name as a walk pointer token: RFC 6901 escaping, then every control
     * character written as a backslash, {@code u}, and four upper-case hexadecimal digits.
     *
     * @param name the member name
     * @return the pointer token
     */
    static String pointerToken(String name) {
        String escaped = name.replace("~", "~0").replace("/", "~1");
        StringBuilder token = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (Character.isISOControl(c)) {
                token.append('\\').append('u').append(String.format("%04X", (int) c));
            } else {
                token.append(c);
            }
        }
        return token.toString();
    }

    /**
     * Reads an RFC 6901 array index: {@code 0} or digits without a leading zero.
     *
     * @param token the unescaped reference token
     * @return the index, or -1 when the token is not an array index
     */
    static int arrayIndex(String token) {
        if (token.isEmpty() || token.length() > 9 || (token.length() > 1 && token.charAt(0) == '0')) {
            return -1;
        }
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) < '0' || token.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(token);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Detects the one structural contradiction this package refuses to emit: a schema location whose
 * conjoined subschemas declare <strong>disjoint</strong> explicit {@code type} keywords, and which no
 * instance can therefore ever satisfy.
 *
 * <p>The check is deliberately narrow and provenance-free. It does not attempt schema satisfiability
 * analysis, and it does not care which contributor — a profile fragment, Swagger property metadata, a
 * Jakarta constraint — supplied which keyword. It computes, per <em>conjunctive location</em>, the
 * intersection of the explicit {@code type} value sets found there; an empty intersection is a
 * failure. A location that declares no explicit {@code type} at all always passes.
 *
 * <p>A conjunctive location is:
 *
 * <ul>
 *   <li>an object node;
 *   <li>each of its direct {@code allOf} branches; and
 *   <li>each locally resolvable {@code $ref} target (a {@code #}-rooted JSON pointer, typically into
 *       {@code $defs}).
 * </ul>
 *
 * <p>The closure deliberately does <strong>not</strong> descend through {@code properties},
 * {@code items}, {@code anyOf}, or {@code oneOf}. Those are not unconditional conjunctions: a
 * nullable overridden property, for example, legally produces {@code anyOf: [{"type":"null"},
 * {"type":"string"}]}, which is an alternation and not a contradiction. Each such subschema instead
 * starts its own conjunctive location when the outer walk reaches it, so every object node in the
 * document is checked exactly once as the head of its own location.
 *
 * <p>A literal {@code "type": []} is caught by the same rule: its value set is empty, so the
 * intersection is empty. Reference cycles are bounded by an identity-based visited set, so a document
 * whose {@code $defs} entries reference each other terminates.
 *
 * <p>The walk is call-local and allocates only fresh state, so it adds nothing to the generator's
 * locking obligations. Its cost is bounded by the document size times the size of one location's
 * closure, which for generated schema documents is small.
 */
final class DisjointTypeDetector {

    /** The {@code allOf} keyword whose branches are conjoined with their parent. */
    private static final String ALL_OF = "allOf";

    /** The reference keyword whose local target is conjoined with the referring node. */
    private static final String REF = "$ref";

    /** The keyword whose value sets are intersected. */
    private static final String TYPE = "type";

    /** Maximum length, in UTF-16 code units, of the document path rendered in a failure message. */
    private static final int MAX_PATH_LENGTH = 160;

    /** Maximum length, in UTF-16 code units, of one rendered type set in a failure message. */
    private static final int MAX_TYPE_SET_LENGTH = 96;

    private DisjointTypeDetector() {}

    /**
     * Rejects a generated document that conjoins disjoint explicit {@code type} declarations.
     *
     * @param document the freshly generated schema document; not mutated
     * @throws JsonSchemaGenerationException if any conjunctive location's explicit {@code type} sets
     *     intersect to nothing
     */
    static void requireNoDisjointTypes(JsonNode document) {
        if (document == null) {
            return;
        }
        walk(document, document, "#");
    }

    /**
     * Visits every node of the document, checking each object node as the head of its own conjunctive
     * location.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param node     the node currently being visited
     * @param path     the JSON-pointer-style path of {@code node}, used in failure messages
     */
    private static void walk(JsonNode document, JsonNode node, String path) {
        if (node.isObject()) {
            requireSatisfiableTypes(document, node, path);
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                walk(document, member.getValue(), path + "/" + member.getKey());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                walk(document, node.get(index), path + "/" + index);
            }
        }
    }

    /**
     * Intersects the explicit {@code type} value sets across one conjunctive location.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param start    the object node heading the location
     * @param path     the location's path, used in failure messages
     * @throws JsonSchemaGenerationException if the intersection is empty
     */
    private static void requireSatisfiableTypes(JsonNode document, JsonNode start, String path) {
        Set<String> intersection = null;
        Map<JsonNode, Boolean> visited = new IdentityHashMap<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.add(start);

        while (!pending.isEmpty()) {
            JsonNode node = pending.poll();
            if (!node.isObject() || visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            enqueueConjoined(document, node, pending);

            Set<String> declared = explicitTypes(node.get(TYPE));
            if (declared == null) {
                continue;
            }
            if (intersection == null) {
                intersection = declared;
            } else {
                intersection.retainAll(declared);
            }
            if (intersection.isEmpty()) {
                throw conflict(path, declared);
            }
        }
    }

    /**
     * Adds a node's direct {@code allOf} branches and its locally resolvable {@code $ref} target to
     * the location's pending queue.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param node     the node being expanded
     * @param pending  the queue of nodes still to visit in this location
     */
    private static void enqueueConjoined(JsonNode document, JsonNode node, Deque<JsonNode> pending) {
        JsonNode allOf = node.get(ALL_OF);
        if (allOf != null && allOf.isArray()) {
            allOf.forEach(pending::add);
        }

        JsonNode reference = node.get(REF);
        if (reference != null && reference.isTextual() && reference.textValue().startsWith("#")) {
            JsonNode target = document.at(reference.textValue().substring(1));
            if (!target.isMissingNode()) {
                pending.add(target);
            }
        }
    }

    /**
     * Reads one node's explicit {@code type} declaration as a value set.
     *
     * @param type the {@code type} member's value, possibly {@code null}
     * @return the declared type names, or {@code null} when the node declares no explicit type this
     *     check can reason about
     */
    private static Set<String> explicitTypes(JsonNode type) {
        if (type == null) {
            return null;
        }
        if (type.isTextual()) {
            Set<String> single = new LinkedHashSet<>();
            single.add(type.textValue());
            return single;
        }
        if (type.isArray()) {
            Set<String> declared = new LinkedHashSet<>();
            type.forEach(element -> {
                if (element.isTextual()) {
                    declared.add(element.textValue());
                }
            });
            return declared;
        }
        return null;
    }

    /**
     * Builds the bounded failure for a location whose type sets intersect to nothing.
     *
     * @param path     the location's path
     * @param declared the type set that emptied the intersection
     * @return the exception to throw
     */
    private static JsonSchemaGenerationException conflict(String path, Set<String> declared) {
        return Diagnostics.failure(
                "cannot generate a JSON Schema: the schema at " + Diagnostics.truncate(path, MAX_PATH_LENGTH)
                        + " conjoins disjoint explicit \"type\" declarations, the last of which is "
                        + Diagnostics.truncate(declared.toString(), MAX_TYPE_SET_LENGTH),
                null);
    }
}

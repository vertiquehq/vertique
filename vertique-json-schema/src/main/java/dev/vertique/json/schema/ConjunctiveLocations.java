// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared conjunctive-closure primitive both post-generation walks in this package fold over:
 * {@link DisjointTypeDetector} (its own diagnostics, its own path-carrying document walk) and
 * {@link NumericDomainKeywordFilter} (its own diagnostics-free suppression policy). Each caller owns
 * its policy fold over the closure this class computes; this class owns only the closure itself and
 * the one keyword read both folds need.
 *
 * <p>A conjunctive location is a node plus every node conjoined with it: the node itself, each of its
 * direct {@code allOf} branches, and each locally resolvable {@code $ref} target (a {@code #}-rooted
 * JSON pointer, typically into {@code $defs}), expanded transitively. The expansion deliberately does
 * <strong>not</strong> descend through {@code properties}, {@code items}, {@code anyOf}, or
 * {@code oneOf} — those are not unconditional conjunctions.
 */
final class ConjunctiveLocations {

    /** The {@code allOf} keyword whose branches are conjoined with their parent. */
    static final String ALL_OF = "allOf";

    /** The reference keyword whose local target is conjoined with the referring node. */
    static final String REF = "$ref";

    /** The keyword both callers intersect explicit values of. */
    static final String TYPE = "type";

    private ConjunctiveLocations() {}

    /**
     * Computes one conjunctive location's closure via identity-visited breadth-first expansion,
     * bounding reference cycles: a document whose {@code $defs} entries reference each other
     * terminates.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param start    the node heading the location
     * @return every object node in the closure, in BFS visit order
     */
    static List<JsonNode> closure(JsonNode document, JsonNode start) {
        List<JsonNode> closure = new ArrayList<>();
        Map<JsonNode, Boolean> visited = new IdentityHashMap<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.add(start);

        while (!pending.isEmpty()) {
            JsonNode node = pending.poll();
            if (!node.isObject() || visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            closure.add(node);
            enqueueConjoined(document, node, pending);
        }
        return closure;
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
     * <p>The returned set is always freshly allocated and unshared: a caller may retain it as an
     * accumulator and mutate it directly (e.g. via {@link Set#retainAll(java.util.Collection)})
     * without defensively copying it first.
     *
     * @param type the {@code type} member's value, possibly {@code null}
     * @return a fresh, mutable set of the declared type names, or {@code null} when the node declares
     *     no explicit type this check can reason about
     */
    static Set<String> explicitTypes(JsonNode type) {
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
}

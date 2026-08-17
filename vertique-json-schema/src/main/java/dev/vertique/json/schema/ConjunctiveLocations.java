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
 * direct {@code allOf} branches, and each locally resolvable {@code $ref} target that is itself a
 * schema head (a {@code #}-rooted JSON pointer, typically into {@code $defs}), expanded transitively.
 * The expansion deliberately does <strong>not</strong> descend through {@code properties},
 * {@code items}, {@code anyOf}, or {@code oneOf} — those are not unconditional conjunctions.
 *
 * <p><strong>Why a target must be a schema head.</strong> A {@code $ref} value is not always machine
 * generated: the Swagger module publishes a developer-authored {@code @Schema(ref = "#/...")}
 * verbatim, so a pointer may resolve to any node in the document — a {@code default} value, an
 * annotation keyword's data, or a <em>container</em> such as the object under {@code properties},
 * whose keys are property names rather than keywords. Conjoining such a node would read JSON data as
 * a schema, which is the exact corruption {@link SchemaPositions} exists to prevent at the outer
 * traversal: a data {@code type} would fail a publishable document, and a data {@code type} that is
 * non-numeric would silently strip a genuine schema's numeric bounds. The closure therefore admits a
 * resolved target only when it belongs to the caller-supplied set of schema heads, which
 * {@link SchemaPositions#collectSchemaHeads(JsonNode)} produces in a pass that must complete before
 * any folding starts — a {@code $ref} may point forward to a head later in document order.
 *
 * <p>The closure is <strong>best-effort over locally resolvable, {@code "#/"}-rooted pointers</strong>:
 * a reference this class cannot resolve within the document itself — a JSON Schema {@code $anchor}
 * such as {@code "#anchorName"}, an external URI, an unresolvable pointer — is skipped rather than
 * followed, so the fold a caller performs simply sees fewer conjoined nodes. Skipping is the only
 * sound choice: the referenced subschema's keywords are not available to reason about, and a walk
 * that treated an anchor as a pointer would fail generation on a document the generator is otherwise
 * perfectly able to publish.
 */
final class ConjunctiveLocations {

    /** The {@code allOf} keyword whose branches are conjoined with their parent. */
    static final String ALL_OF = "allOf";

    /** The reference keyword whose local target is conjoined with the referring node. */
    static final String REF = "$ref";

    /** The keyword both callers refine explicit values of. */
    static final String TYPE = "type";

    /** The JSON Schema type whose instances are the integral subset of {@link #NUMBER}'s. */
    private static final String INTEGER = "integer";

    /** The JSON Schema type whose instance set strictly contains {@link #INTEGER}'s. */
    private static final String NUMBER = "number";

    /** The only reference form this class resolves: the whole document. */
    private static final String SELF_REFERENCE = "#";

    /** Prefix of the only other reference form this class resolves: a document-rooted JSON pointer. */
    private static final String LOCAL_POINTER_PREFIX = "#/";

    private ConjunctiveLocations() {}

    /**
     * Computes one conjunctive location's closure via identity-visited breadth-first expansion,
     * bounding reference cycles: a document whose {@code $defs} entries reference each other
     * terminates.
     *
     * @param document    the whole document, used to resolve {@code $ref} pointers
     * @param start       the node heading the location
     * @param schemaHeads the document's complete, identity-comparing set of schema heads, as produced
     *                    by {@link SchemaPositions#collectSchemaHeads(JsonNode)}; a resolved
     *                    {@code $ref} target outside it is skipped
     * @return every object node in the closure, in BFS visit order
     */
    static List<JsonNode> closure(JsonNode document, JsonNode start, Set<JsonNode> schemaHeads) {
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
            enqueueConjoined(document, node, pending, schemaHeads);
        }
        return closure;
    }

    /**
     * Computes the subset of a location's closure that belongs to the location's head alone: the head
     * itself plus its {@code allOf} branches, expanded transitively through {@code allOf} only and
     * <strong>never</strong> through {@code $ref}.
     *
     * <p>This is the only part of a location a caller may safely <em>mutate</em>. A {@code $ref}
     * target — typically a {@code $defs} entry — is shared: every other member referencing it sees the
     * same node, while a policy decided from one referrer's conjoined keywords holds for that referrer
     * only. Reading the whole {@link #closure(JsonNode, JsonNode, Set) closure} and writing only the
     * local branches keeps a per-referrer decision from silently rewriting another member's contract.
     *
     * @param start the node heading the location
     * @return the head and its transitively conjoined {@code allOf} branches, in BFS visit order
     */
    static List<JsonNode> localBranches(JsonNode start) {
        List<JsonNode> branches = new ArrayList<>();
        Map<JsonNode, Boolean> visited = new IdentityHashMap<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.add(start);

        while (!pending.isEmpty()) {
            JsonNode node = pending.poll();
            if (!node.isObject() || visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            branches.add(node);
            enqueueAllOfBranches(node, pending);
        }
        return branches;
    }

    /**
     * Adds a node's direct {@code allOf} branches and its locally resolvable {@code $ref} target to
     * the location's pending queue.
     *
     * <p>A resolved target is enqueued only when it is one of the document's schema heads. Every other
     * resolvable node — a data value, a container — is skipped exactly as an unresolvable reference
     * is: the fold simply sees fewer conjoined nodes, which is always sound because a node that is not
     * a schema carries no keyword the fold may read.
     *
     * @param document    the whole document, used to resolve {@code $ref} pointers
     * @param node        the node being expanded
     * @param pending     the queue of nodes still to visit in this location
     * @param schemaHeads the document's complete, identity-comparing set of schema heads
     */
    private static void enqueueConjoined(
            JsonNode document, JsonNode node, Deque<JsonNode> pending, Set<JsonNode> schemaHeads) {
        enqueueAllOfBranches(node, pending);

        JsonNode reference = node.get(REF);
        if (reference != null && reference.isTextual() && isLocalPointer(reference.textValue())) {
            JsonNode target = document.at(reference.textValue().substring(1));
            if (schemaHeads.contains(target)) {
                pending.add(target);
            }
        }
    }

    /**
     * Adds a node's direct {@code allOf} branches to a pending queue.
     *
     * @param node    the node being expanded
     * @param pending the queue of nodes still to visit
     */
    private static void enqueueAllOfBranches(JsonNode node, Deque<JsonNode> pending) {
        JsonNode allOf = node.get(ALL_OF);
        if (allOf != null && allOf.isArray()) {
            allOf.forEach(pending::add);
        }
    }

    /**
     * Decides whether a {@code $ref} value is a pointer this class can resolve inside the document.
     *
     * <p>Only the whole-document reference {@code "#"} and a document-rooted JSON pointer
     * ({@code "#/..."}) qualify. Every other {@code #}-rooted form is a JSON Schema {@code $anchor},
     * whose fragment is a plain name rather than a pointer expression; handing such a value to
     * {@link JsonNode#at(String)} would raise an unbounded {@code IllegalArgumentException} quoting
     * the annotation text verbatim.
     *
     * @param reference the raw {@code $ref} text
     * @return {@code true} when the reference is a locally resolvable pointer
     */
    private static boolean isLocalPointer(String reference) {
        return SELF_REFERENCE.equals(reference) || reference.startsWith(LOCAL_POINTER_PREFIX);
    }

    /**
     * Narrows one conjunctive accumulator of explicit {@code type} names against the next node's
     * declaration, yielding the types an instance may still have at that location.
     *
     * <p>This is a raw set intersection for every type name but two. JSON Schema defines
     * {@code integer} as the <em>integral subset</em> of {@code number} rather than a sibling of it,
     * so {@code number ∧ integer} is the satisfiable narrowing {@code integer} — never the empty set.
     * A plain {@link Set#retainAll(java.util.Collection)} would report that pair as a contradiction
     * and reject a document the generator is perfectly able to publish: {@code @Schema(allOf =
     * {Integer.class})} on a {@code double} property emits exactly that conjunction.
     *
     * <p>Both callers share this one primitive deliberately. The two post-generation walks reason
     * about the same documents, so a subtype relation known to only one of them would let them
     * disagree about a single location's effective type.
     *
     * <p>An empty result is still a genuine conflict: no type name survived, so no instance can
     * satisfy the conjunction. The accumulator's iteration order is preserved.
     *
     * @param accumulator the types still admissible before this node, never {@code null}
     * @param declared    the explicit types this node declares, never {@code null}
     * @return a fresh, mutable set of the types admissible after this node
     */
    static Set<String> refine(Set<String> accumulator, Set<String> declared) {
        Set<String> refined = new LinkedHashSet<>();
        for (String type : accumulator) {
            if (declared.contains(type)) {
                refined.add(type);
            } else if (NUMBER.equals(type) && declared.contains(INTEGER)) {
                refined.add(INTEGER);
            } else if (INTEGER.equals(type) && declared.contains(NUMBER)) {
                refined.add(INTEGER);
            }
        }
        return refined;
    }

    /**
     * Reads one node's explicit {@code type} declaration as a value set.
     *
     * <p>The returned set is always freshly allocated and unshared: a caller may retain it as an
     * accumulator and hand it to {@link #refine(Set, Set)} without defensively copying it first.
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

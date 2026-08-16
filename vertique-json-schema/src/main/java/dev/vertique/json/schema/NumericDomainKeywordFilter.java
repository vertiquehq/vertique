// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suppresses the Jakarta numeric-domain keywords {@code minimum}, {@code maximum}, {@code
 * exclusiveMinimum}, and {@code exclusiveMaximum} at any conjunctive location whose effective,
 * explicitly declared {@code type} excludes both {@code number} and {@code integer}.
 *
 * <p><strong>Why this exists (PRD §6.2 wire-honesty).</strong> A Jakarta constraint such as {@code
 * @DecimalMin} targets the <em>materialized Java value</em>, not the wire representation a profile
 * override may substitute for it. {@code JakartaValidationModule} has no visibility into a profile's
 * declared {@link dev.vertique.core.json.JsonSchemaTypeOverride} and contributes {@code minimum} at
 * member scope purely from the annotated Java type, regardless of the member's effective wire type.
 * A profile that republishes {@code BigDecimal} as a bounded decimal <em>string</em> — the built-in
 * {@code vertique-strict} profile — would otherwise publish a {@code minimum} keyword that no JSON
 * Schema validator applies to a string instance: an inert, misleading keyword. Bean Validation still
 * enforces the constraint against the materialized Java value; only the published, wire-facing
 * keyword is suppressed.
 *
 * <p>The check is deliberately provenance-free, mirroring {@link DisjointTypeDetector}: it does not
 * ask which contributor supplied a numeric-domain keyword or which override is in effect. It
 * computes, per conjunctive location — the same closure {@link DisjointTypeDetector} defines (a node,
 * its direct {@code allOf} branches, and locally resolvable {@code $ref} targets) — the intersection
 * of explicit {@code type} declarations found there. When that intersection is non-empty and
 * excludes both {@code number} and {@code integer}, the four keywords are removed from every branch
 * in the closure. A location that declares no explicit {@code type} at all is left untouched: with
 * nothing to reason about, suppressing would risk dropping a keyword that legitimately applies.
 *
 * <p>Applied only by the profile-aware construction modes ({@code forInputProfile}/{@code
 * forOutputProfile}) when at least one override is in effect for that direction; {@code
 * withVictoolsDefaults()} never substitutes a wire type for a Java type, so a numeric Jakarta
 * constraint there always targets a genuinely numeric schema and this filter is never invoked in that
 * mode.
 *
 * <p>Runs after {@link DisjointTypeDetector#requireNoDisjointTypes(JsonNode)} has already accepted the
 * document, so an intersection this class computes is never empty when non-{@code null} — a
 * conjunction of genuinely disjoint types would already have failed generation.
 */
final class NumericDomainKeywordFilter {

    /** The Jakarta numeric-domain keywords suppressed when they target a non-numeric wire type. */
    private static final Set<String> NUMERIC_DOMAIN_KEYWORDS =
            Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");

    /** The explicit {@code type} values that keep a numeric-domain keyword applicable. */
    private static final Set<String> NUMERIC_TYPES = Set.of("number", "integer");

    /** The {@code allOf} keyword whose branches are conjoined with their parent. */
    private static final String ALL_OF = "allOf";

    /** The reference keyword whose local target is conjoined with the referring node. */
    private static final String REF = "$ref";

    /** The keyword whose value sets are intersected. */
    private static final String TYPE = "type";

    private NumericDomainKeywordFilter() {}

    /**
     * Walks a freshly generated document in place, removing the numeric-domain keywords from every
     * conjunctive location whose effective explicit type excludes both {@code number} and {@code
     * integer}.
     *
     * @param document the freshly generated schema document; mutated in place
     */
    static void suppressInapplicableNumericKeywords(JsonNode document) {
        if (document == null) {
            return;
        }
        walk(document, document);
    }

    /**
     * Visits every node of the document, evaluating each object node as the head of its own
     * conjunctive location.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param node     the node currently being visited
     */
    private static void walk(JsonNode document, JsonNode node) {
        if (node.isObject()) {
            applyAtLocation(document, node);
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                walk(document, member.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(document, element);
            }
        }
    }

    /**
     * Computes one conjunctive location's effective explicit type intersection and, when it excludes
     * both {@code number} and {@code integer}, strips the numeric-domain keywords from every branch in
     * the location.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param start    the object node heading the location
     */
    private static void applyAtLocation(JsonNode document, JsonNode start) {
        List<ObjectNode> closure = new ArrayList<>();
        Set<String> intersection = null;
        Map<JsonNode, Boolean> visited = new IdentityHashMap<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.add(start);

        while (!pending.isEmpty()) {
            JsonNode node = pending.poll();
            if (!node.isObject() || visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            closure.add((ObjectNode) node);
            enqueueConjoined(document, node, pending);

            Set<String> declared = explicitTypes(node.get(TYPE));
            if (declared == null) {
                continue;
            }
            if (intersection == null) {
                intersection = new LinkedHashSet<>(declared);
            } else {
                intersection.retainAll(declared);
            }
        }

        if (intersection != null && !intersection.isEmpty() && Collections.disjoint(intersection, NUMERIC_TYPES)) {
            for (ObjectNode member : closure) {
                for (String keyword : NUMERIC_DOMAIN_KEYWORDS) {
                    member.remove(keyword);
                }
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
}

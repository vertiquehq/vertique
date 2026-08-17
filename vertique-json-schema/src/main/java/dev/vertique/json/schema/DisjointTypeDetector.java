// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Detects the one structural contradiction this package refuses to emit: a schema location whose
 * conjoined subschemas declare <strong>disjoint</strong> explicit {@code type} keywords, and which no
 * instance can therefore ever satisfy.
 *
 * <p>The check is deliberately narrow and provenance-free. It does not attempt schema satisfiability
 * analysis, and it does not care which contributor — a profile fragment, Swagger property metadata, a
 * Jakarta constraint — supplied which keyword. It folds, per {@link ConjunctiveLocations conjunctive
 * location}, the explicit {@code type} value sets found there through
 * {@link ConjunctiveLocations#refine(Set, Set)}; an empty result is a failure. A location that
 * declares no explicit {@code type} at all always passes.
 *
 * <p>The fold is a set intersection refined by the one subtype relation JSON Schema's type vocabulary
 * carries: {@code integer} is the integral subset of {@code number}, so conjoining the two narrows to
 * {@code integer} rather than emptying. Every other pair of distinct type names is genuinely disjoint.
 *
 * <p>The closure deliberately does <strong>not</strong> descend through {@code properties},
 * {@code items}, {@code anyOf}, or {@code oneOf}. Those are not unconditional conjunctions: a
 * nullable overridden property, for example, legally produces {@code anyOf: [{"type":"null"},
 * {"type":"string"}]}, which is an alternation and not a contradiction. Each such subschema instead
 * starts its own conjunctive location when the outer walk reaches it, so every <em>subschema</em> in
 * the document is checked exactly once as the head of its own location.
 *
 * <p>Which nodes those are is decided by {@link SchemaPositions}, not by this class: the document is
 * traversed through Draft 2020-12 subschema positions only, so a JSON <em>data</em> object sitting in
 * a {@code default}, {@code const}, {@code enum}, or {@code examples} position — or under any
 * annotation keyword the dialect does not define — is never read as a schema and never fails
 * generation. The same rule governs the closure: a {@code $ref} whose pointer resolves to such a node,
 * or to a container object, is skipped rather than conjoined, so a developer-authored
 * {@code @Schema(ref = "#/...")} cannot smuggle data into the conjunction. That requires the complete
 * set of schema heads up front, which is why this walk runs a collection pass before it folds.
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

    /** Maximum length, in UTF-16 code units, of the document path rendered in a failure message. */
    private static final int MAX_PATH_LENGTH = 160;

    /** Maximum length, in UTF-16 code units, of one rendered type set in a failure message. */
    private static final int MAX_TYPE_SET_LENGTH = 96;

    private DisjointTypeDetector() {}

    /**
     * Rejects a generated document that conjoins disjoint explicit {@code type} declarations.
     *
     * <p>{@code document} is never {@code null} in practice: every caller passes the {@code
     * ObjectNode} a successful Victools generation produced, and generation failure is reported
     * before this method is ever reached.
     *
     * @param document the freshly generated schema document; not mutated
     * @throws JsonSchemaGenerationException if any conjunctive location's explicit {@code type} sets
     *     intersect to nothing
     */
    static void requireNoDisjointTypes(JsonNode document) {
        // Pass 1 collects every schema head in the document; pass 2 folds each location's closure
        // against that completed set. The passes cannot be merged: a $ref may point forward to a head
        // the fold has not reached yet, which an incrementally populated set would wrongly skip.
        Set<JsonNode> schemaHeads = SchemaPositions.collectSchemaHeads(document);
        SchemaPositions.visitSchemaHeads(
                document, (schema, path) -> requireSatisfiableTypes(document, schemaHeads, schema, path));
    }

    /**
     * Refines the explicit {@code type} value sets across one conjunctive location into the types an
     * instance may still have there.
     *
     * @param document    the whole document, used to resolve {@code $ref} pointers
     * @param schemaHeads the document's complete set of schema heads, limiting which {@code $ref}
     *                    targets are conjoined
     * @param start       the object node heading the location
     * @param path        the location's path, used in failure messages
     * @throws JsonSchemaGenerationException if the refined set is empty
     */
    private static void requireSatisfiableTypes(
            JsonNode document, Set<JsonNode> schemaHeads, JsonNode start, String path) {
        Set<String> intersection = null;
        for (JsonNode node : ConjunctiveLocations.closure(document, start, schemaHeads)) {
            Set<String> declared = ConjunctiveLocations.explicitTypes(node.get(ConjunctiveLocations.TYPE));
            if (declared == null) {
                continue;
            }
            if (intersection == null) {
                intersection = declared;
            } else {
                intersection = ConjunctiveLocations.refine(intersection, declared);
            }
            if (intersection.isEmpty()) {
                throw conflict(path, declared);
            }
        }
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

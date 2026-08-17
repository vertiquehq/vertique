// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Suppresses the numeric-domain keywords {@code minimum}, {@code maximum}, {@code exclusiveMinimum},
 * {@code exclusiveMaximum}, and {@code multipleOf} at any conjunctive location whose effective,
 * explicitly declared {@code type} excludes both {@code number} and {@code integer}.
 *
 * <p><strong>Why this exists (PRD §6.2 wire-honesty).</strong> A numeric constraint — a Jakarta
 * {@code @DecimalMin}, a Swagger {@code @Schema(multipleOf = ...)} — targets the <em>materialized
 * Java value</em>, not the wire representation a profile override may substitute for it. Neither
 * {@code JakartaValidationModule} nor {@code Swagger2Module} has visibility into a profile's declared
 * {@link dev.vertique.core.json.JsonSchemaTypeOverride}; each contributes its keyword at member scope
 * purely from the annotated Java type, regardless of the member's effective wire type. A profile that
 * republishes {@code BigDecimal} as a bounded decimal <em>string</em> — the built-in {@code
 * vertique-strict} profile — would otherwise publish keywords that no JSON Schema validator applies
 * to a string instance: inert, misleading keywords. Bean Validation still enforces the constraint
 * against the materialized Java value; only the published, wire-facing keyword is suppressed.
 *
 * <p>The keyword set is the whole numeric-domain vocabulary rather than one contributor's share of
 * it, so two keywords carried by the same property never receive opposite treatment.
 *
 * <p>The check is deliberately provenance-free, mirroring {@link DisjointTypeDetector}: it does not
 * ask which contributor supplied a numeric-domain keyword or which override is in effect. It folds,
 * per {@link ConjunctiveLocations conjunctive location}, the explicit {@code type} declarations found
 * there through {@link ConjunctiveLocations#refine(Set, Set)} — the same subtype-aware narrowing
 * {@link DisjointTypeDetector} applies, so the two walks never disagree about one location's
 * effective type. When that effective type is non-empty and excludes both
 * {@code number} and {@code integer}, the keywords are removed from the location's
 * {@link ConjunctiveLocations#localBranches(JsonNode) local branches} — the head and its {@code allOf}
 * branches — and never from a {@code $ref} target, which other members share and whose own effective
 * type may still admit them. A location that declares no explicit {@code type} at all is left
 * untouched: with nothing to reason about, suppressing would risk dropping a keyword that legitimately
 * applies.
 *
 * <p><strong>What that confinement does and does not guarantee.</strong> Suppression is
 * <em>per-referrer</em>. It reaches every keyword the referrer itself contributes — a member-scope
 * constraint lands as a sibling of the {@code $ref}, which is the location's own head — and it reaches
 * a shared {@code $defs} entry only when that entry, visited as a location head in its own right, has
 * an effective type of its own that excludes both numeric types. It therefore does <em>not</em> reach a
 * numeric keyword a shared definition contributes when that definition declares no type of its own:
 * its own closure yields no explicit type, so nothing is suppressed there, and a referrer whose
 * effective type is non-numeric may only rewrite its own local branches. Such a keyword stays visible
 * in that referrer's effective schema. This is a deliberate trade: JSON Schema applies a numeric
 * keyword only to a number instance, so the surviving keyword is inert rather than wrong, whereas
 * rewriting the shared target from one referrer's effective type would strip a sibling's genuinely
 * declared bounds.
 *
 * <p>Applied only by the profile-aware construction modes ({@code forInputProfile}/{@code
 * forOutputProfile}) when at least one override is in effect for that direction; {@code
 * withVictoolsDefaults()} never substitutes a wire type for a Java type, so a numeric constraint
 * there always targets a genuinely numeric schema and this filter is never invoked in that mode.
 *
 * <p>Runs after {@link DisjointTypeDetector#requireNoDisjointTypes(JsonNode)} has already accepted the
 * document, so an intersection this class computes is never empty when non-{@code null} — a
 * conjunction of genuinely disjoint types would already have failed generation.
 */
final class NumericDomainKeywordFilter {

    /** The numeric-domain keywords suppressed when they target a non-numeric wire type. */
    private static final Set<String> NUMERIC_DOMAIN_KEYWORDS =
            Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf");

    /** The explicit {@code type} values that keep a numeric-domain keyword applicable. */
    private static final Set<String> NUMERIC_TYPES = Set.of("number", "integer");

    private NumericDomainKeywordFilter() {}

    /**
     * Walks a freshly generated document in place, removing the numeric-domain keywords from every
     * conjunctive location whose effective explicit type excludes both {@code number} and {@code
     * integer}.
     *
     * <p>The locations visited are the Draft 2020-12 subschema positions {@link SchemaPositions}
     * classifies, never every object node. That distinction is a correctness requirement, not an
     * optimization: this pass <em>mutates</em> what it reaches, so a position-blind traversal would
     * silently delete members out of a caller's JSON data — a {@code minimum} inside a {@code default}
     * value, for instance — which is not a schema and carries no numeric-domain keyword at all.
     *
     * <p>The same requirement governs where a location's effective type is <em>read</em> from: a
     * {@code $ref} target contributes only when it is itself a schema head. Reading a type out of a
     * data object would be the silent half of the same defect — the location's effective type would be
     * decided by JSON data, and a non-numeric data {@code type} would strip a genuine schema's bounds.
     *
     * <p>{@code document} is never {@code null} in practice: the only caller passes the {@code
     * ObjectNode} a successful Victools generation produced.
     *
     * @param document the freshly generated schema document; mutated in place
     */
    static void suppressInapplicableNumericKeywords(JsonNode document) {
        // Pass 1 collects every schema head; pass 2 folds and suppresses. Collecting first is required
        // for correctness, not speed: a $ref may point forward to a head this walk has not reached.
        // The set survives this walk's own mutations, which remove keywords but never a schema node.
        Set<JsonNode> schemaHeads = SchemaPositions.collectSchemaHeads(document);
        SchemaPositions.visitSchemaHeads(document, (schema, path) -> applyAtLocation(document, schemaHeads, schema));
    }

    /**
     * Refines one conjunctive location's explicit type declarations into its effective type and, when
     * that type excludes both {@code number} and {@code integer}, strips the numeric-domain keywords
     * from the location's local branches.
     *
     * <p>The two sets are deliberately different. The effective type is read from the whole
     * {@link ConjunctiveLocations#closure(JsonNode, JsonNode) closure}, because every conjoined node —
     * including a {@code $ref} target — contributes to the member's effective type. The suppression is
     * written only to the location's
     * {@link ConjunctiveLocations#localBranches(JsonNode) local branches}, because a {@code $defs}
     * entry is shared by every member referencing it: rewriting it from one referrer's effective type
     * would strip keywords from members whose own effective type still admits them.
     *
     * @param document    the whole document, used to resolve {@code $ref} pointers
     * @param schemaHeads the document's complete set of schema heads, limiting which {@code $ref}
     *                    targets contribute to the effective type
     * @param start       the object node heading the location
     */
    private static void applyAtLocation(JsonNode document, Set<JsonNode> schemaHeads, JsonNode start) {
        List<JsonNode> closure = ConjunctiveLocations.closure(document, start, schemaHeads);
        Set<String> intersection = null;

        for (JsonNode node : closure) {
            Set<String> declared = ConjunctiveLocations.explicitTypes(node.get(ConjunctiveLocations.TYPE));
            if (declared == null) {
                continue;
            }
            if (intersection == null) {
                intersection = declared;
            } else {
                intersection = ConjunctiveLocations.refine(intersection, declared);
            }
        }

        if (intersection != null && !intersection.isEmpty() && Collections.disjoint(intersection, NUMERIC_TYPES)) {
            for (JsonNode member : ConjunctiveLocations.localBranches(start)) {
                ObjectNode objectMember = (ObjectNode) member;
                for (String keyword : NUMERIC_DOMAIN_KEYWORDS) {
                    objectMember.remove(keyword);
                }
            }
        }
    }
}

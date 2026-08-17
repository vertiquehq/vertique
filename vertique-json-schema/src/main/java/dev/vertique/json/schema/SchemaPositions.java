// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/**
 * The document traversal both post-generation walks in this package fold over: it visits exactly the
 * nodes that are <strong>schemas</strong> in a generated Draft 2020-12 document, and nothing else.
 *
 * <p><strong>Why an allowlist, not a denylist.</strong> Draft 2020-12 specifies that an unrecognized
 * keyword is an <em>annotation</em> — arbitrary JSON data, with no schema meaning. So are the values
 * of several keywords the dialect does define: {@code default}, {@code const}, {@code enum},
 * {@code examples}. A traversal that descended into every object member would therefore reach JSON
 * <em>data</em> and act on it: {@link NumericDomainKeywordFilter} would strip a {@code minimum} member
 * out of a caller's default value, and {@link DisjointTypeDetector} would fail generation because a
 * data object happens to carry two members it reads as conflicting types. Because the set of possible
 * annotation keywords is open, a denylist of "keywords that carry data" can never be completed, while
 * an allowlist of "keywords that carry subschemas" is completable under a fixed dialect. This class
 * owns that allowlist.
 *
 * <p><strong>Pinned-dialect assumption.</strong> The keyword sets below are the subschema positions of
 * Draft 2020-12, which {@link AnnotationJsonSchemaGenerator} pins at both Victools construction sites.
 * They are correct only for that dialect: <em>changing the generator's schema version, or upgrading
 * Victools to a version that emits a different dialect, requires re-verifying every set against the
 * new dialect's vocabularies.</em> {@code GeneratorPostGenerationWalkTest} carries one case per
 * allowlisted keyword, so a position dropped from a set fails a test rather than silently letting an
 * unsatisfiable subschema publish.
 *
 * <p><strong>What is deliberately not descended.</strong> {@code $vocabulary} and
 * {@code dependentRequired} map to values that are not schemas; {@code additionalItems} and
 * {@code dependencies} are Draft-07 spellings this dialect does not define, so under it they are
 * annotations. {@code definitions} is the sharpest case: under the pinned fixed dialect Victools
 * emits {@code $defs}, and {@code dev.vertique.core.json.JsonSchemaFragment} rejects {@code $defs}
 * but not {@code definitions} — so a {@code definitions} member in a generated document can only have
 * come from a profile fragment as annotation data. Descending it is exactly the corruption this class
 * prevents.
 *
 * <p>The traversal seeds only the document root as a schema head. A <em>container</em> node — the
 * object that is the value of {@code properties}, {@code patternProperties}, {@code $defs}, or
 * {@code dependentSchemas} — is never itself a schema head; only its member values are. Within a
 * keyword that can never carry data the traversal is shape-tolerant: {@code items} written as an
 * object and {@code items} written as an array are both descended, since neither shape can be
 * anything but a subschema there.
 *
 * <p>The traversal is call-local, allocates only fresh state, and never mutates the document, so it
 * adds nothing to the generator's locking obligations. Each caller owns its own policy fold and its
 * own diagnostics over the heads this class hands it.
 */
final class SchemaPositions {

    /** Keywords whose value is a single subschema. */
    private static final Set<String> SINGLE_SUBSCHEMA_KEYWORDS = Set.of(
            "not",
            "if",
            "then",
            "else",
            "items",
            "contains",
            "additionalProperties",
            "propertyNames",
            "unevaluatedItems",
            "unevaluatedProperties",
            "contentSchema");

    /** Keywords whose value is an array of subschemas. */
    private static final Set<String> SUBSCHEMA_ARRAY_KEYWORDS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");

    /** Keywords whose value is an object whose <em>member values</em> are subschemas. */
    private static final Set<String> SUBSCHEMA_MAP_KEYWORDS =
            Set.of("properties", "patternProperties", "$defs", "dependentSchemas");

    /** The JSON-pointer-style path of the document root, which is the only seeded schema head. */
    private static final String ROOT_PATH = "#";

    private SchemaPositions() {}

    /**
     * Receives each node the traversal classifies as a schema.
     *
     * <p>A caller that needs no path — {@link NumericDomainKeywordFilter}, whose suppression is
     * diagnostics-free — simply ignores the second argument. Sharing one traversal rather than one
     * classification keeps the two walks from ever disagreeing about what counts as a schema.
     */
    @FunctionalInterface
    interface SchemaHeadVisitor {

        /**
         * Handles one schema node.
         *
         * @param schema the object node occupying a schema position
         * @param path   the JSON-pointer-style path of {@code schema} within the document
         */
        void visit(JsonNode schema, String path);
    }

    /**
     * Visits the document root and every node reachable from it through a subschema position, in
     * document order.
     *
     * <p>A non-object document has no schema position at all and is visited not at all, which is
     * vacuously correct: neither walk has anything to fold over a scalar or an array root.
     *
     * @param document the whole generated document
     * @param visitor  the caller's per-schema policy
     */
    static void visitSchemaHeads(JsonNode document, SchemaHeadVisitor visitor) {
        visitSchema(document, ROOT_PATH, visitor);
    }

    /**
     * Visits one schema node and descends into its subschema positions.
     *
     * @param schema  the node occupying a schema position; ignored unless it is an object
     * @param path    the node's path within the document
     * @param visitor the caller's per-schema policy
     */
    private static void visitSchema(JsonNode schema, String path, SchemaHeadVisitor visitor) {
        if (!schema.isObject()) {
            return;
        }
        visitor.visit(schema, path);

        for (Map.Entry<String, JsonNode> member : schema.properties()) {
            String keyword = member.getKey();
            String memberPath = path + "/" + keyword;
            if (SINGLE_SUBSCHEMA_KEYWORDS.contains(keyword) || SUBSCHEMA_ARRAY_KEYWORDS.contains(keyword)) {
                visitSubschemaOrArray(member.getValue(), memberPath, visitor);
            } else if (SUBSCHEMA_MAP_KEYWORDS.contains(keyword)) {
                visitSubschemaMap(member.getValue(), memberPath, visitor);
            }
        }
    }

    /**
     * Descends a keyword whose value is either one subschema or an array of subschemas.
     *
     * <p>The two shapes share one method deliberately. Every keyword routed here is defined by the
     * dialect, so its value can never be annotation data whichever shape it takes — {@code items} is
     * a single subschema in Draft 2020-12 and an array in Draft-07, and treating either as a schema
     * position is sound. Accepting both is what lets the allowlist stay a statement about
     * <em>positions</em> rather than about one dialect's exact value shapes.
     *
     * @param value   the keyword's value
     * @param path    the keyword's path within the document
     * @param visitor the caller's per-schema policy
     */
    private static void visitSubschemaOrArray(JsonNode value, String path, SchemaHeadVisitor visitor) {
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                visitSchema(value.get(index), path + "/" + index, visitor);
            }
        } else {
            visitSchema(value, path, visitor);
        }
    }

    /**
     * Descends a keyword whose value is an object whose member values are subschemas.
     *
     * <p>The container object itself is <strong>never</strong> visited as a schema. It is a map of
     * property names to subschemas, so reading its own members as keywords would treat a property
     * literally named {@code type} — or {@code minimum} — as a declaration about the container.
     *
     * @param container the keyword's value
     * @param path      the keyword's path within the document
     * @param visitor   the caller's per-schema policy
     */
    private static void visitSubschemaMap(JsonNode container, String path, SchemaHeadVisitor visitor) {
        if (!container.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> member : container.properties()) {
            visitSchema(member.getValue(), path + "/" + member.getKey(), visitor);
        }
    }
}

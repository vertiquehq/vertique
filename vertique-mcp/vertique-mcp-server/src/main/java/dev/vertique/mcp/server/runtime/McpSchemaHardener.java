// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies MCP's protocol-owned hardening pass to a JSON-005 canonical schema document.
 *
 * <p>Hardening is document-driven, never type-graph-driven, and never dereferences a {@code $ref}:
 * it descends a fixed, fixture-backed schema grammar — {@code properties}, {@code items},
 * {@code additionalProperties}, {@code prefixItems}, {@code anyOf}, {@code oneOf}, {@code allOf},
 * {@code $defs} — keyed on schema position, with visited-node tracking so a structural cycle would
 * still terminate the walk. Two rewrites are applied:
 *
 * <ol>
 *   <li>the root carrier object is closed unconditionally by provenance — including a zero-argument
 *       carrier — by setting {@code additionalProperties: false}; a non-root object schema is closed
 *       the same way exactly when it declares a non-empty {@code properties} member, no sibling
 *       {@code $ref} (a sibling {@code false} beside a {@code $ref} would reject the referenced
 *       object's entire property set), and no {@code additionalProperties} member of its own. A
 *       declared {@code additionalProperties} — a schema, {@code true}, or {@code false} — is never
 *       overwritten, so a type that publishes its typed extra keys keeps accepting them; the walk
 *       descends into that declared schema, closing the value type like any other subschema. A
 *       property-less non-root object — a resolved map included — stays open and
 *       schema-unconstrained for values.
 *   <li>parameter descriptions are attached to root-carrier properties only, as a separate pass
 *       keyed on {@link McpToolParameterMetadata#externalName()}.
 * </ol>
 *
 * <p>The supplied document is mutated in place: the caller owns a private tree parsed by
 * {@link McpCanonicalJsonWriter#read(String)} from JSON-005's own output, so no caller-visible alias
 * is affected. This is not a re-serialization step — {@link McpCanonicalJsonWriter} owns MCP's own
 * canonical re-encoding afterward.
 */
final class McpSchemaHardener {

    /** The three subschema-array positions whose branches are each closed individually. */
    private static final List<String> SUBSCHEMA_ARRAY_KEYWORDS = List.of("anyOf", "oneOf", "allOf");

    private McpSchemaHardener() {}

    /**
     * Hardens one JSON-005 canonical document: closes the argument-object boundary and attaches the
     * declared parameter descriptions to the root carrier's properties.
     *
     * @param json005CanonicalDocument the parsed JSON-005 canonical document; must be a JSON object,
     *     and is mutated in place
     * @param parameters the position-stable declared-parameter metadata whose descriptions apply to
     *     root-carrier properties only
     * @return the same node, hardened
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code json005CanonicalDocument} is not a JSON object
     */
    static ObjectNode harden(JsonNode json005CanonicalDocument, List<McpToolParameterMetadata> parameters) {
        Objects.requireNonNull(json005CanonicalDocument, "json005CanonicalDocument");
        Objects.requireNonNull(parameters, "parameters");
        if (!json005CanonicalDocument.isObject()) {
            throw new IllegalArgumentException("the JSON-005 canonical document must be a JSON object");
        }
        ObjectNode root = (ObjectNode) json005CanonicalDocument;
        closeRecursively(root, true, new IdentityHashMap<>());
        applyParameterDescriptions(root, parameters);
        return root;
    }

    /**
     * Closes {@code node} when its position requires it, then descends the fixed grammar.
     *
     * @param node the schema node being visited
     * @param isRoot whether {@code node} is the document root
     * @param visited the identity-keyed visited set guarding against a structural cycle
     */
    private static void closeRecursively(ObjectNode node, boolean isRoot, Map<JsonNode, Boolean> visited) {
        if (visited.put(node, Boolean.TRUE) != null) {
            // Defensive cycle guard: a tree parsed from JSON-005's own document text cannot
            // structurally cycle (no dereferencing happens here), but the walk stays terminating.
            return;
        }
        if (isRoot) {
            close(node);
        } else if (hasNonEmptyProperties(node) && !node.has("$ref") && !node.has("additionalProperties")) {
            close(node);
        }
        descendInto(node, "properties", visited, McpSchemaHardener::propertyValues);
        descendIntoSingle(node, "items", visited);
        descendIntoSingle(node, "additionalProperties", visited);
        descendInto(node, "prefixItems", visited, McpSchemaHardener::arrayElements);
        for (String keyword : SUBSCHEMA_ARRAY_KEYWORDS) {
            descendInto(node, keyword, visited, McpSchemaHardener::arrayElements);
        }
        descendInto(node, "$defs", visited, McpSchemaHardener::propertyValues);
    }

    private static void close(ObjectNode node) {
        node.put("additionalProperties", false);
    }

    private static boolean hasNonEmptyProperties(ObjectNode node) {
        JsonNode properties = node.get("properties");
        return properties != null && properties.isObject() && !properties.isEmpty();
    }

    private static void descendIntoSingle(ObjectNode node, String keyword, Map<JsonNode, Boolean> visited) {
        JsonNode child = node.get(keyword);
        if (child != null && child.isObject()) {
            closeRecursively((ObjectNode) child, false, visited);
        }
    }

    private static void descendInto(
            ObjectNode node,
            String keyword,
            Map<JsonNode, Boolean> visited,
            java.util.function.Function<JsonNode, Iterable<JsonNode>> childrenOf) {
        JsonNode holder = node.get(keyword);
        if (holder == null) {
            return;
        }
        for (JsonNode child : childrenOf.apply(holder)) {
            if (child.isObject()) {
                closeRecursively((ObjectNode) child, false, visited);
            }
        }
    }

    private static Iterable<JsonNode> propertyValues(JsonNode holder) {
        return holder.isObject() ? holder : List.of();
    }

    private static Iterable<JsonNode> arrayElements(JsonNode holder) {
        return holder.isArray() ? holder : List.of();
    }

    /**
     * Attaches each parameter's description to its matching root-carrier property, when the property
     * is present and the description is non-blank. Never descends past the root's direct properties.
     *
     * @param root the document root
     * @param parameters the declared-parameter metadata
     */
    private static void applyParameterDescriptions(ObjectNode root, List<McpToolParameterMetadata> parameters) {
        JsonNode properties = root.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (McpToolParameterMetadata parameter : parameters) {
            JsonNode propertySchema = properties.get(parameter.externalName());
            if (propertySchema != null
                    && propertySchema.isObject()
                    && !parameter.description().isBlank()) {
                ((ObjectNode) propertySchema).put("description", parameter.description());
            }
        }
    }
}

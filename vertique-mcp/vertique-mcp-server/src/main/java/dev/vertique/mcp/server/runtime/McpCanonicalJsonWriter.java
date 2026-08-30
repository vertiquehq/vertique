// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP's own package-private neutral JSON reader and canonical writer.
 *
 * <p>JSON-005's own {@code SchemaCanonicalizer} and {@code NeutralJson} are package-private inside
 * {@code vertique-json-schema} and are never called, referenced, or reflected into from this
 * module. After {@link McpSchemaHardener} mutates a parsed JSON-005 canonical document, MCP must
 * re-serialize it deterministically itself; this class is that seam.
 *
 * <p>Canonicalization rebuilds every object with its keys ordered by
 * {@link String#compareTo(String)} (UTF-16 code-unit order), recursively; array element order is
 * never changed. The rebuilt tree is written as compact JSON through a plain, never-mutated
 * {@link ObjectMapper} — never through a profile's payload mapper, so the result is independent of
 * any application-configured serialization feature. This is the same UTF-16 key ordering and
 * compact encoding JSON-005's own canonicalizer uses, which is what keeps a document MCP has not
 * mutated byte-identical after a round trip through this class (the differential compatibility
 * corpus bound).
 */
final class McpCanonicalJsonWriter {

    /** The plain, never-mutated mapper backing every read and write in this class. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The compact writer every canonical document is emitted through. */
    private static final ObjectWriter WRITER = MAPPER.writer();

    private McpCanonicalJsonWriter() {}

    /**
     * Reads JSON text into a fresh, unshared tree through the neutral mapper.
     *
     * @param json the JSON text; must not be {@code null}
     * @return the parsed tree
     * @throws NullPointerException if {@code json} is {@code null}
     * @throws IllegalArgumentException if {@code json} is not well-formed JSON
     */
    static JsonNode read(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException failed) {
            throw new IllegalArgumentException("not well-formed JSON", failed);
        }
    }

    /**
     * Serializes a node as MCP's canonical compact JSON text: keys recursively sorted in UTF-16 code
     * unit order, array order preserved, through the neutral writer.
     *
     * @param document the node to serialize; must not be {@code null}
     * @return the canonical, compact document text
     * @throws NullPointerException if {@code document} is {@code null}
     * @throws IllegalStateException if the rebuilt tree cannot be serialized
     */
    static String writeCanonical(JsonNode document) {
        Objects.requireNonNull(document, "document");
        try {
            return WRITER.writeValueAsString(canonicalNode(document));
        } catch (JsonProcessingException failed) {
            throw new IllegalStateException("canonicalization of the MCP schema document failed", failed);
        }
    }

    /**
     * Rebuilds one node in canonical form.
     *
     * @param node the node to rebuild; never {@code null}
     * @return a fresh canonical node, or the node itself when it is a scalar
     */
    private static JsonNode canonicalNode(JsonNode node) {
        if (node.isObject()) {
            return canonicalObject(node);
        }
        if (node.isArray()) {
            ArrayNode rebuilt = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode element : node) {
                rebuilt.add(canonicalNode(element));
            }
            return rebuilt;
        }
        return node;
    }

    /**
     * Rebuilds an object node with its keys in {@link String#compareTo(String)} order.
     *
     * @param node the object node to rebuild
     * @return the fresh canonical object node
     */
    private static JsonNode canonicalObject(JsonNode node) {
        List<String> keys = new ArrayList<>(node.size());
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            keys.add(member.getKey());
        }
        Collections.sort(keys);

        ObjectNode rebuilt = JsonNodeFactory.instance.objectNode();
        for (String key : keys) {
            rebuilt.set(key, canonicalNode(node.get(key)));
        }
        return rebuilt;
    }
}

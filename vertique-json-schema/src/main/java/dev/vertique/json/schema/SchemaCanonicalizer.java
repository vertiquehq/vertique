// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Turns a freshly generated schema tree into this package's canonical document text.
 *
 * <p>Canonicalization is two rewrites and one serialization:
 *
 * <ol>
 *   <li>every object member whose key is {@code default} and whose value is exactly the string
 *       {@code ##default} — the Swagger 2 sentinel for an unset annotation default — is removed,
 *       recursively, preserving the behavior REST has today;
 *   <li>every object's keys are ordered by {@link String#compareTo(String)} (UTF-16 code-unit
 *       order), recursively; array element order is <strong>never</strong> changed; and
 *   <li>the rebuilt tree is written as compact JSON through the neutral writer in
 *       {@link NeutralJson} — never through a profile's payload mapper.
 * </ol>
 *
 * <p>The rewrite builds fresh nodes rather than mutating the supplied tree, so the caller's tree is
 * untouched and every intermediate is call-local. All state used here is call-local, which is what
 * lets the generator hold only one instance-wide lock over the whole operation.
 */
final class SchemaCanonicalizer {

    /** The object key whose sentinel value is stripped. */
    private static final String DEFAULT_KEYWORD = "default";

    /** The Swagger 2 sentinel emitted for an unset {@code @Schema} default value. */
    private static final String DEFAULT_SENTINEL = "##default";

    private SchemaCanonicalizer() {}

    /**
     * Canonicalizes a generated schema tree into its compact document text.
     *
     * @param document the generated schema tree; not mutated
     * @return the canonical, compact document text
     * @throws JsonProcessingException if the rebuilt tree cannot be serialized
     */
    static String canonicalize(JsonNode document) throws JsonProcessingException {
        return NeutralJson.writeCompact(canonicalNode(document));
    }

    /**
     * Rebuilds one node in canonical form.
     *
     * <p>{@code node} is never {@code null}: the top-level call passes the generated document itself,
     * and every recursive call passes either an array element (Jackson never yields a {@code null}
     * array element — an explicit JSON {@code null} is a non-null {@code NullNode} instance) or the
     * value read back for a key {@link #canonicalObject(JsonNode)} just observed present.
     *
     * @param node the node to rebuild
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
     * Rebuilds an object node with its keys in {@link String#compareTo(String)} order, dropping the
     * Swagger sentinel default.
     *
     * @param node the object node to rebuild
     * @return the fresh canonical object node
     */
    private static JsonNode canonicalObject(JsonNode node) {
        List<String> keys = new ArrayList<>(node.size());
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            if (isSentinelDefault(member.getKey(), member.getValue())) {
                continue;
            }
            keys.add(member.getKey());
        }
        Collections.sort(keys);

        ObjectNode rebuilt = JsonNodeFactory.instance.objectNode();
        for (String key : keys) {
            rebuilt.set(key, canonicalNode(node.get(key)));
        }
        return rebuilt;
    }

    /**
     * Returns whether a member is the Swagger unset-default sentinel.
     *
     * <p>{@code value} is never {@code null}: it is always an object member's value read from {@link
     * JsonNode#properties()}, and Jackson represents an explicit JSON {@code null} as a non-null
     * {@code NullNode} instance rather than a Java {@code null} reference.
     *
     * @param key   the member key
     * @param value the member value
     * @return {@code true} when the member is {@code "default": "##default"}
     */
    private static boolean isSentinelDefault(String key, JsonNode value) {
        return DEFAULT_KEYWORD.equals(key) && value.isTextual() && DEFAULT_SENTINEL.equals(value.textValue());
    }
}

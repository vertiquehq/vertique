// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.json.JsonObject;

/**
 * Copies captured and generated schemas into Jackson trees the document owns, and holds the JSON
 * mapper the document's trees are built and written with.
 */
final class SchemaTrees {

    /** The JSON mapper, with Jackson's default settings, that builds and writes document trees. */
    static final ObjectMapper JSON = new ObjectMapper();

    private SchemaTrees() {}

    /**
     * Copies a captured schema into a document tree node. The captured object is only read: the
     * returned tree is a new, independent copy, so changing it never reaches the captured object.
     *
     * @param captured the captured schema
     * @return a new tree holding the same members, in the same order
     * @throws IllegalStateException when the captured schema cannot be read as JSON
     */
    static ObjectNode tree(JsonObject captured) {
        try {
            return (ObjectNode) JSON.readTree(captured.encode());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A captured schema cannot be read as JSON", e);
        }
    }
}

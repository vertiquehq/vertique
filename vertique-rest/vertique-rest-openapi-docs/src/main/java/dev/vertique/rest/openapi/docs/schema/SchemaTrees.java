// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.json.JsonObject;

/**
 * Copies captured and generated schemas into Jackson trees the document owns.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class SchemaTrees {

    /** The JSON mapper, with Jackson's default settings, that reads captured schemas into trees. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private SchemaTrees() {}

    /**
     * Copies a captured schema into a document tree node. The captured object is only read: the
     * returned tree is a new, independent copy, so changing it never reaches the captured object.
     *
     * @param captured the captured schema
     * @return a new tree holding the same members, in the same order
     * @throws IllegalStateException when the captured schema cannot be read as JSON
     */
    public static ObjectNode tree(JsonObject captured) {
        try {
            return (ObjectNode) JSON.readTree(captured.encode());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A captured schema cannot be read as JSON", e);
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * The schema of one payload type.
 *
 * <p>The document is the canonical Draft 2020-12 JSON Schema generated for the type under the process JSON profile.
 * The property order tells a client in which order to present the keys of the document's top-level {@code
 * properties}, which the canonical document itself lists alphabetically: first the properties in the order the
 * profile's JSON mapper deserializes them (declaration or creator order, or an explicit {@code @JsonPropertyOrder}),
 * then any remaining key in the document's order, so alias spellings follow every primary name. Each key appears
 * exactly once. The storage of an any-setter is not a published property and adds no entry.
 *
 * @param document the schema document; the accessor returns a copy, so a caller can modify it freely
 * @param propertyOrder the keys of the document's top-level {@code properties} in presentation order; an unmodifiable
 *     copy; empty when the type is an object by definition ({@code JsonObject} or {@code ObjectNode}) or declares no
 *     properties
 */
public record PayloadSchema(JsonObject document, List<String> propertyOrder) {

    /** Copies the document and the list so that the schema is immutable. */
    public PayloadSchema {
        document = document.copy();
        propertyOrder = List.copyOf(propertyOrder);
    }

    /**
     * The schema document.
     *
     * @return a copy of the document
     */
    @Override
    public JsonObject document() {
        return document.copy();
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Writes a served contract in its JSON and YAML forms from the one parsed tree.
 *
 * <p>Both forms are written from the same tree with its members in parsed order, and nothing is
 * added, removed, or rewritten: no enrichment, no redaction, no validation disclosure, and the
 * {@code servers} and {@code info} exactly as parsed. The JSON form is compact UTF-8; the YAML form is
 * written with the default settings of Jackson's YAML factory, so parsing either form gives a tree
 * equal to the parsed one. Each form carries a strong entity tag computed as {@link
 * DocumentWriter#entityTag} computes it, so the stored document has the shape of a generated one.
 */
final class ServedContractRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ServedContractRenderer() {}

    /**
     * Renders a parsed contract in both forms. The tree is not modified.
     *
     * @param contract the parsed contract
     * @param snapshot the snapshot of the mount whose application serves the contract
     * @return the contract in both forms, with the entity tag of each
     * @throws IllegalStateException when the tree cannot be serialized, with no cause attached
     */
    static PublishedDocument render(JsonNode contract, Snapshot snapshot) {
        try {
            byte[] json = JSON.writeValueAsBytes(contract);
            byte[] yaml = YAML.writeValueAsBytes(contract);
            return new PublishedDocument(
                    json, yaml, DocumentWriter.entityTag(json), DocumentWriter.entityTag(yaml), snapshot);
        } catch (JsonProcessingException unserializable) {
            // The serializer's message can quote content; it is never chained.
            throw new IllegalStateException("The served contract cannot be serialized");
        }
    }
}

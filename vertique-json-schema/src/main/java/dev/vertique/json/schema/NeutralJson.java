// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * The generator-owned <em>neutral</em> Jackson facilities.
 *
 * <p>Every schema document this package emits, and every profile fragment it re-reads, goes through
 * the plain {@link ObjectMapper} held here — never through a profile's payload mapper. A payload
 * mapper may carry registered tree-node serializers, custom character escapes, or other
 * serialization features that would alter or invalidate the schema document's bytes; the profile
 * mapper's only role is driving Victools' Jackson property discovery.
 *
 * <p>The mapper is configured with defaults only, is never mutated after class initialization, and
 * is therefore safe to share: Jackson's {@link ObjectMapper} and {@link ObjectWriter} are
 * thread-safe once configuration is complete.
 */
final class NeutralJson {

    /** The plain, never-mutated mapper backing every read and write in this package. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The compact writer every canonical document is emitted through. */
    private static final ObjectWriter WRITER = MAPPER.writer();

    private NeutralJson() {}

    /**
     * Serializes a node as compact JSON through the neutral writer.
     *
     * @param node the node to serialize
     * @return the compact JSON text
     * @throws JsonProcessingException if Jackson cannot serialize the node
     */
    static String writeCompact(JsonNode node) throws JsonProcessingException {
        return WRITER.writeValueAsString(node);
    }

    /**
     * Reads JSON text into a fresh, unshared tree through the neutral mapper.
     *
     * @param json the JSON text
     * @return the parsed tree
     * @throws JsonProcessingException if the text is not well-formed JSON
     */
    static JsonNode read(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

/**
 * Given values and pinned expectations for {@link McpSchemaHardeningTest}, each hand-authored to
 * exactly the shape {@code AnnotationJsonSchemaGenerator.generateCanonical(Type)} produces for a
 * record, a nested-object, a map-valued, and a polymorphic MCP input carrier — including the real
 * generator's {@code $schema} member, so hardening's byte-preservation of every untouched construct
 * is exercised, not merely assumed.
 */
final class McpSchemaHardeningTestFixture {

    private McpSchemaHardeningTestFixture() {}

    // --- Row 1: nested-object tool ---

    /**
     * The nested-object fixture's adjacent-object marker is {@code address}'s non-empty
     * {@code properties} member: emptying it (the sensitivity mutation) removes {@code address} as a
     * closure candidate without touching anything else in this document.
     */
    static final String NESTED_OBJECT_TOOL_DOCUMENT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"address\":{\"properties\":{\"city\":{\"type\":\"string\"},"
            + "\"street\":{\"type\":\"string\"}},\"type\":\"object\"},\"label\":{\"type\":\"string\"}},"
            + "\"type\":\"object\"}";

    static final String NESTED_OBJECT_TOOL_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"address\":{\"additionalProperties\":false,"
                    + "\"properties\":{\"city\":{\"type\":\"string\"},\"street\":{\"type\":\"string\"}},"
                    + "\"type\":\"object\"},\"label\":{\"type\":\"string\"}},\"type\":\"object\"}";

    // --- Row 2: record tool ---

    static final String RECORD_TOOL_DOCUMENT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"city\":{\"type\":\"string\"},\"zip\":{\"type\":\"integer\"}},"
            + "\"type\":\"object\"}";

    static final String RECORD_TOOL_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"city\":{\"description\":\"The city name.\",\"type\":\"string\"},"
                    + "\"zip\":{\"description\":\"The zip code.\",\"type\":\"integer\"}},\"type\":\"object\"}";

    // --- Row 3: map-valued tool ---

    static final String MAP_VALUED_TOOL_DOCUMENT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"counts\":{\"type\":\"object\"}},\"type\":\"object\"}";

    static final String MAP_VALUED_TOOL_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"counts\":{\"type\":\"object\"}},\"type\":\"object\"}";

    // --- Row 4: polymorphic tool ---

    static final String POLYMORPHIC_TOOL_DOCUMENT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"pet\":{\"anyOf\":["
            + "{\"properties\":{\"indoor\":{\"type\":\"boolean\"},\"name\":{\"type\":\"string\"}},\"type\":\"object\"},"
            + "{\"properties\":{\"breed\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"type\":\"object\"}"
            + "]}},\"type\":\"object\"}";

    static final String POLYMORPHIC_TOOL_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"pet\":{\"anyOf\":["
                    + "{\"additionalProperties\":false,"
                    + "\"properties\":{\"indoor\":{\"type\":\"boolean\"},\"name\":{\"type\":\"string\"}},"
                    + "\"type\":\"object\"},"
                    + "{\"additionalProperties\":false,"
                    + "\"properties\":{\"breed\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},"
                    + "\"type\":\"object\"}"
                    + "]}},\"type\":\"object\"}";
}

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

    // --- T005 TP-001: a declared additionalProperties is kept, and the walk descends into it ---

    /**
     * A non-root object declaring {@code properties} beside a typed {@code additionalProperties}
     * schema — the shape FR-015's any-setter description produces for a type with named properties.
     */
    static final String DECLARED_EXTRAS_SCHEMA_DOCUMENT =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                    + "\"properties\":{\"profile\":{\"additionalProperties\":{\"type\":\"string\"},"
                    + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /** Only the root carrier is closed; the declared extras schema is kept verbatim. */
    static final String DECLARED_EXTRAS_SCHEMA_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"profile\":{\"additionalProperties\":{\"type\":\"string\"},"
                    + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /**
     * A non-root object declaring {@code properties} beside {@code additionalProperties: true} — the
     * shape an application-authored profile override fragment declares (design proof v2, S8).
     */
    static final String DECLARED_EXTRAS_TRUE_DOCUMENT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"profile\":{\"additionalProperties\":true,"
            + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}},"
            + "\"type\":\"object\"}";

    /** Only the root carrier is closed; the declared {@code true} stays {@code true}. */
    static final String DECLARED_EXTRAS_TRUE_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"profile\":{\"additionalProperties\":true,"
                    + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /**
     * A non-root object declaring {@code properties} beside {@code additionalProperties: false} — the
     * shape a class-level {@code @Schema(additionalProperties = FALSE)} declares. Overwriting it
     * leaves it identical, so this row is a characterization of the pre-change hardener too.
     */
    static final String DECLARED_EXTRAS_FALSE_DOCUMENT =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                    + "\"properties\":{\"profile\":{\"additionalProperties\":false,"
                    + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /** Both objects are closed: the root by provenance, the declared one by its own declaration. */
    static final String DECLARED_EXTRAS_FALSE_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"profile\":{\"additionalProperties\":false,"
                    + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /**
     * A non-root object whose declared {@code additionalProperties} is a plain DTO schema carrying
     * {@code properties} of its own — the shape an any-setter over a DTO value type produces (design
     * proof v2, V07). The walk must reach that DTO schema.
     */
    static final String DTO_VALUED_EXTRAS_DOCUMENT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"profile\":{"
            + "\"additionalProperties\":{\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"},"
            + "\"properties\":{\"label\":{\"type\":\"string\"}},\"type\":\"object\"}},"
            + "\"type\":\"object\"}";

    /** The declared extras schema is kept, and the DTO inside it is closed like any other subschema. */
    static final String DTO_VALUED_EXTRAS_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"profile\":{"
                    + "\"additionalProperties\":{\"additionalProperties\":false,"
                    + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"},"
                    + "\"properties\":{\"label\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /**
     * A non-root object carrying FR-015's {@code propertyNames} reservation and FR-016's alias rule —
     * an {@code allOf} of a {@code oneOf} whose branches hold only {@code required} or {@code not}. The
     * object itself declares no {@code additionalProperties}, so it is closed.
     */
    static final String PROPERTY_NAMES_AND_BRANCHES_DOCUMENT =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                    + "\"properties\":{\"profile\":{"
                    + "\"allOf\":[{\"oneOf\":[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]},"
                    + "{\"not\":{\"anyOf\":[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]}]}}]}],"
                    + "\"properties\":{\"qty\":{\"type\":\"integer\"},\"quantity\":{\"type\":\"integer\"}},"
                    + "\"propertyNames\":{\"not\":{\"enum\":[\"role\"]}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";

    /** The object is closed; {@code propertyNames} and every rule branch are byte-preserved. */
    static final String PROPERTY_NAMES_AND_BRANCHES_HARDENED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"additionalProperties\":false,"
                    + "\"properties\":{\"profile\":{\"additionalProperties\":false,"
                    + "\"allOf\":[{\"oneOf\":[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]},"
                    + "{\"not\":{\"anyOf\":[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]}]}}]}],"
                    + "\"properties\":{\"qty\":{\"type\":\"integer\"},\"quantity\":{\"type\":\"integer\"}},"
                    + "\"propertyNames\":{\"not\":{\"enum\":[\"role\"]}},\"type\":\"object\"}},"
                    + "\"type\":\"object\"}";
}

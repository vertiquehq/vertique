// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;

/**
 * Parses raw bytes into a {@link WorkflowDefinitionDocument} using the appropriate
 * {@link ObjectMapper} for the given {@link DocumentFormat}.
 *
 * <p>The parser performs structural deserialization only — it rejects unknown properties and null
 * primitives, but does not validate semantic correctness (id resolution, graph reachability, type
 * compatibility). Semantic validation is the responsibility of the downstream validator in the
 * {@code dev.vertique.workflow.definition.validator} package.
 *
 * <p>Instances are thread-safe after construction: the underlying mappers are shared and
 * thread-safe, and this class holds no mutable state.
 */
@Singleton
public class WorkflowDefinitionParser {

    private final WorkflowDefinitionMapperFactory mapperFactory;

    /**
     * Constructs a parser backed by the given mapper factory.
     *
     * @param mapperFactory factory providing the YAML and JSON mappers; must not be {@code null}
     */
    @Inject
    WorkflowDefinitionParser(WorkflowDefinitionMapperFactory mapperFactory) {
        this.mapperFactory = mapperFactory;
    }

    /**
     * Parses the given bytes as a workflow definition document in the specified format.
     *
     * <p>The format hint is supplied by the caller (typically from the document source's file
     * extension or content-type); it is never sniffed from the byte content.
     *
     * @param bytes  raw bytes of the definition document; must not be {@code null}
     * @param format the serialization format of the bytes; must not be {@code null}
     * @return the deserialized document; never {@code null}
     * @throws WorkflowDefinitionParseException if Jackson cannot deserialize the bytes (unknown
     *         step type, unknown property, missing required field, null for a primitive, etc.)
     */
    public WorkflowDefinitionDocument parse(byte[] bytes, DocumentFormat format) {
        ObjectMapper mapper = selectMapper(format);
        try {
            return mapper.readValue(bytes, WorkflowDefinitionDocument.class);
        } catch (IOException e) {
            throw new WorkflowDefinitionParseException(
                    "Failed to parse workflow definition document (" + format + "): " + e.getMessage(), e);
        }
    }

    // --- Private helpers ---

    /**
     * Returns the mapper appropriate for the given format.
     *
     * @param format the desired format
     * @return the corresponding mapper
     */
    private ObjectMapper selectMapper(DocumentFormat format) {
        return switch (format) {
            case YAML -> mapperFactory.yamlMapper();
            case JSON -> mapperFactory.jsonMapper();
        };
    }
}

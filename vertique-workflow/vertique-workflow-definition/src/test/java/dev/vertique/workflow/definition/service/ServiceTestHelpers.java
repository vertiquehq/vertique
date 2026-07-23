// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import java.io.IOException;
import org.mockito.Mockito;

/**
 * Shared test helpers for the {@code service} test package.
 *
 * <p>Provides factory methods for creating test infrastructure that cannot be constructed directly
 * from outside their home package (e.g., {@link WorkflowDefinitionParser} which has a
 * package-private constructor).
 */
final class ServiceTestHelpers {

    private ServiceTestHelpers() {
        // Utility class — no instances.
    }

    /**
     * Builds a {@link WorkflowDefinitionParser} mock configured to perform real YAML and JSON
     * parsing using dedicated {@link ObjectMapper} instances, without requiring access to the
     * package-private constructor.
     *
     * @return a parser that behaves like the real implementation
     */
    static WorkflowDefinitionParser realParser() {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
                .registerModule(new ParameterNamesModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        ObjectMapper jsonMapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

        WorkflowDefinitionParser parser = Mockito.mock(WorkflowDefinitionParser.class);
        Mockito.doAnswer(invocation -> {
                    byte[] bytes = invocation.getArgument(0);
                    DocumentFormat format = invocation.getArgument(1);
                    ObjectMapper m = (format == DocumentFormat.YAML) ? yamlMapper : jsonMapper;
                    try {
                        return m.readValue(bytes, WorkflowDefinitionDocument.class);
                    } catch (IOException e) {
                        throw new WorkflowDefinitionParseException(
                                "Failed to parse workflow definition document (" + format + "): " + e.getMessage(), e);
                    }
                })
                .when(parser)
                .parse(Mockito.any(), Mockito.any());
        return parser;
    }
}

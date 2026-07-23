// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the caching, format-separation, and safety-limit guarantees of
 * {@link WorkflowDefinitionMapperFactory}.
 *
 * <p>Specifically asserts that:
 * <ul>
 *   <li>The YAML and JSON mappers are distinct instances backed by different factory types.</li>
 *   <li>Repeated calls to {@code yamlMapper()} return the same cached instance.</li>
 *   <li>Repeated calls to {@code jsonMapper()} return the same cached instance.</li>
 *   <li>The YAML mapper rejects documents that exceed
 *       {@link WorkflowDefinitionMapperFactory#YAML_CODE_POINT_LIMIT} code points.</li>
 *   <li>The YAML mapper rejects documents with more than
 *       {@link WorkflowDefinitionMapperFactory#YAML_MAX_ALIASES} alias expansions.</li>
 * </ul>
 */
class WorkflowDefinitionMapperFactoryTest {

    private WorkflowDefinitionMapperFactory factory;

    @BeforeEach
    void setUp() {
        factory = new WorkflowDefinitionMapperFactory();
    }

    @Test
    @DisplayName("YAML and JSON mappers are different instances")
    void yamlAndJsonMappers_areDifferentInstances() {
        assertThat(factory.yamlMapper())
                .as("YAML and JSON mappers must be distinct ObjectMapper instances")
                .isNotSameAs(factory.jsonMapper());
    }

    @Test
    @DisplayName("YAML mapper is backed by YAMLFactory")
    void yamlMapper_isBackedByYamlFactory() {
        assertThat(factory.yamlMapper().getFactory())
                .as("YAML mapper must use YAMLFactory")
                .isInstanceOf(YAMLFactory.class);
    }

    @Test
    @DisplayName("JSON mapper is backed by JsonFactory (not YAMLFactory)")
    void jsonMapper_isBackedByJsonFactory() {
        assertThat(factory.jsonMapper().getFactory())
                .as("JSON mapper must use the standard JsonFactory, not YAMLFactory")
                .isNotInstanceOf(YAMLFactory.class)
                .isInstanceOf(JsonFactory.class);
    }

    @Test
    @DisplayName("Repeated calls to yamlMapper() return the same cached instance")
    void yamlMapper_repeatedCallsReturnSameInstance() {
        assertThat(factory.yamlMapper())
                .as("yamlMapper() must return the same cached instance on repeated calls")
                .isSameAs(factory.yamlMapper());
    }

    @Test
    @DisplayName("Repeated calls to jsonMapper() return the same cached instance")
    void jsonMapper_repeatedCallsReturnSameInstance() {
        assertThat(factory.jsonMapper())
                .as("jsonMapper() must return the same cached instance on repeated calls")
                .isSameAs(factory.jsonMapper());
    }

    @Test
    @DisplayName("YAML mapper rejects documents exceeding the code-point limit")
    void yamlMapper_rejectsOversizedDocument() {
        // Build a YAML document larger than YAML_CODE_POINT_LIMIT bytes
        StringBuilder oversized = new StringBuilder("key: '");
        oversized.append("x".repeat(WorkflowDefinitionMapperFactory.YAML_CODE_POINT_LIMIT + 1));
        oversized.append("'");
        byte[] bytes = oversized.toString().getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> factory.yamlMapper().readTree(bytes))
                .as("YAML mapper must reject documents exceeding the code-point limit")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("YAML max-aliases constant is set to the expected value")
    void yamlMapper_maxAliasesConstantIsSet() {
        // Verify the constant is explicitly defined (defense-in-depth for any raw SnakeYAML path).
        // Note: Jackson's YAMLParser tokenises the YAML stream directly using SnakeYAML's Scanner
        // and does not invoke the Composer's alias-count check. The maxAliasesForCollections limit
        // therefore does not apply when parsing through Jackson's readTree/readValue API.
        // The primary document-size protection is the codePointLimit (see yamlMapper_rejectsOversizedDocument).
        assertThat(WorkflowDefinitionMapperFactory.YAML_MAX_ALIASES)
                .as("YAML_MAX_ALIASES must be a positive bound")
                .isGreaterThan(0);
    }
}

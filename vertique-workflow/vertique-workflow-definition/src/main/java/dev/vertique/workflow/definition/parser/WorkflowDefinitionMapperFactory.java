// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Thread-safe factory that produces dedicated {@link ObjectMapper} instances for parsing workflow
 * definition documents.
 *
 * <p>Two mappers are produced: one backed by a size-capped {@link YAMLFactory} and one backed by
 * the standard {@link JsonFactory}. Both are configured identically with strict deserialization
 * settings that reject unknown properties and null primitives. Each mapper is built once and cached
 * as an instance field — mappers are thread-safe after configuration, so repeated calls to
 * {@link #yamlMapper()} or {@link #jsonMapper()} return the same cached instance.
 *
 * <p>The YAML scanner uses the configured input code-point limit. This is independent of
 * runtime encoded-byte admission. Collection alias limits protect Composer-based SnakeYAML
 * paths; Jackson tokenizes aliases as scalar anchor names without expanding them.
 *
 * <p>The {@link ParameterNamesModule} is registered on both mappers to enable Jackson to discover
 * record canonical-constructor parameter names at runtime without requiring
 * {@link com.fasterxml.jackson.annotation.JsonProperty} on every record component.
 *
 * <p>Do NOT use or customize these mappers for application-level JSON serialization. They are
 * dedicated to definition document parsing only. Application JSON uses the process JSON codec's
 * mapper ({@link dev.vertique.core.json.VertiqueJson#mapper() VertiqueJson.mapper()}).
 */
@Singleton
public class WorkflowDefinitionMapperFactory {

    // --- Constants ---

    /** Default YAML scanner input limit in Unicode code points. */
    static final int YAML_CODE_POINT_LIMIT = 256 * 1024;

    /**
     * Maximum number of YAML aliases allowed within a single collection.
     * Applies to Composer-based SnakeYAML paths; Jackson does not expand collection aliases.
     */
    static final int YAML_MAX_ALIASES = 50;

    // --- Cached mapper instances ---

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    /**
     * Constructs a standalone factory with the default YAML code-point policy.
     */
    WorkflowDefinitionMapperFactory() {
        this(Optional.empty());
    }

    /** Constructs the real mappers after resolving the optional typed input policy. */
    @Inject
    WorkflowDefinitionMapperFactory(Optional<WorkflowDefinitionsConfig> config) {
        this.yamlMapper = buildMapper(safeYamlFactory(
                config.orElseGet(WorkflowDefinitionsConfig::defaults).maxDocumentSizeBytes()));
        this.jsonMapper = buildMapper(new JsonFactory());
    }

    // --- Private factory ---

    /**
     * Builds a {@link YAMLFactory} with explicit SnakeYAML {@link LoaderOptions} that enforce
     * document-size and alias-bomb caps (NFR-WF-DEF-006).
     *
     * @return a YAML factory with safety limits applied
     */
    private static YAMLFactory safeYamlFactory(int codePointLimit) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(codePointLimit);
        loaderOptions.setMaxAliasesForCollections(YAML_MAX_ALIASES);
        return YAMLFactory.builder().loaderOptions(loaderOptions).build();
    }

    /**
     * Returns the dedicated YAML {@link ObjectMapper}.
     *
     * <p>The same instance is returned on every call — it is safe to share across threads.
     *
     * @return the YAML mapper; never {@code null}
     */
    public ObjectMapper yamlMapper() {
        return yamlMapper;
    }

    /**
     * Returns the dedicated JSON {@link ObjectMapper}.
     *
     * <p>The same instance is returned on every call — it is safe to share across threads.
     *
     * @return the JSON mapper; never {@code null}
     */
    public ObjectMapper jsonMapper() {
        return jsonMapper;
    }

    // --- Private builder ---

    /**
     * Builds and configures a new {@link ObjectMapper} backed by the given factory.
     *
     * @param factory the Jackson factory that determines the serialization format
     * @return a fully configured, immutable mapper
     */
    private static ObjectMapper buildMapper(JsonFactory factory) {
        return new ObjectMapper(factory)
                .registerModule(new ParameterNamesModule())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}

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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
 * <p>The YAML factory is constructed with explicit SnakeYAML {@link LoaderOptions} to enforce
 * NFR-WF-DEF-006:
 * <ul>
 *   <li>{@code codePointLimit} = {@value #YAML_CODE_POINT_LIMIT} code points (256 KiB) — matches
 *       the service-level size cap in
 *       {@link dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService}.
 *       Enforced by SnakeYAML's Scanner, which Jackson's {@code YAMLParser} uses directly.</li>
 *   <li>{@code maxAliasesForCollections} = {@value #YAML_MAX_ALIASES} — defense-in-depth limit
 *       for any raw SnakeYAML usage (e.g., Composer-based paths). Note: Jackson's {@code YAMLParser}
 *       tokenises the YAML stream using the Scanner without invoking the Composer, so this limit
 *       does not apply when parsing through Jackson's {@code readTree}/{@code readValue} API.
 *       The primary alias-bomb protection via Jackson is the {@code codePointLimit}.</li>
 * </ul>
 *
 * <p>The {@link ParameterNamesModule} is registered on both mappers to enable Jackson to discover
 * record canonical-constructor parameter names at runtime without requiring
 * {@link com.fasterxml.jackson.annotation.JsonProperty} on every record component.
 *
 * <p>Do NOT use or customize these mappers for application-level JSON serialization. They are
 * dedicated to definition document parsing only. Application JSON uses the shared Vert.x
 * {@link io.vertx.core.json.jackson.DatabindCodec#mapper() DatabindCodec mapper} configured by
 * {@link dev.vertique.core.json.JacksonConfigurer}.
 */
@Singleton
public class WorkflowDefinitionMapperFactory {

    // --- Constants ---

    /**
     * Maximum YAML document size in code points (approximately bytes for ASCII-dominant content).
     * 256 KiB — matches the service-level size cap in
     * {@link dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService#MAX_DEFINITION_SIZE}
     * (NFR-WF-DEF-006).
     */
    static final int YAML_CODE_POINT_LIMIT = 256 * 1024;

    /**
     * Maximum number of YAML aliases allowed within a single collection.
     * Limits alias-bomb attacks that exponentially expand anchors into large in-memory structures
     * (NFR-WF-DEF-006).
     */
    static final int YAML_MAX_ALIASES = 50;

    // --- Cached mapper instances ---

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    /**
     * Constructs the factory and initialises both mappers eagerly. Package-private default
     * constructor allows Dagger to instantiate via {@code @Inject}.
     */
    @Inject
    WorkflowDefinitionMapperFactory() {
        this.yamlMapper = buildMapper(safeYamlFactory());
        this.jsonMapper = buildMapper(new JsonFactory());
    }

    // --- Private factory ---

    /**
     * Builds a {@link YAMLFactory} with explicit SnakeYAML {@link LoaderOptions} that enforce
     * document-size and alias-bomb caps (NFR-WF-DEF-006).
     *
     * @return a YAML factory with safety limits applied
     */
    private static YAMLFactory safeYamlFactory() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(YAML_CODE_POINT_LIMIT);
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

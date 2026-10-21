// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Internal source compiler and cache adapter for durable YAML/JSON definitions. */
@Singleton
public final class DocumentDefinitionLoader implements PersistedDefinitionLoader {
    private final WorkflowDefinitionPipeline pipeline;
    private final WorkflowDefinitionBootstrap bootstrap;
    private final WorkflowDefinitionStore cache;
    private final WorkflowDefinitionsConfig config;
    private final WorkflowTypeNames typeNames;

    /**
     * Creates a loader with an empty type-name index, so every state class is hashed and cached under
     * its binary name. For callers that do not use logical type names.
     *
     * @param pipeline the pipeline that compiles a stored document
     * @param bootstrap the bootstrap that reports the packaged document origins
     * @param cache the definition store that caches restored definitions
     * @param config the typed definition policy, when one is configured
     */
    public DocumentDefinitionLoader(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionBootstrap bootstrap,
            WorkflowDefinitionStore cache,
            Optional<WorkflowDefinitionsConfig> config) {
        this(pipeline, bootstrap, cache, config, new DefaultWorkflowTypeNames());
    }

    /**
     * Creates a loader that hashes and caches a restored definition under the logical name of its
     * state class, as the registry does when it registers the definition.
     *
     * @param pipeline the pipeline that compiles a stored document
     * @param bootstrap the bootstrap that reports the packaged document origins
     * @param cache the definition store that caches restored definitions
     * @param config the typed definition policy, when one is configured
     * @param typeNames the type-name index that names the state class
     */
    @Inject
    public DocumentDefinitionLoader(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionBootstrap bootstrap,
            WorkflowDefinitionStore cache,
            Optional<WorkflowDefinitionsConfig> config,
            WorkflowTypeNames typeNames) {
        this.pipeline = pipeline;
        this.bootstrap = bootstrap;
        this.cache = cache;
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);
        this.typeNames = typeNames;
    }

    @Override
    public boolean supports(String format) {
        return "YAML".equals(format) || "JSON".equals(format);
    }

    @Override
    public List<DocumentOrigin> documentOrigins() {
        return bootstrap.documentOrigins();
    }

    @Override
    public WorkflowDefinition<?, ?> load(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        if (!record.key().equals(document.key())
                || !java.util.Objects.equals(record.documentFormat(), document.documentFormat()))
            throw new IllegalArgumentException("Stored source identity/format does not match " + record.key());
        DefinitionSourceText.validate(document, config);
        return pipeline.load(new DefinitionResource(
                        document.document().getBytes(StandardCharsets.UTF_8),
                        DocumentFormat.valueOf(document.documentFormat()),
                        DefaultWorkflowDefinitionService.source(record.provenance())))
                .definition();
    }

    @Override
    public String planHash(WorkflowDefinition<?, ?> definition) {
        return planHash(definition, typeNames);
    }

    @Override
    public String planHash(WorkflowDefinition<?, ?> definition, Map<String, String> nameBinding) {
        return planHash(definition, DefaultWorkflowTypeNames.bound(typeNames, nameBinding));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String planHash(WorkflowDefinition<?, ?> definition, WorkflowTypeNames names) {
        WorkflowBuilder builder = new WorkflowBuilder(names);
        definition.define(builder);
        return builder.build(
                        definition.definitionId(), definition.definitionVersion(), names.nameOf(definition.stateType()))
                .planHash();
    }

    @Override
    public void onRestored(WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {
        if (cache.get(record.key()).isEmpty())
            cache.storeCandidate(
                    record.key(),
                    new CompiledDefinition(
                            definition,
                            record.planHash(),
                            DefaultWorkflowDefinitionService.source(record.provenance()),
                            typeNames.nameOf(definition.stateType()),
                            typeNames.contractNameOf(definition.contract())));
        cache.metadata(record);
    }

    @Override
    public void onMetadata(WorkflowDefinitionRecord record) {
        if (record.kind() == DefinitionKind.DOCUMENT && supports(record.documentFormat())) cache.metadata(record);
    }
}

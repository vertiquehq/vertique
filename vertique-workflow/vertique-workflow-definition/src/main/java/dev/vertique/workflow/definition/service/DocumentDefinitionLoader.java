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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Internal source compiler and cache adapter for durable YAML/JSON definitions. */
@Singleton
public final class DocumentDefinitionLoader implements PersistedDefinitionLoader {
    private final WorkflowDefinitionPipeline pipeline;
    private final WorkflowDefinitionBootstrap bootstrap;
    private final WorkflowDefinitionStore cache;
    private final WorkflowDefinitionsConfig config;

    @Inject
    public DocumentDefinitionLoader(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionBootstrap bootstrap,
            WorkflowDefinitionStore cache,
            Optional<WorkflowDefinitionsConfig> config) {
        this.pipeline = pipeline;
        this.bootstrap = bootstrap;
        this.cache = cache;
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    public String planHash(WorkflowDefinition<?, ?> definition) {
        WorkflowBuilder builder = new WorkflowBuilder();
        definition.define(builder);
        return builder.build(
                        definition.definitionId(),
                        definition.definitionVersion(),
                        definition.stateType().getName())
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
                            definition.stateType().getName(),
                            definition.contract().getName()));
        cache.metadata(record);
    }

    @Override
    public void onMetadata(WorkflowDefinitionRecord record) {
        if (record.kind() == DefinitionKind.DOCUMENT && supports(record.documentFormat())) cache.metadata(record);
    }
}

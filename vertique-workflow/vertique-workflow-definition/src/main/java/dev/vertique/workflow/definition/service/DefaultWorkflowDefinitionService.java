// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Candidate compilation and metadata facade with optional durable storage.
 *
 * <p>Lazy registry resolution preserves contributor wiring. Runtime byte admission precedes the
 * ordinary pipeline. Durable loads then validate the complete source representation before any
 * candidate write. Without a store, the existing in-memory compilation and activation outcomes
 * remain available through failed or successful Futures. Compilation invoked on a Vert.x context
 * uses that context's worker executor; standalone callers without a context use their caller thread.
 * JVM fatal Errors are not treated as ordinary recoverable load or activation failures.
 */
@Singleton
public final class DefaultWorkflowDefinitionService implements WorkflowDefinitionService {
    private final WorkflowDefinitionPipeline pipeline;
    private final WorkflowDefinitionStore store;
    private final Provider<WorkflowRegistry> registryProvider;
    private final Optional<WorkflowDefinitionRegistryStore> durable;
    private final Optional<WorkflowDefinitionLifecycle> lifecycle;
    private final WorkflowDefinitionsConfig config;

    /** Standalone construction retains the in-memory path and default document policy. */
    public DefaultWorkflowDefinitionService(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionStore store,
            Provider<WorkflowRegistry> registryProvider) {
        this(pipeline, store, registryProvider, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Constructs the facade with optional persistence, lifecycle and typed policy. */
    @Inject
    public DefaultWorkflowDefinitionService(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionStore store,
            Provider<WorkflowRegistry> registryProvider,
            Optional<WorkflowDefinitionRegistryStore> durable,
            Optional<WorkflowDefinitionLifecycle> lifecycle,
            Optional<WorkflowDefinitionsConfig> config) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.store = Objects.requireNonNull(store, "store");
        this.registryProvider = Objects.requireNonNull(registryProvider, "registryProvider");
        this.durable = Objects.requireNonNull(durable, "durable");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);
    }

    @Override
    public Future<CompiledDefinitionRef> load(
            byte[] content, DocumentFormat format, SourceMetadata metadata, @Nullable String tenantKey) {
        return attempt(() -> {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(metadata, "metadata");
            if (content.length > config.maxDocumentSizeBytes())
                throw new WorkflowDefinitionException("workflow.definitions.maxDocumentSizeBytes exceeded: size cap of "
                        + config.maxDocumentSizeBytes() + " bytes (actual: " + content.length + " bytes)");
            // Snapshot caller-owned bytes before asynchronous compilation.
            DefinitionResource resource = new DefinitionResource(content.clone(), format, metadata);
            return work(() -> {
                        CompiledDefinition compiled = pipeline.load(resource);
                        DefinitionKey key = new DefinitionKey(
                                tenantKey,
                                compiled.definition().definitionId(),
                                compiled.definition().definitionVersion());
                        WorkflowDefinitionDocument text = durable.isPresent()
                                ? DefinitionSourceText.decode(key, resource.content(), format, config)
                                : null;
                        return new Candidate(key, compiled, text, format.name());
                    })
                    .compose(candidate -> {
                        if (durable.isEmpty()) {
                            store.candidate(candidate.key(), candidate.compiled(), candidate.format());
                            return Future.succeededFuture(reference(candidate.compiled()));
                        }
                        WorkflowDefinitionRecord record =
                                record(candidate.key(), candidate.compiled(), candidate.format());
                        return durable.orElseThrow()
                                .insertCandidate(record, candidate.document())
                                .map(ignored -> {
                                    store.storeCandidate(candidate.key(), candidate.compiled());
                                    store.metadata(record);
                                    return reference(candidate.compiled());
                                });
                    });
        });
    }

    @Override
    public Future<Void> activate(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            if (durable.isPresent()) {
                if (lifecycle.isEmpty())
                    throw new WorkflowDefinitionLifecycleException(
                            WorkflowDefinitionLifecycleException.Reason.LOADER_MISSING,
                            key,
                            "No lifecycle implementation for " + key);
                return lifecycle.orElseThrow().activate(key);
            }
            boolean claimed = store.tryClaimForActivation(key);
            if (!claimed) {
                ActivationStatus status = store.currentStatus(key);
                if (status == ActivationStatus.ACTIVE) return Future.succeededFuture();
                throw new WorkflowDefinitionException("cannot activate " + key + ": status is " + status);
            }
            try {
                registryProvider
                        .get()
                        .register(store.get(key).orElseThrow().compiled().definition());
                store.markActive(key);
                return Future.succeededFuture();
            } catch (RuntimeException e) {
                store.resetToCandidate(key);
                throw e;
            }
        });
    }

    @Override
    public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
        return attempt(() -> {
            Objects.requireNonNull(filter, "filter");
            return durable.map(s -> s.list(filter)).orElseGet(() -> Future.succeededFuture(store.records(filter)));
        });
    }

    @Override
    public Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return durable.map(s -> s.find(key).map(row -> row.map(r -> source(r.provenance()))))
                    .orElseGet(() -> Future.succeededFuture(store.sourceMetadata(key)));
        });
    }

    static DefinitionProvenance provenance(SourceMetadata m) {
        return new DefinitionProvenance(m.sourceType(), m.uri(), m.revision(), m.author(), m.uploadId(), m.loadedAt());
    }

    static SourceMetadata source(DefinitionProvenance p) {
        return new SourceMetadata(p.sourceType(), p.uri(), p.revision(), p.author(), p.uploadId(), p.loadedAt());
    }

    static WorkflowDefinitionRecord record(DefinitionKey key, CompiledDefinition compiled, String format) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                compiled.planHash(),
                DefinitionStatus.CANDIDATE,
                provenance(compiled.source()),
                format,
                now,
                now);
    }

    private static CompiledDefinitionRef reference(CompiledDefinition compiled) {
        return new CompiledDefinitionRef(
                compiled.definition().definitionId(),
                compiled.definition().definitionVersion(),
                compiled.planHash(),
                ActivationStatus.CANDIDATE,
                compiled.source());
    }

    private static <T> Future<T> attempt(Supplier<Future<T>> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    private static <T> Future<T> work(Supplier<T> action) {
        var context = Vertx.currentContext();
        if (context != null) return context.executeBlocking(action::get, false);
        try {
            return Future.succeededFuture(action.get());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    private record Candidate(
            DefinitionKey key, CompiledDefinition compiled, WorkflowDefinitionDocument document, String format) {}
}

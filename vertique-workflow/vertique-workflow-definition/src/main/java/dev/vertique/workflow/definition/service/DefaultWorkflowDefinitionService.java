// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionActivation;
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
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Candidate compilation and metadata facade over the durable definition registry.
 *
 * <p>Lazy registry resolution preserves contributor wiring. Runtime byte admission precedes the
 * ordinary pipeline. Durable loads then validate the complete source representation before any
 * candidate write. Compilation invoked on a Vert.x context
 * uses that context's worker executor; standalone callers without a context use their caller thread.
 * JVM fatal Errors are not treated as ordinary recoverable load or publication failures.
 */
@Singleton
public final class DefaultWorkflowDefinitionService implements WorkflowDefinitionService {
    private final WorkflowDefinitionPipeline pipeline;
    private final WorkflowDefinitionStore store;
    private final Provider<WorkflowRegistry> registryProvider;
    private final WorkflowDefinitionRegistryStore durable;
    private final WorkflowDefinitionLifecycle lifecycle;
    private final WorkflowDefinitionsConfig config;

    /** Constructs the facade over the durable registry, its lifecycle and the typed policy. */
    @Inject
    public DefaultWorkflowDefinitionService(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionStore store,
            Provider<WorkflowRegistry> registryProvider,
            WorkflowDefinitionRegistryStore durable,
            WorkflowDefinitionLifecycle lifecycle,
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
            String scopeKey, byte[] content, DocumentFormat format, SourceMetadata metadata) {
        return attempt(() -> {
            // The scope is checked before the document is read, so a bad scope costs no parsing work.
            requireValidScope(scopeKey);
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
                                scopeKey,
                                compiled.definition().definitionId(),
                                compiled.definition().definitionVersion());
                        WorkflowDefinitionDocument text =
                                DefinitionSourceText.decode(key, resource.content(), format, config);
                        return new Candidate(
                                key, compiled, text, format.name(), pipeline.nameBinding(compiled.definition()));
                    })
                    .compose(candidate -> {
                        WorkflowDefinitionRecord record = record(
                                candidate.key(), candidate.compiled(), candidate.format(), candidate.nameBinding());
                        return durable.insertCandidate(record, candidate.document())
                                .map(ignored -> {
                                    store.storeCandidate(candidate.key(), candidate.compiled());
                                    store.metadata(record);
                                    return reference(candidate.compiled());
                                });
                    });
        });
    }

    /**
     * Applies the scope-key rules of {@link DefinitionKey} to the caller's scope argument. The key rejects a missing
     * scope with {@link NullPointerException} and any other invalid value with {@link IllegalArgumentException}; its
     * messages name the field and the limit, never the scope value or any document content.
     */
    private static void requireValidScope(String scopeKey) {
        new DefinitionKey(scopeKey, WorkflowScopes.DEFAULT, 1);
    }

    @Override
    public Future<Void> publish(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return lifecycle.publish(key);
        });
    }

    @Override
    public Future<Void> deprecate(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            // The committed row is the source of truth for the local cache projection.
            return lifecycle
                    .deprecate(key)
                    .compose(ignored -> durable.find(key))
                    .map(found -> {
                        found.filter(record -> record.kind() == DefinitionKind.DOCUMENT)
                                .ifPresent(store::metadata);
                        return (Void) null;
                    });
        });
    }

    @Override
    public Future<Void> retire(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            // The committed row is the source of truth for the local cache projection.
            return lifecycle.retire(key).compose(ignored -> durable.find(key)).map(found -> {
                found.filter(record -> record.kind() == DefinitionKind.DOCUMENT).ifPresent(store::metadata);
                return (Void) null;
            });
        });
    }

    @Override
    public Future<Void> reinstate(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            // The committed row is the source of truth for the local cache projection.
            return lifecycle
                    .reinstate(key)
                    .compose(ignored -> durable.find(key))
                    .map(found -> {
                        found.filter(record -> record.kind() == DefinitionKind.DOCUMENT)
                                .ifPresent(store::metadata);
                        return (Void) null;
                    });
        });
    }

    @Override
    public Future<Void> activate(String scopeKey, String definitionId, String targetScopeKey, long version) {
        return attempt(() -> lifecycle.activate(scopeKey, definitionId, targetScopeKey, version));
    }

    @Override
    public Future<Void> deactivateDefault(String definitionId) {
        return attempt(() -> lifecycle.deactivateDefault(definitionId));
    }

    @Override
    public Future<Void> follow(String scopeKey, String definitionId) {
        return attempt(() -> lifecycle.follow(scopeKey, definitionId));
    }

    @Override
    public Future<List<DefinitionActivation>> activations(@Nullable String scopeKey) {
        return attempt(() -> lifecycle.activations(scopeKey));
    }

    @Override
    public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
        return attempt(() -> {
            Objects.requireNonNull(filter, "filter");
            return durable.list(filter);
        });
    }

    @Override
    public Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return durable.find(key).map(row -> row.map(r -> source(r.provenance())));
        });
    }

    static DefinitionProvenance provenance(SourceMetadata m) {
        return new DefinitionProvenance(m.sourceType(), m.uri(), m.revision(), m.author(), m.uploadId(), m.loadedAt());
    }

    static SourceMetadata source(DefinitionProvenance p) {
        return new SourceMetadata(p.sourceType(), p.uri(), p.revision(), p.author(), p.uploadId(), p.loadedAt());
    }

    static WorkflowDefinitionRecord record(
            DefinitionKey key, CompiledDefinition compiled, String format, Map<String, String> nameBinding) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                compiled.contractName(),
                compiled.stateTypeName(),
                compiled.planHash(),
                DefinitionStatus.CANDIDATE,
                provenance(compiled.source()),
                format,
                now,
                now,
                nameBinding,
                null);
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
            DefinitionKey key,
            CompiledDefinition compiled,
            WorkflowDefinitionDocument document,
            String format,
            Map<String, String> nameBinding) {}
}

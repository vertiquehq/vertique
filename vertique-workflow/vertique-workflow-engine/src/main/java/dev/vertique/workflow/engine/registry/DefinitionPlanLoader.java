// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.DefinitionPublication;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DeclaredSignalNames;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: reads the stored row of a document version and builds its plan, for the plan cache.
 *
 * <p>A load reads the record, then the document text, each on a pooled connection that is released when the read
 * ends; it then compiles the document and builds the plan on a worker thread that holds no connection, and checks the
 * rebuilt plan hash against the stored one. The per-plan validators run in the build, as they do for every
 * registration. Nothing is registered: the plan belongs to the cache.
 *
 * <p>A row written before the signal names and the start schema were stored gets both after its first successful
 * build, by the store's fill that only writes while the names are null and the plan hash is still the built one. The
 * fill is a short statement of its own after the build, never inside a transaction of the caller, and a failure of
 * it is logged and does not fail the load.
 *
 * <p>A row that does not exist, that is not an available or deprecated document, or whose text vanished meanwhile
 * fails the load with {@link WorkflowDefinitionNotFoundException}. A document that does not compile, a recorded name
 * that no class resolves, a missing loader and a rebuilt plan hash that differs from the stored one fail it with a
 * {@link WorkflowDefinitionException}. Any other failure is the store's own and says nothing about the plan.
 */
public final class DefinitionPlanLoader {

    private static final Logger log = LoggerFactory.getLogger(DefinitionPlanLoader.class);

    private static final Set<DefinitionStatus> LIVE = Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED);

    private final WorkflowDefinitionRegistryStore store;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefaultWorkflowRegistry builder;
    private final @Nullable Vertx vertx;
    private final Optional<WorkflowPlanSchemaProducer> schemaProducer;

    /**
     * Creates the loader.
     *
     * @param store          the durable definition rows
     * @param loaders        the document loaders, one per supported format
     * @param builder        the registry whose type names and validators the build uses; nothing is registered in it
     * @param vertx          the Vert.x instance whose worker threads run the build, or {@code null} to build on the
     *     calling thread
     * @param schemaProducer when present, the producer of the signal names' companion schema that fills a legacy row
     */
    public DefinitionPlanLoader(
            WorkflowDefinitionRegistryStore store,
            Set<PersistedDefinitionLoader> loaders,
            DefaultWorkflowRegistry builder,
            @Nullable Vertx vertx,
            Optional<WorkflowPlanSchemaProducer> schemaProducer) {
        this.store = Objects.requireNonNull(store, "store");
        this.loaders = Objects.requireNonNull(loaders, "loaders");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.vertx = vertx;
        this.schemaProducer = Objects.requireNonNull(schemaProducer, "schemaProducer");
    }

    /** What the build produced: the plan and, for a legacy row, the columns to fill. */
    private record Built(
            RuntimeWorkflow plan,
            @Nullable JsonObject signalNames,
            @Nullable JsonObject startSchema) {}

    /**
     * Reads the row of the key and builds its plan.
     *
     * @param key the scope, definition id and version
     * @return a future of the plan; see the class description for how it fails
     */
    public Future<RuntimeWorkflow> load(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return store.find(key).compose(found -> {
            WorkflowDefinitionRecord record = found.filter(DefinitionPlanLoader::buildable)
                    .orElseThrow(() -> new WorkflowDefinitionNotFoundException(key));
            PersistedDefinitionLoader loader = DefinitionPublication.loaderFor(loaders, record);
            DefinitionPublication.requireBoundNamesResolve(builder.typeNames(), record);
            return store.findDocument(key)
                    .compose(document -> work(() -> build(
                            loader, record, document.orElseThrow(() -> new WorkflowDefinitionNotFoundException(key)))))
                    .compose(built -> backfill(record, built).map(built.plan()));
        });
    }

    private static boolean buildable(WorkflowDefinitionRecord record) {
        return record.kind() == DefinitionKind.DOCUMENT && LIVE.contains(record.status());
    }

    /**
     * Compiles the document, checks the rebuilt hash and builds the plan. It runs on a worker thread; every failure of
     * the document itself leaves it as a {@link WorkflowDefinitionException}.
     */
    private Built build(
            PersistedDefinitionLoader loader, WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        DefinitionKey key = record.key();
        try {
            WorkflowDefinition<?, ?> definition = loader.load(record, document);
            String hash = DefinitionPublication.planHash(loader, definition, record);
            if (!key.definitionId().equals(definition.definitionId())
                    || key.definitionVersion() != definition.definitionVersion()
                    || !record.planHash().equals(hash)) {
                throw new WorkflowDefinitionLifecycleException(
                        Reason.DRIFT,
                        key,
                        "Definition " + key + ": stored hash " + record.planHash() + " differs from rebuilt hash "
                                + hash);
            }
            RuntimeWorkflow plan = builder.build(key.scopeKey(), definition, record.nameBinding());
            if (schemaProducer.isEmpty() || record.signalNames() != null) {
                return new Built(plan, null, null);
            }
            return new Built(
                    plan,
                    DeclaredSignalNames.of(plan),
                    schemaProducer.orElseThrow().startSchema(plan));
        } catch (WorkflowDefinitionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new WorkflowDefinitionException("Cannot build DOCUMENT " + key + " from its stored source", failure);
        }
    }

    /** Fills the null columns of a legacy row in a statement of its own; a failure is logged and ignored. */
    private Future<Void> backfill(WorkflowDefinitionRecord record, Built built) {
        if (built.signalNames() == null) {
            return Future.succeededFuture();
        }
        Future<Void> filled;
        try {
            filled = store.fillSignalSchemas(record.key(), record.planHash(), built.signalNames(), built.startSchema());
        } catch (RuntimeException failure) {
            filled = Future.failedFuture(failure);
        }
        return filled.recover(failure -> {
            DefinitionKey key = record.key();
            log.warn(
                    "The signal names of definition scope '{}' id '{}' version {} could not be filled: {}",
                    EchoedNames.bounded(key.scopeKey()),
                    EchoedNames.bounded(key.definitionId()),
                    key.definitionVersion(),
                    failure.getClass().getName());
            log.debug("Fill failure", failure);
            return Future.succeededFuture();
        });
    }

    private <T> Future<T> work(Callable<T> action) {
        if (vertx != null) {
            return vertx.executeBlocking(action, false);
        }
        try {
            return Future.succeededFuture(action.call());
        } catch (Exception failure) {
            return Future.failedFuture(failure);
        }
    }
}

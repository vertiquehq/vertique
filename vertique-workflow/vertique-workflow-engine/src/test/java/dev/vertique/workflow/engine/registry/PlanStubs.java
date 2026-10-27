// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-ins for the database and the document compiler behind the plan cache: a store that counts its reads and can
 * fail one, a loader that counts the plans it builds, and the one place that wires them into a cache.
 */
final class PlanStubs {

    /** The hash every stored row and every build of these stubs carries. */
    static final String PLAN_HASH = "a".repeat(64);

    private PlanStubs() {}

    record StubState(String id) {}

    record StubStart(String id) {}

    @WorkflowContract
    interface StubContract {}

    /** A code-first definition standing in for the compiled form of a stored document. */
    static final class StubDefinition implements WorkflowDefinition<StubState, StubContract> {

        private final String id;
        private final long version;

        StubDefinition(DefinitionKey key) {
            this.id = key.definitionId();
            this.version = key.definitionVersion();
        }

        @Override
        public Class<StubContract> contract() {
            return StubContract.class;
        }

        @Override
        public Class<StubState> stateType() {
            return StubState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<StubState> wf) {
            wf.init(StubStart.class, start -> new StubState(start.id()))
                    .initialStep("done")
                    .complete("done");
        }
    }

    /** Counts every plan it builds and reports the hash its rows carry. */
    static final class BuildProbe implements PersistedDefinitionLoader {

        private final AtomicInteger builds = new AtomicInteger();

        int builds() {
            return builds.get();
        }

        @Override
        public boolean supports(String documentFormat) {
            return "YAML".equals(documentFormat);
        }

        @Override
        public List<dev.vertique.workflow.registry.store.DocumentOrigin> documentOrigins() {
            return List.of();
        }

        @Override
        public WorkflowDefinition<?, ?> load(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            builds.incrementAndGet();
            return new StubDefinition(record.key());
        }

        @Override
        public String planHash(WorkflowDefinition<?, ?> definition) {
            return PLAN_HASH;
        }

        @Override
        public void onRestored(WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {}

        @Override
        public void onMetadata(WorkflowDefinitionRecord record) {}
    }

    /** Serves stored document rows, counts the reads it answers and fails the next record read when told to. */
    static final class CountingDefinitionStore extends InMemoryWorkflowDefinitionRegistryStore {

        private final Map<DefinitionKey, WorkflowDefinitionRecord> rows = new LinkedHashMap<>();
        private final AtomicInteger finds = new AtomicInteger();
        private final AtomicInteger documentReads = new AtomicInteger();
        private final AtomicReference<Throwable> nextFailure = new AtomicReference<>();

        /** Stores an available document row of the key, as a published scope document would be. */
        CountingDefinitionStore with(DefinitionKey key) {
            Instant now = Instant.now();
            rows.put(
                    key,
                    new WorkflowDefinitionRecord(
                            key,
                            DefinitionKind.DOCUMENT,
                            StubContract.class.getName(),
                            StubState.class.getName(),
                            PLAN_HASH,
                            DefinitionStatus.AVAILABLE,
                            new DefinitionProvenance("test", "memory:" + key.definitionId(), null, null, null, now),
                            "YAML",
                            now,
                            now,
                            Map.of(),
                            null));
            return this;
        }

        /** Makes the next read of a row fail with the failure, once. */
        CountingDefinitionStore failOnce(Throwable failure) {
            nextFailure.set(failure);
            return this;
        }

        /** The record reads answered or failed so far. */
        int finds() {
            return finds.get();
        }

        /** The document reads answered so far. */
        int documentReads() {
            return documentReads.get();
        }

        /** Every read of a record or a document so far. */
        int reads() {
            return finds.get() + documentReads.get();
        }

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            finds.incrementAndGet();
            Throwable failure = nextFailure.getAndSet(null);
            if (failure != null) return Future.failedFuture(failure);
            return Future.succeededFuture(Optional.ofNullable(rows.get(key)));
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            documentReads.incrementAndGet();
            return Future.succeededFuture(
                    rows.containsKey(key)
                            ? Optional.of(
                                    new WorkflowDefinitionDocument(key, "definitionId: " + key.definitionId(), "YAML"))
                            : Optional.empty());
        }
    }

    /**
     * The one place that wires a plan cache over the stubs: the loader builds through the given registry without
     * registering and reads rows from the store, and the cache holds at most {@code maxEntries} plans.
     */
    static PlanCache planCache(
            CountingDefinitionStore store,
            BuildProbe probe,
            DefaultWorkflowRegistry registry,
            Vertx vertx,
            long maxEntries) {
        DefinitionPlanLoader loader = new DefinitionPlanLoader(store, Set.of(probe), registry, vertx, Optional.empty());
        return new PlanCache(loader, maxEntries, Set.of());
    }

    /** Waits for the future on the calling thread and returns its value. */
    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Waits for the future and returns its failure, or fails when it succeeded. */
    static Throwable failureOf(Future<?> future) throws Exception {
        Future<Throwable> outcome = future.transform(result -> result.failed()
                ? Future.succeededFuture(result.cause())
                : Future.failedFuture(new AssertionError("the future was expected to fail")));
        return await(outcome);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowRegistry#load(DefinitionKey)} answers the keys of code definitions itself and hands
 * every other key to the bound {@link DocumentPlanSource}, and that a registry without a source finds no document.
 */
class DefaultWorkflowRegistryLoadTest {

    record LoadState(String id) {}

    record LoadStart(String id) {}

    @WorkflowContract
    interface LoadContractV1 {}

    private static WorkflowDefinition<LoadState, LoadContractV1> definition(String id, long version) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<LoadContractV1> contract() {
                return LoadContractV1.class;
            }

            @Override
            public Class<LoadState> stateType() {
                return LoadState.class;
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
            public void define(WorkflowBuilder<LoadState> wf) {
                wf.init(LoadStart.class, start -> new LoadState(start.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    /** A source that answers one key with a fixed plan and records every key it was asked to load. */
    private static final class RecordingPlanSource implements DocumentPlanSource {

        private final DefinitionKey known;
        private final RuntimeWorkflow plan;
        private final List<DefinitionKey> loaded = new CopyOnWriteArrayList<>();

        RecordingPlanSource(DefinitionKey known, RuntimeWorkflow plan) {
            this.known = known;
            this.plan = plan;
        }

        @Override
        public Future<RuntimeWorkflow> load(DefinitionKey key) {
            loaded.add(key);
            return key.equals(known)
                    ? Future.succeededFuture(plan)
                    : Future.failedFuture(new IllegalStateException("the source holds no plan for " + key));
        }

        @Override
        public Optional<RuntimeWorkflow> resident(DefinitionKey key) {
            return Optional.empty();
        }

        List<DefinitionKey> loaded() {
            return List.copyOf(loaded);
        }
    }

    @Test
    @DisplayName("load answers code keys itself and delegates every other key to the document plan source")
    void loadAnswersCodeKeysAndDelegatesOtherKeysToTheDocumentPlanSource() {
        // Given a registry with one code definition and a source that knows one document key and its plan
        DefinitionKey codeKey = new DefinitionKey(WorkflowScopes.DEFAULT, "load-code", 1);
        DefinitionKey documentKey = new DefinitionKey("acme", "load-document", 3);
        DefaultWorkflowRegistry planFactory = new DefaultWorkflowRegistry();
        planFactory.register(definition("load-document", 3));
        RuntimeWorkflow documentPlan =
                planFactory.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "load-document", 3));

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(definition("load-code", 1));
        RecordingPlanSource source = new RecordingPlanSource(documentKey, documentPlan);
        bind(registry, source);

        // And a second registry that has no source bound
        DefaultWorkflowRegistry withoutSource = new DefaultWorkflowRegistry();

        // When the code key and the document key are loaded from the first registry
        Future<RuntimeWorkflow> codeLoad = registry.load(codeKey);
        List<DefinitionKey> askedForCode = source.loaded();
        Future<RuntimeWorkflow> documentLoad = registry.load(documentKey);

        // Then the code plan is the registered one and the source was not asked for it
        assertThat(codeLoad.succeeded()).isTrue();
        assertThat(codeLoad.result()).isSameAs(registry.resolve(codeKey));
        assertThat(askedForCode).isEmpty();

        // And the document key was handed to the source once and answered with the source's plan
        assertThat(documentLoad.succeeded()).isTrue();
        assertThat(documentLoad.result()).isSameAs(documentPlan);
        assertThat(source.loaded()).containsExactly(documentKey);

        // When the same document key is loaded from the registry that has no source
        Future<RuntimeWorkflow> unbound = withoutSource.load(documentKey);

        // Then it fails as an absent definition
        assertThat(unbound.failed()).isTrue();
        assertThat(unbound.cause()).isInstanceOf(WorkflowDefinitionNotFoundException.class);
    }

    /** The one place that hands a source to a registry. */
    private static void bind(DefaultWorkflowRegistry registry, DocumentPlanSource source) {
        registry.bindDocumentPlanSource(source);
    }
}

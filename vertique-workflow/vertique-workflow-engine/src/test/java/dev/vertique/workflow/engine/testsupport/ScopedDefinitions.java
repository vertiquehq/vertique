// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.DocumentPlanSource;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stored versions of a scope other than the default one, for tests whose definitions are Java objects.
 *
 * <p>The registry holds code definitions only, so a version of another scope exists as a row of the definition store and
 * as a plan the node builds from it. This helper does both for a Java definition: it builds the plan with the registry's
 * rules, seeds an available document row for it in the {@linkplain InMemoryWorkflowDefinitionRegistryStore#of store of
 * the registry}, and binds a plan source to the registry that answers the plan, which stays resident. It replaces any
 * plan source bound before, and the engine's own plan cache when it is bound later, so publish after the engine was
 * built.
 */
public final class ScopedDefinitions {

    private final DefaultWorkflowRegistry registry;
    private final InMemoryWorkflowDefinitionRegistryStore store;
    private final Map<DefinitionKey, RuntimeWorkflow> plans = new ConcurrentHashMap<>();

    private ScopedDefinitions(DefaultWorkflowRegistry registry) {
        this.registry = registry;
        this.store = InMemoryWorkflowDefinitionRegistryStore.of(registry);
    }

    private void bind() {
        registry.bindDocumentPlanSource(new DocumentPlanSource() {
            @Override
            public Future<RuntimeWorkflow> load(DefinitionKey key) {
                RuntimeWorkflow plan = plans.get(key);
                return plan == null
                        ? Future.failedFuture(new WorkflowDefinitionNotFoundException(key))
                        : Future.succeededFuture(plan);
            }

            @Override
            public Optional<RuntimeWorkflow> resident(DefinitionKey key) {
                return Optional.ofNullable(plans.get(key));
            }
        });
    }

    /**
     * Opens the scoped versions of a registry and binds the plan source that serves them.
     *
     * @param registry the registry of the engine under test
     * @return the scoped versions
     */
    public static ScopedDefinitions of(DefaultWorkflowRegistry registry) {
        return new ScopedDefinitions(registry);
    }

    /**
     * Publishes a Java definition as an available version of a scope.
     *
     * @param scopeKey the scope that owns the version
     * @param definition the definition to publish
     * @return the plan built for it
     */
    public RuntimeWorkflow publish(String scopeKey, WorkflowDefinition<?, ?> definition) {
        RuntimeWorkflow plan = registry.build(scopeKey, definition, Map.of());
        plans.put(new DefinitionKey(scopeKey, definition.definitionId(), definition.definitionVersion()), plan);
        store.seedDocument(plan);
        bind();
        return plan;
    }

    /**
     * Moves the row of a published version to the given status. The plan stays resident.
     *
     * @param key the version
     * @param from the status it has
     * @param to the status it gets
     */
    public void transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
        store.transition(key, from, to)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * Returns the plan built for a published version.
     *
     * @param key the version
     * @return the plan
     */
    public RuntimeWorkflow plan(DefinitionKey key) {
        return plans.get(key);
    }
}

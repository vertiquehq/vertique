// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import jakarta.inject.Singleton;

/**
 * Binds the in-memory definition registry store and the no-op lifecycle for test components that include the
 * definition module but not the engine, which requires both. A component that needs the concrete store or lifecycle
 * can expose them, as both are bound as singletons.
 */
@Module
public abstract class InMemoryDefinitionRegistryModule {
    @Provides
    @Singleton
    static InMemoryWorkflowDefinitionRegistryStore inMemoryDefinitionStore() {
        return new InMemoryWorkflowDefinitionRegistryStore();
    }

    @Provides
    @Singleton
    static NoOpWorkflowDefinitionLifecycle noOpDefinitionLifecycle() {
        return new NoOpWorkflowDefinitionLifecycle();
    }

    @Binds
    abstract WorkflowDefinitionRegistryStore definitionStore(InMemoryWorkflowDefinitionRegistryStore store);

    @Binds
    abstract WorkflowDefinitionLifecycle definitionLifecycle(NoOpWorkflowDefinitionLifecycle lifecycle);
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.examples.workflow.order.TaskReviewWorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowContributor;
import jakarta.inject.Singleton;

/**
 * Dagger module that registers the cycle-3 task-review workflow definition.
 *
 * <p>Contributes one {@link WorkflowContributor} entry into the multibinding so that
 * {@link dev.vertique.workflow.registry.WorkflowRegistry} picks it up at startup alongside the
 * order-fulfillment saga and cycle-2 timer definitions:
 * <ul>
 *   <li>{@link TaskReviewWorkflowDefinition} — {@code "task-review"}</li>
 * </ul>
 */
@Module
public abstract class TaskReviewDefinitionModule {

    /**
     * Contributes the task-review workflow definition as a {@link WorkflowContributor}.
     *
     * @param definition the singleton task-review definition
     * @return a contributor that registers the definition into the workflow registry
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowContributor taskReviewContributor(TaskReviewWorkflowDefinition definition) {
        return registry -> registry.register(definition);
    }
}

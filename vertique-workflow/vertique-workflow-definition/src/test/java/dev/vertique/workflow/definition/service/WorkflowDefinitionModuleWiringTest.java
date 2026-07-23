// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Singleton;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowDefinitionModule} wires correctly with {@link WorkflowCoreModule}
 * in a Dagger component:
 * <ul>
 *   <li>The graph builds with zero {@link WorkflowDefinitionSource} contributions;
 *       {@link WorkflowDefinitionService#list()} returns empty.</li>
 *   <li>{@link WorkflowDefinitionService} resolves to an instance of
 *       {@link DefaultWorkflowDefinitionService}.</li>
 * </ul>
 */
class WorkflowDefinitionModuleWiringTest {

    // --- Empty-source component ---

    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, ForkJoinValidatorModule.class})
    interface EmptySourceComponent {

        WorkflowDefinitionService workflowDefinitionService();

        WorkflowRegistry workflowRegistry();
    }

    /** Provides a null WorkflowPlanValidator so WorkflowCoreModule.registry() compiles. */
    @Module
    static class ForkJoinValidatorModule {

        @Provides
        @Singleton
        static WorkflowPlanValidator forkJoinValidator() {
            return null;
        }
    }

    @Nested
    @DisplayName("empty source set")
    class EmptySourceSet {

        @Test
        @DisplayName("Dagger graph builds with zero sources; WorkflowDefinitionService resolves")
        void graphBuildsWithZeroSources() {
            assertThatCode(() -> DaggerWorkflowDefinitionModuleWiringTest_EmptySourceComponent.create())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("service is DefaultWorkflowDefinitionService and list() returns empty")
        void serviceIsDefaultImplAndListIsEmpty() {
            EmptySourceComponent component = DaggerWorkflowDefinitionModuleWiringTest_EmptySourceComponent.create();
            WorkflowDefinitionService service = component.workflowDefinitionService();

            assertThat(service).isInstanceOf(DefaultWorkflowDefinitionService.class);
            assertThat(service.list()).isEmpty();
        }
    }

    // --- Component with one source contributor ---

    @Module
    static class SingleSourceModule {

        /**
         * Contributes an empty {@link WorkflowDefinitionSource} (returns no resources).
         *
         * @return an in-set empty source
         */
        @Provides
        @IntoSet
        static WorkflowDefinitionSource emptySource() {
            return List::of;
        }
    }

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                ForkJoinValidatorModule.class,
                SingleSourceModule.class
            })
    interface SingleSourceComponent {

        WorkflowDefinitionService workflowDefinitionService();
    }

    @Nested
    @DisplayName("one empty source contributor")
    class OneEmptySource {

        @Test
        @DisplayName("graph builds with a contributor that provides zero resources; list() returns empty")
        void graphBuildsWithEmptyContributorAndListIsEmpty() {
            SingleSourceComponent component = DaggerWorkflowDefinitionModuleWiringTest_SingleSourceComponent.create();
            assertThat(component.workflowDefinitionService().list()).isEmpty();
        }
    }
}

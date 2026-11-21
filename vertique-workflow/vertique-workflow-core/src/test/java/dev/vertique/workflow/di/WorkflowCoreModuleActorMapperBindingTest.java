// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies with Dagger-compiled components that an application replaces the default
 * {@link WorkflowActorMapper} with a plain binding next to {@link WorkflowCoreModule}, and gets the
 * default without one.
 */
class WorkflowCoreModuleActorMapperBindingTest {

    static final WorkflowActorMapper CUSTOM = identity -> new WorkflowActor.Service("custom");

    /** The application's own mapper. */
    @Module
    abstract static class ApplicationMapperModule {

        @Provides
        static WorkflowActorMapper workflowActorMapper() {
            return CUSTOM;
        }
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, ApplicationMapperModule.class})
    interface OverridingComponent {

        Optional<WorkflowActorMapper> mapper();
    }

    @Singleton
    @Component(modules = WorkflowCoreModule.class)
    interface PlainComponent {

        Optional<WorkflowActorMapper> mapper();
    }

    @Test
    @DisplayName("an application binding compiles next to the core module and wins")
    void applicationBindingWins() {
        Optional<WorkflowActorMapper> bound =
                DaggerWorkflowCoreModuleActorMapperBindingTest_OverridingComponent.create()
                        .mapper();

        assertThat(WorkflowActorMapper.orDefault(bound)).isSameAs(CUSTOM);
    }

    @Test
    @DisplayName("without an application binding the default is used")
    void defaultWithoutBinding() {
        Optional<WorkflowActorMapper> bound = DaggerWorkflowCoreModuleActorMapperBindingTest_PlainComponent.create()
                .mapper();

        assertThat(bound).isEmpty();
        assertThat(WorkflowActorMapper.orDefault(bound)).isInstanceOf(DefaultWorkflowActorMapper.class);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.workflow.actor.DefaultTaskPrincipalsMapper;
import dev.vertique.workflow.actor.TaskPrincipalsMapper;
import dev.vertique.workflow.tasks.TaskPrincipals;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies with Dagger-compiled components that an application replaces the default
 * {@link TaskPrincipalsMapper} with a plain binding next to {@link WorkflowCoreModule}, and gets the
 * default without one.
 */
class WorkflowCoreModuleTaskPrincipalsMapperBindingTest {

    static final TaskPrincipalsMapper CUSTOM = context -> TaskPrincipals.ofUser("custom", Set.of(), Set.of());

    /** The application's own mapper. */
    @Module
    abstract static class ApplicationMapperModule {

        @Provides
        static TaskPrincipalsMapper taskPrincipalsMapper() {
            return CUSTOM;
        }
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, ApplicationMapperModule.class})
    interface OverridingComponent {

        Optional<TaskPrincipalsMapper> mapper();
    }

    @Singleton
    @Component(modules = WorkflowCoreModule.class)
    interface PlainComponent {

        Optional<TaskPrincipalsMapper> mapper();
    }

    @Test
    @DisplayName("an application binding compiles next to the core module and wins")
    void applicationBindingWins() {
        Optional<TaskPrincipalsMapper> bound =
                DaggerWorkflowCoreModuleTaskPrincipalsMapperBindingTest_OverridingComponent.create()
                        .mapper();

        assertThat(TaskPrincipalsMapper.orDefault(bound)).isSameAs(CUSTOM);
    }

    @Test
    @DisplayName("without an application binding the default is used")
    void defaultWithoutBinding() {
        Optional<TaskPrincipalsMapper> bound =
                DaggerWorkflowCoreModuleTaskPrincipalsMapperBindingTest_PlainComponent.create()
                        .mapper();

        assertThat(bound).isEmpty();
        assertThat(TaskPrincipalsMapper.orDefault(bound)).isInstanceOf(DefaultTaskPrincipalsMapper.class);
    }
}

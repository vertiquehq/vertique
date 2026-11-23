// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowAttribution;
import dev.vertique.workflow.di.WorkflowCoreModule;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies with Dagger-compiled components that the actor a recorded command is logged with comes from the
 * application's {@link WorkflowActorMapper} when it binds one, and from the default mapper without one.
 */
class CommandActorsBindingTest {

    private static final SecurityIdentity ALICE =
            SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "alice", Map.of()));

    /** Supplies the holder the actor is read from: one caller, alice. */
    @Module
    abstract static class CallerModule {

        @Provides
        static ContextHolder holder() {
            return new ContextHolder() {
                @Override
                public <T> Optional<T> current(Class<T> type) {
                    return SecurityContext.class == type
                            ? Optional.of(type.cast(SecurityContexts.unauthenticated(ALICE)))
                            : Optional.empty();
                }

                @Override
                public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                    throw new UnsupportedOperationException("the recording path only reads the context");
                }
            };
        }
    }

    /** The application's own mapper. */
    @Module
    abstract static class ApplicationMapperModule {

        @Provides
        static WorkflowActorMapper workflowActorMapper() {
            return identity -> new WorkflowActor.Service("custom");
        }
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, CallerModule.class, ApplicationMapperModule.class})
    interface OverridingComponent {

        CommandActors actors();
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, CallerModule.class})
    interface PlainComponent {

        CommandActors actors();
    }

    @Test
    @DisplayName("an application mapper binding decides the actor of a recorded command")
    void applicationMapperIsUsed() {
        WorkflowAttribution actor = DaggerCommandActorsBindingTest_OverridingComponent.create()
                .actors()
                .capture();

        assertThat(actor.actor()).isEqualTo(new WorkflowActor.Service("custom"));
    }

    @Test
    @DisplayName("without an application mapper the default decides the actor")
    void defaultMapperWithoutBinding() {
        WorkflowAttribution actor =
                DaggerCommandActorsBindingTest_PlainComponent.create().actors().capture();

        assertThat(actor.actor()).isEqualTo(new WorkflowActor.User("alice"));
    }
}

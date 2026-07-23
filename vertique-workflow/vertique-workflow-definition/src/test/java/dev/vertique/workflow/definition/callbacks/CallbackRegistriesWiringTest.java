// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.plan.BranchResult;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying that {@link WorkflowDefinitionModule} correctly wires all 10 named callback
 * registries and {@link RegisteredIdentifierLookup} via Dagger multibinding.
 *
 * <p>A tiny test component installs {@code WorkflowDefinitionModule} plus a test module that
 * contributes one entry into each of the 10 multibindings. After graph construction, the test
 * asserts that each registry contains the expected id and that the {@link RegisteredIdentifierLookup}
 * facade exposes all 10 registries correctly.
 */
class CallbackRegistriesWiringTest {

    // --- Shared test types ---

    record _State(String v) {}

    record _Payload(String r) {}

    // --- Test module contributing one entry per multibinding ---

    @Module
    abstract static class TestContributorModule {

        @Provides
        @IntoSet
        static PayloadMapperContributor payloadMapper() {
            return b -> b.register(new NamedPayloadMapper<>("wiring.payload", _State.class, s -> s.v()));
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMapper() {
            return b -> b.register(
                    new NamedStartStateMapper<>("wiring.start", _Payload.class, _State.class, p -> new _State(p.r())));
        }

        @Provides
        @IntoSet
        static StateReducerContributor stateReducer() {
            return b -> b.register(new NamedStateReducer<>("wiring.reduce", _State.class, (s, e) -> s));
        }

        @Provides
        @IntoSet
        static StateMutatorContributor stateMutator() {
            return b -> b.register(new NamedStateMutator<>("wiring.mutate", _State.class, s -> s));
        }

        @Provides
        @IntoSet
        static TimerResolverContributor timerResolver() {
            return b -> b.register(new NamedTimerResolver<>("wiring.timer", _State.class, s -> Instant.EPOCH));
        }

        @Provides
        @IntoSet
        static FailMessageFactoryContributor failMessageFactory() {
            return b -> b.register(new NamedFailMessageFactory<>("wiring.fail", _State.class, s -> "fail"));
        }

        @Provides
        @IntoSet
        static SubjectResolverContributor subjectResolver() {
            return b -> b.register(new NamedSubjectResolver<>(
                    "wiring.subject", _State.class, s -> new WorkflowSubjectRef("T", "1", null)));
        }

        @Provides
        @IntoSet
        static TaskAssignmentResolverContributor taskAssignmentResolver() {
            return b -> b.register(new NamedTaskAssignmentResolver<>(
                    "wiring.assign", _State.class, s -> new TaskAssignment.User("u")));
        }

        @Provides
        @IntoSet
        static BranchResultReducerContributor branchResultReducer() {
            BiFunction<_State, Map<String, BranchResult>, _State> fn = (s, m) -> s;
            return b -> b.register(new NamedBranchResultReducer<>("wiring.branch", _State.class, fn));
        }

        @Provides
        @IntoSet
        static NamedConditionContributor namedCondition() {
            return b -> b.register(new NamedCondition<>("wiring.cond", _State.class, s -> true));
        }
    }

    // --- Dagger component ---

    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, TestContributorModule.class})
    interface TestComponent {

        RegisteredIdentifierLookup registeredIdentifierLookup();
    }

    // --- Test ---

    @Test
    @DisplayName("Dagger graph provides RegisteredIdentifierLookup with all 10 registries populated")
    void wiresAllRegistries() {
        RegisteredIdentifierLookup lookup =
                DaggerCallbackRegistriesWiringTest_TestComponent.create().registeredIdentifierLookup();

        assertThat(lookup.payloadMappers().contains("wiring.payload"))
                .as("payloadMappers should contain wiring.payload")
                .isTrue();
        assertThat(lookup.startStateMappers().contains("wiring.start"))
                .as("startStateMappers should contain wiring.start")
                .isTrue();
        assertThat(lookup.stateReducers().contains("wiring.reduce"))
                .as("stateReducers should contain wiring.reduce")
                .isTrue();
        assertThat(lookup.stateMutators().contains("wiring.mutate"))
                .as("stateMutators should contain wiring.mutate")
                .isTrue();
        assertThat(lookup.timerResolvers().contains("wiring.timer"))
                .as("timerResolvers should contain wiring.timer")
                .isTrue();
        assertThat(lookup.failMessageFactories().contains("wiring.fail"))
                .as("failMessageFactories should contain wiring.fail")
                .isTrue();
        assertThat(lookup.subjectResolvers().contains("wiring.subject"))
                .as("subjectResolvers should contain wiring.subject")
                .isTrue();
        assertThat(lookup.taskAssignmentResolvers().contains("wiring.assign"))
                .as("taskAssignmentResolvers should contain wiring.assign")
                .isTrue();
        assertThat(lookup.branchResultReducers().contains("wiring.branch"))
                .as("branchResultReducers should contain wiring.branch")
                .isTrue();
        assertThat(lookup.namedConditions().contains("wiring.cond"))
                .as("namedConditions should contain wiring.cond")
                .isTrue();
    }
}

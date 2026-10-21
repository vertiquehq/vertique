// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowCoreModule} multibinds {@link WorkflowDefinitionValidator} and
 * hands the contributed set to the {@link WorkflowRegistry} it provides, using Dagger-compiled
 * components.
 *
 * <p>The component with a validator contribution only compiles when the module declares the
 * multibinding for consumers; the component without one proves an empty set stays valid.
 */
class WorkflowCoreModuleDefinitionValidatorBindingTest {

    // --- Fixture types ---

    record State(String id) {}

    record StartPayload(String id) {}

    @WorkflowContract
    interface WiredSagaContract {}

    /** Validator that records every candidate it receives. */
    static final class CountingValidator implements WorkflowDefinitionValidator {

        final List<RegisteredWorkflowDefinition> received = new ArrayList<>();

        @Override
        public void validate(RegisteredWorkflowDefinition definition) {
            received.add(definition);
        }
    }

    static WorkflowDefinition<State, WiredSagaContract> wiredDefinition() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<WiredSagaContract> contract() {
                return WiredSagaContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return "wired-saga";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(StartPayload.class, cmd -> new State(cmd.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    /** Contributes one valid definition and a recording validator. */
    @Module
    static final class ContributingModule {

        private final CountingValidator validator;

        ContributingModule(CountingValidator validator) {
            this.validator = validator;
        }

        @Provides
        @IntoSet
        WorkflowDefinitionValidator definitionValidator() {
            return validator;
        }

        @Provides
        @IntoSet
        static WorkflowContributor definitionContributor() {
            return registry -> registry.register(wiredDefinition());
        }
    }

    /** Contributes one valid definition and no validator. */
    @Module
    abstract static class DefinitionOnlyModule {

        @Provides
        @IntoSet
        static WorkflowContributor definitionContributor() {
            return registry -> registry.register(wiredDefinition());
        }
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, ContributingModule.class})
    interface ValidatingComponent {

        WorkflowRegistry registry();
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, DefinitionOnlyModule.class})
    interface PlainComponent {

        WorkflowRegistry registry();
    }

    // --- Tests ---

    @Test
    @DisplayName("contributed validators reach the Dagger-built registry")
    void contributedValidatorsReachTheRegistry() {
        // Given: a component that contributes a validator and a definition
        CountingValidator validator = new CountingValidator();
        ValidatingComponent component =
                DaggerWorkflowCoreModuleDefinitionValidatorBindingTest_ValidatingComponent.builder()
                        .contributingModule(new ContributingModule(validator))
                        .build();

        // When: the registry is requested, twice
        WorkflowRegistry registry = component.registry();
        component.registry();

        // Then: the validator saw the contributed definition exactly once, and the definition is registered
        assertThat(validator.received).hasSize(1);
        assertThat(validator.received.get(0).plan().definitionId()).isEqualTo("wired-saga");
        assertThat(validator.received.get(0).startPayloadType()).isEqualTo(StartPayload.class);
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wired-saga", 1L))
                        .plan()
                        .definitionVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a component without a validator contribution builds and registers normally")
    void componentWithoutValidatorsRegistersNormally() {
        // Given: a component with no validator contribution
        PlainComponent component = DaggerWorkflowCoreModuleDefinitionValidatorBindingTest_PlainComponent.create();

        // When: the registry is requested
        WorkflowRegistry registry = component.registry();

        // Then: it builds and holds the contributed definition
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wired-saga", 1L))
                        .plan()
                        .definitionId())
                .isEqualTo("wired-saga");
    }
}

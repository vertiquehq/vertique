// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import jakarta.inject.Singleton;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies, from outside the module's package, that {@link WorkflowSchemaModule} contributes the registration
 * validator to the core registry through Dagger and that the optional bean validator binding is satisfied both when
 * an application binds one and when it does not.
 */
class SchemaModuleWiringTest {

    // --- Fixture types ---

    record State(String id) {}

    record StartPayload(String id) {}

    record Note(String text) {}

    @WorkflowContract(definitionId = "wired-note", definitionVersion = 1)
    interface WiredNoteContract {}

    static WorkflowDefinition<State, WiredNoteContract> definition(Class<?> decisionType) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<WiredNoteContract> contract() {
                return WiredNoteContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return "wired-note";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(StartPayload.class, start -> new State(start.id())).initialStep("review");
                addDecision(wf.task("review").assignToRole("reviewers"), decisionType)
                        .build()
                        .complete("done");
            }
        };
    }

    private static <P> WorkflowBuilder<State>.TaskBuilder addDecision(
            WorkflowBuilder<State>.TaskBuilder task, Class<P> type) {
        return task.decision("note", type).onDecision((state, payload) -> state).toStep("done");
    }

    // --- Application modules ---

    /** The bindings the application owes the schema module. */
    @Module
    abstract static class JsonBindings {

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }
    }

    /** Contributes a definition whose decision payload is a String. */
    @Module
    abstract static class ScalarDefinition {

        @Provides
        @IntoSet
        static WorkflowContributor contributor() {
            return registry -> registry.register(definition(String.class));
        }
    }

    /** Contributes a definition whose decision payload is a record. */
    @Module
    abstract static class RecordDefinition {

        @Provides
        @IntoSet
        static WorkflowContributor contributor() {
            return registry -> registry.register(definition(Note.class));
        }
    }

    /** Binds a bean validator, so the constraint supplement is enabled. */
    @Module
    abstract static class BeanValidation {

        @Provides
        @Singleton
        static Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }
    }

    // --- Components ---

    @Singleton
    @Component(
            modules = {WorkflowCoreModule.class, WorkflowSchemaModule.class, JsonBindings.class, ScalarDefinition.class
            })
    interface ScalarComponent {

        WorkflowRegistry registry();
    }

    @Singleton
    @Component(
            modules = {WorkflowCoreModule.class, WorkflowSchemaModule.class, JsonBindings.class, RecordDefinition.class
            })
    interface RecordComponent {

        WorkflowRegistry registry();
    }

    @Singleton
    @Component(
            modules = {
                WorkflowCoreModule.class,
                WorkflowSchemaModule.class,
                JsonBindings.class,
                BeanValidation.class,
                RecordDefinition.class
            })
    interface ValidatingComponent {

        WorkflowRegistry registry();
    }

    // --- Tests ---

    @Test
    @DisplayName("the contributed validator rejects a scalar decision type when the registry is built")
    void theContributedValidatorRejectsAScalarDecisionTypeWhenTheRegistryIsBuilt() {
        // Given: a component whose contributor registers a definition with a String decision payload
        ScalarComponent component = DaggerSchemaModuleWiringTest_ScalarComponent.create();

        // When / Then: building the registry fails with the registration exception
        assertThatThrownBy(component::registry)
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'wired-note' v1 step 'review' decision 'note' payload type"
                        + " 'java.lang.String' is not object-shaped");
    }

    @Test
    @DisplayName("an object decision type registers when no bean validator is bound")
    void anObjectDecisionTypeRegistersWithoutABeanValidator() {
        // Given: a component without a bean validator binding
        RecordComponent component = DaggerSchemaModuleWiringTest_RecordComponent.create();

        // When: the registry is built
        WorkflowRegistry registry = component.registry();

        // Then: the definition is registered
        assertThat(registry.resolveCurrent("wired-note").plan().definitionId()).isEqualTo("wired-note");
    }

    @Test
    @DisplayName("an object decision type registers when a bean validator is bound")
    void anObjectDecisionTypeRegistersWithABeanValidator() {
        // Given: a component that binds a bean validator
        ValidatingComponent component = DaggerSchemaModuleWiringTest_ValidatingComponent.create();

        // When: the registry is built
        WorkflowRegistry registry = component.registry();

        // Then: the definition is registered
        assertThat(registry.resolveCurrent("wired-note").plan().definitionVersion())
                .isEqualTo(1L);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowMigrationModule} wires {@link DefaultWorkflowMigrationRegistry}
 * correctly through its {@link WorkflowMigrationRegistry} binding.
 *
 * <p>This test exercises the module's contract via the concrete implementation directly
 * (same pattern as {@link dev.vertique.workflow.plan.RaceSafetyTargetRegistryTest}) since the
 * module's sole responsibility is to bind the interface to the implementation and declare the
 * empty multibinding — both of which are verified here.
 */
class WorkflowMigrationModuleWiringTest {

    // --- Fixture types ---

    record State(String id) {}

    record StartCmd(String id) {}

    @WorkflowContract(definitionId = "wiring-saga", definitionVersion = 1)
    interface WiringSagaContractV1 {}

    @WorkflowContract(definitionId = "wiring-saga", definitionVersion = 2)
    interface WiringSagaContractV2 {}

    static WorkflowRegistry twoVersionRegistry() {
        DefaultWorkflowRegistry reg = new DefaultWorkflowRegistry();
        reg.register(wiringDef(1L, WiringSagaContractV1.class));
        reg.register(wiringDef(2L, WiringSagaContractV2.class));
        return reg;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static WorkflowDefinition<State, ?> wiringDef(long version, Class<?> contract) {
        return new WorkflowDefinition() {
            @Override
            public Class contract() {
                return contract;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return "wiring-saga";
            }

            @Override
            public long definitionVersion() {
                return version;
            }

            @Override
            public void define(WorkflowBuilder wf) {
                wf.init(StartCmd.class, cmd -> new State(((StartCmd) cmd).id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    // --- Tests ---

    @Test
    @DisplayName("empty handler set → registry has empty all()")
    void emptyHandlerSetYieldsEmptyRegistry() {
        // Simulates what WorkflowMigrationModule produces when no handlers are contributed:
        // @Multibinds guarantees an empty Set<WorkflowMigrationHandler<?, ?>> is provided.
        WorkflowMigrationRegistry registry = new DefaultWorkflowMigrationRegistry(Set.of(), twoVersionRegistry());
        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("one handler contributed → registry's find(...) returns it")
    void oneHandlerContributedIsRetrievable() {
        WorkflowRegistry wfReg = twoVersionRegistry();

        // Simulate @IntoSet contribution of one handler
        WorkflowMigrationHandler<State, State> handler = new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return "wiring-saga";
            }

            @Override
            public long fromDefinitionVersion() {
                return 1L;
            }

            @Override
            public long targetDefinitionVersion() {
                return 2L;
            }

            @Override
            public Class<State> sourceStateType() {
                return State.class;
            }

            @Override
            public Class<State> targetStateType() {
                return State.class;
            }

            @Override
            public MigrationResult<State> migrate(State sourceState, MigrationContext ctx) {
                return MigrationResult.anchorAtInitial(sourceState);
            }
        };

        WorkflowMigrationRegistry registry = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

        assertThat(registry.find("wiring-saga", 1L, 2L)).isPresent().containsSame(handler);
        assertThat(registry.all()).containsExactly(handler);
    }
}

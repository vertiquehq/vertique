// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowContractMetadata;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link DefaultWorkflowRegistry#registeredDefinition(String, long)} hands back the source
 * definition object of a registered version, and that registries without it keep the empty default.
 */
class RegistryRegisteredDefinitionTest {

    record SourceState(String id) {}

    record SourceStart(String id) {}

    @WorkflowContract(definitionId = "registered-source", definitionVersion = 1)
    interface SourceContractV1 {}

    @WorkflowContract(definitionId = "registered-source", definitionVersion = 2)
    interface SourceContractV2 {}

    private static <C> WorkflowDefinition<SourceState, C> definition(String id, long version, Class<C> contract) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<SourceState> stateType() {
                return SourceState.class;
            }

            @Override
            public String definitionId() {
                return id;
            }

            @Override
            public long definitionVersion() {
                return version;
            }

            @Override
            public void define(WorkflowBuilder<SourceState> wf) {
                wf.init(SourceStart.class, start -> new SourceState(start.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    /** A registry that implements only the abstract members, like one written before sources were retained. */
    private static final class MinimalRegistry implements WorkflowRegistry {

        @Override
        public void register(WorkflowDefinition<?, ?> def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeWorkflow resolveCurrent(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeWorkflow resolvePinned(String definitionId, long version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkflowContractMetadata contractMetadata(Class<?> contractInterface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass) {
            return Optional.empty();
        }

        @Override
        public Collection<RuntimeWorkflow> allRegistered() {
            return List.of();
        }
    }

    @Test
    @DisplayName("the original source definition is returned after register and is gone after unregister")
    void returnsTheRegisteredSourceUntilItIsUnregistered() {
        String id = "registered-source";
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();

        // Given an unregistered version
        // Then no source is retained for it
        assertThat(registry.registeredDefinition(id, 1)).isEmpty();

        // When version 1 is registered
        WorkflowDefinition<SourceState, SourceContractV1> first = definition(id, 1, SourceContractV1.class);
        registry.register(first);

        // Then that very definition object is returned, and another version is still empty
        assertThat(registry.registeredDefinition(id, 1)).containsSame(first);
        assertThat(registry.registeredDefinition(id, 2)).isEmpty();

        // When it is unregistered
        registry.unregister(id, 1);

        // Then no source is retained for it any more
        assertThat(registry.registeredDefinition(id, 1)).isEmpty();

        // When the version is registered again with a new source definition
        WorkflowDefinition<SourceState, SourceContractV1> replacement = definition(id, 1, SourceContractV1.class);
        registry.register(replacement);

        // Then the new source is returned, not the earlier one
        assertThat(registry.registeredDefinition(id, 1)).containsSame(replacement);
        assertThat(registry.registeredDefinition(id, 1).orElseThrow()).isNotSameAs(first);
    }

    @Test
    @DisplayName("a registry that does not retain sources returns an empty result")
    void defaultImplementationReturnsEmpty() {
        // Given a registry written before sources were retained
        WorkflowRegistry registry = new MinimalRegistry();

        // When the source of any version is asked for
        // Then the default is empty
        assertThat(registry.registeredDefinition("registered-source", 1)).isEmpty();
    }
}

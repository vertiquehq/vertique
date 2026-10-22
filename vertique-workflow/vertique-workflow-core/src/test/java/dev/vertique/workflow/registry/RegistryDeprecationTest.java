// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowContractMetadata;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies how {@link DefaultWorkflowRegistry} treats deprecated versions: current resolution skips
 * them, pinned resolution keeps serving them, unregistering clears both the contract mapping and the
 * mark, and registries that do not implement the new members keep their documented defaults.
 */
class RegistryDeprecationTest {

    record InvoiceState(String id) {}

    record InvoiceStart(String id) {}

    @WorkflowContract(definitionId = "deprecation-invoice", definitionVersion = 1)
    interface InvoiceContractV1 {}

    @WorkflowContract(definitionId = "deprecation-invoice", definitionVersion = 2)
    interface InvoiceContractV2 {}

    @WorkflowContract(definitionId = "deprecation-refund", definitionVersion = 1)
    interface RefundContractV1 {}

    private static <C> WorkflowDefinition<InvoiceState, C> definition(String id, long version, Class<C> contract) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<InvoiceState> stateType() {
                return InvoiceState.class;
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
            public void define(WorkflowBuilder<InvoiceState> wf) {
                wf.init(InvoiceStart.class, start -> new InvoiceState(start.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    /** A registry written before deprecation existed: it implements only the abstract members. */
    private static final class LegacyRegistry implements WorkflowRegistry {

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
        public Collection<RuntimeWorkflow> allRegistered() {
            return java.util.List.of();
        }
    }

    @Test
    @DisplayName("deprecated versions are skipped for current resolution but still served to pinned resolution")
    void skipsDeprecatedButKeepsPinnedResume() {
        String id = "deprecation-invoice";
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();

        // Given version 1 and version 2 registered with distinct contract classes
        registry.register(definition(id, 1, InvoiceContractV1.class));
        registry.register(definition(id, 2, InvoiceContractV2.class));
        assertThat(registry.resolveCurrent(id).plan().definitionVersion())
                .as("before any deprecation the highest version is current")
                .isEqualTo(2);
        assertThat(registry.isDeprecated(id, 2)).isFalse();

        // When version 2 is deprecated
        registry.markDeprecated(id, 2);

        // Then current resolution selects version 1, version 2 is marked, and pinned resolution still serves it
        assertThat(registry.isDeprecated(id, 2)).isTrue();
        assertThat(registry.isDeprecated(id, 1)).isFalse();
        assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(1);
        assertThat(registry.resolvePinned(id, 2).plan().definitionVersion()).isEqualTo(2);
        assertThat(registry.resolvePinned(id, 1).plan().definitionVersion()).isEqualTo(1);
        assertThat(registry.allRegistered())
                .extracting(runtime -> runtime.plan().definitionVersion())
                .containsExactlyInAnyOrder(1L, 2L);

        // When version 1 is deprecated as well
        registry.markDeprecated(id, 1);

        // Then current resolution fails typed naming the definition, while both versions stay pinned-resolvable
        assertThatThrownBy(() -> registry.resolveCurrent(id))
                .isInstanceOf(WorkflowDefinitionMissingException.class)
                .hasMessageContaining(id);
        assertThat(registry.resolvePinned(id, 1).plan().definitionVersion()).isEqualTo(1);
        assertThat(registry.resolvePinned(id, 2).plan().definitionVersion()).isEqualTo(2);
        assertThat(registry.isDeprecated(id, 1)).isTrue();
        assertThat(registry.isDeprecated(id, 2)).isTrue();
    }

    @Test
    @DisplayName(
            "unregistering a deprecated version clears its contract mapping and its mark so it can be registered again")
    void unregisterClearsContractAndMark() {
        String id = "deprecation-refund";
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();

        // Given a deprecated registered definition with contract RefundContractV1
        registry.register(definition(id, 1, RefundContractV1.class));
        registry.markDeprecated(id, 1);
        assertThat(registry.isDeprecated(id, 1)).isTrue();
        assertThat(registry.contractMetadata(RefundContractV1.class).definitionId())
                .isEqualTo(id);
        assertThatThrownBy(() -> registry.resolveCurrent(id)).isInstanceOf(WorkflowDefinitionMissingException.class);

        // When it is unregistered
        registry.unregister(id, 1);

        // Then the version, its contract mapping and its mark are all gone
        assertThat(registry.allRegistered()).isEmpty();
        assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertThatThrownBy(() -> registry.contractMetadata(RefundContractV1.class))
                .isInstanceOf(WorkflowProxyContractException.class);
        assertThat(registry.isDeprecated(id, 1)).isFalse();

        // When the same definition is registered again
        assertThatCode(() -> registry.register(definition(id, 1, RefundContractV1.class)))
                .doesNotThrowAnyException();

        // Then the contract metadata resolves again and the old deprecation does not carry over
        WorkflowContractMetadata metadata = registry.contractMetadata(RefundContractV1.class);
        assertThat(metadata.definitionId()).isEqualTo(id);
        assertThat(metadata.definitionVersion()).isEqualTo(1);
        assertThat(registry.isDeprecated(id, 1)).isFalse();
        assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("a registry that does not implement deprecation refuses to mark a version")
    void defaultMarkDeprecatedIsUnsupported() {
        WorkflowRegistry legacy = new LegacyRegistry();

        assertThatThrownBy(() -> legacy.markDeprecated("deprecation-invoice", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a registry that does not implement deprecation reports no version as deprecated")
    void defaultIsDeprecatedReportsFalse() {
        WorkflowRegistry legacy = new LegacyRegistry();

        assertThat(legacy.isDeprecated("deprecation-invoice", 1)).isFalse();
        assertThat(legacy.isDeprecated("deprecation-invoice", 2)).isFalse();
    }
}

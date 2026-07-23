// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowClientProxyLinkageException;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated-proxy selection logic in {@link WorkflowClientFactory#create(Class)}:
 *
 * <ul>
 *   <li>A contract whose companion ({@code {Contract}_WorkflowClientProxy}) is present on the
 *       classpath is instantiated via that companion (not via the JDK dynamic proxy).</li>
 *   <li>A nested contract uses the flattened companion name ({@code Outer_Inner_...}) resolved by
 *       {@link dev.vertique.core.util.GeneratedNames#companionFqn}; a regression to
 *       {@code Class.getName() + suffix} would yield {@code Outer$Inner_...} and miss it.</li>
 *   <li>A contract with no companion on the classpath falls back to a JDK dynamic proxy.</li>
 *   <li>A contract whose companion is present but throws on construction fails loudly with
 *       {@link WorkflowClientProxyLinkageException}.</li>
 *   <li>Validation runs before the generated-proxy lookup: a structurally valid but unregistered
 *       contract throws {@link WorkflowProxyContractException}, not a proxy-linkage error.</li>
 * </ul>
 */
@DisplayName("WorkflowClientFactory — generated-proxy selection")
class WorkflowClientFactorySelectionTest {

    // --- Shared state ---

    /** State record used across all definitions registered in this test. */
    record SelectionState(String id) {}

    private DefaultWorkflowRegistry registry;
    private WorkflowOperations ops;
    private WorkflowClientFactory factory;

    // --- Setup ---

    @BeforeEach
    void setUp() {
        registry = new DefaultWorkflowRegistry();
        ops = mock(WorkflowOperations.class);

        registerDefinition(registry, SelectionWorkflow.class, "selection-saga", 1);
        registerDefinition(registry, NestedSelectionHost.NestedJob.class, "selection-saga-nested", 1);
        registerDefinition(registry, BrokenSelectionWorkflow.class, "selection-saga-broken", 1);
        registerDefinition(registry, FallbackSelectionWorkflow.class, "selection-saga-fallback", 1);
        // UnregisteredSelectionWorkflow is intentionally NOT registered.

        factory = new WorkflowClientFactory(ops, registry);
    }

    /**
     * Registers a workflow definition for the given contract class into the given registry.
     *
     * <p>The definition uses {@link SelectionStartPayload} as the start payload and
     * {@link SelectionSignalPayload} for both {@code "order.confirmed"} and
     * {@code "order.shipped"} signal nodes — matching the method shapes declared on all selection
     * contract fixtures.
     *
     * @param <C> the contract type
     * @param reg the registry to populate
     * @param contract the contract class to link the definition to
     * @param definitionId the definition id to register under
     * @param definitionVersion the definition version to register under
     */
    private <C> void registerDefinition(
            DefaultWorkflowRegistry reg, Class<C> contract, String definitionId, long definitionVersion) {
        reg.register(new WorkflowDefinition<SelectionState, C>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<SelectionState> stateType() {
                return SelectionState.class;
            }

            @Override
            public String definitionId() {
                return definitionId;
            }

            @Override
            public long definitionVersion() {
                return definitionVersion;
            }

            @Override
            public void define(WorkflowBuilder<SelectionState> wf) {
                wf.init(SelectionStartPayload.class, p -> new SelectionState(p.orderId()))
                        .initialStep("charge")
                        .dispatch("charge", "payment-svc", s -> new Object(), "wait-confirm")
                        .waitFor(
                                "wait-confirm",
                                "order.confirmed",
                                SelectionSignalPayload.class,
                                (s, p) -> s,
                                "wait-shipped")
                        .waitFor("wait-shipped", "order.shipped", SelectionSignalPayload.class, (s, p) -> s, "done")
                        .complete("done");
            }
        });
    }

    // --- Tests ---

    @Nested
    @DisplayName("generated-proxy selection")
    class GeneratedProxySelection {

        @Test
        @DisplayName("uses the generated proxy when present on the classpath")
        void usesGeneratedProxyWhenPresent() {
            SelectionWorkflow proxy = factory.create(SelectionWorkflow.class);

            assertThat(proxy).isInstanceOf(SelectionWorkflow_WorkflowClientProxy.class);
            assertThat(Proxy.isProxyClass(proxy.getClass()))
                    .as("should not be a JDK dynamic proxy")
                    .isFalse();
        }

        @Test
        @DisplayName("uses the generated proxy for a nested contract via the flattened companion name")
        void usesGeneratedProxyForNestedContract() {
            NestedSelectionHost.NestedJob proxy = factory.create(NestedSelectionHost.NestedJob.class);

            // Selected only if the factory derives the lookup via GeneratedNames.companionFqn
            // (Outer$Inner -> Outer_Inner); a regression to contract.getName() + suffix would yield
            // NestedSelectionHost$NestedJob_WorkflowClientProxy and miss this stand-in.
            assertThat(proxy).isInstanceOf(NestedSelectionHost_NestedJob_WorkflowClientProxy.class);
            assertThat(Proxy.isProxyClass(proxy.getClass()))
                    .as("should not be a JDK dynamic proxy")
                    .isFalse();
        }

        @Test
        @DisplayName("falls back to a JDK dynamic proxy when no generated proxy exists")
        void fallsBackToJdkProxyWhenAbsent() {
            FallbackSelectionWorkflow proxy = factory.create(FallbackSelectionWorkflow.class);

            assertThat(Proxy.isProxyClass(proxy.getClass()))
                    .as("should be a JDK dynamic proxy when no companion is on the classpath")
                    .isTrue();
        }

        @Test
        @DisplayName("fails loudly when a present generated proxy cannot be instantiated")
        void failsLoudlyWhenGeneratedProxyBroken() {
            assertThatThrownBy(() -> factory.create(BrokenSelectionWorkflow.class))
                    .isInstanceOf(WorkflowClientProxyLinkageException.class)
                    .hasMessageContaining("BrokenSelectionWorkflow_WorkflowClientProxy");
        }

        @Test
        @DisplayName(
                "validation runs before proxy selection — unregistered contract throws WorkflowProxyContractException")
        void validationRunsBeforeProxySelection() {
            // UnregisteredSelectionWorkflow is structurally valid but not in the registry.
            // Validation (step 1 in create()) must fire before the generated-proxy lookup so the
            // thrown exception type is WorkflowProxyContractException, not WorkflowClientProxyLinkageException.
            // There is also no stand-in proxy for this contract, so if selection somehow ran first
            // and found the absent companion, it would fall back to the JDK proxy and fail when
            // pre-compiling handlers — a different exception. Only validation-first produces
            // WorkflowProxyContractException here.
            assertThatThrownBy(() -> factory.create(UnregisteredSelectionWorkflow.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }
    }
}

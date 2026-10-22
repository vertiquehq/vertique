// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowClientProxyLinkageException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated-proxy selection logic in {@link WorkflowClientFactory#create(Class, String)}:
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
 *   <li>A definition id that is not registered still gets a client: creation checks the contract
 *       only.</li>
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

    // --- Generated and reflective proxies behave the same ---

    @Test
    @DisplayName("generated and reflective proxies send identical commands")
    void generatedAndReflectiveProxiesSendIdenticalCommands() {
        // Given a definition for a contract with a generated proxy and one for a contract without, each with
        // its own recording operations
        DefaultWorkflowRegistry definitions = new DefaultWorkflowRegistry();
        definitions.register(new SelectionDefinitions.Selection());
        definitions.register(new SelectionDefinitions.Fallback());
        RecordingWorkflowOperations generatedOps = new RecordingWorkflowOperations();
        RecordingWorkflowOperations reflectiveOps = new RecordingWorkflowOperations();
        WorkflowClientFactory generatedFactory = new WorkflowClientFactory(generatedOps, definitions);
        WorkflowClientFactory reflectiveFactory = new WorkflowClientFactory(reflectiveOps, definitions);

        // When each client is created pinned to version 3 and starts, signals and queries once
        SelectionWorkflow generated = generatedFactory
                .client(SelectionDefinitions.Selection.class)
                .version(3)
                .create();
        FallbackSelectionWorkflow reflective = reflectiveFactory
                .client(SelectionDefinitions.Fallback.class)
                .version(3)
                .create();
        recordAll(generated);
        recordAll(reflective);

        // Then the first is the generated class and the second a JDK proxy
        assertThat(generated).isInstanceOf(SelectionWorkflow_WorkflowClientProxy.class);
        assertThat(Proxy.isProxyClass(reflective.getClass())).isTrue();

        // And the commands differ only in the definition they name; the unset start scope stays null in both
        StartCommand generatedStart = generatedOps.starts().get(0);
        StartCommand reflectiveStart = reflectiveOps.starts().get(0);
        assertThat(generatedStart.definitionId()).isEqualTo("selection-saga");
        assertThat(generatedStart.contractName()).isEqualTo(SelectionWorkflow.class.getName());
        assertThat(reflectiveStart.definitionId()).isEqualTo("selection-saga-fallback");
        assertThat(reflectiveStart.contractName()).isEqualTo(FallbackSelectionWorkflow.class.getName());
        assertThat(generatedStart)
                .usingRecursiveComparison()
                .ignoringFields("definitionId", "contractName")
                .isEqualTo(reflectiveStart);
        assertThat(generatedStart.requestedDefinitionVersion()).isEqualTo(3L);
        assertThat(generatedStart.startScope()).isNull();
        assertThat(reflectiveStart.startScope()).isNull();
        assertThat(generatedOps.signals()).hasSize(2).isEqualTo(reflectiveOps.signals());
        assertThat(generatedOps.queries()).hasSize(1).isEqualTo(reflectiveOps.queries());

        // When clients built with a start scope and a version in a target scope start
        RecordingWorkflowOperations generatedScopedOps = new RecordingWorkflowOperations();
        RecordingWorkflowOperations reflectiveScopedOps = new RecordingWorkflowOperations();
        new WorkflowClientFactory(generatedScopedOps, definitions)
                .client(SelectionDefinitions.Selection.class)
                .scope("acme")
                .version("eu", 3)
                .create()
                .start(new SelectionStartPayload("order-1"));
        new WorkflowClientFactory(reflectiveScopedOps, definitions)
                .client(SelectionDefinitions.Fallback.class)
                .scope("acme")
                .version("eu", 3)
                .create()
                .start(new SelectionStartPayload("order-1"));

        // Then both record "acme" as the start scope and "eu" as the target scope, never "default"
        for (RecordingWorkflowOperations scoped : List.of(generatedScopedOps, reflectiveScopedOps)) {
            StartCommand scopedStart = scoped.starts().get(0);
            assertThat(scopedStart.startScope()).isEqualTo("acme");
            assertThat(scopedStart.requestedTargetScope()).isEqualTo("eu");
            assertThat(scopedStart.requestedDefinitionVersion()).isEqualTo(3L);
        }
    }

    private static void recordAll(SelectionWorkflow client) {
        WorkflowInstanceId id =
                client.start(new SelectionStartPayload("order-1")).result();
        client.confirm(id, new SelectionSignalPayload("event-1"));
        client.ship(id, new SelectionSignalPayload("event-2"));
        client.getView(id);
    }

    private static void recordAll(FallbackSelectionWorkflow client) {
        WorkflowInstanceId id =
                client.start(new SelectionStartPayload("order-1")).result();
        client.confirm(id, new SelectionSignalPayload("event-1"));
        client.ship(id, new SelectionSignalPayload("event-2"));
        client.getView(id);
    }

    @Nested
    @DisplayName("generated-proxy selection")
    class GeneratedProxySelection {

        @Test
        @DisplayName("uses the generated proxy when present on the classpath")
        void usesGeneratedProxyWhenPresent() {
            SelectionWorkflow proxy = factory.create(SelectionWorkflow.class, "selection-saga");

            assertThat(proxy).isInstanceOf(SelectionWorkflow_WorkflowClientProxy.class);
            assertThat(Proxy.isProxyClass(proxy.getClass()))
                    .as("should not be a JDK dynamic proxy")
                    .isFalse();
        }

        @Test
        @DisplayName("uses the generated proxy for a nested contract via the flattened companion name")
        void usesGeneratedProxyForNestedContract() {
            NestedSelectionHost.NestedJob proxy =
                    factory.create(NestedSelectionHost.NestedJob.class, "selection-saga-nested");

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
            FallbackSelectionWorkflow proxy =
                    factory.create(FallbackSelectionWorkflow.class, "selection-saga-fallback");

            assertThat(Proxy.isProxyClass(proxy.getClass()))
                    .as("should be a JDK dynamic proxy when no companion is on the classpath")
                    .isTrue();
        }

        @Test
        @DisplayName("fails loudly when a present generated proxy cannot be instantiated")
        void failsLoudlyWhenGeneratedProxyBroken() {
            assertThatThrownBy(() -> factory.create(BrokenSelectionWorkflow.class, "selection-saga-broken"))
                    .isInstanceOf(WorkflowClientProxyLinkageException.class)
                    .hasMessageContaining("BrokenSelectionWorkflow_WorkflowClientProxy");
        }

        @Test
        @DisplayName("a definition id that is not registered still gets a client, since documents may load later")
        void unregisteredDefinitionIdStillGetsAClient() {
            // UnregisteredSelectionWorkflow is structurally valid and has no stand-in proxy; its definition id
            // is not registered. Client creation checks the contract only, so it falls back to the JDK proxy.
            UnregisteredSelectionWorkflow proxy =
                    factory.create(UnregisteredSelectionWorkflow.class, "selection-saga-unregistered");

            assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        }
    }
}

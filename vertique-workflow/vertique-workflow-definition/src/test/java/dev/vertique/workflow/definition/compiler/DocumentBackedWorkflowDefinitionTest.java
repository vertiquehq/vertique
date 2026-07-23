// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.ServiceStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link DocumentBackedWorkflowDefinition} behaviour:
 *
 * <ul>
 *   <li>{@link DocumentBackedWorkflowDefinition#fromDocument} resolves classes and populates
 *       metadata fields correctly.</li>
 *   <li>An unresolvable class name produces a {@link WorkflowDefinitionException}.</li>
 *   <li>{@code define(builder)} produces the expected plan shape for a minimal document.</li>
 * </ul>
 */
class DocumentBackedWorkflowDefinitionTest {

    // --- Test types ---

    record OrderState(String orderId) {}

    record PlaceOrder(String id) {}

    @WorkflowContract(definitionId = "order-v1", definitionVersion = 1)
    interface OrderV1Contract {}

    // --- Shared infrastructure ---

    private WorkflowDefinitionCompiler compiler;
    private RegisteredIdentifierLookup lookup;
    private WorkflowDefinitionMapperFactory mapperFactory;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
        ExpressionProfile profile = new CelExpressionProfile();
        StartStateMapperRegistry startStateMapperRegistry =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.fromPlaceOrder", PlaceOrder.class, OrderState.class, cmd -> new OrderState(cmd.id())))));
        PayloadMapperRegistry payloadMapperRegistry = Mockito.mock(PayloadMapperRegistry.class);
        // Use raw type to sidestep wildcard capture inference
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("ship.payload", OrderState.class, s -> s);
        Mockito.when(payloadMapperRegistry.lookup(Mockito.anyString())).thenReturn(rawMapper);
        DefaultNamedConditionRegistry conditionRegistry = new DefaultNamedConditionRegistry(Set.of());

        lookup = Helpers.lookupWith(payloadMapperRegistry, startStateMapperRegistry, conditionRegistry);
        DecisionRouteCompiler decisionRouteCompiler =
                new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
        compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);
    }

    // --- fromDocument factory ---

    @Nested
    @DisplayName("fromDocument factory")
    class FromDocumentFactory {

        @Test
        @DisplayName("resolves state type and contract; metadata is correct")
        void resolvesClassesAndMetadata() {
            WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                    "order-v1",
                    1L,
                    OrderState.class.getName(),
                    OrderV1Contract.class.getName(),
                    PlaceOrder.class.getName(),
                    "order.fromPlaceOrder",
                    null,
                    "ship",
                    List.of(
                            new ServiceStep("ship", "shipping.create", "ship.payload", null, "done"),
                            new CompleteStep("done")));

            DocumentBackedWorkflowDefinition<?, ?> def = DocumentBackedWorkflowDefinition.fromDocument(
                    doc, compiler, Thread.currentThread().getContextClassLoader());

            assertThat(def.stateType()).isEqualTo(OrderState.class);
            assertThat(def.contract()).isEqualTo(OrderV1Contract.class);
            assertThat(def.definitionId()).isEqualTo("order-v1");
            assertThat(def.definitionVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("unresolvable stateType class name throws WorkflowDefinitionException")
        void unresolvableStateTypeThrows() {
            WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                    "order-v1",
                    1L,
                    "com.example.NonExistentState",
                    OrderV1Contract.class.getName(),
                    PlaceOrder.class.getName(),
                    "order.fromPlaceOrder",
                    null,
                    "ship",
                    List.of(new CompleteStep("ship")));

            assertThatThrownBy(() -> DocumentBackedWorkflowDefinition.fromDocument(
                            doc, compiler, Thread.currentThread().getContextClassLoader()))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("com.example.NonExistentState");
        }

        @Test
        @DisplayName("unresolvable contract class name throws WorkflowDefinitionException")
        void unresolvableContractThrows() {
            WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                    "order-v1",
                    1L,
                    OrderState.class.getName(),
                    "com.example.NonExistentContract",
                    PlaceOrder.class.getName(),
                    "order.fromPlaceOrder",
                    null,
                    "ship",
                    List.of(new CompleteStep("ship")));

            assertThatThrownBy(() -> DocumentBackedWorkflowDefinition.fromDocument(
                            doc, compiler, Thread.currentThread().getContextClassLoader()))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("com.example.NonExistentContract");
        }
    }

    // --- define(builder) produces the correct plan ---

    @Nested
    @DisplayName("define(builder) plan shape")
    class DefinePlanShape {

        @Test
        @DisplayName("minimal service + complete document produces correct plan nodes")
        void minimalServiceCompletePlan() {
            WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                    "order-v1",
                    1L,
                    OrderState.class.getName(),
                    OrderV1Contract.class.getName(),
                    PlaceOrder.class.getName(),
                    "order.fromPlaceOrder",
                    null,
                    "ship",
                    List.of(
                            new ServiceStep("ship", "shipping.create", "ship.payload", null, "done"),
                            new CompleteStep("done")));

            @SuppressWarnings("unchecked")
            DocumentBackedWorkflowDefinition<OrderState, OrderV1Contract> def =
                    (DocumentBackedWorkflowDefinition<OrderState, OrderV1Contract>)
                            DocumentBackedWorkflowDefinition.fromDocument(
                                    doc, compiler, Thread.currentThread().getContextClassLoader());

            WorkflowBuilder<OrderState> builder = new WorkflowBuilder<>();
            def.define(builder);
            WorkflowPlan plan = builder.build("order-v1", 1L, OrderState.class.getName());

            assertThat(plan.nodes()).hasSize(2);
            WorkflowNode first = plan.nodes().get(0);
            assertThat(first).isInstanceOf(ServiceDispatchNode.class);
            ServiceDispatchNode serviceNode = (ServiceDispatchNode) first;
            assertThat(serviceNode.stepId()).isEqualTo("ship");
            assertThat(serviceNode.targetId()).isEqualTo("shipping.create");
        }
    }
}

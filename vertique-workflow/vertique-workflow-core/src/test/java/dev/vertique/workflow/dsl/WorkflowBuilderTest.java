// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowBuilder} produces a valid {@link WorkflowPlan} from DSL
 * invocations, registers callbacks correctly, and computes a deterministic {@code planHash}.
 */
class WorkflowBuilderTest {

    // --- Sample types ---

    record OrderState(String orderId) {}

    record PlaceOrderCmd(String orderId) {
        String idempotencyKey() {
            return orderId;
        }
    }

    record ShippedEvent(String trackingCode) {}

    /** Minimal contract required for definition registration. */
    @WorkflowContract(definitionId = "order-fulfillment", definitionVersion = 1)
    interface OrderFulfillmentContract {}

    // --- Helper: build a standard 4-node plan via the registry ---

    /**
     * Builds a plan with: dispatch("charge"), dispatch("ship"), waitFor("wait-shipped"), complete("done")
     * by registering a definition through the registry so stateTypeName is populated correctly.
     */
    private static WorkflowPlan buildFourNodePlan(DefaultWorkflowRegistry registry) {
        registry.register(new WorkflowDefinition<OrderState, OrderFulfillmentContract>() {
            @Override
            public Class<OrderFulfillmentContract> contract() {
                return OrderFulfillmentContract.class;
            }

            @Override
            public Class<OrderState> stateType() {
                return OrderState.class;
            }

            @Override
            public String definitionId() {
                return "order-fulfillment";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<OrderState> wf) {
                wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                        .initialStep("charge")
                        .dispatch("charge", "payment-svc", s -> new Object(), "ship")
                        .dispatch("ship", "fulfillment-svc", s -> new Object(), "wait-shipped")
                        .waitFor(
                                "wait-shipped",
                                "order.shipped",
                                ShippedEvent.class,
                                (s, e) -> new OrderState(s.orderId()),
                                "done")
                        .complete("done");
            }
        });
        return registry.resolveCurrent("order-fulfillment").plan();
    }

    // --- Tests ---

    @Nested
    @DisplayName("plan structure")
    class PlanStructure {

        @Test
        @DisplayName("DSL with init + 2 dispatch + waitFor + complete produces 4 nodes")
        void shouldProduceFourNodes() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            assertThat(plan.nodes()).hasSize(4);
        }

        @Test
        @DisplayName("initialStepId matches the step passed to initialStep()")
        void shouldSetInitialStepId() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            assertThat(plan.initialStepId()).isEqualTo("charge");
        }

        @Test
        @DisplayName("definitionId and definitionVersion are set from build() args")
        void shouldCarryDefinitionMetadata() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            assertThat(plan.definitionId()).isEqualTo("order-fulfillment");
            assertThat(plan.definitionVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("stateTypeName is set from the builder's state type parameter")
        void shouldSetStateTypeName() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            assertThat(plan.stateTypeName()).isEqualTo(OrderState.class.getName());
        }

        @Test
        @DisplayName("dispatch node carries targetId and nextStepId")
        void shouldBuildDispatchNode() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            WorkflowNode chargeNode = plan.nodes().stream()
                    .filter(n -> n.stepId().equals("charge"))
                    .findFirst()
                    .orElseThrow();
            assertThat(chargeNode).isInstanceOf(ServiceDispatchNode.class);
            ServiceDispatchNode dispatch = (ServiceDispatchNode) chargeNode;
            assertThat(dispatch.targetId()).isEqualTo("payment-svc");
            assertThat(dispatch.nextStepId()).isEqualTo("ship");
            assertThat(dispatch.compensationStepId()).isNull();
        }

        @Test
        @DisplayName("waitFor node carries signalName, payloadTypeName, and nextStepId")
        void shouldBuildWaitSignalNode() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            WorkflowNode waitNode = plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-shipped"))
                    .findFirst()
                    .orElseThrow();
            assertThat(waitNode).isInstanceOf(WaitSignalNode.class);
            WaitSignalNode wait = (WaitSignalNode) waitNode;
            assertThat(wait.signalName()).isEqualTo("order.shipped");
            assertThat(wait.payloadTypeName()).isEqualTo(ShippedEvent.class.getName());
            assertThat(wait.nextStepId()).isEqualTo("done");
        }

        @Test
        @DisplayName("complete node is a CompleteNode")
        void shouldBuildCompleteNode() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            WorkflowNode doneNode = plan.nodes().stream()
                    .filter(n -> n.stepId().equals("done"))
                    .findFirst()
                    .orElseThrow();
            assertThat(doneNode).isInstanceOf(CompleteNode.class);
        }
    }

    @Nested
    @DisplayName("callback registry")
    class CallbackRegistration {

        @Test
        @DisplayName("all CallbackIds in the plan resolve in the callback registry")
        void shouldRegisterAllCallbackIds() {
            WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
            wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "ship")
                    .dispatch("ship", "fulfillment-svc", s -> new Object(), "wait-shipped")
                    .waitFor(
                            "wait-shipped",
                            "order.shipped",
                            ShippedEvent.class,
                            (s, e) -> new OrderState(s.orderId()),
                            "done")
                    .complete("done");
            wf.build("order-fulfillment", 1L);

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();

            // All CallbackIds in dispatch nodes must resolve as payload factories
            List<CallbackId> dispatchCbIds = wf.build("order-fulfillment", 1L).nodes().stream()
                    .filter(n -> n instanceof ServiceDispatchNode)
                    .map(n -> ((ServiceDispatchNode) n).payloadCallbackId())
                    .toList();

            for (CallbackId cbId : dispatchCbIds) {
                assertThat(callbacks.payloadFactory(cbId)).isNotNull();
            }

            // WaitSignalNode callback id must resolve as a state updater
            WaitSignalNode waitNode = (WaitSignalNode) wf.build("order-fulfillment", 1L).nodes().stream()
                    .filter(n -> n instanceof WaitSignalNode)
                    .findFirst()
                    .orElseThrow();
            assertThat(callbacks.stateUpdater(waitNode.stateUpdaterCallbackId()))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("plan hash")
    class PlanHash {

        @Test
        @DisplayName("planHash is non-null and non-empty")
        void shouldProduceNonNullHash() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            WorkflowPlan plan = buildFourNodePlan(registry);

            assertThat(plan.planHash()).isNotBlank();
        }

        @Test
        @DisplayName("two builders with identical DSL produce the same planHash")
        void shouldProduceDeterministicHash() {
            WorkflowBuilder<OrderState> wf1 = new WorkflowBuilder<>();
            wf1.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "ship")
                    .complete("ship");
            WorkflowPlan plan1 = wf1.build("test-def", 1L);

            WorkflowBuilder<OrderState> wf2 = new WorkflowBuilder<>();
            wf2.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "ship")
                    .complete("ship");
            WorkflowPlan plan2 = wf2.build("test-def", 1L);

            assertThat(plan1.planHash()).isEqualTo(plan2.planHash());
        }

        @Test
        @DisplayName("plan with an extra step produces a different planHash")
        void shouldProduceDifferentHashForDifferentStructure() {
            WorkflowBuilder<OrderState> wf1 = new WorkflowBuilder<>();
            wf1.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "done")
                    .complete("done");
            WorkflowPlan plan1 = wf1.build("test-def", 1L);

            WorkflowBuilder<OrderState> wf2 = new WorkflowBuilder<>();
            wf2.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "extra-step")
                    .dispatch("extra-step", "other-svc", s -> new Object(), "done")
                    .complete("done");
            WorkflowPlan plan2 = wf2.build("test-def", 1L);

            assertThat(plan1.planHash()).isNotEqualTo(plan2.planHash());
        }
    }

    @Nested
    @DisplayName("validation errors")
    class ValidationErrors {

        @Test
        @DisplayName("building without calling init() throws WorkflowDefinitionException")
        void shouldRejectMissingInit() {
            WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
            // init() was never called; build() must reject.
            assertThatThrownBy(() -> wf.build("test-def", 1L))
                    .isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("calling init() twice throws WorkflowDefinitionException")
        void shouldRejectDoubleInit() {
            WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
            wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()));
            assertThatThrownBy(() -> wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId())))
                    .isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class)
                    .hasMessageContaining("once");
        }

        @Test
        @DisplayName("calling a step DSL method before init() throws WorkflowDefinitionException")
        void shouldRejectStepBeforeInit() {
            WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
            assertThatThrownBy(() -> wf.dispatch("charge", "payment-svc", s -> new Object(), "done"))
                    .isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class)
                    .hasMessageContaining("init");
        }

        @Test
        @DisplayName("calling init() after a step DSL method throws WorkflowDefinitionException")
        void shouldRejectInitAfterStep() {
            WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
            wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId())).complete("done");
            assertThatThrownBy(() -> wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId())))
                    .isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("init() followed by dispatch() succeeds (valid ordering)")
        void shouldAcceptStepAfterInit() {
            WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
            assertThat(wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
                            .dispatch("charge", "payment-svc", s -> new Object(), "done")
                            .complete("done")
                            .build("test-def", 1L))
                    .isNotNull();
        }
    }
}

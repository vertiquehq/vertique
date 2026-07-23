// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DefaultWorkflowRegistry} behaviour: version coexistence, resolution semantics,
 * pin-unavailable error contract, and plan-validation rules enforced at registration time.
 */
class WorkflowRegistryTest {

    // --- Fixture types ---

    record OrderState(String id) {}

    record StartCmd(String id) {}

    record SignalPayload(String data) {}

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface OrderSagaContractV1 {}

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 2)
    interface OrderSagaContractV2 {}

    /** Valid definition — version 1. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContractV1> contract() {
            return OrderSagaContractV1.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "order-saga";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                    .initialStep("wait")
                    .waitFor("wait", "order.confirmed", SignalPayload.class, (s, p) -> s, "done")
                    .complete("done");
        }
    };

    /** Valid definition — version 2 (different structure). */
    static final WorkflowDefinition<OrderState, OrderSagaContractV2> DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContractV2> contract() {
            return OrderSagaContractV2.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "order-saga";
        }

        @Override
        public long definitionVersion() {
            return 2L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "done")
                    .complete("done");
        }
    };

    /** Invalid definition: never calls wf.init(). */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_NO_INIT = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContractV1> contract() {
            return OrderSagaContractV1.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "bad-def";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            // Intentionally omitted: wf.init(...)
            wf.initialStep("done").complete("done");
        }
    };

    // --- Plan graph validation fixtures ---

    /** Invalid definition: two nodes share the same stepId. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_DUPLICATE_STEP_ID =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "dup-step-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                            .initialStep("step")
                            // Two nodes with the same stepId — must be rejected by graph validation
                            .dispatch("step", "svc-a", s -> new Object(), "done")
                            .dispatch("step", "svc-b", s -> new Object(), "done")
                            .complete("done");
                }
            };

    /** Invalid definition: initialStepId does not match any node. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_DANGLING_INITIAL_STEP =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "dangling-initial-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    // initialStep("nonexistent") sets initialStepId to a step not in the node list
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                            .initialStep("nonexistent-step")
                            .complete("done");
                }
            };

    /** Invalid definition: ServiceDispatchNode.nextStepId does not resolve. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_DANGLING_NEXT_STEP =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "dangling-next-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                            .initialStep("dispatch")
                            // nextStepId "nonexistent" does not match any node
                            .dispatch("dispatch", "svc", s -> new Object(), "nonexistent")
                            .complete("done");
                }
            };

    /** Invalid definition: compensationStepId resolves to a non-CompensationNode. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_COMP_STEP_WRONG_TYPE =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "comp-wrong-type-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                            .initialStep("dispatch")
                            // compensationStepId "done" resolves to a CompleteNode, not a CompensationNode
                            .dispatchWithCompensation("dispatch", "svc", s -> new Object(), "done", "wait")
                            .waitFor("wait", "sig", SignalPayload.class, (s, p) -> s, "done")
                            .complete("done");
                }
            };

    /** Invalid definition: CompensationNode.forwardStepId resolves to a non-ServiceDispatchNode. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_FWD_STEP_WRONG_TYPE =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "fwd-wrong-type-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                            .initialStep("wait")
                            .waitFor("wait", "sig", SignalPayload.class, (s, p) -> s, "done")
                            .complete("done")
                            // forwardStepId "wait" resolves to a WaitSignalNode, not a ServiceDispatchNode
                            .compensate("comp", "wait", "svc-rollback", s -> new Object());
                }
            };

    /** Invalid definition: compensation pair is mismatched (sdn.compensationStepId != comp.stepId). */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_MISMATCHED_COMP_PAIR =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "mismatch-comp-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    // dispatch-a references comp-a as its compensation.
                    // comp-a declares forwardStepId = "dispatch-a" — consistent.
                    // comp-b declares forwardStepId = "dispatch-a" — mismatched (dispatch-a.compensationStepId !=
                    // "comp-b").
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                            .initialStep("dispatch-a")
                            .dispatchWithCompensation("dispatch-a", "svc", s -> new Object(), "comp-a", "done")
                            .complete("done")
                            .compensate("comp-a", "dispatch-a", "svc-rollback", s -> new Object())
                            // comp-b.forwardStepId = "dispatch-a" but dispatch-a.compensationStepId = "comp-a" !=
                            // "comp-b"
                            .compensate("comp-b", "dispatch-a", "svc-rollback-b", s -> new Object());
                }
            };

    /** Invalid definition: duplicate signal name. */
    static final WorkflowDefinition<OrderState, OrderSagaContractV1> DEF_DUPLICATE_SIGNAL = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContractV1> contract() {
            return OrderSagaContractV1.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "dup-signal-def";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                    .initialStep("wait1")
                    // Two WaitSignalNodes with the same signalName — must be rejected
                    .waitFor("wait1", "same.signal", SignalPayload.class, (s, p) -> s, "wait2")
                    .waitFor("wait2", "same.signal", SignalPayload.class, (s, p) -> s, "done")
                    .complete("done");
        }
    };

    private DefaultWorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultWorkflowRegistry();
    }

    // --- Tests ---

    @Nested
    @DisplayName("version coexistence")
    class VersionCoexistence {

        @Test
        @DisplayName("registering two versions of the same definitionId succeeds")
        void shouldAcceptTwoVersions() {
            registry.register(DEF_V1);
            registry.register(DEF_V2);

            // no exception — both registrations are accepted
        }

        @Test
        @DisplayName("resolveCurrent returns the highest-version RuntimeWorkflow")
        void shouldReturnHighestVersion() {
            registry.register(DEF_V1);
            registry.register(DEF_V2);

            RuntimeWorkflow rw = registry.resolveCurrent("order-saga");

            assertThat(rw.plan().definitionVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("resolvePinned returns exactly the requested version")
        void shouldReturnPinnedVersion() {
            registry.register(DEF_V1);
            registry.register(DEF_V2);

            RuntimeWorkflow rw = registry.resolvePinned("order-saga", 1L);

            assertThat(rw.plan().definitionVersion()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("version-pin unavailable")
    class VersionPinUnavailable {

        @Test
        @DisplayName("resolvePinned with missing version throws WorkflowVersionPinUnavailableException")
        void shouldThrowOnMissingPinnedVersion() {
            registry.register(DEF_V1);

            assertThatThrownBy(() -> registry.resolvePinned("order-saga", 99L))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class)
                    .hasMessageContaining("order-saga")
                    .hasMessageContaining("99");
        }
    }

    @Nested
    @DisplayName("plan validation at registration")
    class PlanValidation {

        @Test
        @DisplayName("registering a definition without wf.init() throws WorkflowDefinitionException")
        void shouldRejectMissingInit() {
            assertThatThrownBy(() -> registry.register(DEF_NO_INIT)).isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("registering a definition with duplicate signal names throws WorkflowDefinitionException")
        void shouldRejectDuplicateSignalNames() {
            assertThatThrownBy(() -> registry.register(DEF_DUPLICATE_SIGNAL))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("same.signal");
        }
    }

    @Nested
    @DisplayName("plan graph shape validation at registration")
    class PlanGraphValidation {

        @Test
        @DisplayName("duplicate stepId in plan → WorkflowDefinitionException naming the step")
        void shouldRejectDuplicateStepId() {
            assertThatThrownBy(() -> registry.register(DEF_DUPLICATE_STEP_ID))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("duplicate stepId")
                    .hasMessageContaining("step");
        }

        @Test
        @DisplayName("initialStepId not in node list → WorkflowDefinitionException naming initialStepId")
        void shouldRejectDanglingInitialStepId() {
            assertThatThrownBy(() -> registry.register(DEF_DANGLING_INITIAL_STEP))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("initialStepId")
                    .hasMessageContaining("nonexistent-step");
        }

        @Test
        @DisplayName("ServiceDispatchNode.nextStepId does not resolve → WorkflowDefinitionException")
        void shouldRejectDanglingNextStepId() {
            assertThatThrownBy(() -> registry.register(DEF_DANGLING_NEXT_STEP))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("nextStepId")
                    .hasMessageContaining("nonexistent");
        }

        @Test
        @DisplayName("compensationStepId resolves to non-CompensationNode → WorkflowDefinitionException")
        void shouldRejectCompensationStepIdWrongType() {
            assertThatThrownBy(() -> registry.register(DEF_COMP_STEP_WRONG_TYPE))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("compensationStepId")
                    .hasMessageContaining("CompensationNode");
        }

        @Test
        @DisplayName("CompensationNode.forwardStepId resolves to non-ServiceDispatchNode → WorkflowDefinitionException")
        void shouldRejectForwardStepIdWrongType() {
            assertThatThrownBy(() -> registry.register(DEF_FWD_STEP_WRONG_TYPE))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("forwardStepId")
                    .hasMessageContaining("ServiceDispatchNode");
        }

        @Test
        @DisplayName(
                "mismatched compensation pair (comp.forwardStepId → sdn, but sdn.compensationStepId != comp.stepId) → WorkflowDefinitionException")
        void shouldRejectMismatchedCompensationPair() {
            assertThatThrownBy(() -> registry.register(DEF_MISMATCHED_COMP_PAIR))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("mismatched");
        }

        @Test
        @DisplayName("well-formed plan with dispatch+wait+compensation registers without error")
        void shouldAcceptWellFormedPlan() {
            assertThatCode(() -> registry.register(DEF_V1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("definition that calls wf.init(...) with no step DSL → WorkflowDefinitionException")
        void shouldRejectEmptyPlan() {
            WorkflowDefinition<OrderState, OrderSagaContractV1> emptyPlan = new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContractV1> contract() {
                    return OrderSagaContractV1.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "empty-plan-saga";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    // Only init() — no nodes added. Currently this is invalid: the engine cannot
                    // start an instance with a null currentStepId.
                    wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()));
                }
            };

            assertThatThrownBy(() -> registry.register(emptyPlan))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContainingAll("empty-plan-saga", "no nodes");
        }
    }

    @Nested
    @DisplayName("duplicate registration rejection")
    class DuplicateRejection {

        // --- Fixture types for duplicate tests ---

        record StateA(String v) {}

        record StateB(String v) {}

        record PayloadA(String id) {}

        record PayloadB(String id) {}

        /** Contract A bound to definitionId "dup", version 1. */
        @WorkflowContract(definitionId = "dup", definitionVersion = 1)
        interface ContractA {}

        /** Contract B bound to definitionId "dup", version 1 (same id/version as ContractA). */
        @WorkflowContract(definitionId = "dup", definitionVersion = 1)
        interface ContractB {}

        /** Contract D1 — unique definitionId "d1", version 1. */
        @WorkflowContract(definitionId = "d1", definitionVersion = 1)
        interface ContractD1 {}

        @Test
        @DisplayName(
                "registering (id='dup', v=1) twice with different contract classes throws WorkflowDefinitionException")
        void registeringSameDefinitionIdAndVersionTwiceThrows() {
            // First: register (id="dup", v=1) with ContractA
            WorkflowDefinition<StateA, ContractA> defA = new WorkflowDefinition<>() {
                @Override
                public Class<ContractA> contract() {
                    return ContractA.class;
                }

                @Override
                public Class<StateA> stateType() {
                    return StateA.class;
                }

                @Override
                public String definitionId() {
                    return "dup";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StateA> wf) {
                    wf.init(PayloadA.class, p -> new StateA(p.id()))
                            .initialStep("done")
                            .complete("done");
                }
            };

            // Second: register (id="dup", v=1) with ContractB (distinct contract type, same def id+version)
            WorkflowDefinition<StateB, ContractB> defB = new WorkflowDefinition<>() {
                @Override
                public Class<ContractB> contract() {
                    return ContractB.class;
                }

                @Override
                public Class<StateB> stateType() {
                    return StateB.class;
                }

                @Override
                public String definitionId() {
                    return "dup";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StateB> wf) {
                    wf.init(PayloadB.class, p -> new StateB(p.id()))
                            .initialStep("done")
                            .complete("done");
                }
            };

            registry.register(defA);

            assertThatThrownBy(() -> registry.register(defB))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("dup")
                    .hasMessageContaining("v1")
                    .hasMessageContaining("already registered");
        }

        @Test
        @DisplayName(
                "registering same contract class to two different definitionIds throws WorkflowDefinitionException")
        void registeringSameContractTwiceThrows() {
            // Both definitions share the same contract class (ContractD1), but have different ids.
            WorkflowDefinition<StateA, ContractD1> defD1 = new WorkflowDefinition<>() {
                @Override
                public Class<ContractD1> contract() {
                    return ContractD1.class;
                }

                @Override
                public Class<StateA> stateType() {
                    return StateA.class;
                }

                @Override
                public String definitionId() {
                    return "d1";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StateA> wf) {
                    wf.init(PayloadA.class, p -> new StateA(p.id()))
                            .initialStep("done")
                            .complete("done");
                }
            };

            // A second definition that also declares ContractD1 but under a different definitionId.
            WorkflowDefinition<StateB, ContractD1> defD2 = new WorkflowDefinition<>() {
                @Override
                public Class<ContractD1> contract() {
                    // Re-using the same ContractD1 class — must be rejected.
                    return ContractD1.class;
                }

                @Override
                public Class<StateB> stateType() {
                    return StateB.class;
                }

                @Override
                public String definitionId() {
                    return "d2";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StateB> wf) {
                    wf.init(PayloadB.class, p -> new StateB(p.id()))
                            .initialStep("done")
                            .complete("done");
                }
            };

            registry.register(defD1);

            // Second registration re-binds ContractD1 to a different definitionId — must be rejected.
            assertThatThrownBy(() -> registry.register(defD2))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining(ContractD1.class.getName());
        }

        @Test
        @DisplayName("registering different versions of the same definitionId succeeds; resolveCurrent returns highest")
        void registeringDifferentVersionsOfSameDefinitionWorks() {
            // Contract V1 and V2 for definitionId "multi" — different contract types, different versions.
            @WorkflowContract(definitionId = "multi", definitionVersion = 1)
            interface MultiContractV1 {}

            @WorkflowContract(definitionId = "multi", definitionVersion = 2)
            interface MultiContractV2 {}

            WorkflowDefinition<StateA, MultiContractV1> defV1 = new WorkflowDefinition<>() {
                @Override
                public Class<MultiContractV1> contract() {
                    return MultiContractV1.class;
                }

                @Override
                public Class<StateA> stateType() {
                    return StateA.class;
                }

                @Override
                public String definitionId() {
                    return "multi";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StateA> wf) {
                    wf.init(PayloadA.class, p -> new StateA(p.id()))
                            .initialStep("done")
                            .complete("done");
                }
            };

            WorkflowDefinition<StateB, MultiContractV2> defV2 = new WorkflowDefinition<>() {
                @Override
                public Class<MultiContractV2> contract() {
                    return MultiContractV2.class;
                }

                @Override
                public Class<StateB> stateType() {
                    return StateB.class;
                }

                @Override
                public String definitionId() {
                    return "multi";
                }

                @Override
                public long definitionVersion() {
                    return 2L;
                }

                @Override
                public void define(WorkflowBuilder<StateB> wf) {
                    wf.init(PayloadB.class, p -> new StateB(p.id()))
                            .initialStep("done")
                            .complete("done");
                }
            };

            // Both registrations must succeed.
            assertThatCode(() -> registry.register(defV1)).doesNotThrowAnyException();
            assertThatCode(() -> registry.register(defV2)).doesNotThrowAnyException();

            // resolveCurrent must return the v=2 RuntimeWorkflow.
            RuntimeWorkflow rw = registry.resolveCurrent("multi");
            assertThat(rw.plan().definitionVersion()).isEqualTo(2L);
        }
    }
}

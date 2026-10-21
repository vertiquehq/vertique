// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DefaultWorkflowRegistry} behaviour: version coexistence, resolution semantics,
 * pin-unavailable error contract, and plan-validation rules enforced at registration time.
 */
class WorkflowRegistryTest {

    private static final int GRINNING_FACE = 0x1F600;

    // --- Fixture types ---

    record OrderState(String id) {}

    record StartCmd(String id) {}

    record SignalPayload(String data) {}

    @WorkflowContract
    interface OrderSagaContract {
        @WorkflowSignal("order.confirmed")
        Future<Void> confirmed(WorkflowInstanceId id, SignalPayload payload, @SignalDedupKey String dedupKey);
    }

    /** Valid definition — version 1. */
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContract> contract() {
            return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContract> contract() {
            return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_NO_INIT = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContract> contract() {
            return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_DUPLICATE_STEP_ID = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContract> contract() {
            return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_DANGLING_INITIAL_STEP =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContract> contract() {
                    return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_DANGLING_NEXT_STEP = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContract> contract() {
            return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_COMP_STEP_WRONG_TYPE =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContract> contract() {
                    return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_FWD_STEP_WRONG_TYPE =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContract> contract() {
                    return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_MISMATCHED_COMP_PAIR =
            new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContract> contract() {
                    return OrderSagaContract.class;
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
    static final WorkflowDefinition<OrderState, OrderSagaContract> DEF_DUPLICATE_SIGNAL = new WorkflowDefinition<>() {
        @Override
        public Class<OrderSagaContract> contract() {
            return OrderSagaContract.class;
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

            RuntimeWorkflow rw = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "order-saga", 1L));

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

            assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "order-saga", 99L)))
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
            WorkflowDefinition<OrderState, OrderSagaContract> emptyPlan = new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContract> contract() {
                    return OrderSagaContract.class;
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
        @WorkflowContract
        interface ContractA {}

        /** Contract B bound to definitionId "dup", version 1 (same id/version as ContractA). */
        @WorkflowContract
        interface ContractB {}

        /** Contract D1 — unique definitionId "d1", version 1. */
        @WorkflowContract
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
        @DisplayName("registering the same contract class for two different definitionIds succeeds")
        void registeringSameContractForTwoDefinitionIdsSucceeds() {
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
                    // Re-using the same ContractD1 class — accepted, a contract may have many definitions.
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

            // Second registration names ContractD1 for a different definitionId — accepted.
            assertThatCode(() -> registry.register(defD2)).doesNotThrowAnyException();
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "d1", 1L))
                            .contractName())
                    .isEqualTo(ContractD1.class.getName());
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "d2", 1L))
                            .contractName())
                    .isEqualTo(ContractD1.class.getName());
        }

        @Test
        @DisplayName("registering different versions of the same definitionId succeeds; resolveCurrent returns highest")
        void registeringDifferentVersionsOfSameDefinitionWorks() {
            // Versions 1 and 2 of definitionId "multi" share one contract.
            @WorkflowContract
            interface MultiContract {}

            WorkflowDefinition<StateA, MultiContract> defV1 = new WorkflowDefinition<>() {
                @Override
                public Class<MultiContract> contract() {
                    return MultiContract.class;
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

            WorkflowDefinition<StateB, MultiContract> defV2 = new WorkflowDefinition<>() {
                @Override
                public Class<MultiContract> contract() {
                    return MultiContract.class;
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

    // --- Many definitions per contract: tests ---

    @Test
    @DisplayName("many definition ids and versions implement one contract and keep their own identity")
    void manyDefinitionsAndVersionsImplementOneContract() {
        // Given: three distinct definition classes, two ids and three versions, naming one contract
        // and waiting only for signals the contract declares
        List<WorkflowDefinition<ApprovalState, ApprovalContract>> definitions =
                List.of(new PurchaseApprovalV1(), new PurchaseApprovalV2(), new TravelApprovalV1());

        // When: all three register in one registry
        for (WorkflowDefinition<ApprovalState, ApprovalContract> definition : definitions) {
            registry.register(definition);
        }

        // Then: each resolves to its own id, version, state type and the shared contract name
        record Expected(String id, long version, Class<?> definitionClass) {}
        List<Expected> expected = List.of(
                new Expected("purchase-approval", 1L, PurchaseApprovalV1.class),
                new Expected("purchase-approval", 2L, PurchaseApprovalV2.class),
                new Expected("travel-approval", 1L, TravelApprovalV1.class));
        for (Expected e : expected) {
            RuntimeWorkflow rw = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, e.id(), e.version()));
            assertThat(rw.plan().definitionId()).as("plan id").isEqualTo(e.id());
            assertThat(rw.plan().definitionVersion()).as("plan version").isEqualTo(e.version());
            assertThat(rw.stateType()).as("state type").isEqualTo(ApprovalState.class);
            assertThat(rw.contractName()).as("contract name").isEqualTo("approval");
            assertThat(registry.definitionClass(e.definitionClass()))
                    .as("definition class %s", e.definitionClass().getSimpleName())
                    .hasValueSatisfying(registered -> {
                        assertThat(registered.definitionId()).isEqualTo(e.id());
                        assertThat(registered.contractName()).isEqualTo("approval");
                        assertThat(registered.contract()).isEqualTo(ApprovalContract.class);
                    });
        }
        assertThat(registry.allRegistered()).hasSize(3);
    }

    @Test
    @DisplayName("a version naming another contract than its id's earlier versions is refused")
    void versionNamingAnotherContractIsRefused() {
        // Given: purchase-approval v1 registered with the approval contract
        registry.register(new PurchaseApprovalV1());

        // When: purchase-approval v2 names another contract and registers
        assertThatThrownBy(() -> registry.register(new PurchaseApprovalV2OtherContract()))
                // Then: the registration is refused naming the version and both contract names
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("'purchase-approval' v2")
                .hasMessageContaining("contract 'approval'")
                .hasMessageContaining("contract 'other'");

        // and: the refused version is not resolvable while v1 is still registered
        assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 2L)))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 1L))
                        .plan()
                        .definitionVersion())
                .isEqualTo(1L);
        assertThat(registry.allRegistered()).hasSize(1);

        // and: the refused contract's name is released from the shared index, while the names v1 holds stay
        assertThat(registry.typeNames().findContract("other")).isEmpty();
        assertThat(registry.typeNames().findContract(OtherContract.class.getName()))
                .isEmpty();
        assertThat(registry.typeNames().findContract("approval")).contains(ApprovalContract.class);
        assertThat(registry.typeNames().find(ApprovalState.class.getName())).contains(ApprovalState.class);
    }

    @Test
    @DisplayName("a version waiting for a signal its contract does not declare is refused")
    void versionWaitingForUndeclaredSignalIsRefused() {
        // Given: the contract declares approve and escalate; this definition waits for approve, then
        // for remind (guarded by a timeout), then for audit inside a fork branch and for escalate in
        // the other branch
        WorkflowDefinition<ApprovalState, ApprovalContract> undeclaredWaits = new WaitsForUndeclaredSignals();

        // When: it registers
        assertThatThrownBy(() -> registry.register(undeclaredWaits))
                // Then: the registration is refused naming the version and the undeclared signals, sorted
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("'undeclared-waits' v1")
                .hasMessageContaining("audit, remind");

        // and: nothing is registered
        assertThat(registry.allRegistered()).isEmpty();
        assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "undeclared-waits", 1L)))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertThat(registry.definitionClass(WaitsForUndeclaredSignals.class)).isEmpty();
    }

    @Test
    @DisplayName("the refusal for undeclared signals names at most the first five, in sorted order")
    void refusalNamesAtMostFiveUndeclaredSignals() {
        // Given: a definition waiting for eight signals the contract does not declare
        WorkflowDefinition<ApprovalState, ApprovalContract> manyUndeclared =
                new ManyUndeclaredWaits("sig-8", "sig-3", "sig-1", "sig-7", "sig-5", "sig-2", "sig-6", "sig-4");

        // When: it registers
        assertThatThrownBy(() -> registry.register(manyUndeclared))
                // Then: the refusal names the version and the first five signals in sorted order, and no more
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("'many-undeclared' v1")
                .hasMessageContaining("sig-1, sig-2, sig-3, sig-4, sig-5")
                .message()
                .doesNotContain("sig-6")
                .doesNotContain("sig-7")
                .doesNotContain("sig-8");

        // and: nothing is registered
        assertThat(registry.allRegistered()).isEmpty();
    }

    @Test
    @DisplayName("the refusal repeats a document-supplied signal name cut and without control characters")
    void refusalCutsAndSanitizesHostileSignalNames() {
        // Given: definitions waiting for undeclared signals whose names carry a line break, a control
        // character and 10000 supplementary code points, and one name of exactly 64 plain code points
        String hostile = "audit" + "\r\n" + "FORGED-LOG-LINE" + (char) 7
                + Character.toString(GRINNING_FACE).repeat(10_000);
        String plain64 = "p".repeat(64);
        String plain65 = plain64 + "q";

        // When: each registers
        Throwable hostileRefusal = catchThrowable(() -> registry.register(new ManyUndeclaredWaits(hostile)));
        Throwable plain64Refusal = catchThrowable(() -> registry.register(new ManyUndeclaredWaits(plain64)));
        Throwable plain65Refusal = catchThrowable(() -> registry.register(new ManyUndeclaredWaits(plain65)));

        // Then: the hostile name is cut, holds no control character and no lone surrogate, and is still
        // recognizable by its start; the message stays short
        assertThat(hostileRefusal).isInstanceOf(WorkflowDefinitionException.class);
        String message = hostileRefusal.getMessage();
        assertThat(message.codePoints().noneMatch(Character::isISOControl))
                .as("no control characters in the refusal")
                .isTrue();
        assertThat(message.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF))
                .as("no unpaired surrogate")
                .isTrue();
        assertThat(message).hasSizeLessThan(1_000).contains("audit??FORGED-LOG-LINE?");
        assertThat(message).doesNotContain(Character.toString(GRINNING_FACE).repeat(100));

        // and: a name of 64 code points is repeated whole, one of 65 is cut at 64
        assertThat(plain64Refusal.getMessage()).contains(plain64).doesNotContain(plain65);
        assertThat(plain65Refusal.getMessage()).contains(plain64).doesNotContain(plain65);
    }

    @Test
    @DisplayName("registration reads identity from the definition, not from the contract annotation")
    void registrationReadsIdentityFromTheDefinitionNotTheContractAnnotation() {
        // Given: a contract still carrying the legacy attributes definitionId "unused" and
        // definitionVersion 99, and a definition whose own identity differs from them

        // When: the definition registers
        registry.register(new PurchaseApprovalV3());

        // Then: the registry holds it under the identity the definition reports
        RuntimeWorkflow rw = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 3L));
        assertThat(rw.plan().definitionId()).as("definition id").isEqualTo("purchase-approval");
        assertThat(rw.plan().definitionVersion()).as("definition version").isEqualTo(3L);
        assertThat(rw.stateType()).as("state type").isEqualTo(ApprovalState.class);
        assertThat(rw.contractName()).as("contract name").isEqualTo("approval");
        assertThat(registry.definitionClass(PurchaseApprovalV3.class))
                .as("definition class")
                .hasValueSatisfying(
                        registered -> assertThat(registered.definitionId()).isEqualTo("purchase-approval"));

        // and: nothing is registered under the annotation's legacy values
        assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "unused", 99L)))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertThat(registry.allRegistered())
                .extracting(registered -> registered.plan().definitionId() + " v"
                        + registered.plan().definitionVersion())
                .containsExactly("purchase-approval v3");
    }

    // --- Scoped registry: tests ---

    @Test
    @DisplayName("definitions are keyed by scope and code registers in the default scope")
    void definitionsAreKeyedByScopeAndCodeRegistersInTheDefaultScope() {
        // Given: purchase-approval v1 to v3 registered as code, and a different graph of purchase-approval v1
        // registered in the acme scope
        registry.register(new PurchaseApprovalV1());
        registry.register(new PurchaseApprovalV2());
        registry.register(new PurchaseApprovalV3());
        registry.register("acme", approvalDefinition(1L, "escalate", "approve"));
        DefinitionKey acmeV1 = new DefinitionKey("acme", "purchase-approval", 1L);
        DefinitionKey defaultV1 = new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 1L);
        DefinitionKey defaultV3 = new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 3L);
        DefinitionKey defaultV4 = new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 4L);
        DefinitionKey globexV1 = new DefinitionKey("globex", "purchase-approval", 1L);

        // When: the registry is read through every lookup, the acme version is registered again, and default v4
        // is registered afterwards
        List<DefinitionKey> keysBefore = registry.keysFor("purchase-approval");
        RuntimeWorkflow acmeResolved = registry.resolve(acmeV1);
        RuntimeWorkflow defaultResolved = registry.resolve(defaultV1);
        RuntimeWorkflow currentBefore = registry.resolveCurrent("purchase-approval");
        Throwable globexRefusal = catchThrowable(() -> registry.resolve(globexV1));
        Throwable duplicate =
                catchThrowable(() -> registry.register("acme", approvalDefinition(1L, "approve", "escalate")));
        registry.register(approvalDefinition(4L, "approve"));

        // Then: the listing is ordered by scope, then version, and holds the acme entry beside the default ones
        assertThat(keysBefore)
                .containsExactly(
                        acmeV1,
                        defaultV1,
                        new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 2L),
                        defaultV3);

        // and: each key resolves its own graph, and the graph reports the scope of its key
        assertThat(acmeResolved.definitionScopeKey()).isEqualTo("acme");
        assertThat(acmeResolved.plan().definitionVersion()).isEqualTo(1L);
        assertThat(defaultResolved.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(defaultResolved.plan().definitionVersion()).isEqualTo(1L);
        assertThat(acmeResolved.plan().planHash())
                .isNotEqualTo(defaultResolved.plan().planHash());
        assertThat(registry.find(acmeV1))
                .hasValueSatisfying(found -> assertThat(found.plan()).isEqualTo(acmeResolved.plan()));
        assertThat(registry.find(defaultV1))
                .hasValueSatisfying(found -> assertThat(found.plan()).isEqualTo(defaultResolved.plan()));

        // and: a scope that holds nothing of the id has no entry, and resolving it names the scope
        assertThat(registry.find(globexV1)).isEmpty();
        assertThat(globexRefusal)
                .isInstanceOf(WorkflowVersionPinUnavailableException.class)
                .hasMessageContaining("globex")
                .hasMessageContaining("purchase-approval");

        // and: current resolution considers the default scope only, before and after default v4
        assertThat(currentBefore.plan().definitionVersion()).isEqualTo(3L);
        assertThat(currentBefore.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(registry.resolveCurrent("purchase-approval").plan().definitionVersion())
                .isEqualTo(4L);

        // and: the second acme v1 is refused naming its scope, id and version, and the first entry is unchanged
        assertNamesKey(duplicate, "acme", "purchase-approval", 1L);
        assertThat(registry.resolve(acmeV1).plan()).isEqualTo(acmeResolved.plan());

        // and: default v4 joined the default entries and the acme entry stayed as it was
        assertThat(registry.keysFor("purchase-approval"))
                .containsExactly(
                        acmeV1,
                        defaultV1,
                        new DefinitionKey(WorkflowScopes.DEFAULT, "purchase-approval", 2L),
                        defaultV3,
                        defaultV4);
        assertThat(registry.resolve(defaultV4).definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);

        // and: code registered without a scope appears under no key outside the default scope
        assertThat(registry.allRegistered())
                .filteredOn(registered -> !"acme".equals(registered.definitionScopeKey()))
                .hasSize(4)
                .allSatisfy(registered ->
                        assertThat(registered.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT));
        assertThat(registry.allRegistered()).hasSize(5);
    }

    @Test
    @DisplayName("the contract and signal rules apply per scope and definition id")
    void contractAndSignalRulesApplyPerScopeAndDefinitionId() {
        // Given: contract C declaring ok, contract D declaring go, and default X v1 naming C and waiting for ok
        registry.register(definition(ScopeContractC.class, "X", 1L, "ok"));
        DefinitionKey acmeV1 = new DefinitionKey("acme", "X", 1L);
        DefinitionKey acmeV2 = new DefinitionKey("acme", "X", 2L);
        DefinitionKey defaultV2 = new DefinitionKey(WorkflowScopes.DEFAULT, "X", 2L);

        // When: the registrations of the scenario run in order
        Throwable acmeFirst =
                catchThrowable(() -> registry.register("acme", definition(ScopeContractD.class, "X", 1L, "go")));
        Throwable defaultOtherContract =
                catchThrowable(() -> registry.register(definition(ScopeContractD.class, "X", 2L, "go")));
        Throwable acmeOtherContract =
                catchThrowable(() -> registry.register("acme", definition(ScopeContractC.class, "X", 2L, "ok")));
        Throwable acmeUndeclared =
                catchThrowable(() -> registry.register("acme", definition(ScopeContractD.class, "X", 2L, "ok")));
        boolean refusedLeftNothing =
                registry.find(acmeV2).isEmpty() && registry.find(defaultV2).isEmpty();
        Throwable acmeValid =
                catchThrowable(() -> registry.register("acme", definition(ScopeContractD.class, "X", 2L, "go")));
        Throwable defaultOtherId =
                catchThrowable(() -> registry.register(definition(ScopeContractD.class, "Y", 1L, "go")));

        // Then: acme X v1 is accepted although the default X names another contract
        assertThat(acmeFirst).isNull();

        // and: default X v2 naming D is refused, its (default, X) names C
        assertNamesKey(defaultOtherContract, WorkflowScopes.DEFAULT, "X", 2L);
        assertThat(defaultOtherContract.getMessage())
                .containsIgnoringCase("contract")
                .contains("scope-c")
                .contains("scope-d");

        // and: acme X v2 naming C is refused, its (acme, X) names D
        assertNamesKey(acmeOtherContract, "acme", "X", 2L);
        assertThat(acmeOtherContract.getMessage())
                .containsIgnoringCase("contract")
                .contains("scope-c")
                .contains("scope-d");

        // and: acme X v2 waiting for a signal that D does not declare is refused, naming the signal
        assertNamesKey(acmeUndeclared, "acme", "X", 2L);
        assertThat(acmeUndeclared.getMessage()).contains("ok");

        // and: the refusals registered nothing
        assertThat(refusedLeftNothing).isTrue();

        // and: the valid acme v2 and the default Y, which shares contract D with the acme X, are accepted
        assertThat(acmeValid).isNull();
        assertThat(defaultOtherId).isNull();
        assertThat(registry.find(acmeV2)).isPresent();
        assertThat(registry.find(new DefinitionKey(WorkflowScopes.DEFAULT, "Y", 1L)))
                .isPresent();
        assertThat(registry.allRegistered()).hasSize(4);

        // and: the contract of each (scope, id) is the one its versions name, and a scope without the id has none
        assertThat(registry.contractOf("acme", "X")).contains("scope-d");
        assertThat(registry.contractOf(WorkflowScopes.DEFAULT, "X")).contains("scope-c");
        assertThat(registry.contractOf(WorkflowScopes.DEFAULT, "Y")).contains("scope-d");
        assertThat(registry.contractOf("globex", "X")).isEmpty();
        assertThat(registry.find(acmeV1)).isPresent();
    }

    private static void assertNamesKey(Throwable refusal, String scopeKey, String definitionId, long version) {
        assertThat(refusal).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(refusal.getMessage())
                .contains(scopeKey)
                .contains(definitionId)
                .containsPattern("(\\bv" + version + "\\b|\\bversion " + version + "\\b|, " + version + "\\))");
    }

    /** A definition of {@code definitionId} that waits, one after the other, for the given signals. */
    private static <C> WorkflowDefinition<ApprovalState, C> definition(
            Class<C> contract, String definitionId, long version, String... signals) {
        return new SequentialWaitDefinition<C>(contract, definitionId, version, signals) {};
    }

    /** A purchase-approval definition of the approval contract that waits for the given signals in order. */
    private static WorkflowDefinition<ApprovalState, ApprovalContract> approvalDefinition(
            long version, String... signals) {
        return definition(ApprovalContract.class, "purchase-approval", version, signals);
    }

    // --- Many definitions per contract: fixtures ---

    record ApprovalState(String id) {}

    record ApprovalStart(String id) {}

    record ApprovalSignal(String note) {}

    /** Contract named {@code approval}; the legacy attributes name an id and version no definition has. */
    @WorkflowContract("approval")
    interface ApprovalContract {

        @WorkflowStart
        Future<WorkflowInstanceId> start(ApprovalStart payload);

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, ApprovalSignal payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("escalate")
        Future<Void> escalate(WorkflowInstanceId id, ApprovalSignal payload, @SignalDedupKey String dedupKey);
    }

    /** A second contract, named {@code other}, that also declares {@code approve}. */
    @WorkflowContract("other")
    interface OtherContract {

        @WorkflowStart
        Future<WorkflowInstanceId> start(ApprovalStart payload);

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, ApprovalSignal payload, @SignalDedupKey String dedupKey);
    }

    /** A contract named {@code scope-c} that declares only {@code ok}. */
    @WorkflowContract("scope-c")
    interface ScopeContractC {

        @WorkflowSignal("ok")
        Future<Void> ok(WorkflowInstanceId id, ApprovalSignal payload, @SignalDedupKey String dedupKey);
    }

    /** A contract named {@code scope-d} that declares only {@code go}. */
    @WorkflowContract("scope-d")
    interface ScopeContractD {

        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, ApprovalSignal payload, @SignalDedupKey String dedupKey);
    }

    /**
     * Definition that waits, one after the other, for each signal it is constructed with. Each
     * concrete subclass is a distinct definition class.
     */
    abstract static class SequentialWaitDefinition<C> implements WorkflowDefinition<ApprovalState, C> {

        private final Class<C> contract;
        private final String id;
        private final long version;
        private final List<String> signals;

        SequentialWaitDefinition(Class<C> contract, String id, long version, String... signals) {
            this.contract = contract;
            this.id = id;
            this.version = version;
            this.signals = List.of(signals);
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<ApprovalState> stateType() {
            return ApprovalState.class;
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
        public void define(WorkflowBuilder<ApprovalState> wf) {
            wf.init(ApprovalStart.class, start -> new ApprovalState(start.id())).initialStep("wait-0");
            for (int i = 0; i < signals.size(); i++) {
                String next = i + 1 < signals.size() ? "wait-" + (i + 1) : "done";
                wf.waitForSignal("wait-" + i, signals.get(i), ApprovalSignal.class)
                        .onSignal((state, payload) -> state)
                        .toStepOnSignal(next)
                        .build();
            }
            wf.complete("done");
        }
    }

    static final class PurchaseApprovalV1 extends SequentialWaitDefinition<ApprovalContract> {
        PurchaseApprovalV1() {
            super(ApprovalContract.class, "purchase-approval", 1L, "approve");
        }
    }

    static final class PurchaseApprovalV2 extends SequentialWaitDefinition<ApprovalContract> {
        PurchaseApprovalV2() {
            super(ApprovalContract.class, "purchase-approval", 2L, "escalate");
        }
    }

    static final class PurchaseApprovalV3 extends SequentialWaitDefinition<ApprovalContract> {
        PurchaseApprovalV3() {
            super(ApprovalContract.class, "purchase-approval", 3L, "approve", "escalate");
        }
    }

    static final class TravelApprovalV1 extends SequentialWaitDefinition<ApprovalContract> {
        TravelApprovalV1() {
            super(ApprovalContract.class, "travel-approval", 1L, "approve", "escalate");
        }
    }

    static final class PurchaseApprovalV2OtherContract extends SequentialWaitDefinition<OtherContract> {
        PurchaseApprovalV2OtherContract() {
            super(OtherContract.class, "purchase-approval", 2L, "approve");
        }
    }

    /** Waits, one after the other, for signals that {@link ApprovalContract} does not declare. */
    static final class ManyUndeclaredWaits extends SequentialWaitDefinition<ApprovalContract> {
        ManyUndeclaredWaits(String... signals) {
            super(ApprovalContract.class, "many-undeclared", 1L, signals);
        }
    }

    /**
     * Waits for {@code approve}, then for {@code remind} with a timeout, then forks into a branch
     * waiting for {@code audit} and a branch waiting for {@code escalate}. Of the four signals only
     * {@code audit} and {@code remind} are not declared by {@link ApprovalContract}.
     */
    static final class WaitsForUndeclaredSignals implements WorkflowDefinition<ApprovalState, ApprovalContract> {

        @Override
        public Class<ApprovalContract> contract() {
            return ApprovalContract.class;
        }

        @Override
        public Class<ApprovalState> stateType() {
            return ApprovalState.class;
        }

        @Override
        public String definitionId() {
            return "undeclared-waits";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ApprovalState> wf) {
            wf.init(ApprovalStart.class, start -> new ApprovalState(start.id()))
                    .initialStep("wait-approve")
                    .waitForSignal("wait-approve", "approve", ApprovalSignal.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("wait-remind")
                    .build()
                    .waitForSignal("wait-remind", "remind", ApprovalSignal.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("fork-step")
                    .timeoutAfter(Duration.ofHours(1))
                    .onTimeout(state -> state)
                    .toStepOnTimeout("timed-out")
                    .complete("timed-out")
                    .fork("fork-step")
                    .branch("branch-audit", "wait-audit", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-escalate", "wait-escalate", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .waitForSignal("wait-audit", "audit", ApprovalSignal.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("audited")
                    .build()
                    .complete("audited")
                    .waitForSignal("wait-escalate", "escalate", ApprovalSignal.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("escalated")
                    .build()
                    .complete("escalated")
                    .join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin()
                    .complete("done");
        }
    }
}

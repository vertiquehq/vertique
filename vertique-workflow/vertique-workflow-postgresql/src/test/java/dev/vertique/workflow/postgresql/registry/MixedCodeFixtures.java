// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.PaymentCapturedDoc;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.plan.RaceSafety;
import java.time.Duration;

/**
 * Code definitions for tests that run nodes deploying different versions of one definition id: version 1 completes
 * at once, and a later version stops at a point the test chooses.
 */
final class MixedCodeFixtures {

    private MixedCodeFixtures() {}

    /** Where an instance of the definition stops. */
    enum Shape {
        /** The instance completes when it starts. */
        COMPLETES,
        /** The instance waits for the {@code inventory.reserved} signal. */
        WAITS_FOR_SIGNAL,
        /** The instance waits for a human task decision. */
        WAITS_FOR_TASK,
        /** The instance waits for a human task that expires in an hour. */
        WAITS_FOR_TASK_WITH_DUE_DATE,
        /** The instance forks; its first branch waits for a task that expires in an hour, its second for a signal. */
        WAITS_IN_BRANCHES_FOR_TASK_WITH_DUE_DATE,
        /** The instance waits for a timer that is due in an hour. */
        WAITS_FOR_TIMER,
        /** The instance forks into two branches, each waiting for its own signal. */
        WAITS_IN_BRANCHES
    }

    /** The two signal waits of the forked shape. */
    static final String FORK_STEP = "fork";

    static final String FIRST_BRANCH = "branch-a";
    static final String SECOND_BRANCH = "branch-b";

    /** Code of the id and version that stops where the shape says, under the contract. */
    static WorkflowDefinition<?, ?> code(String definitionId, long version, Shape shape, Class<?> contract) {
        return new CodeWorkflow(definitionId, version, shape, contract);
    }

    private static final class CodeWorkflow implements WorkflowDefinition<OrderDocState, Object> {

        private final String definitionId;
        private final long version;
        private final Shape shape;
        private final Class<?> contract;

        private CodeWorkflow(String definitionId, long version, Shape shape, Class<?> contract) {
            this.definitionId = definitionId;
            this.version = version;
            this.shape = shape;
            this.contract = contract;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) contract;
        }

        @Override
        public Class<OrderDocState> stateType() {
            return OrderDocState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<OrderDocState> workflow) {
            WorkflowBuilder<OrderDocState> start = workflow.init(PlaceOrderDoc.class, OrderDocState::fromPayload);
            switch (shape) {
                case COMPLETES -> start.initialStep("done").complete("done");
                case WAITS_FOR_SIGNAL ->
                    start.initialStep("await-signal")
                            .waitForSignal("await-signal", "inventory.reserved", InventoryReservedDoc.class)
                            .onSignal(OrderDocState::applyInventoryReserved)
                            .toStepOnSignal("done")
                            .build()
                            .complete("done");
                case WAITS_FOR_TASK ->
                    start.initialStep("review")
                            .task("review")
                            .assignToRole("reviewers")
                            .decision("approve", void.class)
                            .onDecision((state, decision) -> state)
                            .toStep("done")
                            .build()
                            .complete("done");
                case WAITS_FOR_TASK_WITH_DUE_DATE ->
                    start.initialStep("review")
                            .task("review")
                            .assignToRole("reviewers")
                            .dueIn(Duration.ofHours(1))
                            .onDue(state -> state)
                            .toStepOnDue("expired")
                            .decision("approve", void.class)
                            .onDecision((state, decision) -> state)
                            .toStep("done")
                            .build()
                            .complete("done")
                            .complete("expired");
                case WAITS_IN_BRANCHES_FOR_TASK_WITH_DUE_DATE ->
                    start.initialStep(FORK_STEP)
                            .fork(FORK_STEP)
                            .branch(FIRST_BRANCH, "review", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .branch(SECOND_BRANCH, "await-second", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .join("join")
                            .task("review")
                            .assignToRole("reviewers")
                            .dueIn(Duration.ofHours(1))
                            .onDue(state -> state)
                            .toStepOnDue("first-expired")
                            .decision("approve", void.class)
                            .onDecision((state, decision) -> state)
                            .toStep("first-done")
                            .build()
                            .complete("first-done")
                            .complete("first-expired")
                            .waitForSignal("await-second", "payment.captured", PaymentCapturedDoc.class)
                            .onSignal(OrderDocState::applyPaymentCaptured)
                            .toStepOnSignal("second-done")
                            .build()
                            .complete("second-done")
                            .join("join")
                            .allRequired((state, results) -> state)
                            .toStep("done")
                            .onFailure("done")
                            .endJoin()
                            .complete("done");
                case WAITS_FOR_TIMER ->
                    start.initialStep("await-timer")
                            .timer("await-timer", Duration.ofHours(1))
                            .toStep("done")
                            .complete("done");
                case WAITS_IN_BRANCHES ->
                    start.initialStep(FORK_STEP)
                            .fork(FORK_STEP)
                            .branch(FIRST_BRANCH, "await-first", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .branch(SECOND_BRANCH, "await-second", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .join("join")
                            .waitForSignal("await-first", "inventory.reserved", InventoryReservedDoc.class)
                            .onSignal(OrderDocState::applyInventoryReserved)
                            .toStepOnSignal("first-done")
                            .build()
                            .complete("first-done")
                            .waitForSignal("await-second", "payment.captured", PaymentCapturedDoc.class)
                            .onSignal(OrderDocState::applyPaymentCaptured)
                            .toStepOnSignal("second-done")
                            .build()
                            .complete("second-done")
                            .join("join")
                            .allRequired((state, results) -> state)
                            .toStep("done")
                            .onFailure("done")
                            .endJoin()
                            .complete("done");
            }
        }
    }
}

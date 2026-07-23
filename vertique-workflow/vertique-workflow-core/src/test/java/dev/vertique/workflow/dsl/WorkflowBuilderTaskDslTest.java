// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the cycle-3 task DSL extensions on {@link WorkflowBuilder}: the {@code .task(...)}
 * fluent chain including literal and state-resolver assignment variants, multiple decisions,
 * due-date branch configuration, and builder precondition enforcement.
 */
class WorkflowBuilderTaskDslTest {

    record State(String value) {}

    record Cmd(String seed) {}

    record ApprovalPayload(boolean approved) {}

    private static WorkflowBuilder<State> baseBuilder() {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        wf.init(Cmd.class, cmd -> new State(cmd.seed())).initialStep("start");
        return wf;
    }

    // --- Role assignment + single decision ---

    @Nested
    @DisplayName("assignToRole literal + single decision")
    class RoleAssignmentSingleDecision {

        @Test
        @DisplayName(
                ".task().assignToRole().decision().onDecision().toStep().build() emits HumanTaskNode with Role assignment")
        void roleAssignmentRoundTrip() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("ship")
                    .build()
                    .complete("ship");

            WorkflowPlan plan = wf.build("def", 1L);
            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("review"))
                    .findFirst()
                    .orElseThrow();

            assertThat(node.assignment()).isInstanceOf(HumanTaskNode.AssignmentSpec.Role.class);
            assertThat(((HumanTaskNode.AssignmentSpec.Role) node.assignment()).roleId())
                    .isEqualTo("compliance");
            assertThat(node.decisions()).hasSize(1);
            assertThat(node.decisions().get(0).name()).isEqualTo("approve");
            assertThat(node.decisions().get(0).nextStepId()).isEqualTo("ship");
        }
    }

    // --- User-from-state assignment ---

    @Nested
    @DisplayName("assignToUser state-resolver")
    class UserFromStateAssignment {

        @Test
        @DisplayName(".assignToUser(state -> ...) lowers to UserFromState and registers a resolver")
        void userFromStateAssignment() {
            Function<State, String> resolver = s -> "user-" + s.value();

            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToUser(resolver)
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("ship")
                    .build()
                    .complete("ship");

            WorkflowPlan plan = wf.build("def", 1L);
            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("review"))
                    .findFirst()
                    .orElseThrow();

            assertThat(node.assignment()).isInstanceOf(HumanTaskNode.AssignmentSpec.UserFromState.class);

            HumanTaskNode.AssignmentSpec.UserFromState fromState =
                    (HumanTaskNode.AssignmentSpec.UserFromState) node.assignment();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, TaskAssignment> registeredResolver =
                    callbacks.taskAssignmentResolver(fromState.resolverCallbackId());
            assertThat(registeredResolver).isNotNull();
            assertThat(registeredResolver.apply(new State("alice"))).isEqualTo(new TaskAssignment.User("user-alice"));
        }
    }

    // --- Multiple decisions ---

    @Nested
    @DisplayName("multiple decisions")
    class MultipleDecisions {

        @Test
        @DisplayName("two decisions on the same task land in the decisions list with correct step ids")
        void twoDecisionsInOrder() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", ApprovalPayload.class)
                    .onDecision((s, p) -> new State("approved"))
                    .toStep("ship")
                    .decision("reject", String.class)
                    .onDecision((s, p) -> new State("rejected"))
                    .toStep("notify")
                    .build()
                    .complete("ship")
                    .complete("notify");

            WorkflowPlan plan = wf.build("def", 1L);
            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("review"))
                    .findFirst()
                    .orElseThrow();

            assertThat(node.decisions()).hasSize(2);
            assertThat(node.decisions().get(0).name()).isEqualTo("approve");
            assertThat(node.decisions().get(0).nextStepId()).isEqualTo("ship");
            assertThat(node.decisions().get(1).name()).isEqualTo("reject");
            assertThat(node.decisions().get(1).nextStepId()).isEqualTo("notify");
        }

        @Test
        @DisplayName("decision applicators are registered and fire correctly")
        void decisionApplicatorsRegistered() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", ApprovalPayload.class)
                    .onDecision((s, p) -> new State(s.value() + ":approved"))
                    .toStep("ship")
                    .build()
                    .complete("ship");

            WorkflowPlan plan = wf.build("def", 1L);
            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("review"))
                    .findFirst()
                    .orElseThrow();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            BiFunction<State, ApprovalPayload, State> applicator =
                    callbacks.decisionApplicator(node.decisions().get(0).applicatorCallbackId());
            assertThat(applicator).isNotNull();
            State result = applicator.apply(new State("pending"), new ApprovalPayload(true));
            assertThat(result.value()).isEqualTo("pending:approved");
        }
    }

    // --- Due-date branch ---

    @Nested
    @DisplayName("due-date branch")
    class DueDateBranch {

        @Test
        @DisplayName(".dueIn(Duration).onDue(mutator).toStepOnDue(step) produces TimerSpec.After and registers mutator")
        void dueInProducesTimerSpecAfter() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("ship")
                    .dueIn(Duration.ofHours(1))
                    .onDue(s -> new State("expired"))
                    .toStepOnDue("escalate")
                    .build()
                    .complete("ship")
                    .complete("escalate");

            WorkflowPlan plan = wf.build("def", 1L);
            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("review"))
                    .findFirst()
                    .orElseThrow();

            assertThat(node.dueDate()).isInstanceOf(TimerSpec.After.class);
            assertThat(((TimerSpec.After) node.dueDate()).delay()).isEqualTo(Duration.ofHours(1));
            assertThat(node.dueNextStepId()).isEqualTo("escalate");
            assertThat(node.onDueMutatorCallbackId()).isNotNull();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, State> mutator = callbacks.stateMutator(node.onDueMutatorCallbackId());
            assertThat(mutator.apply(new State("pending"))).isEqualTo(new State("expired"));
        }
    }

    // --- Builder precondition enforcement ---

    @Nested
    @DisplayName("builder precondition enforcement")
    class BuilderPreconditions {

        @Test
        @DisplayName(".build() before any .decision() throws WorkflowDefinitionException")
        void buildWithoutDecisionRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review");
            assertThatThrownBy(
                            () -> wf.task("review").assignToRole("compliance").build())
                    .isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName(".build() without .assignToX() throws WorkflowDefinitionException")
        void buildWithoutAssignmentRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review");
            assertThatThrownBy(() -> wf.task("review")
                            .decision("approve", String.class)
                            .onDecision((s, p) -> s)
                            .toStep("ship")
                            .build())
                    .isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName(".dueIn(...) without .onDue().toStepOnDue() at .build() throws WorkflowDefinitionException")
        void dueInWithoutMutatorRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review");
            assertThatThrownBy(() -> wf.task("review")
                            .assignToRole("compliance")
                            .decision("approve", String.class)
                            .onDecision((s, p) -> s)
                            .toStep("ship")
                            .dueIn(Duration.ofHours(1))
                            // missing .onDue() and .toStepOnDue()
                            .build())
                    .isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName(".onDue() without a due spec at .build() throws WorkflowDefinitionException")
        void onDueWithoutDueSpecRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review");
            assertThatThrownBy(() -> wf.task("review")
                            .assignToRole("compliance")
                            .decision("approve", String.class)
                            .onDecision((s, p) -> s)
                            .toStep("ship")
                            .onDue(s -> s)
                            // missing dueIn/dueAt and toStepOnDue
                            .build())
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("dueIn");
        }

        @Test
        @DisplayName(".toStepOnDue() without a due spec at .build() throws WorkflowDefinitionException")
        void toStepOnDueWithoutDueSpecRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "review");
            assertThatThrownBy(() -> wf.task("review")
                            .assignToRole("compliance")
                            .decision("approve", String.class)
                            .onDecision((s, p) -> s)
                            .toStep("ship")
                            .toStepOnDue("escalate")
                            // missing dueIn/dueAt and onDue
                            .build())
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("dueIn");
        }
    }
}

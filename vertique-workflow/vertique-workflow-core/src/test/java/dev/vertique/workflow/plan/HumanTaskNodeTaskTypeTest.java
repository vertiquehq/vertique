// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.CallbackId;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the optional {@code taskType} of {@link HumanTaskNode} and of the task DSL that builds it. */
class HumanTaskNodeTaskTypeTest {

    private static final CallbackId CB = new CallbackId("test.cb");
    private static final HumanTaskNode.AssignmentSpec ROLE = new HumanTaskNode.AssignmentSpec.Role("approvers");
    private static final HumanTaskNode.TaskDecision APPROVE =
            new HumanTaskNode.TaskDecision("approve", String.class.getName(), CB, "done");

    record State(String id) {}

    record Start(String id) {}

    private static WorkflowPlan plan(Consumer<WorkflowBuilder<State>.TaskBuilder> configure) {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        WorkflowBuilder<State>.TaskBuilder task = wf.init(Start.class, cmd -> new State(cmd.id()))
                .initialStep("task")
                .task("task")
                .assignToRole("approvers")
                .decision("approve", String.class)
                .onDecision((s, p) -> s)
                .toStep("done");
        configure.accept(task);
        task.build().complete("done");
        return wf.build("test-def", 1L, State.class.getName());
    }

    private static HumanTaskNode node(WorkflowPlan plan) {
        return (HumanTaskNode) plan.nodes().stream()
                .filter(n -> n.stepId().equals("task"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the eight-argument constructor leaves the task type null")
    void legacyConstructorHasNoTaskType() {
        HumanTaskNode node = new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, false);

        assertThat(node.taskType()).isNull();
    }

    @Test
    @DisplayName("the canonical constructor keeps the task type")
    void canonicalConstructorKeepsTaskType() {
        HumanTaskNode node =
                new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, false, "approval");

        assertThat(node.taskType()).isEqualTo("approval");
    }

    @Test
    @DisplayName("a blank task type is rejected")
    void blankTaskTypeIsRejected() {
        assertThatThrownBy(() -> new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, false, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskType");
    }

    @Test
    @DisplayName("a task type of 255 code points is accepted and 256 is rejected")
    void taskTypeLengthIsBounded() {
        assertThat(new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, false, "t".repeat(255))
                        .taskType())
                .hasSize(255);
        assertThatThrownBy(() -> new HumanTaskNode(
                        "task", ROLE, List.of(APPROVE), null, null, null, null, false, "t".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("the DSL stamps the task type on the node and defaults to none")
    void dslSetsTaskType() {
        assertThat(node(plan(t -> t.taskType("approval"))).taskType()).isEqualTo("approval");
        assertThat(node(plan(t -> {})).taskType()).isNull();
    }

    @Test
    @DisplayName("the DSL rejects a null or blank task type")
    void dslRejectsBlankTaskType() {
        assertThatThrownBy(() -> plan(t -> t.taskType(" ")))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("taskType");
        assertThatThrownBy(() -> plan(t -> t.taskType(null))).isInstanceOf(WorkflowDefinitionException.class);
    }

    @Test
    @DisplayName("the DSL accepts 255 code points and rejects 256")
    void dslRejectsATaskTypeOver255CodePoints() {
        assertThat(node(plan(t -> t.taskType("t".repeat(255)))).taskType()).hasSize(255);
        assertThatThrownBy(() -> plan(t -> t.taskType("t".repeat(256))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("taskType")
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("the task type is not part of the plan hash")
    void taskTypeDoesNotChangeThePlanHash() {
        assertThat(plan(t -> t.taskType("approval")).planHash())
                .isEqualTo(plan(t -> {}).planHash())
                .isEqualTo(plan(t -> t.taskType("review")).planHash());
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code .requireVersionStability()} DSL method on
 * {@link WorkflowBuilder.TaskBuilder}:
 * <ul>
 *   <li>Calling the method produces a {@link HumanTaskNode} with
 *       {@code requireVersionStability=true}.</li>
 *   <li>Not calling the method leaves the flag {@code false} (cycle-3/4-equivalent behaviour).</li>
 * </ul>
 */
class TaskBuilderRequireVersionStabilityTest {

    record State(String id) {}

    record Cmd(String id) {}

    /**
     * Builds a minimal one-task plan and returns the {@link HumanTaskNode} for step {@code "task"}.
     *
     * @param configure consumer that may call {@code .requireVersionStability()} on the builder
     * @return the resolved {@link HumanTaskNode}
     */
    private static HumanTaskNode buildTaskNode(
            java.util.function.Consumer<WorkflowBuilder<State>.TaskBuilder> configure) {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        WorkflowBuilder<State>.TaskBuilder tb = wf.init(Cmd.class, cmd -> new State(cmd.id()))
                .initialStep("task")
                .task("task")
                .assignToRole("approvers")
                .decision("approve", String.class)
                .onDecision((s, p) -> s)
                .toStep("done");
        configure.accept(tb);
        WorkflowPlan plan = tb.build().complete("done").build("rvs-test", 1L, State.class.getName());
        return (HumanTaskNode) plan.nodes().stream()
                .filter(n -> n.stepId().equals("task"))
                .findFirst()
                .orElseThrow();
    }

    // --- Without the call ---

    @Nested
    @DisplayName("without .requireVersionStability() call")
    class WithoutCall {

        @Test
        @DisplayName("omitting .requireVersionStability() produces requireVersionStability=false")
        void omittedProducesFalse() {
            HumanTaskNode node = buildTaskNode(tb -> {
                // no requireVersionStability() call
            });

            assertThat(node.requireVersionStability())
                    .as("requireVersionStability must be false when .requireVersionStability() is not called")
                    .isFalse();
        }
    }

    // --- With the call ---

    @Nested
    @DisplayName("with .requireVersionStability() call")
    class WithCall {

        @Test
        @DisplayName(".requireVersionStability() produces requireVersionStability=true")
        void calledProducesTrue() {
            HumanTaskNode node = buildTaskNode(WorkflowBuilder.TaskBuilder::requireVersionStability);

            assertThat(node.requireVersionStability())
                    .as("requireVersionStability must be true when .requireVersionStability() is called")
                    .isTrue();
        }

        @Test
        @DisplayName(".requireVersionStability() chains correctly — can be followed by .build()")
        void chainsCorrectly() {
            // If the method doesn't return the TaskBuilder the whole chain breaks at compile time.
            // This test validates the chain compiles and still produces the correct flag value.
            WorkflowBuilder<State> wf = new WorkflowBuilder<>();
            WorkflowPlan plan = wf.init(Cmd.class, cmd -> new State(cmd.id()))
                    .initialStep("task")
                    .task("task")
                    .assignToRole("approvers")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .requireVersionStability()
                    .build()
                    .complete("done")
                    .build("chain-test", 1L, State.class.getName());

            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("task"))
                    .findFirst()
                    .orElseThrow();

            assertThat(node.requireVersionStability()).isTrue();
        }
    }

    // --- Two independent builds are independent ---

    @Nested
    @DisplayName("independence — two builders don't share state")
    class Independence {

        @Test
        @DisplayName("two independent TaskBuilders produce independent flags")
        void twoBuildersDontShareState() {
            HumanTaskNode withFlag = buildTaskNode(WorkflowBuilder.TaskBuilder::requireVersionStability);
            HumanTaskNode withoutFlag = buildTaskNode(tb -> {
                // no call
            });

            assertThat(withFlag.requireVersionStability()).isTrue();
            assertThat(withoutFlag.requireVersionStability()).isFalse();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.registry.CallbackId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code requireVersionStability} flag on {@link HumanTaskNode}:
 * <ul>
 *   <li>Default value is {@code false} in both direct construction and DSL-built nodes.</li>
 *   <li>The flag can be set to {@code true} via direct construction.</li>
 *   <li>The flag round-trips correctly through the record accessor.</li>
 * </ul>
 */
class HumanTaskNodeRequireVersionStabilityTest {

    private static final CallbackId CB = new CallbackId("test.cb");
    private static final HumanTaskNode.AssignmentSpec ROLE = new HumanTaskNode.AssignmentSpec.Role("approvers");
    private static final HumanTaskNode.TaskDecision APPROVE =
            new HumanTaskNode.TaskDecision("approve", String.class.getName(), CB, "done");

    // --- Default value ---

    @Nested
    @DisplayName("default value is false")
    class DefaultFalse {

        @Test
        @DisplayName("direct construction with requireVersionStability=false leaves the flag false")
        void directConstructionFalse() {
            HumanTaskNode node = new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, false);

            assertThat(node.requireVersionStability())
                    .as("requireVersionStability must be false when explicitly set to false")
                    .isFalse();
        }

        @Test
        @DisplayName("DSL-built node without .requireVersionStability() call has flag=false")
        void dslBuiltNodeDefaultsFalse() {
            WorkflowBuilder<SimpleState> wf = new WorkflowBuilder<>();
            wf.init(SimpleCmd.class, cmd -> new SimpleState(cmd.id()))
                    .initialStep("task")
                    .task("task")
                    .assignToRole("approvers")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done");
            WorkflowPlan plan = wf.build("test-def", 1L, SimpleState.class.getName());

            HumanTaskNode node = (HumanTaskNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("task"))
                    .findFirst()
                    .orElseThrow();

            assertThat(node.requireVersionStability())
                    .as("DSL-built node without .requireVersionStability() must default to false")
                    .isFalse();
        }
    }

    // --- Flag can be set to true ---

    @Nested
    @DisplayName("flag flips to true when set")
    class FlagFlipsTrue {

        @Test
        @DisplayName("direct construction with requireVersionStability=true stores true")
        void directConstructionTrue() {
            HumanTaskNode node = new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, true);

            assertThat(node.requireVersionStability())
                    .as("requireVersionStability must be true when explicitly set to true")
                    .isTrue();
        }
    }

    // --- Accessor round-trip ---

    @Nested
    @DisplayName("round-trip through the record accessor")
    class AccessorRoundTrip {

        @Test
        @DisplayName("requireVersionStability() returns exactly the value provided at construction (false)")
        void roundTripFalse() {
            HumanTaskNode node = new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, false);
            assertThat(node.requireVersionStability()).isFalse();
        }

        @Test
        @DisplayName("requireVersionStability() returns exactly the value provided at construction (true)")
        void roundTripTrue() {
            HumanTaskNode node = new HumanTaskNode("task", ROLE, List.of(APPROVE), null, null, null, null, true);
            assertThat(node.requireVersionStability()).isTrue();
        }
    }

    // --- Helper types ---

    record SimpleState(String id) {}

    record SimpleCmd(String id) {}
}

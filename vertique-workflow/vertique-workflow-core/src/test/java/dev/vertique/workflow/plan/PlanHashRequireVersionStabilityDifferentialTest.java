// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Differential plan-hash tests for the cycle-5 {@code requireVersionStability} flag.
 *
 * <p>These tests prove that the flag participates in the plan hash when {@code true}, and is absent
 * (zero bytes written) when {@code false}. The golden-hash continuity test in
 * {@link PlanHashContinuityTest} separately pins the exact hash bytes for the cycle-3 reference
 * plan — this class only proves differential behaviour for the new flag.
 *
 * <p>Tests:
 * <ol>
 *   <li>A plan with {@code .requireVersionStability()} and one without produce <em>different</em>
 *       plan hashes — confirming the flag participates when set.</li>
 *   <li>Two independent builds of the same plan (both with the flag) produce <em>identical</em>
 *       hashes — confirming the hash is deterministic.</li>
 *   <li>Two independent builds of the same plan (both without the flag) produce <em>identical</em>
 *       hashes — confirming absence is handled consistently.</li>
 * </ol>
 */
class PlanHashRequireVersionStabilityDifferentialTest {

    // --- Minimal state / command types ---

    record SimpleState(String id) {}

    record StartCmd(String id) {}

    /**
     * Builds a minimal plan with one task step, optionally with {@code requireVersionStability}.
     *
     * @param withStability whether to call {@code .requireVersionStability()} on the task builder
     * @return the built {@link WorkflowPlan}
     */
    private static WorkflowPlan buildMinimalPlan(boolean withStability) {
        WorkflowBuilder<SimpleState> wf = new WorkflowBuilder<>();
        WorkflowBuilder<SimpleState>.TaskBuilder taskBuilder = wf.init(StartCmd.class, cmd -> new SimpleState(cmd.id()))
                .initialStep("task")
                .task("task")
                .assignToRole("approvers")
                .decision("approve", String.class)
                .onDecision((s, p) -> s)
                .toStep("done");
        if (withStability) {
            taskBuilder.requireVersionStability();
        }
        return taskBuilder.build().complete("done").build("rvs-diff-test", 1L, SimpleState.class.getName());
    }

    // --- (a) Flag changes the hash ---

    @Nested
    @DisplayName("(a) requireVersionStability flag participates in hash when true")
    class FlagParticipates {

        @Test
        @DisplayName("plan with .requireVersionStability() hashes differently from plan without it")
        void withVsWithoutFlagHashesDiffer() {
            String hashWith = buildMinimalPlan(true).planHash();
            String hashWithout = buildMinimalPlan(false).planHash();

            assertThat(hashWith)
                    .as("plan with requireVersionStability=true must hash differently from plan without it")
                    .isNotEqualTo(hashWithout);
        }
    }

    // --- (b) Determinism with flag ---

    @Nested
    @DisplayName("(b) determinism — two builds with flag produce equal hash")
    class DeterminismWithFlag {

        @Test
        @DisplayName("two independent builds with requireVersionStability=true produce identical plan hashes")
        void twoBuildsWithFlagAreEqual() {
            String hash1 = buildMinimalPlan(true).planHash();
            String hash2 = buildMinimalPlan(true).planHash();

            assertThat(hash1)
                    .as("two builds with requireVersionStability=true must produce equal hashes")
                    .isEqualTo(hash2);
        }
    }

    // --- (c) Determinism without flag ---

    @Nested
    @DisplayName("(c) determinism — two builds without flag produce equal hash")
    class DeterminismWithoutFlag {

        @Test
        @DisplayName("two independent builds without requireVersionStability produce identical plan hashes")
        void twoBuildsWithoutFlagAreEqual() {
            String hash1 = buildMinimalPlan(false).planHash();
            String hash2 = buildMinimalPlan(false).planHash();

            assertThat(hash1)
                    .as("two builds without requireVersionStability must produce equal hashes")
                    .isEqualTo(hash2);
        }
    }
}

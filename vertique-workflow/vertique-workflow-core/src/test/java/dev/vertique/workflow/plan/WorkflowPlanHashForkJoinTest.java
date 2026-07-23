// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.registry.CallbackId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Differential plan-hash coverage for the PRD-WF-002 fork/join nodes.
 *
 * <p>Each test mutates exactly one field of a baseline fork+join plan and asserts the resulting
 * plan hash differs from the baseline's. This is the per-field drift coverage required by
 * PRD-WF-002 §A.4.8: a plan hash MUST change when any of the following changes —
 * <ol>
 *   <li>branch declaration order,</li>
 *   <li>branch id,</li>
 *   <li>branch start step id,</li>
 *   <li>branch race-safety setting,</li>
 *   <li>join step id,</li>
 *   <li>join policy,</li>
 *   <li>branch retry policy (max attempts, initial delay, backoff),</li>
 *   <li>reducer callback id,</li>
 *   <li>join next step id, and</li>
 *   <li>join failure step id.</li>
 * </ol>
 *
 * <p>The DSL methods for fork/branch/join are introduced in a later slice; until then this test
 * builds plans by hand-constructing a {@link WorkflowPlan} record and computes the hash via the
 * package-private {@link WorkflowBuilder#computePlanHashForTesting(WorkflowPlan)} helper.
 */
class WorkflowPlanHashForkJoinTest {

    private static final String DEF_ID = "fork-join-hash-test";
    private static final long DEF_VERSION = 1L;
    private static final String STATE_TYPE = "java.lang.Object";

    private static ForkNode baselineFork() {
        return new ForkNode(
                "fork",
                List.of(
                        new BranchStart("a", "step-a", RaceSafety.NORMAL),
                        new BranchStart("b", "step-b", RaceSafety.NORMAL)),
                "join",
                BranchRetryPolicy.maxAttempts(3)
                        .initialDelay(Duration.ofSeconds(1))
                        .backoff(BranchRetryPolicy.BackoffStrategy.EXPONENTIAL)
                        .build());
    }

    private static JoinNode baselineJoin() {
        return new JoinNode(
                "join", AllRequiredJoinPolicy.INSTANCE, new CallbackId("join.reducer"), "after-join", "compensate");
    }

    private static CompleteNode after() {
        return new CompleteNode("after-join");
    }

    private static CompleteNode compensate() {
        return new CompleteNode("compensate");
    }

    private static CompleteNode stepA() {
        return new CompleteNode("step-a");
    }

    private static CompleteNode stepB() {
        return new CompleteNode("step-b");
    }

    private static String hashOf(ForkNode fork, JoinNode join) {
        WorkflowPlan plan = new WorkflowPlan(
                DEF_ID,
                DEF_VERSION,
                "",
                STATE_TYPE,
                fork.stepId(),
                List.of(fork, stepA(), stepB(), join, after(), compensate()),
                null);
        return WorkflowBuilder.computePlanHashForTesting(plan);
    }

    private static String baselineHash() {
        return hashOf(baselineFork(), baselineJoin());
    }

    @Nested
    @DisplayName("Fork field drift")
    class ForkDrift {

        @Test
        @DisplayName("changing branch declaration order changes the hash")
        void branchOrderChanges() {
            ForkNode reordered = new ForkNode(
                    "fork",
                    List.of(
                            new BranchStart("b", "step-b", RaceSafety.NORMAL),
                            new BranchStart("a", "step-a", RaceSafety.NORMAL)),
                    "join",
                    baselineFork().retryPolicy());
            assertThat(hashOf(reordered, baselineJoin())).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing a branch id changes the hash")
        void branchIdChanges() {
            ForkNode renamed = new ForkNode(
                    "fork",
                    List.of(
                            new BranchStart("a-prime", "step-a", RaceSafety.NORMAL),
                            new BranchStart("b", "step-b", RaceSafety.NORMAL)),
                    "join",
                    baselineFork().retryPolicy());
            assertThat(hashOf(renamed, baselineJoin())).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing a branch start step id changes the hash")
        void branchStartStepIdChanges() {
            ForkNode moved = new ForkNode(
                    "fork",
                    List.of(
                            new BranchStart("a", "step-a-other", RaceSafety.NORMAL),
                            new BranchStart("b", "step-b", RaceSafety.NORMAL)),
                    "join",
                    baselineFork().retryPolicy());
            // step-a-other isn't in the plan, but plan-hash doesn't validate references — only
            // hashes the declared fields. Use a node list that contains the alternate id.
            WorkflowPlan plan = new WorkflowPlan(
                    DEF_ID,
                    DEF_VERSION,
                    "",
                    STATE_TYPE,
                    "fork",
                    List.of(moved, new CompleteNode("step-a-other"), stepB(), baselineJoin(), after(), compensate()),
                    null);
            String mutatedHash = WorkflowBuilder.computePlanHashForTesting(plan);
            assertThat(mutatedHash).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing branch race-safety changes the hash")
        void raceSafetyChanges() {
            ForkNode raceSafe = new ForkNode(
                    "fork",
                    List.of(
                            new BranchStart("a", "step-a", RaceSafety.CANCEL_SAFE),
                            new BranchStart("b", "step-b", RaceSafety.NORMAL)),
                    "join",
                    baselineFork().retryPolicy());
            assertThat(hashOf(raceSafe, baselineJoin())).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing the join step id changes the hash")
        void joinStepIdChanges() {
            ForkNode otherJoin = new ForkNode(
                    "fork",
                    baselineFork().branches(),
                    "other-join",
                    baselineFork().retryPolicy());
            JoinNode otherJoinNode = new JoinNode(
                    "other-join",
                    AllRequiredJoinPolicy.INSTANCE,
                    new CallbackId("join.reducer"),
                    "after-join",
                    "compensate");
            WorkflowPlan plan = new WorkflowPlan(
                    DEF_ID,
                    DEF_VERSION,
                    "",
                    STATE_TYPE,
                    "fork",
                    List.of(otherJoin, stepA(), stepB(), otherJoinNode, after(), compensate()),
                    null);
            assertThat(WorkflowBuilder.computePlanHashForTesting(plan)).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing branch retry policy max attempts changes the hash")
        void retryMaxAttemptsChanges() {
            ForkNode mutated = new ForkNode(
                    "fork",
                    baselineFork().branches(),
                    "join",
                    BranchRetryPolicy.maxAttempts(5)
                            .initialDelay(Duration.ofSeconds(1))
                            .backoff(BranchRetryPolicy.BackoffStrategy.EXPONENTIAL)
                            .build());
            assertThat(hashOf(mutated, baselineJoin())).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing branch retry initial delay changes the hash")
        void retryInitialDelayChanges() {
            ForkNode mutated = new ForkNode(
                    "fork",
                    baselineFork().branches(),
                    "join",
                    BranchRetryPolicy.maxAttempts(3)
                            .initialDelay(Duration.ofSeconds(7))
                            .backoff(BranchRetryPolicy.BackoffStrategy.EXPONENTIAL)
                            .build());
            assertThat(hashOf(mutated, baselineJoin())).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing branch retry backoff strategy changes the hash")
        void retryBackoffChanges() {
            ForkNode mutated = new ForkNode(
                    "fork",
                    baselineFork().branches(),
                    "join",
                    BranchRetryPolicy.maxAttempts(3)
                            .initialDelay(Duration.ofSeconds(1))
                            .backoff(BranchRetryPolicy.BackoffStrategy.FIXED)
                            .build());
            assertThat(hashOf(mutated, baselineJoin())).isNotEqualTo(baselineHash());
        }
    }

    @Nested
    @DisplayName("Join field drift")
    class JoinDrift {

        @Test
        @DisplayName("changing the join policy variant changes the hash")
        void joinPolicyChanges() {
            JoinNode firstSuccess = new JoinNode(
                    "join",
                    FirstSuccessJoinPolicy.INSTANCE,
                    new CallbackId("join.reducer"),
                    "after-join",
                    "compensate");
            assertThat(hashOf(baselineFork(), firstSuccess)).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing the reducer callback id changes the hash")
        void reducerCallbackChanges() {
            JoinNode mutated = new JoinNode(
                    "join",
                    AllRequiredJoinPolicy.INSTANCE,
                    new CallbackId("join.reducer.v2"),
                    "after-join",
                    "compensate");
            assertThat(hashOf(baselineFork(), mutated)).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing the join next step id changes the hash")
        void nextStepIdChanges() {
            JoinNode mutated = new JoinNode(
                    "join",
                    AllRequiredJoinPolicy.INSTANCE,
                    new CallbackId("join.reducer"),
                    "after-join-other",
                    "compensate");
            WorkflowPlan plan = new WorkflowPlan(
                    DEF_ID,
                    DEF_VERSION,
                    "",
                    STATE_TYPE,
                    "fork",
                    List.of(
                            baselineFork(),
                            stepA(),
                            stepB(),
                            mutated,
                            new CompleteNode("after-join-other"),
                            compensate()),
                    null);
            assertThat(WorkflowBuilder.computePlanHashForTesting(plan)).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("changing the join failure step id changes the hash")
        void failureStepIdChanges() {
            JoinNode mutated = new JoinNode(
                    "join",
                    AllRequiredJoinPolicy.INSTANCE,
                    new CallbackId("join.reducer"),
                    "after-join",
                    "compensate-other");
            WorkflowPlan plan = new WorkflowPlan(
                    DEF_ID,
                    DEF_VERSION,
                    "",
                    STATE_TYPE,
                    "fork",
                    List.of(baselineFork(), stepA(), stepB(), mutated, after(), new CompleteNode("compensate-other")),
                    null);
            assertThat(WorkflowBuilder.computePlanHashForTesting(plan)).isNotEqualTo(baselineHash());
        }

        @Test
        @DisplayName("setting failure step id from null to non-null changes the hash")
        void failureStepIdFromNullChanges() {
            JoinNode noFailure = new JoinNode(
                    "join", AllRequiredJoinPolicy.INSTANCE, new CallbackId("join.reducer"), "after-join", null);
            String hashWithoutFailure = hashOf(baselineFork(), noFailure);
            assertThat(hashWithoutFailure).isNotEqualTo(baselineHash());
        }
    }

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        @Test
        @DisplayName("two independent computations of the same fork+join plan produce identical hashes")
        void deterministic() {
            assertThat(baselineHash()).isEqualTo(baselineHash());
        }
    }
}

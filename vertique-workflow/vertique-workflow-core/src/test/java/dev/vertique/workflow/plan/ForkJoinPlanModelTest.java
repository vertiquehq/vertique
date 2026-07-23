// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.registry.CallbackId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Record-shape and invariant unit tests for the PRD-WF-002 plan-model types: {@link ForkNode},
 * {@link JoinNode}, {@link BranchStart}, {@link BranchRetryPolicy}, and the
 * {@link JoinPolicy} hierarchy.
 *
 * <p>Verifies:
 * <ul>
 *   <li>each record's compact constructor rejects null/empty inputs;</li>
 *   <li>{@code ForkNode.branches()} is unmodifiable;</li>
 *   <li>policy markers are equal by structural identity;</li>
 *   <li>{@code BranchRetryPolicy.maxAttempts(...)} fluent builder produces a record with the
 *       expected component values.</li>
 * </ul>
 */
class ForkJoinPlanModelTest {

    @Nested
    @DisplayName("BranchStart")
    class BranchStartTest {

        @Test
        @DisplayName("rejects null branchId")
        void rejectsNullBranchId() {
            assertThatThrownBy(() -> new BranchStart(null, "step", RaceSafety.NORMAL))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects empty branchId")
        void rejectsEmptyBranchId() {
            assertThatThrownBy(() -> new BranchStart("", "step", RaceSafety.NORMAL))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null raceSafety")
        void rejectsNullRaceSafety() {
            assertThatThrownBy(() -> new BranchStart("a", "step", null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("convenience factory defaults to NORMAL")
        void convenienceFactoryNormal() {
            BranchStart b = BranchStart.of("a", "step");
            assertThat(b.raceSafety()).isEqualTo(RaceSafety.NORMAL);
        }
    }

    @Nested
    @DisplayName("BranchRetryPolicy")
    class BranchRetryPolicyTest {

        @Test
        @DisplayName("rejects maxAttempts < 1")
        void rejectsZeroAttempts() {
            assertThatThrownBy(() -> new BranchRetryPolicy(0, Duration.ZERO, BranchRetryPolicy.BackoffStrategy.FIXED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative initialDelay")
        void rejectsNegativeDelay() {
            assertThatThrownBy(() ->
                            new BranchRetryPolicy(3, Duration.ofSeconds(-1), BranchRetryPolicy.BackoffStrategy.FIXED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("none() returns single-attempt policy")
        void noneIsSingleAttempt() {
            BranchRetryPolicy p = BranchRetryPolicy.none();
            assertThat(p.maxAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("fluent builder produces expected fields")
        void fluentBuilder() {
            BranchRetryPolicy p = BranchRetryPolicy.maxAttempts(4)
                    .initialDelay(Duration.ofSeconds(2))
                    .backoff(BranchRetryPolicy.BackoffStrategy.EXPONENTIAL)
                    .build();
            assertThat(p.maxAttempts()).isEqualTo(4);
            assertThat(p.initialDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.backoff()).isEqualTo(BranchRetryPolicy.BackoffStrategy.EXPONENTIAL);
        }
    }

    @Nested
    @DisplayName("ForkNode")
    class ForkNodeTest {

        private static ForkNode minimal() {
            return new ForkNode(
                    "fork",
                    List.of(BranchStart.of("a", "step-a"), BranchStart.of("b", "step-b")),
                    "join",
                    BranchRetryPolicy.none());
        }

        @Test
        @DisplayName("rejects null branches list")
        void rejectsNullBranches() {
            assertThatThrownBy(() -> new ForkNode("fork", null, "join", BranchRetryPolicy.none()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects empty stepId")
        void rejectsEmptyStepId() {
            assertThatThrownBy(() ->
                            new ForkNode("", List.of(BranchStart.of("a", "step")), "join", BranchRetryPolicy.none()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("branches() returns an unmodifiable list")
        void branchesUnmodifiable() {
            ForkNode f = minimal();
            assertThatThrownBy(() -> f.branches().add(BranchStart.of("c", "step-c")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("JoinNode")
    class JoinNodeTest {

        @Test
        @DisplayName("rejects empty failureStepId when set")
        void rejectsEmptyFailureStepId() {
            assertThatThrownBy(
                            () -> new JoinNode("join", AllRequiredJoinPolicy.INSTANCE, new CallbackId("r"), "next", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("allows null failureStepId")
        void allowsNullFailureStepId() {
            JoinNode j = new JoinNode("join", AllRequiredJoinPolicy.INSTANCE, new CallbackId("r"), "next", null);
            assertThat(j.failureStepId()).isNull();
        }
    }

    @Nested
    @DisplayName("Policy markers")
    class PolicyMarkers {

        @Test
        @DisplayName("AllRequiredJoinPolicy markers are equal")
        void allRequiredEqual() {
            assertThat(AllRequiredJoinPolicy.INSTANCE).isEqualTo(new AllRequiredJoinPolicy());
        }

        @Test
        @DisplayName("FirstSuccessJoinPolicy markers are equal")
        void firstSuccessEqual() {
            assertThat(FirstSuccessJoinPolicy.INSTANCE).isEqualTo(new FirstSuccessJoinPolicy());
        }

        @Test
        @DisplayName("FirstFailureJoinPolicy markers are equal")
        void firstFailureEqual() {
            assertThat(FirstFailureJoinPolicy.INSTANCE).isEqualTo(new FirstFailureJoinPolicy());
        }

        @Test
        @DisplayName("Different policies are not equal")
        void differentPoliciesNotEqual() {
            assertThat(AllRequiredJoinPolicy.INSTANCE).isNotEqualTo(FirstSuccessJoinPolicy.INSTANCE);
            assertThat(FirstSuccessJoinPolicy.INSTANCE).isNotEqualTo(FirstFailureJoinPolicy.INSTANCE);
        }
    }
}

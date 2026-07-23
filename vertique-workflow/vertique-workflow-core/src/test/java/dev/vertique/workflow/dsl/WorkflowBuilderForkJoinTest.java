// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.plan.AllRequiredJoinPolicy;
import dev.vertique.workflow.plan.BranchResult;
import dev.vertique.workflow.plan.BranchRetryPolicy;
import dev.vertique.workflow.plan.BranchStart;
import dev.vertique.workflow.plan.FirstFailureJoinPolicy;
import dev.vertique.workflow.plan.FirstSuccessJoinPolicy;
import dev.vertique.workflow.plan.ForkNode;
import dev.vertique.workflow.plan.JoinNode;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the PRD-WF-002 fork/join DSL on {@link WorkflowBuilder}.
 *
 * <p>Verifies that {@code wf.fork(...).branch(...).join(...)} produces a {@link ForkNode} appended
 * to the canonical {@link WorkflowPlan#nodes()} list, that {@code wf.join(...)} produces a
 * {@link JoinNode} with the selected policy, that the reducer callback is registered and
 * retrievable via the callback registry, and that policy/route invariants are enforced.
 */
class WorkflowBuilderForkJoinTest {

    record State(String value) {}

    record StartCmd() {}

    private static WorkflowBuilder<State> baseline() {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        wf.init(StartCmd.class, c -> new State("init"));
        return wf;
    }

    @Nested
    @DisplayName("Fork DSL")
    class ForkDsl {

        @Test
        @DisplayName("fork().branch().join() emits a ForkNode in canonical plan order")
        void emitsForkNode() {
            WorkflowBuilder<State> wf = baseline();
            wf.fork("fork")
                    .retry(BranchRetryPolicy.maxAttempts(3)
                            .initialDelay(Duration.ofSeconds(2))
                            .build())
                    .branch("a", "step-a")
                    .branch("b", "step-b", RaceSafety.CANCEL_SAFE)
                    .join("join")
                    .complete("step-a")
                    .complete("step-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .onFailure("compensate")
                    .endJoin()
                    .complete("done")
                    .complete("compensate");
            wf.initialStep("fork");
            WorkflowPlan plan = wf.build("fork-test", 1L, State.class.getName());

            assertThat(plan.nodes())
                    .filteredOn(ForkNode.class::isInstance)
                    .hasSize(1)
                    .first()
                    .satisfies(n -> {
                        ForkNode f = (ForkNode) n;
                        assertThat(f.stepId()).isEqualTo("fork");
                        assertThat(f.joinStepId()).isEqualTo("join");
                        assertThat(f.branches())
                                .extracting(BranchStart::branchId)
                                .containsExactly("a", "b");
                        assertThat(f.branches())
                                .extracting(BranchStart::raceSafety)
                                .containsExactly(RaceSafety.NORMAL, RaceSafety.CANCEL_SAFE);
                        assertThat(f.retryPolicy().maxAttempts()).isEqualTo(3);
                    });
        }

        @Test
        @DisplayName("fork without retry() uses BranchRetryPolicy.none()")
        void defaultRetryPolicyIsNone() {
            WorkflowBuilder<State> wf = baseline();
            wf.fork("fork")
                    .branch("a", "step-a")
                    .join("join")
                    .complete("step-a")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
            WorkflowPlan plan = wf.build("fork-test", 1L, State.class.getName());

            ForkNode f = (ForkNode) plan.nodes().stream()
                    .filter(ForkNode.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            assertThat(f.retryPolicy().maxAttempts()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Join DSL")
    class JoinDsl {

        @Test
        @DisplayName("allRequired join emits a JoinNode with AllRequiredJoinPolicy and registers the reducer")
        void allRequiredEmitsJoin() {
            WorkflowBuilder<State> wf = baseline();
            BiFunctionMarker reducer = new BiFunctionMarker();
            wf.fork("fork")
                    .branch("a", "step-a")
                    .join("join")
                    .complete("step-a")
                    .join("join")
                    .allRequired(reducer.fn())
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
            WorkflowPlan plan = wf.build("join-test", 1L, State.class.getName());

            JoinNode jn = (JoinNode) plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            assertThat(jn.policy()).isInstanceOf(AllRequiredJoinPolicy.class);
            assertThat(jn.nextStepId()).isEqualTo("done");
            assertThat(jn.failureStepId()).isNull();
            assertThat(jn.branchResultReducerCallbackId().value()).isEqualTo("join.reducer");
            // Reducer is registered in the per-definition callback registry.
            var registry = wf.buildCallbackRegistry();
            assertThat(registry.<State>branchResultReducer(jn.branchResultReducerCallbackId()))
                    .isNotNull();
        }

        @Test
        @DisplayName("firstSuccess + onFailure produces FirstSuccessJoinPolicy and a failure route")
        void firstSuccessWithFailureRoute() {
            WorkflowBuilder<State> wf = baseline();
            wf.fork("fork")
                    .branch("a", "step-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .complete("step-a")
                    .join("join")
                    .firstSuccess((s, results) -> s)
                    .toStep("done")
                    .onFailure("no-quote")
                    .endJoin()
                    .complete("done")
                    .complete("no-quote");
            wf.initialStep("fork");
            WorkflowPlan plan = wf.build("first-success-test", 1L, State.class.getName());

            JoinNode jn = (JoinNode) plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            assertThat(jn.policy()).isInstanceOf(FirstSuccessJoinPolicy.class);
            assertThat(jn.failureStepId()).isEqualTo("no-quote");
        }

        @Test
        @DisplayName("firstFailure produces FirstFailureJoinPolicy")
        void firstFailureSelected() {
            WorkflowBuilder<State> wf = baseline();
            wf.fork("fork")
                    .branch("a", "step-a", RaceSafety.CANCEL_SAFE)
                    .join("join")
                    .complete("step-a")
                    .join("join")
                    .firstFailure((s, results) -> s)
                    .toStep("done")
                    .onFailure("aborted")
                    .endJoin()
                    .complete("done")
                    .complete("aborted");
            wf.initialStep("fork");
            WorkflowPlan plan = wf.build("first-failure-test", 1L, State.class.getName());

            JoinNode jn = (JoinNode) plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            assertThat(jn.policy()).isInstanceOf(FirstFailureJoinPolicy.class);
        }

        @Test
        @DisplayName("endJoin() without policy throws")
        void endJoinWithoutPolicyThrows() {
            WorkflowBuilder<State> wf = baseline();
            JoinScope<State> scope = wf.join("join").toStep("next");
            assertThatThrownBy(scope::endJoin)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("allRequired/firstSuccess/firstFailure");
        }

        @Test
        @DisplayName("endJoin() without toStep() throws")
        void endJoinWithoutNextStepThrows() {
            WorkflowBuilder<State> wf = baseline();
            JoinScope<State> scope = wf.join("join").allRequired((s, r) -> s);
            assertThatThrownBy(scope::endJoin)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("toStep");
        }

        @Test
        @DisplayName("setting policy twice throws")
        void doubleSelectionThrows() {
            WorkflowBuilder<State> wf = baseline();
            JoinScope<State> scope = wf.join("join").allRequired((s, r) -> s);
            assertThatThrownBy(() -> scope.firstSuccess((s, r) -> s))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already set");
        }
    }

    @Nested
    @DisplayName("BranchResult / reducer wiring")
    class BranchResultWiring {

        @Test
        @DisplayName("the registered reducer receives the BranchResult map")
        void reducerReceivesMap() {
            WorkflowBuilder<State> wf = baseline();
            wf.fork("fork")
                    .branch("a", "step-a")
                    .join("join")
                    .complete("step-a")
                    .join("join")
                    .allRequired((s, results) -> new State("merged:" + results.size()))
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
            WorkflowPlan plan = wf.build("reducer-test", 1L, State.class.getName());

            JoinNode jn = (JoinNode) plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            var registry = wf.buildCallbackRegistry();
            var reducer = registry.<State>branchResultReducer(jn.branchResultReducerCallbackId());
            State result = reducer.apply(
                    new State("init"),
                    Map.of(
                            "a",
                            new BranchResult(
                                    "a", dev.vertique.workflow.state.BranchStatus.COMPLETED, null, null, null)));
            assertThat(result.value()).isEqualTo("merged:1");
        }
    }

    /** Helper to capture a reducer reference for assertions. */
    private static final class BiFunctionMarker {
        java.util.function.BiFunction<State, Map<String, BranchResult>, State> fn() {
            return (s, results) -> s;
        }
    }
}

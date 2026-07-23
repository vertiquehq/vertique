// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.CallbackId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowPlanValidator}.
 *
 * <p>Hand-builds {@link WorkflowPlan} instances containing fork/join structures and asserts the
 * validator accepts well-formed plans and rejects each of the PRD-WF-002 invariants.
 *
 * <p>Coverage:
 * <ul>
 *   <li>well-formed ALL_REQUIRED fork/join is accepted;</li>
 *   <li>empty branch list rejected;</li>
 *   <li>branch count above {@link WorkflowPlanValidator#MAX_BRANCHES_V1} rejected;</li>
 *   <li>duplicate branch ids rejected;</li>
 *   <li>missing branch start step rejected;</li>
 *   <li>missing/mistyped joinStepId rejected;</li>
 *   <li>nested ForkNode rejected (FR-WF-PAR-008);</li>
 *   <li>FIRST_SUCCESS branch with NORMAL race-safety rejected (FR-WF-PAR-011);</li>
 *   <li>race branch with CompensationNode rejected (FR-WF-PAR-054);</li>
 *   <li>race branch with compensable ServiceDispatchNode rejected (FR-WF-PAR-054);</li>
 *   <li>race branch with non-race-safe target rejected (FR-WF-PAR-012);</li>
 *   <li>FIRST_FAILURE without failureStepId rejected;</li>
 *   <li>JoinNode with unknown nextStepId/failureStepId rejected;</li>
 *   <li>JoinNode without a matching ForkNode rejected;</li>
 *   <li>plan without fork/join is accepted unchanged.</li>
 * </ul>
 */
class WorkflowPlanValidatorTest {

    private static final RaceSafetyTargetRegistry EMPTY_REGISTRY = new DefaultRaceSafetyTargetRegistry(Set.of());

    private static WorkflowPlanValidator validator(RaceSafetyTargetRegistry r) {
        return new WorkflowPlanValidator(r);
    }

    private static WorkflowPlanValidator emptyValidator() {
        return validator(EMPTY_REGISTRY);
    }

    private static WorkflowPlan plan(String initialStepId, List<WorkflowNode> nodes) {
        return new WorkflowPlan("test-def", 1L, "", "java.lang.Object", initialStepId, nodes, null);
    }

    private static ServiceDispatchNode dispatch(String stepId, String target, String next) {
        return new ServiceDispatchNode(stepId, target, new CallbackId(stepId + ".payload"), null, next);
    }

    private static ServiceDispatchNode dispatchWithCompensation(
            String stepId, String target, String compensateStepId, String next) {
        return new ServiceDispatchNode(stepId, target, new CallbackId(stepId + ".payload"), compensateStepId, next);
    }

    private static CompleteNode complete(String stepId) {
        return new CompleteNode(stepId);
    }

    private static JoinNode allRequiredJoin(String stepId, String nextStepId, String failureStepId) {
        return new JoinNode(
                stepId, AllRequiredJoinPolicy.INSTANCE, new CallbackId(stepId + ".reducer"), nextStepId, failureStepId);
    }

    private static JoinNode firstSuccessJoin(String stepId, String nextStepId, String failureStepId) {
        return new JoinNode(
                stepId,
                FirstSuccessJoinPolicy.INSTANCE,
                new CallbackId(stepId + ".reducer"),
                nextStepId,
                failureStepId);
    }

    private static JoinNode firstFailureJoin(String stepId, String nextStepId, String failureStepId) {
        return new JoinNode(
                stepId,
                FirstFailureJoinPolicy.INSTANCE,
                new CallbackId(stepId + ".reducer"),
                nextStepId,
                failureStepId);
    }

    /** ALL_REQUIRED fork with two dispatch+complete branches. */
    private static List<WorkflowNode> baselineAllRequiredPlan() {
        ForkNode fork = new ForkNode(
                "fork",
                List.of(BranchStart.of("a", "step-a"), BranchStart.of("b", "step-b")),
                "join",
                BranchRetryPolicy.none());
        return new ArrayList<>(List.of(
                fork,
                dispatch("step-a", "svc.a", "done-a"),
                complete("done-a"),
                dispatch("step-b", "svc.b", "done-b"),
                complete("done-b"),
                allRequiredJoin("join", "after", "compensate"),
                complete("after"),
                complete("compensate")));
    }

    @Nested
    @DisplayName("Accepted plans")
    class Accepted {

        @Test
        @DisplayName("plan with no ForkNode is accepted")
        void noForkAccepted() {
            WorkflowPlan p = plan("only", List.of(complete("only")));
            assertThatCode(() -> emptyValidator().validate(p)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("baseline ALL_REQUIRED fan-out is accepted")
        void baselineAllRequiredAccepted() {
            WorkflowPlan p = plan("fork", baselineAllRequiredPlan());
            assertThatCode(() -> emptyValidator().validate(p)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FIRST_SUCCESS with race-safe branches and registered targets is accepted")
        void firstSuccessAccepted() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(
                            new BranchStart("a", "step-a", RaceSafety.IGNORE_LATE_RESULT_SAFE),
                            new BranchStart("b", "step-b", RaceSafety.CANCEL_SAFE)),
                    "join",
                    BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            dispatch("step-a", "svc.a", "done-a"),
                            complete("done-a"),
                            dispatch("step-b", "svc.b", "done-b"),
                            complete("done-b"),
                            firstSuccessJoin("join", "after", "no-quote"),
                            complete("after"),
                            complete("no-quote")));
            RaceSafetyTargetRegistry r = new DefaultRaceSafetyTargetRegistry(
                    Set.of(b -> b.register("svc.a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .register("svc.b", RaceSafety.IGNORE_LATE_RESULT_SAFE)));
            assertThatCode(() -> validator(r).validate(p)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ForkNode shape rejections")
    class ForkShape {

        @Test
        @DisplayName("ForkNode with empty branches is rejected (record constructor)")
        void emptyBranchesRejectedAtConstruction() {
            // Record itself accepts an empty list (no min-size check); validator catches it
            // when the empty list is presented in a plan.
            ForkNode fork = new ForkNode("fork", List.of(), "join", BranchRetryPolicy.none());
            WorkflowPlan p = plan("fork", List.of(fork, allRequiredJoin("join", "after", null), complete("after")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("declares no branches");
        }

        @Test
        @DisplayName("ForkNode with > MAX_BRANCHES_V1 branches is rejected")
        void tooManyBranchesRejected() {
            List<BranchStart> branches = new ArrayList<>();
            List<WorkflowNode> nodes = new ArrayList<>();
            for (int i = 0; i <= WorkflowPlanValidator.MAX_BRANCHES_V1; i++) {
                branches.add(BranchStart.of("b" + i, "step-" + i));
                nodes.add(complete("step-" + i));
            }
            ForkNode fork = new ForkNode("fork", branches, "join", BranchRetryPolicy.none());
            nodes.addAll(List.of(fork, allRequiredJoin("join", "after", null), complete("after")));
            WorkflowPlan p = plan("fork", nodes);
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("caps fork groups at");
        }

        @Test
        @DisplayName("duplicate branchId is rejected")
        void duplicateBranchIdRejected() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(BranchStart.of("a", "step-a"), BranchStart.of("a", "step-b")),
                    "join",
                    BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            complete("step-a"),
                            complete("step-b"),
                            allRequiredJoin("join", "after", null),
                            complete("after")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("duplicate branchId");
        }

        @Test
        @DisplayName("branch start step that doesn't exist is rejected")
        void unknownStartStepRejected() {
            ForkNode fork =
                    new ForkNode("fork", List.of(BranchStart.of("a", "missing")), "join", BranchRetryPolicy.none());
            WorkflowPlan p = plan("fork", List.of(fork, allRequiredJoin("join", "after", null), complete("after")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("startStepId 'missing'");
        }

        @Test
        @DisplayName("joinStepId that doesn't resolve is rejected")
        void unknownJoinStepRejected() {
            ForkNode fork = new ForkNode(
                    "fork", List.of(BranchStart.of("a", "step-a")), "missing-join", BranchRetryPolicy.none());
            WorkflowPlan p = plan("fork", List.of(fork, complete("step-a")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("unknown joinStepId");
        }

        @Test
        @DisplayName("joinStepId pointing to non-JoinNode is rejected")
        void joinStepIdNotJoinNode() {
            ForkNode fork =
                    new ForkNode("fork", List.of(BranchStart.of("a", "step-a")), "step-a", BranchRetryPolicy.none());
            WorkflowPlan p = plan("fork", List.of(fork, complete("step-a")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("does not resolve to a JoinNode");
        }
    }

    @Nested
    @DisplayName("Nested fork rejection (FR-WF-PAR-008)")
    class NestedForkRejection {

        @Test
        @DisplayName("a branch reaching another ForkNode is rejected")
        void nestedForkRejected() {
            ForkNode inner = new ForkNode(
                    "inner-fork", List.of(BranchStart.of("x", "step-x")), "inner-join", BranchRetryPolicy.none());
            ForkNode outer = new ForkNode(
                    "outer-fork", List.of(BranchStart.of("a", "inner-fork")), "outer-join", BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "outer-fork",
                    List.of(
                            outer,
                            inner,
                            complete("step-x"),
                            allRequiredJoin("inner-join", "after-inner", null),
                            complete("after-inner"),
                            allRequiredJoin("outer-join", "after-outer", null),
                            complete("after-outer")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("nested fan-out");
        }
    }

    @Nested
    @DisplayName("Race-safety rejection (FR-WF-PAR-011/012/054)")
    class RaceSafetyRejection {

        @Test
        @DisplayName("FIRST_SUCCESS with NORMAL branch is rejected")
        void firstSuccessWithNormalBranchRejected() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(new BranchStart("a", "step-a", RaceSafety.NORMAL)),
                    "join",
                    BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            dispatch("step-a", "svc.a", "done-a"),
                            complete("done-a"),
                            firstSuccessJoin("join", "after", "no-quote"),
                            complete("after"),
                            complete("no-quote")));
            RaceSafetyTargetRegistry r = new DefaultRaceSafetyTargetRegistry(
                    Set.of(b -> b.register("svc.a", RaceSafety.IGNORE_LATE_RESULT_SAFE)));
            assertThatThrownBy(() -> validator(r).validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("RaceSafety.NORMAL");
        }

        @Test
        @DisplayName("race branch containing CompensationNode is rejected")
        void compensationNodeInRaceBranchRejected() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(new BranchStart("a", "step-a", RaceSafety.CANCEL_SAFE)),
                    "join",
                    BranchRetryPolicy.none());
            CompensationNode comp =
                    new CompensationNode("comp-a", "step-a", "svc.a.compensate", new CallbackId("comp-a.payload"));
            // step-a transitions to comp-a within forward flow (artificial but exercises the rule)
            ServiceDispatchNode stepA =
                    new ServiceDispatchNode("step-a", "svc.a", new CallbackId("step-a.payload"), null, "comp-a");
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            stepA,
                            comp,
                            firstSuccessJoin("join", "after", "no-quote"),
                            complete("after"),
                            complete("no-quote")));
            RaceSafetyTargetRegistry r =
                    new DefaultRaceSafetyTargetRegistry(Set.of(b -> b.register("svc.a", RaceSafety.CANCEL_SAFE)));
            assertThatThrownBy(() -> validator(r).validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("CompensationNode");
        }

        @Test
        @DisplayName("race branch with compensable ServiceDispatchNode is rejected")
        void compensableDispatchInRaceBranchRejected() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(new BranchStart("a", "step-a", RaceSafety.CANCEL_SAFE)),
                    "join",
                    BranchRetryPolicy.none());
            CompensationNode comp =
                    new CompensationNode("comp-a", "step-a", "svc.a.compensate", new CallbackId("comp-a.payload"));
            ServiceDispatchNode stepA = dispatchWithCompensation("step-a", "svc.a", "comp-a", "done-a");
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            stepA,
                            comp,
                            complete("done-a"),
                            firstSuccessJoin("join", "after", "no-quote"),
                            complete("after"),
                            complete("no-quote")));
            RaceSafetyTargetRegistry r =
                    new DefaultRaceSafetyTargetRegistry(Set.of(b -> b.register("svc.a", RaceSafety.CANCEL_SAFE)));
            assertThatThrownBy(() -> validator(r).validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("declares a compensationStepId");
        }

        @Test
        @DisplayName("race branch dispatching to non-race-safe target is rejected")
        void nonRaceSafeTargetRejected() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(new BranchStart("a", "step-a", RaceSafety.CANCEL_SAFE)),
                    "join",
                    BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            dispatch("step-a", "svc.unsafe", "done-a"),
                            complete("done-a"),
                            firstSuccessJoin("join", "after", "no-quote"),
                            complete("after"),
                            complete("no-quote")));
            // Empty registry — svc.unsafe defaults to NORMAL.
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("svc.unsafe")
                    .hasMessageContaining("RaceSafetyTargetContributor");
        }

        @Test
        @DisplayName("FIRST_FAILURE without failureStepId is rejected")
        void firstFailureWithoutFailureRouteRejected() {
            ForkNode fork = new ForkNode(
                    "fork",
                    List.of(new BranchStart("a", "step-a", RaceSafety.CANCEL_SAFE)),
                    "join",
                    BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            dispatch("step-a", "svc.a", "done-a"),
                            complete("done-a"),
                            firstFailureJoin("join", "after", null),
                            complete("after")));
            RaceSafetyTargetRegistry r =
                    new DefaultRaceSafetyTargetRegistry(Set.of(b -> b.register("svc.a", RaceSafety.CANCEL_SAFE)));
            assertThatThrownBy(() -> validator(r).validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("FirstFailureJoinPolicy")
                    .hasMessageContaining("failureStepId is null");
        }
    }

    @Nested
    @DisplayName("JoinNode standalone shape")
    class JoinShape {

        @Test
        @DisplayName("JoinNode without matching ForkNode is rejected")
        void unmatchedJoinRejected() {
            WorkflowPlan p = plan("join", List.of(allRequiredJoin("join", "after", null), complete("after")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("no matching ForkNode");
        }

        @Test
        @DisplayName("JoinNode referencing unknown nextStepId is rejected")
        void unknownNextStepRejected() {
            ForkNode fork =
                    new ForkNode("fork", List.of(BranchStart.of("a", "step-a")), "join", BranchRetryPolicy.none());
            WorkflowPlan p =
                    plan("fork", List.of(fork, complete("step-a"), allRequiredJoin("join", "missing-next", null)));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("unknown nextStepId");
        }

        @Test
        @DisplayName("JoinNode referencing unknown failureStepId is rejected")
        void unknownFailureStepRejected() {
            ForkNode fork =
                    new ForkNode("fork", List.of(BranchStart.of("a", "step-a")), "join", BranchRetryPolicy.none());
            WorkflowPlan p = plan(
                    "fork",
                    List.of(
                            fork,
                            complete("step-a"),
                            allRequiredJoin("join", "after", "missing-failure"),
                            complete("after")));
            assertThatThrownBy(() -> emptyValidator().validate(p))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("unknown failureStepId");
        }
    }

    @Nested
    @DisplayName("Smoke")
    class Smoke {

        @Test
        @DisplayName("validator constants are stable")
        void constantsStable() {
            assertThat(WorkflowPlanValidator.MAX_BRANCHES_V1).isEqualTo(16);
        }
    }
}

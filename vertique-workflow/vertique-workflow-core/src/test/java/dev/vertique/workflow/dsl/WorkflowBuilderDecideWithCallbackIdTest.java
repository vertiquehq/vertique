// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.plan.DecisionNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the additive {@link WorkflowBuilder#decideWithCallbackId(String, Function, CallbackId)}
 * overload introduced in WF-003 Slice F.
 *
 * <p>The overload allows the document compiler to embed a stable expression/route fingerprint into
 * the callback id rather than allocating the conventional {@code "{stepId}.resolver"} id. These
 * tests verify:
 * <ul>
 *   <li>The registered decision resolver is reachable via the explicit callback id.</li>
 *   <li>The emitted {@link DecisionNode} carries the explicit callback id, not the default
 *       {@code "{stepId}.resolver"} form.</li>
 *   <li>The plan hash differs when the explicit callback id differs (since the callback id
 *       participates in {@code computePlanHash}).</li>
 *   <li>{@code null} explicit callback id is rejected.</li>
 * </ul>
 */
class WorkflowBuilderDecideWithCallbackIdTest {

    // --- Sample types ---

    record TestState(String status) {}

    record TestCmd(String id) {}

    // --- Helper ---

    /**
     * Builds a minimal plan using {@code decideWithCallbackId} and returns it.
     * The plan has: init → decide (with explicit callback id) → complete.
     *
     * @param explicitId the {@link CallbackId} to pass to {@code decideWithCallbackId}
     * @return the resulting plan
     */
    @SuppressWarnings("rawtypes")
    private static WorkflowPlan buildPlan(CallbackId explicitId) {
        WorkflowBuilder<TestState> wf = new WorkflowBuilder<>();
        Function<TestState, String> resolver = s -> "done";
        wf.init(TestCmd.class, cmd -> new TestState(cmd.id()))
                .initialStep("route")
                .decideWithCallbackId("route", resolver, explicitId)
                .complete("done");
        return wf.build("test-wf", 1L, TestState.class.getName());
    }

    // --- Tests ---

    @Nested
    @DisplayName("decideWithCallbackId — resolver lookup")
    class ResolverLookup {

        @Test
        @DisplayName("registered resolver is reachable via explicit callback id")
        void resolverReachableViaExplicitId() {
            CallbackId explicitId = new CallbackId("doc:test-wf:v1:route.routes:abc123");
            WorkflowBuilder<TestState> wf = new WorkflowBuilder<>();
            Function<TestState, String> resolver = s -> "done";
            wf.init(TestCmd.class, cmd -> new TestState(cmd.id()))
                    .initialStep("route")
                    .decideWithCallbackId("route", resolver, explicitId)
                    .complete("done");

            WorkflowCallbackRegistry registry = wf.callbackRegistry();
            @SuppressWarnings("unchecked")
            Function<TestState, String> found = registry.decisionResolver(explicitId);
            assertThat(found).isNotNull();
            assertThat(found.apply(new TestState("active"))).isEqualTo("done");
        }

        @Test
        @DisplayName("resolver is NOT registered under the default '{stepId}.resolver' key")
        void resolverNotRegisteredUnderDefaultKey() {
            CallbackId explicitId = new CallbackId("doc:test-wf:v1:route.routes:abc123");
            WorkflowBuilder<TestState> wf = new WorkflowBuilder<>();
            wf.init(TestCmd.class, cmd -> new TestState(cmd.id()))
                    .initialStep("route")
                    .decideWithCallbackId("route", s -> "done", explicitId)
                    .complete("done");

            WorkflowCallbackRegistry registry = wf.callbackRegistry();
            // The default key "route.resolver" should NOT have been registered
            CallbackId defaultId = new CallbackId("route.resolver");
            assertThatThrownBy(() -> registry.decisionResolver(defaultId)).isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("decideWithCallbackId — emitted DecisionNode")
    class EmittedNode {

        @Test
        @DisplayName("DecisionNode carries the explicit callback id")
        void decisionNodeCarriesExplicitCallbackId() {
            CallbackId explicitId = new CallbackId("doc:test-wf:v1:route.routes:deadbeef");
            WorkflowPlan plan = buildPlan(explicitId);

            WorkflowNode routeNode = plan.nodes().stream()
                    .filter(n -> n.stepId().equals("route"))
                    .findFirst()
                    .orElseThrow();
            assertThat(routeNode).isInstanceOf(DecisionNode.class);
            DecisionNode decision = (DecisionNode) routeNode;
            assertThat(decision.nextStepResolverCallbackId()).isEqualTo(explicitId);
            assertThat(decision.nextStepResolverCallbackId().value()).isEqualTo("doc:test-wf:v1:route.routes:deadbeef");
        }

        @Test
        @DisplayName("callback id value is NOT the conventional '{stepId}.resolver' form")
        void callbackIdIsNotConventionalForm() {
            CallbackId explicitId = new CallbackId("doc:test-wf:v1:route.routes:aabbccdd");
            WorkflowPlan plan = buildPlan(explicitId);

            DecisionNode decision = (DecisionNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("route"))
                    .findFirst()
                    .orElseThrow();
            assertThat(decision.nextStepResolverCallbackId().value()).doesNotEndWith(".resolver");
        }
    }

    @Nested
    @DisplayName("decideWithCallbackId — plan hash participation")
    class PlanHashParticipation {

        @Test
        @DisplayName("identical explicit callback id produces identical plan hash")
        void identicalCallbackIdProducesIdenticalHash() {
            CallbackId id = new CallbackId("doc:test-wf:v1:route.routes:fingerprint1");
            WorkflowPlan plan1 = buildPlan(id);
            WorkflowPlan plan2 = buildPlan(id);
            assertThat(plan1.planHash()).isEqualTo(plan2.planHash());
        }

        @Test
        @DisplayName("different explicit callback id produces different plan hash")
        void differentCallbackIdProducesDifferentHash() {
            WorkflowPlan planA = buildPlan(new CallbackId("doc:test-wf:v1:route.routes:fingerprint1"));
            WorkflowPlan planB = buildPlan(new CallbackId("doc:test-wf:v1:route.routes:fingerprint2"));
            assertThat(planA.planHash()).isNotEqualTo(planB.planHash());
        }

        @Test
        @DisplayName("explicit callback id hash differs from conventional decide hash")
        void explicitIdHashDiffersFromConventionalDecide() {
            // Plan built with decideWithCallbackId and a non-standard id
            CallbackId explicitId = new CallbackId("doc:test-wf:v1:route.routes:someprint");
            WorkflowPlan explicitPlan = buildPlan(explicitId);

            // Plan built with the conventional decide method
            WorkflowBuilder<TestState> wf = new WorkflowBuilder<>();
            wf.init(TestCmd.class, cmd -> new TestState(cmd.id()))
                    .initialStep("route")
                    .decide("route", s -> "done")
                    .complete("done");
            WorkflowPlan conventionalPlan = wf.build("test-wf", 1L, TestState.class.getName());

            // The plan hashes must differ because the callback id differs
            assertThat(explicitPlan.planHash()).isNotEqualTo(conventionalPlan.planHash());
        }
    }

    @Nested
    @DisplayName("decideWithCallbackId — null guard")
    class NullGuard {

        @Test
        @DisplayName("null explicitCallbackId throws NullPointerException")
        void nullExplicitCallbackIdThrows() {
            WorkflowBuilder<TestState> wf = new WorkflowBuilder<>();
            wf.init(TestCmd.class, cmd -> new TestState(cmd.id())).initialStep("route");
            assertThatThrownBy(() -> wf.decideWithCallbackId("route", s -> "done", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

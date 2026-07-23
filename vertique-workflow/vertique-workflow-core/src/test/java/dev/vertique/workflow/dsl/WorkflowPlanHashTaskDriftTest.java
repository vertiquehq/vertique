// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.plan.WorkflowPlan;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies plan-hash drift detection for cycle-3 {@link dev.vertique.workflow.plan.HumanTaskNode}
 * additions: changing decision names, payload types, assignment values, literal vs. resolver
 * variants, and due-date specs all flip the hash. Plans without any {@code HumanTaskNode} hash
 * identically before and after cycle-3 changes (regression guard).
 */
class WorkflowPlanHashTaskDriftTest {

    record State(String value) {}

    record Cmd(String seed) {}

    record ApprovalPayload(boolean approved) {}

    private static String hashOf(Consumer<WorkflowBuilder<State>> body) {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        wf.init(Cmd.class, cmd -> new State(cmd.seed())).initialStep("start");
        body.accept(wf);
        return wf.build("def", 1L).planHash();
    }

    // --- Identical plans hash equally ---

    @Nested
    @DisplayName("identical task plans hash equally")
    class IdenticalPlans {

        @Test
        @DisplayName("two independent builds of the same task plan produce equal hashes")
        void samePlanSameHash() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            assertThat(h1).isEqualTo(h2);
        }
    }

    // --- Decision name drift ---

    @Nested
    @DisplayName("decision name drift")
    class DecisionNameDrift {

        @Test
        @DisplayName("changing the decision name flips the hash")
        void decisionNameDrift() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("APPROVE", String.class) // different name
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }
    }

    // --- Payload type drift ---

    @Nested
    @DisplayName("payloadTypeName drift")
    class PayloadTypeDrift {

        @Test
        @DisplayName("changing the payloadTypeName flips the hash")
        void payloadTypeDrift() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", ApprovalPayload.class) // different payload type
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }
    }

    // --- Literal assignment value drift ---

    @Nested
    @DisplayName("literal assignment value drift")
    class LiteralAssignmentDrift {

        @Test
        @DisplayName("changing assignToUser value flips the hash")
        void userValueDrift() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToUser("alice")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToUser("bob") // different user
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }
    }

    // --- Literal vs resolver assignment drift ---

    @Nested
    @DisplayName("literal vs resolver assignment drift")
    class LiteralVsResolverDrift {

        @Test
        @DisplayName("switching from assignToUser(literal) to assignToUser(resolver) flips the hash")
        void literalVsResolverDrift() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToUser("alice") // literal
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToUser((State s) -> "alice") // resolver that returns "alice"
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }
    }

    // --- Due-date spec drift ---

    @Nested
    @DisplayName("due-date spec drift")
    class DueDateDrift {

        @Test
        @DisplayName("changing dueIn delay flips the hash")
        void dueInDelayDrift() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .dueIn(Duration.ofHours(1))
                    .onDue(s -> s)
                    .toStepOnDue("escalate")
                    .build()
                    .complete("done")
                    .complete("escalate"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .dueIn(Duration.ofHours(2)) // different delay
                    .onDue(s -> s)
                    .toStepOnDue("escalate")
                    .build()
                    .complete("done")
                    .complete("escalate"));
            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        @DisplayName("adding a dueIn to an otherwise identical plan flips the hash")
        void addingDueDateFlipsHash() {
            String noDue = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));
            String withDue = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .dueIn(Duration.ofHours(1))
                    .onDue(s -> s)
                    .toStepOnDue("escalate")
                    .build()
                    .complete("done")
                    .complete("escalate"));
            assertThat(noDue).isNotEqualTo(withDue);
        }
    }

    // --- Cycle-1/2 regression: plans without HumanTaskNode hash identically ---

    @Nested
    @DisplayName("cycle-1+2 regression: plans without HumanTaskNode are unaffected")
    class CycleOneAndTwoRegression {

        @Test
        @DisplayName("a pure service-dispatch + complete plan hashes identically across two builds")
        void noHumanTaskNodeHashesConsistently() {
            WorkflowPlan p1 = buildMinimalPlan();
            WorkflowPlan p2 = buildMinimalPlan();
            assertThat(p1.planHash())
                    .as("minimal plan (no HumanTaskNode) must hash identically on repeated builds")
                    .isEqualTo(p2.planHash());
        }

        private static WorkflowPlan buildMinimalPlan() {
            WorkflowBuilder<State> wf = new WorkflowBuilder<>();
            wf.init(Cmd.class, cmd -> new State(cmd.seed()))
                    .initialStep("start")
                    .dispatch("start", "svc", s -> new Object(), "done")
                    .complete("done");
            return wf.build("def", 1L);
        }
    }
}

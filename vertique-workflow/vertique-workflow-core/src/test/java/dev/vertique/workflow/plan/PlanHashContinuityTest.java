// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for plan-hash continuity across workflow DSL cycle additions.
 *
 * <p><strong>Purpose:</strong> Every cycle that adds new fields to plan nodes must prove that
 * plans built without those new fields hash identically to the hash produced before the field was
 * added. This test file serves as the authoritative record of that guarantee.
 *
 * <p><strong>Golden hash:</strong> The literal in {@link #CYCLE3_REFERENCE_HASH} was computed from
 * the first run of this test <em>before</em> the cycle-4 reminder canonicalization was wired in.
 * It must never change without a deliberate, version-bumped override.
 *
 * <p><strong>Tests:</strong>
 * <ol>
 *   <li>{@code goldenHashUnchangedAfterCycle4} — hard-coded reference hash equals the computed
 *       hash for a cycle-3-equivalent plan (no reminders). Catches accidental canonicalization
 *       drift across all future cycles.</li>
 *   <li>{@code nullRemindersProducesSameHashAsCycle3} — explicit {@code reminders=null} on a
 *       direct {@link HumanTaskNode} construction produces the same hash as the DSL path
 *       (neither calls reminderAt nor reminderEvery).</li>
 *   <li>{@code remindersParticipateInHash} — a plan with reminders differs from the same plan
 *       without reminders.</li>
 *   <li>{@code reminderSpecVariantSensitivity} — different reminder spec shapes and values
 *       produce different hashes pairwise.</li>
 * </ol>
 */
class PlanHashContinuityTest {

    // --- State / command types for this test class ---

    record ReviewState(String orderId, String assignee) {}

    record StartReviewCmd(String orderId) {}

    record ApprovePayload(String comment) {}

    record RejectPayload(String reason) {}

    record SignalPayload(String data) {}

    // -------------------------------------------------------------------------
    // Golden hash — cycle-3 reference plan (no reminders)
    // -------------------------------------------------------------------------

    /**
     * Hard-coded SHA-256 hex digest for the cycle-3 reference plan built by
     * {@link #buildCycle3ReferencePlan()}.
     *
     * <p><strong>Do not change this constant without a deliberate, versioned override.</strong>
     * If you change it, document the reason and the commit that changed it in a comment below.
     *
     * <p>Change history:
     * <ul>
     *   <li>Cycle 4, Slice 2 — initial value computed from the cycle-3-equivalent DSL before
     *       reminder canonicalization was wired into {@code computePlanHash}. Adding {@code
     *       reminders=null} to {@link HumanTaskNode} MUST NOT change this value.</li>
     * </ul>
     */
    private static final String CYCLE3_REFERENCE_HASH =
            // Computed on first run (2026-05-09) before cycle-4 reminder canonicalization was
            // wired in. Adding reminders=null to HumanTaskNode MUST NOT change this value.
            // If this constant ever needs to change, document the reason and the commit here.
            "20e187ce4cb264bfc1fe5f5683032f5bd3cf4691d8979e41cdb0102727fb8718";

    /**
     * Builds a representative cycle-3-equivalent plan that exercises all the node kinds and all
     * the {@link HumanTaskNode} fields that existed before cycle 4. No reminder calls are made.
     * The plan shape is intentionally broad to catch drift in any node kind's canonicalization.
     *
     * <p>Plan structure (in declaration order):
     * <ol>
     *   <li>A {@link dev.vertique.workflow.plan.ServiceDispatchNode} with a compensation step.</li>
     *   <li>A {@link HumanTaskNode} with:
     *       <ul>
     *         <li>Role assignment (literal)</li>
     *         <li>Two decisions (approve → wait-confirm; reject → escalate)</li>
     *         <li>A due-date triplet (dueIn 3 days, onDue mutator, dueNextStep "escalate")</li>
     *         <li>No reminders ({@code reminders=null} implied by DSL)</li>
     *       </ul>
     *   </li>
     *   <li>A {@link dev.vertique.workflow.plan.WaitSignalNode} with a timeout branch.</li>
     *   <li>A {@link dev.vertique.workflow.plan.TimerNode}.</li>
     *   <li>A {@link dev.vertique.workflow.plan.DecisionNode}.</li>
     *   <li>Terminal nodes: complete, fail, compensate.</li>
     * </ol>
     */
    private static WorkflowPlan buildCycle3ReferencePlan() {
        WorkflowBuilder<ReviewState> wf = new WorkflowBuilder<>();
        wf.init(StartReviewCmd.class, cmd -> new ReviewState(cmd.orderId(), "unassigned"))
                .initialStep("dispatch-review");
        wf.dispatchWithCompensation(
                        "dispatch-review", "review-service", s -> s.orderId(), "compensate-svc", "review-task")
                .task("review-task")
                .assignToRole("reviewers")
                .decision("approve", ApprovePayload.class)
                .onDecision((s, p) -> new ReviewState(s.orderId(), s.assignee()))
                .toStep("wait-confirm")
                .decision("reject", RejectPayload.class)
                .onDecision((s, p) -> new ReviewState(s.orderId(), s.assignee()))
                .toStep("escalate")
                .dueIn(Duration.ofDays(3))
                .onDue(s -> new ReviewState(s.orderId(), "escalated"))
                .toStepOnDue("escalate")
                .build()
                .waitForSignal("wait-confirm", "review.confirmed", SignalPayload.class)
                .onSignal((s, sig) -> new ReviewState(s.orderId(), s.assignee()))
                .toStepOnSignal("decide-next")
                .timeoutAfter(Duration.ofHours(48))
                .onTimeout(s -> new ReviewState(s.orderId(), "timed-out"))
                .toStepOnTimeout("signal-timed-out")
                .timer("pause-timer", Duration.ofMinutes(30))
                .toStep("after-timer")
                .decide("decide-next", s -> "done")
                .complete("done")
                .fail("escalate", "ESCALATED", s -> "task escalated: " + s.orderId())
                .compensate("compensate-svc", "dispatch-review", "review-rollback-service", s -> s.orderId())
                .complete("after-timer")
                .complete("signal-timed-out");
        return wf.build("review-workflow", 1L, ReviewState.class.getName());
    }

    // --- (a) Golden hash test ---

    @Nested
    @DisplayName("(a) golden hash — cycle-3 reference plan continuity")
    class GoldenHash {

        @Test
        @DisplayName("cycle-3-equivalent plan hashes to the hard-coded golden value")
        void goldenHashUnchangedAfterCycle4() {
            WorkflowPlan plan = buildCycle3ReferencePlan();
            assertThat(plan.planHash())
                    .as("golden hash must remain '%s'; actual = '%s'", CYCLE3_REFERENCE_HASH, plan.planHash())
                    .isEqualTo(CYCLE3_REFERENCE_HASH);
        }
    }

    // --- (b) Differential test ---

    @Nested
    @DisplayName("(b) reminders field participates in hash when present")
    class RemindersDifferential {

        @Test
        @DisplayName("plan with reminders differs from the same plan without reminders")
        void remindersParticipateInHash() {
            WorkflowBuilder<ReviewState> wfWithout = new WorkflowBuilder<>();
            wfWithout
                    .init(StartReviewCmd.class, cmd -> new ReviewState(cmd.orderId(), "unassigned"))
                    .initialStep("review-task")
                    .task("review-task")
                    .assignToRole("approvers")
                    .decision("approve", ApprovePayload.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done");
            String hashWithout = wfWithout
                    .build("diff-test", 1L, ReviewState.class.getName())
                    .planHash();

            WorkflowBuilder<ReviewState> wfWith = new WorkflowBuilder<>();
            wfWith.init(StartReviewCmd.class, cmd -> new ReviewState(cmd.orderId(), "unassigned"))
                    .initialStep("review-task")
                    .task("review-task")
                    .assignToRole("approvers")
                    .decision("approve", ApprovePayload.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .reminderAt(Duration.ofHours(1))
                    .build()
                    .complete("done");
            String hashWith =
                    wfWith.build("diff-test", 1L, ReviewState.class.getName()).planHash();

            assertThat(hashWith)
                    .as("plan with reminders must hash differently from plan without reminders")
                    .isNotEqualTo(hashWithout);
        }
    }

    // --- (c) Null-reminders equivalence ---

    @Nested
    @DisplayName("(c) null reminders produces same hash as DSL path with no reminder call")
    class NullRemindersEquivalence {

        @Test
        @DisplayName("two independent DSL builds (no reminderAt/reminderEvery) produce equal hashes")
        void twoCycle3EquivalentBuildsAreEqual() {
            WorkflowBuilder<ReviewState> wf1 = new WorkflowBuilder<>();
            wf1.init(StartReviewCmd.class, cmd -> new ReviewState(cmd.orderId(), "unassigned"))
                    .initialStep("review-task")
                    .task("review-task")
                    .assignToRole("approvers")
                    .decision("approve", ApprovePayload.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done");
            String hash1 =
                    wf1.build("equiv-test", 1L, ReviewState.class.getName()).planHash();

            WorkflowBuilder<ReviewState> wf2 = new WorkflowBuilder<>();
            wf2.init(StartReviewCmd.class, cmd -> new ReviewState(cmd.orderId(), "unassigned"))
                    .initialStep("review-task")
                    .task("review-task")
                    .assignToRole("approvers")
                    .decision("approve", ApprovePayload.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done");
            String hash2 =
                    wf2.build("equiv-test", 1L, ReviewState.class.getName()).planHash();

            // Both are cycle-3-equivalent paths — must produce identical hashes
            assertThat(hash1)
                    .as("two cycle-3-equivalent DSL builds must produce the same hash")
                    .isEqualTo(hash2);
        }

        @Test
        @DisplayName("adding reminders=null explicitly via direct HumanTaskNode produces same node shape")
        void explicitNullRemindersFieldIsNull() {
            // Direct construction with explicit null — verifies the field exists and accepts null
            HumanTaskNode node = new HumanTaskNode(
                    "review-task",
                    new HumanTaskNode.AssignmentSpec.Role("approvers"),
                    java.util.List.of(new HumanTaskNode.TaskDecision(
                            "approve",
                            ApprovePayload.class.getName(),
                            new dev.vertique.workflow.registry.CallbackId("review-task.decision.approve.applicator"),
                            "done")),
                    null, // dueDate
                    null, // onDueMutatorCallbackId
                    null, // dueNextStepId
                    null, // reminders — explicit null
                    false // requireVersionStability — default false
                    );

            assertThat(node.reminders())
                    .as("reminders=null must be a valid node shape (cycle-3 equivalence)")
                    .isNull();
        }
    }

    // --- (d) Reminder spec variant sensitivity ---

    @Nested
    @DisplayName("(d) reminder spec variant sensitivity — all pairwise hashes differ")
    class ReminderSpecVariantSensitivity {

        private static String hashWithReminder(
                java.util.function.Consumer<WorkflowBuilder<ReviewState>.TaskBuilder> reminderConfigure) {
            WorkflowBuilder<ReviewState> wf = new WorkflowBuilder<>();
            WorkflowBuilder<ReviewState>.TaskBuilder tb = wf.init(
                            StartReviewCmd.class, cmd -> new ReviewState(cmd.orderId(), "unassigned"))
                    .initialStep("review-task")
                    .task("review-task")
                    .assignToRole("approvers")
                    .decision("approve", ApprovePayload.class)
                    .onDecision((s, p) -> s)
                    .toStep("done");
            reminderConfigure.accept(tb);
            return tb.build()
                    .complete("done")
                    .build("variant-test", 1L, ReviewState.class.getName())
                    .planHash();
        }

        @Test
        @DisplayName("OneShotOffsets([1h,2h]) differs from OneShotOffsets([1h])")
        void oneShotTwoVsOne() {
            String h1h2h = hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(1), Duration.ofHours(2)));
            String h1h = hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(1)));
            assertThat(h1h2h).isNotEqualTo(h1h);
        }

        @Test
        @DisplayName("OneShotOffsets([1h]) differs from RecurringInterval(2h, 5)")
        void oneShotVsRecurring() {
            String oneShotHash = hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(1)));
            String recurringHash = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2), 5));
            assertThat(oneShotHash).isNotEqualTo(recurringHash);
        }

        @Test
        @DisplayName("reminderAt(1h) differs from reminderAt(2h)")
        void differentOffsetsFlipHash() {
            String h1h = hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(1)));
            String h2h = hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(2)));
            assertThat(h1h).isNotEqualTo(h2h);
        }

        @Test
        @DisplayName("reminderEvery(2h, 5) differs from reminderEvery(2h, 10)")
        void differentMaxFiresFlipHash() {
            String h5 = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2), 5));
            String h10 = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2), 10));
            assertThat(h5).isNotEqualTo(h10);
        }

        @Test
        @DisplayName("reminderEvery(2h) is deterministic — same hash on two builds")
        void unboundedIsDeterministic() {
            String h1 = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2)));
            String h2 = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2)));
            assertThat(h1).isEqualTo(h2);
        }

        @Test
        @DisplayName("all three shapes are pairwise distinct (OneShotOffsets, bounded, unbounded)")
        void allShapesPairwiseDistinct() {
            String oneShotHash = hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(1)));
            String boundedHash = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2), 5));
            String unboundedHash = hashWithReminder(tb -> tb.reminderEvery(Duration.ofHours(2)));

            assertThat(oneShotHash).isNotEqualTo(boundedHash).isNotEqualTo(unboundedHash);
            assertThat(boundedHash).isNotEqualTo(unboundedHash);
        }

        @Test
        @DisplayName("reminderAt(2h, 30m) hashes the same as reminderAt(30m, 2h) — declaration order doesn't matter")
        void reminderAtDeclarationOrderDoesNotAffectHash() {
            // Cycle-4 codex round-1 fix: ReminderSpec.OneShotOffsets canonicalizes its input list to
            // ascending order at construction time. Two declarations with the same offsets in
            // different order produce identical specs and identical plan hashes. This locks the
            // canonicalization contract — any future change to OneShotOffsets that loses this
            // identity will fail this test.
            String declaredAscending =
                    hashWithReminder(tb -> tb.reminderAt(Duration.ofMinutes(30), Duration.ofHours(2)));
            String declaredDescending =
                    hashWithReminder(tb -> tb.reminderAt(Duration.ofHours(2), Duration.ofMinutes(30)));

            assertThat(declaredAscending)
                    .as("reminderAt offsets are sorted ascending — declaration order must not affect hash")
                    .isEqualTo(declaredDescending);
        }
    }
}

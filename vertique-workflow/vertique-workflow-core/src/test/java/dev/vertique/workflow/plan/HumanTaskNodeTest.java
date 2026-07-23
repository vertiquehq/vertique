// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.registry.CallbackId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link HumanTaskNode} and its nested types:
 * <ul>
 *   <li>{@link HumanTaskNode.AssignmentSpec} — sealed-sum exhaustiveness over 6 permits</li>
 *   <li>{@link HumanTaskNode.TaskDecision} — compact-constructor null/blank rejection</li>
 *   <li>{@link HumanTaskNode} — empty decisions rejection, partial due-triplet rejection,
 *       all-null due-triplet acceptance, full due-triplet acceptance, immutable decisions list</li>
 * </ul>
 */
class HumanTaskNodeTest {

    private static final CallbackId CB = new CallbackId("test.cb");
    private static final HumanTaskNode.AssignmentSpec ROLE_ASSIGNMENT =
            new HumanTaskNode.AssignmentSpec.Role("compliance");
    private static final HumanTaskNode.TaskDecision APPROVE_DECISION =
            new HumanTaskNode.TaskDecision("approve", String.class.getName(), CB, "ship");

    // --- AssignmentSpec sealed exhaustiveness ---

    @Nested
    @DisplayName("AssignmentSpec sealed-sum exhaustiveness")
    class AssignmentSpecExhaustiveness {

        @Test
        @DisplayName("switch expression covers all 6 permits without default")
        void switchCoversAllPermits() {
            HumanTaskNode.AssignmentSpec user = new HumanTaskNode.AssignmentSpec.User("alice");
            HumanTaskNode.AssignmentSpec userFromState = new HumanTaskNode.AssignmentSpec.UserFromState(CB);
            HumanTaskNode.AssignmentSpec role = new HumanTaskNode.AssignmentSpec.Role("admin");
            HumanTaskNode.AssignmentSpec roleFromState = new HumanTaskNode.AssignmentSpec.RoleFromState(CB);
            HumanTaskNode.AssignmentSpec queue = new HumanTaskNode.AssignmentSpec.Queue("inbox");
            HumanTaskNode.AssignmentSpec queueFromState = new HumanTaskNode.AssignmentSpec.QueueFromState(CB);

            assertThat(label(user)).isEqualTo("user:alice");
            assertThat(label(userFromState)).isEqualTo("userFromState:" + CB.value());
            assertThat(label(role)).isEqualTo("role:admin");
            assertThat(label(roleFromState)).isEqualTo("roleFromState:" + CB.value());
            assertThat(label(queue)).isEqualTo("queue:inbox");
            assertThat(label(queueFromState)).isEqualTo("queueFromState:" + CB.value());
        }

        private static String label(HumanTaskNode.AssignmentSpec spec) {
            return switch (spec) {
                case HumanTaskNode.AssignmentSpec.User u -> "user:" + u.userId();
                case HumanTaskNode.AssignmentSpec.UserFromState u ->
                    "userFromState:" + u.resolverCallbackId().value();
                case HumanTaskNode.AssignmentSpec.Role r -> "role:" + r.roleId();
                case HumanTaskNode.AssignmentSpec.RoleFromState r ->
                    "roleFromState:" + r.resolverCallbackId().value();
                case HumanTaskNode.AssignmentSpec.Queue q -> "queue:" + q.queueName();
                case HumanTaskNode.AssignmentSpec.QueueFromState q ->
                    "queueFromState:" + q.resolverCallbackId().value();
            };
        }
    }

    // --- TaskDecision compact-constructor validation ---

    @Nested
    @DisplayName("TaskDecision compact-constructor validation")
    class TaskDecisionValidation {

        @Test
        @DisplayName("null name throws NullPointerException")
        void nullName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision(null, String.class.getName(), CB, "ship"));
        }

        @Test
        @DisplayName("blank name throws IllegalArgumentException")
        void blankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision("  ", String.class.getName(), CB, "ship"));
        }

        @Test
        @DisplayName("null payloadTypeName throws NullPointerException")
        void nullPayloadTypeName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision("approve", null, CB, "ship"));
        }

        @Test
        @DisplayName("blank payloadTypeName throws IllegalArgumentException")
        void blankPayloadTypeName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision("approve", "  ", CB, "ship"));
        }

        @Test
        @DisplayName("null applicatorCallbackId throws NullPointerException")
        void nullApplicatorCallbackId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision("approve", String.class.getName(), null, "ship"));
        }

        @Test
        @DisplayName("null nextStepId throws NullPointerException")
        void nullNextStepId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision("approve", String.class.getName(), CB, null));
        }

        @Test
        @DisplayName("blank nextStepId throws IllegalArgumentException")
        void blankNextStepId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode.TaskDecision("approve", String.class.getName(), CB, "  "));
        }
    }

    // --- HumanTaskNode constructor validation ---

    @Nested
    @DisplayName("HumanTaskNode constructor validation")
    class NodeValidation {

        @Test
        @DisplayName("empty decisions list throws IllegalArgumentException")
        void emptyDecisionsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            new HumanTaskNode("review", ROLE_ASSIGNMENT, List.of(), null, null, null, null, false));
        }

        @Test
        @DisplayName("null stepId throws NullPointerException")
        void nullStepId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HumanTaskNode(
                            null, ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, null, false));
        }

        @Test
        @DisplayName("null assignment throws NullPointerException")
        void nullAssignment() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HumanTaskNode(
                            "review", null, List.of(APPROVE_DECISION), null, null, null, null, false));
        }

        // --- partial due-date triplet rejection ---

        @Test
        @DisplayName("only dueDate set (mutator + nextStep null) throws IllegalArgumentException")
        void onlyDueDateSet() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode(
                            "review",
                            ROLE_ASSIGNMENT,
                            List.of(APPROVE_DECISION),
                            new TimerSpec.After(Duration.ofHours(1)), // dueDate set
                            null, // mutator null
                            null, // nextStep null
                            null, // reminders null
                            false)); // requireVersionStability
        }

        @Test
        @DisplayName("only onDueMutatorCallbackId set (dueDate + nextStep null) throws IllegalArgumentException")
        void onlyMutatorSet() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode(
                            "review",
                            ROLE_ASSIGNMENT,
                            List.of(APPROVE_DECISION),
                            null, // dueDate null
                            CB, // mutator set
                            null, // nextStep null
                            null, // reminders null
                            false)); // requireVersionStability
        }

        @Test
        @DisplayName("only dueNextStepId set (dueDate + mutator null) throws IllegalArgumentException")
        void onlyNextStepSet() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode(
                            "review",
                            ROLE_ASSIGNMENT,
                            List.of(APPROVE_DECISION),
                            null, // dueDate null
                            null, // mutator null
                            "escalate", // nextStep set
                            null, // reminders null
                            false)); // requireVersionStability
        }

        @Test
        @DisplayName("dueDate + mutator set but nextStep null throws IllegalArgumentException (2-of-3)")
        void twoOfThreeMissingNextStep() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HumanTaskNode(
                            "review",
                            ROLE_ASSIGNMENT,
                            List.of(APPROVE_DECISION),
                            new TimerSpec.After(Duration.ofHours(1)), // dueDate set
                            CB, // mutator set
                            null, // nextStep null
                            null, // reminders null
                            false)); // requireVersionStability
        }

        // --- all-null due-date is accepted ---

        @Test
        @DisplayName("all due-date fields null constructs successfully (no due-date)")
        void allNullDueDateAccepted() {
            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, null, false);

            assertThat(node.dueDate()).isNull();
            assertThat(node.onDueMutatorCallbackId()).isNull();
            assertThat(node.dueNextStepId()).isNull();
        }

        // --- full due-date triplet is accepted ---

        @Test
        @DisplayName("full due-date triplet constructs successfully")
        void fullDueDateAccepted() {
            TimerSpec due = new TimerSpec.After(Duration.ofHours(24));
            CallbackId mutator = new CallbackId("review.dueMutator");

            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), due, mutator, "escalate", null, false);

            assertThat(node.dueDate()).isEqualTo(due);
            assertThat(node.onDueMutatorCallbackId()).isEqualTo(mutator);
            assertThat(node.dueNextStepId()).isEqualTo("escalate");
        }

        // --- decisions() returns immutable list ---

        @Test
        @DisplayName("decisions() returns an immutable list (add throws UnsupportedOperationException)")
        void decisionsImmutable() {
            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, null, false);

            assertThatThrownBy(() -> node.decisions().add(APPROVE_DECISION))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // --- Reminders field ---

    @Nested
    @DisplayName("reminders field")
    class RemindersField {

        @Test
        @DisplayName("reminders=null is accepted (cycle-3-equivalent shape)")
        void nullRemindersAccepted() {
            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, null, false);

            assertThat(node.reminders()).isNull();
        }

        @Test
        @DisplayName("reminders=OneShotOffsets is accepted")
        void oneShotRemindersAccepted() {
            dev.vertique.workflow.plan.ReminderSpec spec = new dev.vertique.workflow.plan.ReminderSpec.OneShotOffsets(
                    List.of(Duration.ofHours(1), Duration.ofHours(24)));

            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, spec, false);

            assertThat(node.reminders()).isEqualTo(spec);
            assertThat(node.reminders()).isInstanceOf(dev.vertique.workflow.plan.ReminderSpec.OneShotOffsets.class);
        }

        @Test
        @DisplayName("reminders=RecurringInterval is accepted")
        void recurringRemindersAccepted() {
            dev.vertique.workflow.plan.ReminderSpec spec =
                    new dev.vertique.workflow.plan.ReminderSpec.RecurringInterval(Duration.ofHours(12), 5);

            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, spec, false);

            assertThat(node.reminders()).isEqualTo(spec);
            assertThat(node.reminders()).isInstanceOf(dev.vertique.workflow.plan.ReminderSpec.RecurringInterval.class);
        }

        @Test
        @DisplayName("reminders=RecurringInterval with null maxFires (unbounded) is accepted")
        void recurringUnboundedAccepted() {
            dev.vertique.workflow.plan.ReminderSpec spec =
                    new dev.vertique.workflow.plan.ReminderSpec.RecurringInterval(Duration.ofHours(4), null);

            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), null, null, null, spec, false);

            assertThat(node.reminders()).isEqualTo(spec);
        }

        @Test
        @DisplayName("reminders can coexist with a full due-date triplet")
        void remindersWithDueDateAccepted() {
            TimerSpec due = new TimerSpec.After(Duration.ofDays(3));
            CallbackId mutator = new CallbackId("review.dueMutator");
            dev.vertique.workflow.plan.ReminderSpec reminders =
                    new dev.vertique.workflow.plan.ReminderSpec.OneShotOffsets(List.of(Duration.ofHours(24)));

            HumanTaskNode node = new HumanTaskNode(
                    "review", ROLE_ASSIGNMENT, List.of(APPROVE_DECISION), due, mutator, "escalate", reminders, false);

            assertThat(node.dueDate()).isEqualTo(due);
            assertThat(node.reminders()).isEqualTo(reminders);
        }
    }
}

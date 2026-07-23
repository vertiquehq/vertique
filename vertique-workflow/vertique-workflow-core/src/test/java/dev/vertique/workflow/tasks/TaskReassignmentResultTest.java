// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two {@link TaskReassignmentResult} permits ({@link TaskReassignmentResult.Applied},
 * {@link TaskReassignmentResult.LostToTerminal}): field round-trips including {@code reassignedBy}
 * and {@code reason}, and sealed-sum exhaustiveness via a {@code switch} expression.
 */
class TaskReassignmentResultTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final WorkflowInstanceId WORKFLOW_ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000020"));
    private static final Instant NOW = Instant.parse("2026-03-15T08:00:00Z");

    // --- Applied permit ---

    @Nested
    @DisplayName("TaskReassignmentResult.Applied")
    class AppliedPermit {

        @Test
        @DisplayName("carries all fields including reassignedBy and reason")
        void allFieldsRoundTrip() {
            WorkflowActor actor = new WorkflowActor.User("manager");
            TaskAssignment oldA = new TaskAssignment.Role("junior-dev");
            TaskAssignment newA = new TaskAssignment.User("senior-dev");

            TaskReassignmentResult.Applied result = new TaskReassignmentResult.Applied(
                    TASK_ID, WORKFLOW_ID, "review", oldA, newA, actor, "out of office", NOW);

            assertThat(result.taskId()).isEqualTo(TASK_ID);
            assertThat(result.workflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(result.stepId()).isEqualTo("review");
            assertThat(result.oldAssignment()).isEqualTo(oldA);
            assertThat(result.newAssignment()).isEqualTo(newA);
            assertThat(result.reassignedBy()).isEqualTo(actor);
            assertThat(result.reason()).isEqualTo("out of office");
            assertThat(result.reassignedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("null reason is accepted")
        void nullReasonAccepted() {
            WorkflowActor actor = new WorkflowActor.Service("orchestrator");
            TaskReassignmentResult.Applied result = new TaskReassignmentResult.Applied(
                    TASK_ID,
                    WORKFLOW_ID,
                    "review",
                    new TaskAssignment.Queue("q"),
                    new TaskAssignment.User("bob"),
                    actor,
                    null, // reason is optional
                    NOW);

            assertThat(result.reason()).isNull();
            assertThat(result.reassignedBy()).isEqualTo(actor);
        }
    }

    // --- LostToTerminal permit ---

    @Nested
    @DisplayName("TaskReassignmentResult.LostToTerminal")
    class LostToTerminalPermit {

        @Test
        @DisplayName("carries the TaskTransition indicating which terminal state was reached")
        void transitionRoundTrip() {
            TaskReassignmentResult.LostToTerminal result =
                    new TaskReassignmentResult.LostToTerminal(TaskTransition.LOST_TO_COMPLETED);

            assertThat(result.transition()).isEqualTo(TaskTransition.LOST_TO_COMPLETED);
        }

        @Test
        @DisplayName("LOST_TO_CANCELLED and LOST_TO_EXPIRED are also valid transitions")
        void otherTransitions() {
            assertThat(new TaskReassignmentResult.LostToTerminal(TaskTransition.LOST_TO_CANCELLED).transition())
                    .isEqualTo(TaskTransition.LOST_TO_CANCELLED);
            assertThat(new TaskReassignmentResult.LostToTerminal(TaskTransition.LOST_TO_EXPIRED).transition())
                    .isEqualTo(TaskTransition.LOST_TO_EXPIRED);
        }
    }

    // --- Sealed-sum exhaustiveness ---

    @Nested
    @DisplayName("sealed-sum exhaustiveness")
    class SealedSumExhaustiveness {

        @Test
        @DisplayName("switch expression covers Applied and LostToTerminal without default")
        void switchCoversAllPermits() {
            WorkflowActor actor = new WorkflowActor.User("alice");
            TaskReassignmentResult applied = new TaskReassignmentResult.Applied(
                    TASK_ID,
                    WORKFLOW_ID,
                    "review",
                    new TaskAssignment.Role("r"),
                    new TaskAssignment.User("u"),
                    actor,
                    null,
                    NOW);
            TaskReassignmentResult lost = new TaskReassignmentResult.LostToTerminal(TaskTransition.LOST_TO_EXPIRED);

            assertThat(label(applied)).isEqualTo("applied");
            assertThat(label(lost)).isEqualTo("lost:LOST_TO_EXPIRED");
        }

        private static String label(TaskReassignmentResult result) {
            return switch (result) {
                case TaskReassignmentResult.Applied ignored -> "applied";
                case TaskReassignmentResult.LostToTerminal l ->
                    "lost:" + l.transition().name();
            };
        }
    }
}

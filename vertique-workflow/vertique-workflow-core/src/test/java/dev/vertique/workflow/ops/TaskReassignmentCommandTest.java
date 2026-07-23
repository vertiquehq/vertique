// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the compact-constructor validation of {@link TaskReassignmentCommand}: null/blank
 * rejection for required fields, optional {@code reason} semantics (null accepted, blank
 * normalized to null), and happy-path getter round-trips.
 */
class TaskReassignmentCommandTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final TaskAssignment NEW_ASSIGNMENT = new TaskAssignment.User("bob");
    private static final WorkflowActor ACTOR = new WorkflowActor.User("manager");

    // --- taskId validation ---

    @Nested
    @DisplayName("taskId validation")
    class TaskIdValidation {

        @Test
        @DisplayName("null taskId throws NullPointerException mentioning 'taskId'")
        void nullTaskId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskReassignmentCommand(null, NEW_ASSIGNMENT, ACTOR, "idem-1", null))
                    .withMessageContaining("taskId");
        }
    }

    // --- newAssignment validation ---

    @Nested
    @DisplayName("newAssignment validation")
    class NewAssignmentValidation {

        @Test
        @DisplayName("null newAssignment throws NullPointerException mentioning 'newAssignment'")
        void nullNewAssignment() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskReassignmentCommand(TASK_ID, null, ACTOR, "idem-1", null))
                    .withMessageContaining("newAssignment");
        }
    }

    // --- reassignedBy validation ---

    @Nested
    @DisplayName("reassignedBy validation")
    class ReassignedByValidation {

        @Test
        @DisplayName("null reassignedBy throws NullPointerException mentioning 'reassignedBy'")
        void nullReassignedBy() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, null, "idem-1", null))
                    .withMessageContaining("reassignedBy");
        }
    }

    // --- idempotencyKey validation ---

    @Nested
    @DisplayName("idempotencyKey validation")
    class IdempotencyKeyValidation {

        @Test
        @DisplayName("null idempotencyKey throws NullPointerException mentioning 'idempotencyKey'")
        void nullIdempotencyKey() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, null, null))
                    .withMessageContaining("idempotencyKey");
        }

        @Test
        @DisplayName("blank idempotencyKey throws IllegalArgumentException")
        void blankIdempotencyKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, "   ", null));
        }
    }

    // --- reason optionality ---

    @Nested
    @DisplayName("reason optionality")
    class ReasonOptionality {

        @Test
        @DisplayName("null reason is allowed")
        void nullReasonAllowed() {
            assertThatNoException()
                    .isThrownBy(() -> new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, "idem-1", null));
        }

        @Test
        @DisplayName("non-null non-blank reason is stored as-is")
        void nonBlankReasonStored() {
            TaskReassignmentCommand cmd =
                    new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, "idem-1", "out of office");

            assertThat(cmd.reason()).isEqualTo("out of office");
        }

        @Test
        @DisplayName("blank reason is normalized to null")
        void blankReasonNormalizedToNull() {
            TaskReassignmentCommand cmd = new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, "idem-1", "   ");

            assertThat(cmd.reason()).isNull();
        }

        @Test
        @DisplayName("whitespace-only reason is normalized to null")
        void whitespaceOnlyReasonNormalized() {
            TaskReassignmentCommand cmd = new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, "idem-1", "\t\n");

            assertThat(cmd.reason()).isNull();
        }
    }

    // --- happy path ---

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("all getters return the values supplied to the constructor")
        void allGettersRoundTrip() {
            TaskReassignmentCommand cmd =
                    new TaskReassignmentCommand(TASK_ID, NEW_ASSIGNMENT, ACTOR, "my-key", "vacation");

            assertThat(cmd.taskId()).isEqualTo(TASK_ID);
            assertThat(cmd.newAssignment()).isEqualTo(NEW_ASSIGNMENT);
            assertThat(cmd.reassignedBy()).isEqualTo(ACTOR);
            assertThat(cmd.idempotencyKey()).isEqualTo("my-key");
            assertThat(cmd.reason()).isEqualTo("vacation");
        }
    }
}

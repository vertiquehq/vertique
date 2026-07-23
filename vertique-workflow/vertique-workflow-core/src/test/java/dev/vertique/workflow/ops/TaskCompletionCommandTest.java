// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.actor.WorkflowActor;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the compact-constructor validation of {@link TaskCompletionCommand}: null/blank
 * rejection for required fields, nullability of {@code payload} and {@code reviewedSubjectVersion},
 * and happy-path getter round-trips.
 */
class TaskCompletionCommandTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final WorkflowActor ACTOR = new WorkflowActor.User("alice");

    // --- taskId validation ---

    @Nested
    @DisplayName("taskId validation")
    class TaskIdValidation {

        @Test
        @DisplayName("null taskId throws NullPointerException mentioning 'taskId'")
        void nullTaskId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskCompletionCommand(null, "approve", null, "idem-1", ACTOR, null))
                    .withMessageContaining("taskId");
        }
    }

    // --- decisionName validation ---

    @Nested
    @DisplayName("decisionName validation")
    class DecisionNameValidation {

        @Test
        @DisplayName("null decisionName throws NullPointerException mentioning 'decisionName'")
        void nullDecisionName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, null, null, "idem-1", ACTOR, null))
                    .withMessageContaining("decisionName");
        }

        @Test
        @DisplayName("blank decisionName throws IllegalArgumentException")
        void blankDecisionName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "   ", null, "idem-1", ACTOR, null));
        }

        @Test
        @DisplayName("empty decisionName throws IllegalArgumentException")
        void emptyDecisionName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "", null, "idem-1", ACTOR, null));
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
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, null, ACTOR, null))
                    .withMessageContaining("idempotencyKey");
        }

        @Test
        @DisplayName("blank idempotencyKey throws IllegalArgumentException")
        void blankIdempotencyKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "  ", ACTOR, null));
        }
    }

    // --- completedBy validation ---

    @Nested
    @DisplayName("completedBy validation")
    class CompletedByValidation {

        @Test
        @DisplayName("null completedBy throws NullPointerException mentioning 'completedBy'")
        void nullCompletedBy() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", null, null))
                    .withMessageContaining("completedBy");
        }
    }

    // --- payload nullability ---

    @Nested
    @DisplayName("payload nullability")
    class PayloadNullability {

        @Test
        @DisplayName("null payload is allowed at construction (engine-side check)")
        void nullPayloadAllowedAtConstruction() {
            assertThatNoException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, null));
        }

        @Test
        @DisplayName("non-null payload is also accepted")
        void nonNullPayloadAccepted() {
            assertThatNoException()
                    .isThrownBy(
                            () -> new TaskCompletionCommand(TASK_ID, "approve", "some-payload", "idem-1", ACTOR, null));
        }
    }

    // --- reviewedSubjectVersion nullability ---

    @Nested
    @DisplayName("reviewedSubjectVersion nullability")
    class ReviewedSubjectVersionNullability {

        @Test
        @DisplayName("null reviewedSubjectVersion is accepted at the API boundary")
        void nullReviewedSubjectVersionAccepted() {
            assertThatNoException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, null));
        }

        @Test
        @DisplayName("non-null reviewedSubjectVersion is also accepted")
        void nonNullReviewedSubjectVersionAccepted() {
            assertThatNoException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, "v3"));
        }
    }

    // --- happy path ---

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("all getters return the values supplied to the constructor")
        void allGettersRoundTrip() {
            Object payload = new Object();
            TaskCompletionCommand cmd =
                    new TaskCompletionCommand(TASK_ID, "approve", payload, "my-idempotency-key", ACTOR, "v3");

            assertThat(cmd.taskId()).isEqualTo(TASK_ID);
            assertThat(cmd.decisionName()).isEqualTo("approve");
            assertThat(cmd.payload()).isSameAs(payload);
            assertThat(cmd.idempotencyKey()).isEqualTo("my-idempotency-key");
            assertThat(cmd.completedBy()).isEqualTo(ACTOR);
            assertThat(cmd.reviewedSubjectVersion()).isEqualTo("v3");
        }

        @Test
        @DisplayName("reviewedSubjectVersion null round-trips correctly")
        void nullReviewedSubjectVersionRoundTrips() {
            TaskCompletionCommand cmd =
                    new TaskCompletionCommand(TASK_ID, "approve", null, "my-idempotency-key", ACTOR, null);

            assertThat(cmd.reviewedSubjectVersion()).isNull();
        }
    }
}

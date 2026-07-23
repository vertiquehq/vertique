// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import dev.vertique.workflow.actor.WorkflowActor;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code reviewedSubjectVersion} field on {@link TaskCompletionCommand}:
 * <ul>
 *   <li>{@code null} is accepted at the API boundary (engine validates per-task).</li>
 *   <li>A non-null version string round-trips through the record accessor.</li>
 *   <li>The field does not affect validation of other required fields.</li>
 * </ul>
 */
class TaskCompletionCommandReviewedSubjectVersionTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final WorkflowActor ACTOR = new WorkflowActor.User("reviewer");

    // --- Null is accepted ---

    @Nested
    @DisplayName("null reviewedSubjectVersion is accepted at the API boundary")
    class NullAccepted {

        @Test
        @DisplayName("null reviewedSubjectVersion constructs without exception")
        void nullAcceptedAtConstruction() {
            assertThatNoException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, null));
        }

        @Test
        @DisplayName("null reviewedSubjectVersion round-trips as null")
        void nullRoundTrips() {
            TaskCompletionCommand cmd = new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, null);

            assertThat(cmd.reviewedSubjectVersion())
                    .as("reviewedSubjectVersion must be null when null was supplied")
                    .isNull();
        }
    }

    // --- Non-null round-trip ---

    @Nested
    @DisplayName("non-null reviewedSubjectVersion round-trips correctly")
    class NonNullRoundTrip {

        @Test
        @DisplayName("a version string round-trips through reviewedSubjectVersion()")
        void versionStringRoundTrips() {
            String version = "abc123-rev7";
            TaskCompletionCommand cmd = new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, version);

            assertThat(cmd.reviewedSubjectVersion())
                    .as("reviewedSubjectVersion() must return the exact value supplied")
                    .isEqualTo(version);
        }

        @Test
        @DisplayName("an integer-string version round-trips correctly")
        void integerVersionRoundTrips() {
            String version = "42";
            TaskCompletionCommand cmd = new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, version);

            assertThat(cmd.reviewedSubjectVersion()).isEqualTo("42");
        }

        @Test
        @DisplayName("a UUID-format version round-trips correctly")
        void uuidVersionRoundTrips() {
            String version = UUID.randomUUID().toString();
            TaskCompletionCommand cmd = new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", ACTOR, version);

            assertThat(cmd.reviewedSubjectVersion()).isEqualTo(version);
        }
    }

    // --- Other required fields are unaffected ---

    @Nested
    @DisplayName("reviewedSubjectVersion does not interact with required field validation")
    class NoInteractionWithRequiredFields {

        @Test
        @DisplayName("other required fields are still validated when reviewedSubjectVersion is non-null")
        void requiredFieldsStillValidatedWithNonNullVersion() {
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new TaskCompletionCommand(TASK_ID, "approve", null, "idem-1", null, "v3"))
                    .withMessageContaining("completedBy");
        }
    }

    @Nested
    @DisplayName("blank reviewedSubjectVersion is rejected at the API boundary")
    class BlankRejection {

        @Test
        @DisplayName("empty string reviewedSubjectVersion throws IllegalArgumentException")
        void emptyStringRejected() {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskCompletionCommand(
                            TASK_ID,
                            "approve",
                            null,
                            "idem-1",
                            new dev.vertique.workflow.actor.WorkflowActor.User("alice"),
                            ""))
                    .withMessageContaining("reviewedSubjectVersion");
        }

        @Test
        @DisplayName("whitespace-only reviewedSubjectVersion throws IllegalArgumentException")
        void whitespaceRejected() {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskCompletionCommand(
                            TASK_ID,
                            "approve",
                            null,
                            "idem-1",
                            new dev.vertique.workflow.actor.WorkflowActor.User("alice"),
                            "   "))
                    .withMessageContaining("reviewedSubjectVersion");
        }
    }
}

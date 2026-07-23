// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link TaskRecord} record: construction with all fields populated and with optional
 * fields null; getter round-trips.
 */
class TaskRecordTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final WorkflowInstanceId WORKFLOW_ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    // --- Full population ---

    @Nested
    @DisplayName("fully populated record")
    class FullyPopulated {

        @Test
        @DisplayName("all getters return the values supplied to the constructor")
        void allGettersRoundTrip() {
            UUID dueDateTimerId = UUID.fromString("00000000-0000-0000-0000-000000000003");
            WorkflowActor completedBy = new WorkflowActor.User("alice");
            WorkflowActor cancelledBy = new WorkflowActor.System("workflow-cancelled");
            WorkflowActor reassignedBy = new WorkflowActor.Service("orchestrator");
            List<TaskDecisionDescriptor> decisions =
                    List.of(new TaskDecisionDescriptor("approve", String.class.getName(), "ship"));

            TaskRecord record = new TaskRecord(
                    TASK_ID,
                    WORKFLOW_ID,
                    "review",
                    new TaskAssignment.Role("compliance"),
                    TaskStatus.COMPLETED,
                    decisions,
                    NOW.plusSeconds(3600),
                    dueDateTimerId,
                    NOW.plusSeconds(60),
                    NOW.plusSeconds(70),
                    NOW.plusSeconds(80),
                    NOW,
                    "approve",
                    "{\"value\":\"ok\"}",
                    completedBy,
                    cancelledBy,
                    reassignedBy,
                    "WORKFLOW_CANCELLED",
                    "out of office",
                    "v3", // subjectVersionAtCreation
                    null, // branchTokenId
                    null, // forkStepId
                    null); // branchId

            assertThat(record.taskId()).isEqualTo(TASK_ID);
            assertThat(record.workflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(record.stepId()).isEqualTo("review");
            assertThat(record.assignment()).isEqualTo(new TaskAssignment.Role("compliance"));
            assertThat(record.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(record.decisions()).isEqualTo(decisions);
            assertThat(record.dueAt()).isEqualTo(NOW.plusSeconds(3600));
            assertThat(record.dueDateTimerId()).isEqualTo(dueDateTimerId);
            assertThat(record.completedAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(record.cancelledAt()).isEqualTo(NOW.plusSeconds(70));
            assertThat(record.expiredAt()).isEqualTo(NOW.plusSeconds(80));
            assertThat(record.updatedAt()).isEqualTo(NOW);
            assertThat(record.decisionName()).isEqualTo("approve");
            assertThat(record.decisionPayloadJson()).isEqualTo("{\"value\":\"ok\"}");
            assertThat(record.completedBy()).isEqualTo(completedBy);
            assertThat(record.cancelledBy()).isEqualTo(cancelledBy);
            assertThat(record.reassignedBy()).isEqualTo(reassignedBy);
            assertThat(record.cancellationReason()).isEqualTo("WORKFLOW_CANCELLED");
            assertThat(record.reassignmentReason()).isEqualTo("out of office");
        }
    }

    // --- Minimal / optional-null population ---

    @Nested
    @DisplayName("optional fields null")
    class OptionalFieldsNull {

        @Test
        @DisplayName("OPEN task with no due-date and no audit fields accepts all-null optionals")
        void openTaskNoOptionals() {
            List<TaskDecisionDescriptor> decisions =
                    List.of(new TaskDecisionDescriptor("approve", String.class.getName(), "ship"));

            TaskRecord record = new TaskRecord(
                    TASK_ID,
                    WORKFLOW_ID,
                    "review",
                    new TaskAssignment.User("bob"),
                    TaskStatus.OPEN,
                    decisions,
                    null, // dueAt
                    null, // dueDateTimerId
                    null, // completedAt
                    null, // cancelledAt
                    null, // expiredAt
                    NOW,
                    null, // decisionName
                    null, // decisionPayloadJson
                    null, // completedBy
                    null, // cancelledBy
                    null, // reassignedBy
                    null, // cancellationReason
                    null, // reassignmentReason
                    null, // subjectVersionAtCreation
                    null, // branchTokenId
                    null, // forkStepId
                    null); // branchId

            assertThat(record.taskId()).isEqualTo(TASK_ID);
            assertThat(record.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(record.dueAt()).isNull();
            assertThat(record.dueDateTimerId()).isNull();
            assertThat(record.completedAt()).isNull();
            assertThat(record.cancelledAt()).isNull();
            assertThat(record.expiredAt()).isNull();
            assertThat(record.decisionName()).isNull();
            assertThat(record.decisionPayloadJson()).isNull();
            assertThat(record.completedBy()).isNull();
            assertThat(record.cancelledBy()).isNull();
            assertThat(record.reassignedBy()).isNull();
            assertThat(record.cancellationReason()).isNull();
            assertThat(record.reassignmentReason()).isNull();
        }

        @Test
        @DisplayName("reassignedBy and reassignmentReason may be null independently of other fields")
        void reassignFieldsIndependentlyNull() {
            List<TaskDecisionDescriptor> decisions =
                    List.of(new TaskDecisionDescriptor("approve", String.class.getName(), "ship"));

            TaskRecord record = new TaskRecord(
                    TASK_ID,
                    WORKFLOW_ID,
                    "review",
                    new TaskAssignment.Queue("q"),
                    TaskStatus.OPEN,
                    decisions,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    null,
                    null,
                    null,
                    null,
                    null, // reassignedBy = null
                    null,
                    null, // reassignmentReason = null
                    null, // subjectVersionAtCreation
                    null, // branchTokenId
                    null, // forkStepId
                    null); // branchId

            assertThat(record.reassignedBy()).isNull();
            assertThat(record.reassignmentReason()).isNull();
        }
    }
}

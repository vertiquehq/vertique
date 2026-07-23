// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.json.Json;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TaskReassignedHistoryPayload}'s required-field validation and the
 * {@code commandCorrelationId} stitching field (PRD FR-WF-CTX-050/051, AC-5, Contract Appendix
 * C5).
 */
class TaskReassignedHistoryPayloadTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final Instant REASSIGNED_AT = Instant.parse("2026-05-08T12:00:00Z");
    private static final TaskAssignment OLD_ASSIGNMENT = new TaskAssignment.User("user-1");
    private static final TaskAssignment NEW_ASSIGNMENT = new TaskAssignment.User("user-2");
    private static final WorkflowActor REASSIGNED_BY = new WorkflowActor.User("user-3");

    @Test
    @DisplayName("null stepId throws NullPointerException")
    void nullStepId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReassignedHistoryPayload(
                        null, TASK_ID, OLD_ASSIGNMENT, NEW_ASSIGNMENT, REASSIGNED_BY, null, REASSIGNED_AT, null))
                .withMessageContaining("stepId");
    }

    @Test
    @DisplayName("valid construction succeeds and commandCorrelationId is accessible")
    void validConstruction() {
        TaskReassignedHistoryPayload payload = new TaskReassignedHistoryPayload(
                "step-1", TASK_ID, OLD_ASSIGNMENT, NEW_ASSIGNMENT, REASSIGNED_BY, "reason", REASSIGNED_AT, "corr-E");

        assertThat(payload.stepId()).isEqualTo("step-1");
        assertThat(payload.commandCorrelationId()).isEqualTo("corr-E");
    }

    @Test
    @DisplayName("commandCorrelationId is nullable and not validated")
    void nullCommandCorrelationIdAllowed() {
        TaskReassignedHistoryPayload payload = new TaskReassignedHistoryPayload(
                "step-1", TASK_ID, OLD_ASSIGNMENT, NEW_ASSIGNMENT, REASSIGNED_BY, null, REASSIGNED_AT, null);

        assertThat(payload.commandCorrelationId()).isNull();
    }

    @Test
    @DisplayName("the reassignedBy field round-trips through Json.encode/decodeValue(WorkflowActor.class)")
    void reassignedByFieldRoundTrips() {
        String encodedActor = Json.encode(REASSIGNED_BY);

        WorkflowActor decoded = Json.decodeValue(encodedActor, WorkflowActor.class);

        assertThat(decoded).isEqualTo(REASSIGNED_BY);
    }

    @Test
    @DisplayName("the full payload round-trips through Json.encode/decodeValue(TaskReassignedHistoryPayload.class)")
    void fullPayloadRoundTrips() {
        TaskReassignedHistoryPayload original = new TaskReassignedHistoryPayload(
                "step-1", TASK_ID, OLD_ASSIGNMENT, NEW_ASSIGNMENT, REASSIGNED_BY, "reason", REASSIGNED_AT, "corr-E");

        TaskReassignedHistoryPayload decoded =
                Json.decodeValue(Json.encode(original), TaskReassignedHistoryPayload.class);

        assertThat(decoded).isEqualTo(original);
    }
}

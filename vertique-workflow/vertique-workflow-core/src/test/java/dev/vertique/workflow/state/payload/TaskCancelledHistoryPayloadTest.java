// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.actor.WorkflowActor;
import io.vertx.core.json.Json;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TaskCancelledHistoryPayload}'s required-field validation and the
 * {@code commandCorrelationId} stitching field (PRD FR-WF-CTX-050/051, AC-5, Contract Appendix
 * C5).
 */
class TaskCancelledHistoryPayloadTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final Instant CANCELLED_AT = Instant.parse("2026-05-08T12:00:00Z");
    private static final WorkflowActor CANCELLED_BY = new WorkflowActor.System("workflow-cancelled");

    @Test
    @DisplayName("null stepId throws NullPointerException")
    void nullStepId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskCancelledHistoryPayload(
                        null, TASK_ID, CANCELLED_AT, "WORKFLOW_CANCELLED", CANCELLED_BY, null))
                .withMessageContaining("stepId");
    }

    @Test
    @DisplayName("valid construction succeeds and commandCorrelationId is accessible")
    void validConstruction() {
        TaskCancelledHistoryPayload payload = new TaskCancelledHistoryPayload(
                "step-1", TASK_ID, CANCELLED_AT, "WORKFLOW_CANCELLED", CANCELLED_BY, "corr-E");

        assertThat(payload.stepId()).isEqualTo("step-1");
        assertThat(payload.commandCorrelationId()).isEqualTo("corr-E");
    }

    @Test
    @DisplayName("commandCorrelationId is nullable and not validated")
    void nullCommandCorrelationIdAllowed() {
        TaskCancelledHistoryPayload payload = new TaskCancelledHistoryPayload(
                "step-1", TASK_ID, CANCELLED_AT, "WORKFLOW_CANCELLED", CANCELLED_BY, null);

        assertThat(payload.commandCorrelationId()).isNull();
    }

    @Test
    @DisplayName("a full payload round-trips through Json.encode/decodeValue, including cancelledBy")
    void fullPayloadRoundTrips() {
        TaskCancelledHistoryPayload original = new TaskCancelledHistoryPayload(
                "step-1", TASK_ID, CANCELLED_AT, "WORKFLOW_CANCELLED", CANCELLED_BY, "corr-E");

        TaskCancelledHistoryPayload decoded =
                Json.decodeValue(Json.encode(original), TaskCancelledHistoryPayload.class);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.cancelledBy()).isEqualTo(CANCELLED_BY);
    }
}

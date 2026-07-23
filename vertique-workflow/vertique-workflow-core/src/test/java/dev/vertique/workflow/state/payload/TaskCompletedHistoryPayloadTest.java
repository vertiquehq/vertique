// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.actor.WorkflowActor;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TaskCompletedHistoryPayload}'s required-field validation and the
 * {@code commandCorrelationId} stitching field (PRD FR-WF-CTX-050/051, AC-5, Contract Appendix
 * C5).
 */
class TaskCompletedHistoryPayloadTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final Instant COMPLETED_AT = Instant.parse("2026-05-08T12:00:00Z");
    private static final WorkflowActor COMPLETED_BY = new WorkflowActor.User("user-1");

    @Test
    @DisplayName("null stepId throws NullPointerException")
    void nullStepId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskCompletedHistoryPayload(
                        null, TASK_ID, "approve", COMPLETED_BY, COMPLETED_AT, null, null))
                .withMessageContaining("stepId");
    }

    @Test
    @DisplayName("valid construction succeeds and commandCorrelationId is accessible")
    void validConstruction() {
        TaskCompletedHistoryPayload payload = new TaskCompletedHistoryPayload(
                "step-1", TASK_ID, "approve", COMPLETED_BY, COMPLETED_AT, "v1", "corr-E");

        assertThat(payload.stepId()).isEqualTo("step-1");
        assertThat(payload.taskId()).isEqualTo(TASK_ID);
        assertThat(payload.commandCorrelationId()).isEqualTo("corr-E");
    }

    @Test
    @DisplayName("commandCorrelationId is nullable and not validated")
    void nullCommandCorrelationIdAllowed() {
        TaskCompletedHistoryPayload payload =
                new TaskCompletedHistoryPayload("step-1", TASK_ID, "approve", COMPLETED_BY, COMPLETED_AT, null, null);

        assertThat(payload.commandCorrelationId()).isNull();
    }

    @Test
    @DisplayName("a legacy JSON payload genuinely omits the commandCorrelationId key (additive field)")
    void taskCompletedHistoryPayload_legacyJsonFixture_omitsCommandCorrelationIdKey() {
        String legacyJson = """
                {
                  "stepId": "step-1",
                  "taskId": "%s",
                  "decisionName": "approve",
                  "completedBy": {"userId": "user-1"},
                  "completedAt": "2026-05-08T12:00:00Z",
                  "reviewedSubjectVersion": null
                }
                """.formatted(TASK_ID);

        assertThat(new JsonObject(legacyJson).containsKey("commandCorrelationId"))
                .as("the legacy fixture must genuinely omit the new field")
                .isFalse();

        TaskCompletedHistoryPayload decoded = Json.decodeValue(legacyJson, TaskCompletedHistoryPayload.class);

        assertThat(decoded.commandCorrelationId())
                .as("an old row with no commandCorrelationId key must deserialize with a null field"
                        + " (additive Jackson compatibility)")
                .isNull();
        assertThat(decoded.completedBy()).isEqualTo(new WorkflowActor.User("user-1"));
    }

    @Test
    @DisplayName("a full payload round-trips through Json.encode/decodeValue, including completedBy")
    void fullPayloadRoundTrips() {
        TaskCompletedHistoryPayload original = new TaskCompletedHistoryPayload(
                "step-1", TASK_ID, "approve", COMPLETED_BY, COMPLETED_AT, "v1", "corr-E");

        TaskCompletedHistoryPayload decoded =
                Json.decodeValue(Json.encode(original), TaskCompletedHistoryPayload.class);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.completedBy()).isEqualTo(COMPLETED_BY);
    }
}

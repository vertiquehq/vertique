// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vertique.workflow.events.WorkflowEventEnvelope;
import dev.vertique.workflow.events.WorkflowEventType;
import io.vertx.core.json.Json;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused mapping tests for {@link WorkflowEventEnvelope} JSON serialization.
 *
 * <p>All encoding and decoding goes through {@link io.vertx.core.json.Json}, which is the codec
 * used in production (the outbox relay uses Vert.x JSON to encode payloads). Using the Vert.x
 * codec here keeps tests faithful to the real wire path.
 *
 * <p>These tests verify the stable wire-contract properties of the envelope:
 * <ul>
 *   <li>{@code eventType} is serialized as a plain string, not as an enum constant.</li>
 *   <li>{@code schemaVersion=1} is always emitted (via {@code @Builder.Default}).</li>
 *   <li>An envelope carrying an unknown {@code eventType} string round-trips successfully.</li>
 *   <li>An envelope JSON with unknown top-level fields round-trips successfully
 *       ({@code @JsonIgnoreProperties(ignoreUnknown=true)}).</li>
 * </ul>
 */
class WorkflowEventEnvelopeMappingTest {

    // --- eventType as String ---

    @Test
    @DisplayName("eventType is serialized as a plain string, not an enum constant")
    void serialize_eventTypeIsString() {
        WorkflowEventEnvelope envelope = WorkflowEventEnvelope.builder()
                .eventType(WorkflowEventType.WORKFLOW_STARTED.name())
                .workflowId(UUID.randomUUID())
                .definitionId("order-fulfillment")
                .definitionVersion(1L)
                .occurredAt(Instant.now())
                .sequence(1L)
                .build();

        String json = Json.encode(envelope);

        assertThat(json).contains("\"eventType\":\"WORKFLOW_STARTED\"");
    }

    // --- schemaVersion=1 default ---

    @Test
    @DisplayName("schemaVersion=1 is emitted without explicit builder call")
    void serialize_schemaVersion_defaultsTo1() {
        // Build without calling .schemaVersion(...) — relies on @Builder.Default
        WorkflowEventEnvelope envelope = WorkflowEventEnvelope.builder()
                .eventType("WORKFLOW_COMPLETED")
                .workflowId(UUID.randomUUID())
                .definitionId("my-def")
                .definitionVersion(2L)
                .occurredAt(Instant.now())
                .sequence(3L)
                .build();

        String json = Json.encode(envelope);
        WorkflowEventEnvelope decoded = Json.decodeValue(json, WorkflowEventEnvelope.class);

        assertThat(decoded.schemaVersion()).isEqualTo(1);
    }

    // --- Unknown eventType string round-trips ---

    @Test
    @DisplayName("unknown eventType string round-trips without error — open-string consumer contract")
    void roundTrip_unknownEventType_succeeds() {
        WorkflowEventEnvelope envelope = WorkflowEventEnvelope.builder()
                .eventType("UNKNOWN_FUTURE_EVENT_TYPE")
                .workflowId(UUID.randomUUID())
                .definitionId("def-x")
                .definitionVersion(1L)
                .occurredAt(Instant.now())
                .sequence(1L)
                .build();

        String json = Json.encode(envelope);
        WorkflowEventEnvelope decoded = Json.decodeValue(json, WorkflowEventEnvelope.class);

        assertThat(decoded.eventType()).isEqualTo("UNKNOWN_FUTURE_EVENT_TYPE");
    }

    // --- Unknown top-level field ignored ---

    @Test
    @DisplayName("envelope JSON with unknown top-level field deserializes without error")
    void deserialize_unknownField_ignored() {
        String json = """
                {
                  "schemaVersion": 1,
                  "eventType": "WORKFLOW_STARTED",
                  "workflowId": "00000000-0000-0000-0000-000000000001",
                  "definitionId": "order-def",
                  "definitionVersion": 1,
                  "sequence": 1,
                  "unknownFutureField": "some-value",
                  "anotherNewField": 42
                }
                """;

        assertThatCode(() -> Json.decodeValue(json, WorkflowEventEnvelope.class))
                .doesNotThrowAnyException();
    }

    // --- Full field round-trip ---

    @Test
    @DisplayName("all envelope fields round-trip correctly")
    void roundTrip_allFields_preserved() {
        UUID workflowId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-09T10:00:00Z");

        WorkflowEventEnvelope original = WorkflowEventEnvelope.builder()
                .schemaVersion(1)
                .eventType(WorkflowEventType.TASK_CREATED.name())
                .workflowId(workflowId)
                .definitionId("order-fulfillment")
                .definitionVersion(2L)
                .businessKey("BK-9999")
                .occurredAt(now)
                .sequence(7L)
                .taskId(taskId)
                .stepId("approve-step")
                .attributes(Map.of("priority", "urgent"))
                .correlationId("corr-123")
                .causationId("cause-456")
                .build();

        String json = Json.encode(original);
        WorkflowEventEnvelope decoded = Json.decodeValue(json, WorkflowEventEnvelope.class);

        assertThat(decoded.schemaVersion()).isEqualTo(1);
        assertThat(decoded.eventType()).isEqualTo("TASK_CREATED");
        assertThat(decoded.workflowId()).isEqualTo(workflowId);
        assertThat(decoded.definitionId()).isEqualTo("order-fulfillment");
        assertThat(decoded.definitionVersion()).isEqualTo(2L);
        assertThat(decoded.businessKey()).isEqualTo("BK-9999");
        assertThat(decoded.occurredAt()).isEqualTo(now);
        assertThat(decoded.sequence()).isEqualTo(7L);
        assertThat(decoded.taskId()).isEqualTo(taskId);
        assertThat(decoded.stepId()).isEqualTo("approve-step");
        assertThat(decoded.attributes()).containsEntry("priority", "urgent");
        assertThat(decoded.correlationId()).isEqualTo("corr-123");
        assertThat(decoded.causationId()).isEqualTo("cause-456");
    }
}

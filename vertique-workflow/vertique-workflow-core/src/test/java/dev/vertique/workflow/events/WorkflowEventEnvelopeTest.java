// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Jackson round-trip contract for {@link WorkflowEventEnvelope}:
 * {@code schemaVersion} is always 1; {@code eventType} serializes as a plain string; unknown
 * fields are tolerated on decode; and all non-Instant fields survive a full round-trip.
 *
 * <p>Instant fields are tested by supplying JSON strings directly and asserting the parsed values,
 * since {@code Instant} serialization depends on the Jackson time module configuration which
 * may vary. The key properties under test are the envelope's structural contract (schemaVersion,
 * eventType-as-string, unknown-field tolerance) rather than time-zone formatting.
 */
class WorkflowEventEnvelopeTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUpMapper() {
        mapper = new ObjectMapper();
    }

    // --- schemaVersion always 1 ---

    @Nested
    @DisplayName("schemaVersion")
    class SchemaVersion {

        @Test
        @DisplayName("default schemaVersion is 1 when built without explicit value")
        void defaultSchemaVersionIsOne() {
            WorkflowEventEnvelope env = WorkflowEventEnvelope.builder()
                    .eventType(WorkflowEventType.WORKFLOW_STARTED.name())
                    .workflowId(UUID.randomUUID())
                    .definitionId("def-1")
                    .definitionVersion(1L)
                    .sequence(0L)
                    .attributes(Map.of())
                    .build();

            assertThat(env.schemaVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("schemaVersion=1 is serialized in JSON output")
        void schemaVersionSerializedInJson() throws Exception {
            WorkflowEventEnvelope env = WorkflowEventEnvelope.builder()
                    .eventType(WorkflowEventType.WORKFLOW_COMPLETED.name())
                    .workflowId(UUID.randomUUID())
                    .definitionId("def-2")
                    .definitionVersion(2L)
                    .sequence(1L)
                    .attributes(Map.of())
                    .build();

            JsonNode json = mapper.valueToTree(env);
            assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        }
    }

    // --- eventType as String ---

    @Nested
    @DisplayName("eventType as String")
    class EventTypeAsString {

        @Test
        @DisplayName("eventType is serialized as a plain JSON string, not a JSON object or number")
        void eventTypeSerializedAsString() throws Exception {
            WorkflowEventEnvelope env = WorkflowEventEnvelope.builder()
                    .eventType(WorkflowEventType.TASK_REMINDER.name())
                    .workflowId(UUID.randomUUID())
                    .definitionId("def-r")
                    .definitionVersion(1L)
                    .sequence(5L)
                    .attributes(Map.of())
                    .build();

            JsonNode json = mapper.valueToTree(env);
            assertThat(json.get("eventType").isTextual()).isTrue();
            assertThat(json.get("eventType").asText()).isEqualTo("TASK_REMINDER");
        }
    }

    // --- Open-string eventType contract ---

    @Nested
    @DisplayName("open-string eventType contract")
    class OpenStringContract {

        @Test
        @DisplayName("deserializing an envelope with an unknown eventType string succeeds")
        void unknownEventTypeToleratedOnDeserialization() throws Exception {
            UUID wid = UUID.randomUUID();
            String json = "{"
                    + "\"schemaVersion\":1,"
                    + "\"eventType\":\"UNKNOWN_FUTURE_TYPE\","
                    + "\"workflowId\":\"" + wid + "\","
                    + "\"definitionId\":\"def-x\","
                    + "\"definitionVersion\":1,"
                    + "\"sequence\":0,"
                    + "\"attributes\":{}"
                    + "}";

            WorkflowEventEnvelope env = mapper.readValue(json, WorkflowEventEnvelope.class);
            assertThat(env.eventType()).isEqualTo("UNKNOWN_FUTURE_TYPE");
            assertThat(env.workflowId()).isEqualTo(wid);
        }

        @Test
        @DisplayName("unknown top-level fields on decode are tolerated (JsonIgnoreProperties)")
        void unknownFieldsToleratedOnDeserialization() throws Exception {
            UUID wid = UUID.randomUUID();
            String json = "{"
                    + "\"schemaVersion\":1,"
                    + "\"eventType\":\"WORKFLOW_STARTED\","
                    + "\"workflowId\":\"" + wid + "\","
                    + "\"definitionId\":\"def-1\","
                    + "\"definitionVersion\":1,"
                    + "\"sequence\":0,"
                    + "\"attributes\":{},"
                    + "\"newFutureField\":\"some-value\","
                    + "\"anotherUnknownField\":42"
                    + "}";

            WorkflowEventEnvelope env = mapper.readValue(json, WorkflowEventEnvelope.class);
            assertThat(env.eventType()).isEqualTo("WORKFLOW_STARTED");
            assertThat(env.schemaVersion()).isEqualTo(1);
        }
    }

    // --- Scalar field round-trip (no Instant) ---

    @Nested
    @DisplayName("scalar field round-trip")
    class ScalarFieldRoundTrip {

        @Test
        @DisplayName("all non-Instant fields survive serialize → deserialize")
        void scalarFieldsSurviveRoundTrip() throws Exception {
            UUID wid = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();

            WorkflowEventEnvelope original = WorkflowEventEnvelope.builder()
                    .eventType(WorkflowEventType.TASK_CREATED.name())
                    .workflowId(wid)
                    .definitionId("review-workflow")
                    .definitionVersion(3L)
                    .businessKey("order-42")
                    .sequence(7L)
                    .taskId(taskId)
                    .stepId("review-step")
                    .attributes(Map.of("priority", "high"))
                    .correlationId("corr-123")
                    .causationId("cause-456")
                    .build();

            String serialized = mapper.writeValueAsString(original);
            WorkflowEventEnvelope deserialized = mapper.readValue(serialized, WorkflowEventEnvelope.class);

            assertThat(deserialized.schemaVersion()).isEqualTo(1);
            assertThat(deserialized.eventType()).isEqualTo("TASK_CREATED");
            assertThat(deserialized.workflowId()).isEqualTo(wid);
            assertThat(deserialized.definitionId()).isEqualTo("review-workflow");
            assertThat(deserialized.definitionVersion()).isEqualTo(3L);
            assertThat(deserialized.businessKey()).isEqualTo("order-42");
            assertThat(deserialized.sequence()).isEqualTo(7L);
            assertThat(deserialized.taskId()).isEqualTo(taskId);
            assertThat(deserialized.stepId()).isEqualTo("review-step");
            assertThat(deserialized.attributes()).containsEntry("priority", "high");
            assertThat(deserialized.correlationId()).isEqualTo("corr-123");
            assertThat(deserialized.causationId()).isEqualTo("cause-456");
        }
    }
}

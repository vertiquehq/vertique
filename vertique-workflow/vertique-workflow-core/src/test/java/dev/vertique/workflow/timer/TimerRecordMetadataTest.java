// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link TimerRecord#metadata()} field invariants after the durable context
 * propagation migration (FR-CTX-178):
 *
 * <ul>
 *   <li>Null metadata is normalised to {@link DurableMetadata#empty()}.
 *   <li>Non-null metadata is preserved by value equality.
 *   <li>Immutability is structural — {@link DurableMetadata} has no {@code put} method.
 * </ul>
 */
@DisplayName("TimerRecord.metadata field invariants")
class TimerRecordMetadataTest {

    private static final UUID TIMER_ID = UUID.randomUUID();
    private static final WorkflowInstanceId WORKFLOW_ID = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant FIRE_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant SCHEDULED_AT = Instant.parse("2026-05-25T12:00:00Z");

    // --- Null normalisation ---

    @Nested
    @DisplayName("null metadata normalisation")
    class NullNormalisation {

        @Test
        @DisplayName("null metadata is normalised to empty()")
        void nullMetadataNormalisedToEmpty() {
            TimerRecord record = buildRecord(null);
            assertTrue(record.metadata().isEmpty(), "null metadata must normalise to DurableMetadata.empty()");
        }
    }

    // --- Value-equality round-trip ---

    @Nested
    @DisplayName("namespace body round-trip")
    class NamespaceRoundTrip {

        @Test
        @DisplayName("single-namespace body round-trips through the record")
        void singleNamespaceBodyRoundTrips() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-1"));
            TimerRecord record = buildRecord(meta);
            assertTrue(record.metadata().has("test"), "namespace must be present");
            assertEquals(
                    "c-1",
                    record.metadata().body("test").orElseThrow().getString("corr"),
                    "body field must round-trip");
        }

        @Test
        @DisplayName("DurableMetadata instance is preserved by value through the record")
        void metadataInstancePreservedByValue() {
            DurableMetadata meta = DurableMetadata.of(
                    "test", new JsonObject().put("corr", "c-1").put("tenant", "t-1"));
            TimerRecord record = buildRecord(meta);
            assertEquals(meta, record.metadata(), "stored value must equal the supplied one");
        }

        @Test
        @DisplayName("multi-namespace metadata round-trips through the record")
        void multiNamespaceMetadataRoundTrips() {
            DurableMetadata meta = DurableMetadata.of(
                            "test", new JsonObject().put("corr", "c-1").put("tenant", "t-1"))
                    .with("loc", new JsonObject().put("locale", "fi"));
            TimerRecord record = buildRecord(meta);
            assertTrue(record.metadata().has("test"));
            assertTrue(record.metadata().has("loc"));
            assertFalse(record.metadata().has("other"));
        }
    }

    // --- Helpers ---

    /**
     * Builds a {@link TimerRecord} with a fixed set of base fields and the supplied metadata.
     *
     * @param metadata the durable metadata (may be null — will be normalised to empty)
     * @return a constructed timer record
     */
    private static TimerRecord buildRecord(DurableMetadata metadata) {
        return new TimerRecord(
                TIMER_ID,
                WORKFLOW_ID,
                "step-1",
                FIRE_AT,
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                SCHEDULED_AT,
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                metadata);
    }
}

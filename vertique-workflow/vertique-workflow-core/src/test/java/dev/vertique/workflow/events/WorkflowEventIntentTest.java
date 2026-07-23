// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowEventIntent}: non-null field validation, defensive copy of
 * {@code attributes}, and null-to-empty promotion for {@code attributes}.
 */
class WorkflowEventIntentTest {

    private static final WorkflowInstanceId WORKFLOW_ID = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-08T12:00:00Z");

    // --- Null validation ---

    @Nested
    @DisplayName("non-null field validation")
    class NullValidation {

        @Test
        @DisplayName("null eventType throws NullPointerException")
        void nullEventType() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowEventIntent(
                            null, WORKFLOW_ID, "def-1", 1L, null, null, OCCURRED_AT, 0L, null, null, null, null, null))
                    .withMessageContaining("eventType");
        }

        @Test
        @DisplayName("null workflowId throws NullPointerException")
        void nullWorkflowId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowEventIntent(
                            WorkflowEventType.WORKFLOW_STARTED,
                            null,
                            "def-1",
                            1L,
                            null,
                            null,
                            OCCURRED_AT,
                            0L,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("workflowId");
        }

        @Test
        @DisplayName("null definitionId throws NullPointerException")
        void nullDefinitionId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowEventIntent(
                            WorkflowEventType.WORKFLOW_STARTED,
                            WORKFLOW_ID,
                            null,
                            1L,
                            null,
                            null,
                            OCCURRED_AT,
                            0L,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("definitionId");
        }

        @Test
        @DisplayName("null occurredAt throws NullPointerException")
        void nullOccurredAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowEventIntent(
                            WorkflowEventType.WORKFLOW_STARTED,
                            WORKFLOW_ID,
                            "def-1",
                            1L,
                            null,
                            null,
                            null,
                            0L,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("occurredAt");
        }
    }

    // --- Defensive copy of attributes ---

    @Nested
    @DisplayName("attributes defensive copy")
    class AttributesDefensiveCopy {

        @Test
        @DisplayName("mutating the source map after construction does not affect the record")
        void mutatingSourceMapDoesNotAffectRecord() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("key", "value");

            WorkflowEventIntent intent = new WorkflowEventIntent(
                    WorkflowEventType.WORKFLOW_STARTED,
                    WORKFLOW_ID,
                    "def-1",
                    1L,
                    null,
                    null,
                    OCCURRED_AT,
                    0L,
                    null,
                    null,
                    attrs,
                    null,
                    null);

            attrs.put("key", "mutated");
            attrs.put("other", "new");

            assertThat(intent.attributes()).containsEntry("key", "value").doesNotContainKey("other");
        }

        @Test
        @DisplayName("null attributes produces empty Map")
        void nullAttributesBecomesEmptyMap() {
            WorkflowEventIntent intent = new WorkflowEventIntent(
                    WorkflowEventType.WORKFLOW_STARTED,
                    WORKFLOW_ID,
                    "def-1",
                    1L,
                    null,
                    null,
                    OCCURRED_AT,
                    0L,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThat(intent.attributes()).isEmpty();
        }

        @Test
        @DisplayName("attributes map is unmodifiable after construction")
        void attributesMapIsUnmodifiable() {
            WorkflowEventIntent intent = new WorkflowEventIntent(
                    WorkflowEventType.WORKFLOW_STARTED,
                    WORKFLOW_ID,
                    "def-1",
                    1L,
                    null,
                    null,
                    OCCURRED_AT,
                    0L,
                    null,
                    null,
                    Map.of("k", "v"),
                    null,
                    null);

            assertThat(intent.attributes()).containsEntry("k", "v");
            // Map.copyOf returns an unmodifiable map
            org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> intent.attributes().put("new", "val"));
        }
    }
}

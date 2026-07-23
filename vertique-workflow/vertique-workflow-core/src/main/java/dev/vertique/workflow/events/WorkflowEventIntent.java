// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal record carrying the payload of a {@code WORKFLOW_EVENT} side-effect intent.
 *
 * <p>Produced by the engine when a lifecycle event occurs and passed to the
 * {@link dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder} responsible for
 * {@link dev.vertique.workflow.sideeffect.IntentKind#WORKFLOW_EVENT}. The recorder maps this to a
 * {@link WorkflowEventEnvelope} for durable storage and downstream delivery.
 *
 * <p>The {@code attributes} map is defensively copied at construction time (null is promoted to
 * an empty map). All other non-null fields are validated in the compact constructor.
 *
 * @param eventType        the type of the event; never null
 * @param workflowId       the owning workflow instance id; never null
 * @param definitionId     the workflow definition id; never null
 * @param definitionVersion the version of the workflow definition
 * @param subjectRef       optional domain entity reference attached to the workflow instance
 * @param businessKey      optional application-supplied correlation key for the instance
 * @param occurredAt       the UTC instant at which the event occurred; never null
 * @param sequence         the {@code workflow_history} sequence number at the point of emission
 * @param taskId           the task UUID for task-lifecycle events; null for workflow-lifecycle events
 * @param stepId           the plan step id that produced the event; null when not step-bound
 * @param attributes       arbitrary key-value metadata; null is promoted to {@link Map#of()}
 * @param correlationId    optional correlation trace id propagated across systems
 * @param causationId      optional id of the cause event that produced this event
 */
public record WorkflowEventIntent(
        WorkflowEventType eventType,
        WorkflowInstanceId workflowId,
        String definitionId,
        long definitionVersion,
        @Nullable WorkflowSubjectRef subjectRef,
        @Nullable String businessKey,
        Instant occurredAt,
        long sequence,
        @Nullable UUID taskId,
        @Nullable String stepId,
        Map<String, Object> attributes,
        @Nullable String correlationId,
        @Nullable String causationId) {

    /**
     * Validates non-null fields and defensively copies {@code attributes}.
     *
     * @throws NullPointerException if {@code eventType}, {@code workflowId}, {@code definitionId},
     *     or {@code occurredAt} is null
     */
    public WorkflowEventIntent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}

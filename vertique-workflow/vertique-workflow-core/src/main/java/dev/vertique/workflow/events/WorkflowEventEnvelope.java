// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Stable JSON wire contract for workflow lifecycle events published via the outbox.
 *
 * <p>This class is the durable serialization envelope — distinct from the internal
 * {@link WorkflowEventIntent} so that internal engine field changes do not bleed into the wire
 * format consumed by external systems.
 *
 * <h2>Key wire contract rules</h2>
 * <ul>
 *   <li>{@link #schemaVersion()} is always {@code 1} in cycle 4.</li>
 *   <li>{@link #eventType()} is a plain {@link String} (populated from
 *       {@link WorkflowEventType#name()}), NOT the enum. This means adding new event types to
 *       {@link WorkflowEventType} is non-breaking: consumers that cannot handle a new string
 *       simply skip/log it rather than deserializing into an unknown enum constant.</li>
 *   <li>Unknown top-level fields are silently ignored on deserialization
 *       ({@link JsonIgnoreProperties#ignoreUnknown()}).</li>
 * </ul>
 *
 * <h2>Consumer contract</h2>
 * Consumers MUST treat {@code eventType} as a string-based discriminator and pattern-match
 * defensively (skip/log unknowns rather than throw). Known values are documented in
 * {@code vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md}.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class WorkflowEventEnvelope {

    /**
     * Schema version of this envelope. Always {@code 1} in cycle 4. Incremented only when
     * the envelope's field set changes in a breaking way.
     */
    @Builder.Default
    private final int schemaVersion = 1;

    /**
     * The event type name as a plain string (e.g., {@code "TASK_CREATED"}).
     * Populated from {@link WorkflowEventType#name()} by the producer.
     * Consumers must tolerate unknown values.
     */
    private final String eventType;

    /**
     * UUID of the workflow instance that produced this event.
     */
    private final UUID workflowId;

    /**
     * Id of the workflow definition that the instance belongs to.
     */
    private final String definitionId;

    /**
     * Version of the workflow definition at the time the instance was created.
     */
    private final long definitionVersion;

    /**
     * Optional reference to the domain entity that the workflow is acting on.
     */
    @Nullable
    private final WorkflowSubjectRef subjectRef;

    /**
     * Optional application-supplied business correlation key for the instance.
     */
    @Nullable
    private final String businessKey;

    /**
     * UTC instant at which the event occurred in the engine.
     */
    @Nullable
    private final Instant occurredAt;

    /**
     * The {@code workflow_history} sequence number at the point of emission.
     */
    private final long sequence;

    /**
     * UUID of the task involved, for task-lifecycle events; null for workflow-lifecycle events.
     */
    @Nullable
    private final UUID taskId;

    /**
     * The plan step id that produced this event; null when not step-bound.
     */
    @Nullable
    private final String stepId;

    /**
     * Arbitrary key-value metadata attached by the engine at emission time.
     */
    @Nullable
    private final Map<String, Object> attributes;

    /**
     * Optional correlation trace id propagated across systems.
     */
    @Nullable
    private final String correlationId;

    /**
     * Optional id of the cause event that triggered this event.
     */
    @Nullable
    private final String causationId;
}

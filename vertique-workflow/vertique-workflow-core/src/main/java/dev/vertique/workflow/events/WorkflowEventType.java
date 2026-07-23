// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events;

/**
 * Producer enum listing the known cycle-4 workflow event type names.
 *
 * <p>Used internally by the engine to label emitted {@link WorkflowEventIntent} payloads. Note:
 * the stable wire envelope ({@link WorkflowEventEnvelope}) serializes {@code eventType} as a
 * {@link String} (populated from {@link #name()}), NOT as this enum, so adding new event types is
 * non-breaking for downstream Java/Jackson consumers. Consumers are expected to treat
 * {@code eventType} as a string discriminator and tolerate unknown values defensively.
 *
 * <p>Timer and signal events are not surfaced in cycle 4 — they are internal mechanism, not
 * lifecycle. Future cycles can add them additively at {@code schemaVersion=1}.
 */
public enum WorkflowEventType {

    /** Workflow instance was created and the first step was reached. */
    WORKFLOW_STARTED,

    /** Workflow reached a {@link dev.vertique.workflow.plan.CompleteNode}. */
    WORKFLOW_COMPLETED,

    /** Workflow reached a {@link dev.vertique.workflow.plan.FailNode}. */
    WORKFLOW_FAILED,

    /** Workflow instance was cancelled by an operator or API call. */
    WORKFLOW_CANCELLED,

    /** Workflow instance expired (reserved; not emitted in cycle 4). */
    WORKFLOW_EXPIRED,

    /** Compensation flow was initiated (instance transitioned to {@code COMPENSATING}). */
    WORKFLOW_COMPENSATING,

    /** All compensation steps have been recorded; instance transitioned to {@code COMPENSATED}. */
    WORKFLOW_COMPENSATED,

    /** A human task was created and the workflow is waiting for an actor's decision. */
    TASK_CREATED,

    /** An actor submitted a decision and the task was completed. */
    TASK_COMPLETED,

    /** A task was cancelled because the parent workflow instance was cancelled. */
    TASK_CANCELLED,

    /** A task's due-date timer fired before the task was completed. */
    TASK_EXPIRED,

    /** The assignment of a task was changed to a different user, role, or queue. */
    TASK_REASSIGNED,

    /** A task reminder timer fired; emitted as an event with no workflow state change. */
    TASK_REMINDER
}

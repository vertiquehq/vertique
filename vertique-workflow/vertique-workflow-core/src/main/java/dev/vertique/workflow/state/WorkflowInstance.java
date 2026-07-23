// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Snapshot of a workflow instance's current state as persisted in the repository.
 *
 * <p>The {@code version} field is used for optimistic concurrency control: the engine only writes
 * if the persisted version matches the expected version, preventing concurrent updates from
 * silently overwriting each other.
 *
 * <p>The {@code stateJson} field holds the JSON-encoded workflow-application state object. Its
 * type is identified by looking up the {@code RuntimeWorkflow} for {@code definitionId + version}.
 *
 * <p>Use the {@code with*} methods to produce a modified copy with all other fields preserved.
 * These are the preferred way to produce updated snapshots inside the engine, avoiding the
 * 18-argument canonical constructor at every mutation site.
 *
 * @param id unique identifier for this workflow instance
 * @param definitionId identifier of the workflow definition that created this instance
 * @param definitionVersion version of the plan this instance was started against; pinned at start
 *     time so the engine always uses the correct plan even if a newer version is registered later
 * @param planHash hash of the plan at start time; used to detect plan-content drift
 * @param version optimistic-concurrency token; incremented on every successful state mutation
 * @param status current lifecycle status
 * @param businessKey optional application-defined unique key within the definition scope
 * @param subjectRef optional reference to the domain entity this workflow is acting on
 * @param currentStepId the step id the instance is currently at or last completed
 * @param waitType typed discriminator identifying what the instance is waiting for ({@link
 *     WaitType#SIGNAL}, {@link WaitType#TIMER}, {@link WaitType#TASK}); null when not waiting
 * @param waitKey the value the instance is waiting on (e.g., signal name); null when not waiting
 * @param waitAuxId auxiliary UUID associated with the current wait state; used by timer waits to
 *     store the timer id so the timer can be cancelled when the wait resolves (e.g., a signal
 *     arrives before the timeout); null when not waiting or when the wait has no associated timer
 * @param stateJson JSON-encoded application state object
 * @param errorType application-defined error category when {@code status} is {@code FAILED} or
 *     {@code COMPENSATED}; null otherwise
 * @param errorMessage human-readable error description; null when no error
 * @param createdAt timestamp when this instance was created
 * @param updatedAt timestamp of the most recent state mutation
 * @param metadata durable context captured at start time (ADR-0065 carrier shape); {@code null}
 *     when no ambient durable context was present at start (empty capture) or for instances
 *     started before this carrier existed. Immutable for the lifetime of the instance — no
 *     {@code with*} updater exists for this field; every updater passes it through unchanged.
 */
public record WorkflowInstance(
        WorkflowInstanceId id,
        String definitionId,
        long definitionVersion,
        String planHash,
        long version,
        WorkflowStatus status,
        @Nullable String businessKey,
        @Nullable WorkflowSubjectRef subjectRef,
        String currentStepId,
        @Nullable WaitType waitType,
        @Nullable String waitKey,
        @Nullable java.util.UUID waitAuxId,
        String stateJson,
        @Nullable String errorType,
        @Nullable String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        @Nullable DurableMetadata metadata) {

    // --- Fluent updaters ---

    /**
     * Returns a copy of this instance with the version set to {@code newVersion} and all other
     * fields preserved.
     *
     * @param newVersion the new optimistic-concurrency version
     * @return a new {@link WorkflowInstance} with the updated version
     */
    public WorkflowInstance withVersion(long newVersion) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                newVersion,
                status,
                businessKey,
                subjectRef,
                currentStepId,
                waitType,
                waitKey,
                waitAuxId,
                stateJson,
                errorType,
                errorMessage,
                createdAt,
                updatedAt,
                metadata);
    }

    /**
     * Returns a copy of this instance with the status set to {@code newStatus} and all other
     * fields preserved.
     *
     * @param newStatus the new lifecycle status
     * @return a new {@link WorkflowInstance} with the updated status
     */
    public WorkflowInstance withStatus(WorkflowStatus newStatus) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                version,
                newStatus,
                businessKey,
                subjectRef,
                currentStepId,
                waitType,
                waitKey,
                waitAuxId,
                stateJson,
                errorType,
                errorMessage,
                createdAt,
                updatedAt,
                metadata);
    }

    /**
     * Returns a copy of this instance with {@code currentStepId} set to {@code stepId} and all
     * other fields preserved.
     *
     * @param stepId the new current step id
     * @return a new {@link WorkflowInstance} with the updated step id
     */
    public WorkflowInstance withCurrentStepId(String stepId) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                version,
                status,
                businessKey,
                subjectRef,
                stepId,
                waitType,
                waitKey,
                waitAuxId,
                stateJson,
                errorType,
                errorMessage,
                createdAt,
                updatedAt,
                metadata);
    }

    /**
     * Returns a copy of this instance with {@code waitType}, {@code waitKey}, and
     * {@code waitAuxId} replaced together and all other fields preserved. All three fields change
     * together because they are jointly meaningful as the wait state of the instance.
     *
     * @param newWaitType the new typed wait-type discriminator, or {@code null} when clearing the
     *     wait
     * @param newWaitKey the new wait key, or {@code null} when clearing the wait
     * @param newWaitAuxId the new auxiliary wait id (e.g., timer UUID), or {@code null} when
     *     clearing the wait or when the wait has no associated auxiliary id
     * @return a new {@link WorkflowInstance} with the updated wait fields
     */
    public WorkflowInstance withWait(
            @Nullable WaitType newWaitType, @Nullable String newWaitKey, @Nullable java.util.UUID newWaitAuxId) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                version,
                status,
                businessKey,
                subjectRef,
                currentStepId,
                newWaitType,
                newWaitKey,
                newWaitAuxId,
                stateJson,
                errorType,
                errorMessage,
                createdAt,
                updatedAt,
                metadata);
    }

    /**
     * Returns a copy of this instance with {@code waitType} and {@code waitKey} replaced together
     * and {@code waitAuxId} set to {@code null}. Convenience overload for cycle-1 callers that do
     * not need to set an auxiliary id.
     *
     * <p>Equivalent to {@link #withWait(WaitType, String, java.util.UUID)} with
     * {@code newWaitAuxId = null}.
     *
     * @param newWaitType the new typed wait-type discriminator, or {@code null} when clearing the
     *     wait
     * @param newWaitKey the new wait key, or {@code null} when clearing the wait
     * @return a new {@link WorkflowInstance} with the updated wait fields and {@code waitAuxId=null}
     */
    public WorkflowInstance withWait(@Nullable WaitType newWaitType, @Nullable String newWaitKey) {
        return withWait(newWaitType, newWaitKey, null);
    }

    /**
     * Returns a copy of this instance with {@code stateJson} set to {@code newStateJson} and all
     * other fields preserved.
     *
     * @param newStateJson JSON-encoded workflow-application state
     * @return a new {@link WorkflowInstance} with the updated state
     */
    public WorkflowInstance withState(String newStateJson) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                version,
                status,
                businessKey,
                subjectRef,
                currentStepId,
                waitType,
                waitKey,
                waitAuxId,
                newStateJson,
                errorType,
                errorMessage,
                createdAt,
                updatedAt,
                metadata);
    }

    /**
     * Returns a copy of this instance with {@code errorType} and {@code errorMessage} replaced
     * together and all other fields preserved.
     *
     * @param newErrorType the new error category, or {@code null} to clear the error
     * @param newErrorMessage the new human-readable error description, or {@code null} to clear
     * @return a new {@link WorkflowInstance} with the updated error fields
     */
    public WorkflowInstance withError(@Nullable String newErrorType, @Nullable String newErrorMessage) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                version,
                status,
                businessKey,
                subjectRef,
                currentStepId,
                waitType,
                waitKey,
                waitAuxId,
                stateJson,
                newErrorType,
                newErrorMessage,
                createdAt,
                updatedAt,
                metadata);
    }

    /**
     * Returns a copy of this instance with {@code updatedAt} set to {@code when} and all other
     * fields preserved.
     *
     * @param when the timestamp to record as the most recent state mutation
     * @return a new {@link WorkflowInstance} with the updated timestamp
     */
    public WorkflowInstance withUpdatedAt(Instant when) {
        return new WorkflowInstance(
                id,
                definitionId,
                definitionVersion,
                planHash,
                version,
                status,
                businessKey,
                subjectRef,
                currentStepId,
                waitType,
                waitKey,
                waitAuxId,
                stateJson,
                errorType,
                errorMessage,
                createdAt,
                when,
                metadata);
    }
}

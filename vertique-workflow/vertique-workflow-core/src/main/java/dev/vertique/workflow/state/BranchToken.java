// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted execution token for a single fork-group branch (PRD-WF-002).
 *
 * <p>Each branch in a {@link dev.vertique.workflow.plan.ForkNode} gets exactly one branch token,
 * stored in {@code workflow_branch_tokens}. The token carries the branch's current step, status,
 * wait reason, retry budget, accumulated error metadata, and durable propagation context.
 * Branch transitions use optimistic concurrency on {@link #version()}; sibling branches do not
 * contend on each other.
 *
 * <p>The {@link #metadata()} field carries durable propagation context in wire-format key/value
 * pairs (FR-CTX-178). It is captured at branch-creation time by
 * {@link dev.vertique.workflow.postgresql.engine.PgWorkflowEngine} via
 * {@code DurableContextPropagator.mergeCaptured(..., WORKFLOW)} and persisted in
 * {@code workflow_branch_tokens.metadata JSONB}. Before each service-dispatch or recovery
 * advance, the branch metadata is restored via {@code DurableContextPropagator.bindFrom(...)}.
 *
 * @param id branch token primary key (UUID)
 * @param workflowId parent workflow instance id
 * @param forkStepId step id of the {@link dev.vertique.workflow.plan.ForkNode} that spawned this
 *     branch
 * @param branchId the {@link dev.vertique.workflow.plan.BranchStart#branchId()} this token
 *     represents; unique within {@code (workflowId, forkStepId)}
 * @param currentStepId the step id where this branch is currently positioned (or paused at)
 * @param status the branch's lifecycle status
 * @param waitType wait reason discriminator when {@link #status()} is {@link BranchStatus#WAITING};
 *     null otherwise
 * @param waitKey wait identifier paired with {@code waitType} (signal name, task id string,
 *     timer id string); null when not waiting
 * @param waitAuxId auxiliary id for the wait (e.g., timer id when waiting on a signal-with-timeout
 *     branch); null when unused
 * @param resultJson serialized branch-local result; consumed by the join's reducer. Null when the
 *     branch did not produce one or when it terminated abnormally.
 * @param errorType terminal error type; null on success
 * @param errorMessage terminal error message; null on success
 * @param attemptCount current attempt counter (0-based; incremented at each retry schedule)
 * @param maxAttempts attempt budget inherited from the fork's
 *     {@link dev.vertique.workflow.plan.BranchRetryPolicy#maxAttempts()}
 * @param nextRetryAt when the next retry is due, when {@link #status()} is
 *     {@link BranchStatus#RETRY_SCHEDULED}; null otherwise
 * @param lastErrorType most recent error type (preserved across retries)
 * @param lastErrorMessage most recent error message (preserved across retries)
 * @param lastErrorAt most recent error timestamp
 * @param version optimistic-concurrency token; CAS-incremented on each branch update
 * @param createdAt insert timestamp
 * @param updatedAt last-update timestamp
 * @param metadata durable propagation context as a {@link DurableMetadata} namespaced document;
 *     never null — defaults to {@link DurableMetadata#empty()} when no ambient durable context
 *     was captured at branch-create time. The {@code {"context": {...}}} carrier shape is
 *     persisted in {@code workflow_branch_tokens.metadata JSONB} via
 *     {@link DurableMetadata#toCarrier()} and read back via
 *     {@link DurableMetadata#fromCarrier(io.vertx.core.json.JsonObject)}. The {@code with*}
 *     updaters pass this field through unchanged — metadata is immutable after branch creation.
 */
public record BranchToken(
        UUID id,
        WorkflowInstanceId workflowId,
        String forkStepId,
        String branchId,
        String currentStepId,
        BranchStatus status,
        @Nullable WaitType waitType,
        @Nullable String waitKey,
        @Nullable UUID waitAuxId,
        @Nullable String resultJson,
        @Nullable String errorType,
        @Nullable String errorMessage,
        int attemptCount,
        int maxAttempts,
        @Nullable Instant nextRetryAt,
        @Nullable String lastErrorType,
        @Nullable String lastErrorMessage,
        @Nullable Instant lastErrorAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        DurableMetadata metadata) {

    /**
     * Compact constructor enforcing non-null required fields. Null {@code metadata} is normalised
     * to {@link DurableMetadata#empty()}.
     */
    public BranchToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(forkStepId, "forkStepId");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(currentStepId, "currentStepId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be >= 0, got " + attemptCount);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        metadata = metadata != null ? metadata : DurableMetadata.empty();
    }

    // --- Convenience updaters ---

    /**
     * Returns a copy of this token with a new {@code currentStepId}, {@code version}, and
     * {@code updatedAt}. The {@link #metadata()} field is passed through unchanged.
     *
     * @param newStepId the next step id
     * @param newVersion the new version (typically {@code version + 1})
     * @param now the current timestamp
     * @return the updated token
     */
    public BranchToken withStep(String newStepId, long newVersion, Instant now) {
        return new BranchToken(
                id,
                workflowId,
                forkStepId,
                branchId,
                newStepId,
                status,
                waitType,
                waitKey,
                waitAuxId,
                resultJson,
                errorType,
                errorMessage,
                attemptCount,
                maxAttempts,
                nextRetryAt,
                lastErrorType,
                lastErrorMessage,
                lastErrorAt,
                newVersion,
                createdAt,
                now,
                metadata);
    }

    /**
     * Returns a copy of this token with a new status and version. The {@link #metadata()} field
     * is passed through unchanged.
     *
     * @param newStatus the new branch status
     * @param newVersion the new version
     * @param now the current timestamp
     * @return the updated token
     */
    public BranchToken withStatus(BranchStatus newStatus, long newVersion, Instant now) {
        return new BranchToken(
                id,
                workflowId,
                forkStepId,
                branchId,
                currentStepId,
                newStatus,
                waitType,
                waitKey,
                waitAuxId,
                resultJson,
                errorType,
                errorMessage,
                attemptCount,
                maxAttempts,
                nextRetryAt,
                lastErrorType,
                lastErrorMessage,
                lastErrorAt,
                newVersion,
                createdAt,
                now,
                metadata);
    }

    /**
     * Returns a copy of this token with a wait set. The branch's status MUST be
     * {@link BranchStatus#WAITING} (callers are responsible for ensuring this). The
     * {@link #metadata()} field is passed through unchanged.
     *
     * @param newWaitType the wait discriminator (non-null)
     * @param newWaitKey the wait identifier
     * @param newWaitAuxId optional wait auxiliary id
     * @param newVersion the new version
     * @param now the current timestamp
     * @return the updated token
     */
    public BranchToken withWait(
            WaitType newWaitType, String newWaitKey, @Nullable UUID newWaitAuxId, long newVersion, Instant now) {
        Objects.requireNonNull(newWaitType, "newWaitType");
        Objects.requireNonNull(newWaitKey, "newWaitKey");
        return new BranchToken(
                id,
                workflowId,
                forkStepId,
                branchId,
                currentStepId,
                BranchStatus.WAITING,
                newWaitType,
                newWaitKey,
                newWaitAuxId,
                resultJson,
                errorType,
                errorMessage,
                attemptCount,
                maxAttempts,
                nextRetryAt,
                lastErrorType,
                lastErrorMessage,
                lastErrorAt,
                newVersion,
                createdAt,
                now,
                metadata);
    }

    /**
     * Returns a copy of this token with its wait fields ({@code waitType}, {@code waitKey},
     * {@code waitAuxId}, {@code nextRetryAt}) cleared, the status set to {@code newStatus}, and the
     * {@code currentStepId} advanced to {@code newStepId}. Increments {@link #version()} by one.
     * The {@link #metadata()} field is passed through unchanged.
     *
     * <p>Used by signal/timer/task/race callbacks that release a wait and either resume the branch
     * ({@code newStatus = RUNNING}, {@code newStepId} = the next step) or terminate it
     * ({@code newStatus = CANCELLED} / {@code SUPERSEDED}, {@code newStepId} = the step the branch
     * was holding at).
     *
     * <p>{@link #nextRetryAt()} is cleared unconditionally: once the branch is no longer
     * {@link BranchStatus#RETRY_SCHEDULED}, a lingering retry timestamp would be stale state. Retry
     * metadata that survives across a retry attempt ({@code attemptCount}, {@code maxAttempts},
     * {@code lastError*}) is preserved.
     *
     * @param newStatus the post-clear status (typically {@code RUNNING}, {@code CANCELLED}, or
     *     {@code SUPERSEDED})
     * @param newStepId the step id to advance to (callers that don't move the cursor pass the
     *     current step)
     * @param now the current timestamp
     * @return the updated token
     */
    public BranchToken withClearedWait(BranchStatus newStatus, String newStepId, Instant now) {
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(newStepId, "newStepId");
        Objects.requireNonNull(now, "now");
        return new BranchToken(
                id,
                workflowId,
                forkStepId,
                branchId,
                newStepId,
                newStatus,
                null,
                null,
                null,
                resultJson,
                errorType,
                errorMessage,
                attemptCount,
                maxAttempts,
                null,
                lastErrorType,
                lastErrorMessage,
                lastErrorAt,
                version + 1,
                createdAt,
                now,
                metadata);
    }
}

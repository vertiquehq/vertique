// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Public command value type carrying the inputs for a task-completion operation.
 *
 * <p>Passed to {@code TaskService.complete(...)} (cycle 3) and routed to
 * {@link TransactionalTaskCallbacks#taskCompleted}. The command carries both the idempotency key
 * required for at-least-once retry safety and the typed actor identity required for audit.
 *
 * <p>Payload nullability is decision-specific and is validated in the engine against the resolved
 * {@code TaskDecision.payloadTypeName}: when that class is {@code Void.class} (the conventional
 * "no-payload" marker), {@code payload} MUST be {@code null}; for any other class, {@code payload}
 * MUST be non-null and assignable to that class after JSON coercion.
 *
 * <p>{@code reviewedSubjectVersion} carries the subject-object version the reviewer saw when making
 * the decision (cycle 5). The field has a dual semantic that callers MUST understand:
 *
 * <ol>
 *   <li><b>Version-stability validation</b> — for tasks where
 *       {@link dev.vertique.workflow.plan.HumanTaskNode#requireVersionStability()} is {@code true},
 *       the engine validates that this value equals the snapshot taken at task creation. A
 *       mismatch throws {@link dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException}.
 *       For tasks where the flag is {@code false} (the default), this field is <b>ignored for
 *       version-stability validation</b>.</li>
 *   <li><b>Idempotency fingerprint</b> — the field always participates in the {@code task-complete}
 *       fingerprint v2, regardless of the per-task version-stability flag. Cycle-3's fingerprint
 *       runs <em>before</em> plan-node loading (so retries-after-successful-completion observe
 *       {@code LOST_TO_RACE} without re-parsing the plan), and the fingerprint shape is uniform
 *       across all task-complete commands. <b>Two retries of the same idempotency key with
 *       different {@code reviewedSubjectVersion} values produce different fingerprints and
 *       surface as {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException} —
 *       even on a non-stability task</b>. Pass the same value (typically {@code null}) on every
 *       retry of the same logical command.</li>
 * </ol>
 *
 * <p>{@code null} is always legal at the API boundary; blank strings are rejected (use {@code null}
 * instead). See ADR-0055 (version-aware approvals) and ADR-0057 (fingerprint v2) for the full
 * contract.
 *
 * <p>{@code expectedAssignment} is an optional precondition: the assignment the caller observed
 * when it decided that {@code completedBy} was eligible to act. The engine compares it against the
 * task row it holds locked for the completion and fails with
 * {@link dev.vertique.workflow.exception.WorkflowStaleAssignmentException} when the assignment has
 * moved in the meantime, so an eligibility decision made outside the transaction cannot be applied
 * to a task that has since been reassigned. {@code null} disables the check. It is a precondition,
 * not mutation content, and is <b>not</b> part of the idempotency fingerprint.
 *
 * @param taskId the stable UUID of the task to complete; must not be null
 * @param decisionName the name of the decision being submitted; must not be null or blank
 * @param payload the decision payload; nullability is decision-specific and validated by the engine
 * @param idempotencyKey caller-supplied key used to de-duplicate retries; must not be null or blank
 * @param completedBy the actor completing the task; required for audit; must not be null
 * @param reviewedSubjectVersion the version of the subject object the reviewer reviewed when making
 *     the decision; may be null; ignored for version-stability validation when the target task's
 *     {@code requireVersionStability=false}, but always participates in idempotency fingerprinting
 * @param expectedAssignment the assignment the caller observed when it decided the actor was eligible;
 *     compared against the locked task row, with a mismatch failing the command with
 *     {@link dev.vertique.workflow.exception.WorkflowStaleAssignmentException}; may be null, which
 *     disables the check; not part of the idempotency fingerprint
 */
public record TaskCompletionCommand(
        UUID taskId,
        String decisionName,
        Object payload,
        String idempotencyKey,
        WorkflowActor completedBy,
        @Nullable String reviewedSubjectVersion,
        @Nullable TaskAssignment expectedAssignment) {

    /**
     * Creates a completion command without an assignment precondition.
     *
     * <p>Equivalent to the canonical constructor with a {@code null} {@code expectedAssignment}.
     *
     * @param taskId the stable UUID of the task to complete; must not be null
     * @param decisionName the name of the decision being submitted; must not be null or blank
     * @param payload the decision payload; nullability is decision-specific and validated by the engine
     * @param idempotencyKey caller-supplied key used to de-duplicate retries; must not be null or blank
     * @param completedBy the actor completing the task; required for audit; must not be null
     * @param reviewedSubjectVersion the version of the subject object the reviewer reviewed; may be null
     * @throws NullPointerException if {@code taskId}, {@code decisionName}, {@code idempotencyKey},
     *     or {@code completedBy} is null
     * @throws IllegalArgumentException if {@code decisionName} or {@code idempotencyKey} is blank, or
     *     {@code reviewedSubjectVersion} is blank
     */
    public TaskCompletionCommand(
            UUID taskId,
            String decisionName,
            Object payload,
            String idempotencyKey,
            WorkflowActor completedBy,
            @Nullable String reviewedSubjectVersion) {
        this(taskId, decisionName, payload, idempotencyKey, completedBy, reviewedSubjectVersion, null);
    }

    /**
     * Validates required fields at the public-API boundary.
     *
     * @throws NullPointerException if {@code taskId}, {@code decisionName}, {@code idempotencyKey},
     *     or {@code completedBy} is null
     * @throws IllegalArgumentException if {@code decisionName} or {@code idempotencyKey} is blank
     */
    public TaskCompletionCommand {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(decisionName, "decisionName");
        if (decisionName.isBlank()) {
            throw new IllegalArgumentException("decisionName must not be blank");
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(completedBy, "completedBy");
        // payload nullability is decision-specific; validated in the engine.
        // reviewedSubjectVersion is nullable at the API boundary; engine validates per-task. A
        // blank string is rejected here (consistent with WorkflowSubjectRef.version) — the
        // persisted snapshot can never be blank, so a blank command value would always fail the
        // engine's Objects.equals check with a confusing "did not match" message; reject up front.
        if (reviewedSubjectVersion != null && reviewedSubjectVersion.isBlank()) {
            throw new IllegalArgumentException("reviewedSubjectVersion must not be blank (use null instead)");
        }
    }
}

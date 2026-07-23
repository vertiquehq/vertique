// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * A plan node that suspends the workflow until an assigned human actor submits a typed decision.
 *
 * <p>When the engine reaches this node it creates a task row, sets the instance status to
 * {@code WAITING} with {@code waitType="TASK"}, and waits for a matching
 * {@link dev.vertique.workflow.ops.TaskCompletionCommand}. The task is assigned to a user, role,
 * or queue, either as a literal or resolved dynamically from the workflow state via a callback.
 *
 * <p>At least one {@link TaskDecision} is required — a task with no decisions can never complete.
 * An optional {@link TimerSpec} due-date can be configured; when present, all three
 * due-date fields ({@code dueDate}, {@code onDueMutatorCallbackId}, {@code dueNextStepId}) must be
 * set together or all left null.
 *
 * <p>An optional {@link ReminderSpec} configures reminder timers that fire as event-only side
 * effects during the lifetime of the task. Reminders do not mutate workflow state. A {@code null}
 * value means no reminders are configured, which is the default and the cycle-3-equivalent shape.
 *
 * <p>When {@code requireVersionStability} is {@code true}, the engine requires
 * {@link dev.vertique.workflow.ops.TaskCompletionCommand#reviewedSubjectVersion()} to be non-null
 * and equal to the subject-version snapshot taken at task creation; a mismatch fails with
 * {@link dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException}. When {@code false}
 * (the default), the engine ignores {@code reviewedSubjectVersion} for version-stability
 * validation. <b>The field still participates in the {@code task-complete} idempotency fingerprint
 * regardless of this flag</b> (see {@link dev.vertique.workflow.ops.TaskCompletionCommand}), so
 * retries with different reviewed-version values surface as
 * {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException} on every task —
 * stability-required or not. ADR-0055 covers the validation contract; ADR-0057 covers the
 * fingerprint contract.
 *
 * @param stepId unique step identifier within the plan
 * @param assignment how the initial assignee is determined at task-creation time; may be a literal
 *     or computed from the workflow state via a callback
 * @param decisions the closed list of named decisions this task accepts; must not be empty
 * @param dueDate optional spec describing when the task expires; null means no due-date
 * @param onDueMutatorCallbackId callback id for the {@code Function<S, S>} that mutates the
 *     workflow state when the due-date fires; must be non-null iff {@code dueDate} is non-null
 * @param dueNextStepId step to advance to when the due-date fires; must be non-null iff
 *     {@code dueDate} is non-null
 * @param reminders optional reminder schedule; null means no reminders (cycle-3-equivalent shape)
 * @param requireVersionStability when {@code true}, completion requires a matching
 *     {@code reviewedSubjectVersion}; when {@code false} (default), the version field is ignored
 *     for validation but still participates in idempotency fingerprinting
 */
public record HumanTaskNode(
        String stepId,
        AssignmentSpec assignment,
        List<TaskDecision> decisions,
        @Nullable TimerSpec dueDate,
        @Nullable CallbackId onDueMutatorCallbackId,
        @Nullable String dueNextStepId,
        @Nullable ReminderSpec reminders,
        boolean requireVersionStability)
        implements WorkflowNode {

    /**
     * Validates required fields and enforces the all-or-nothing due-date triplet rule.
     *
     * @throws NullPointerException if {@code stepId}, {@code assignment}, or {@code decisions} is
     *     null
     * @throws IllegalArgumentException if {@code decisions} is empty, or if the due-date triplet
     *     is partially set
     */
    public HumanTaskNode {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(decisions, "decisions");
        decisions = List.copyOf(decisions);
        if (decisions.isEmpty()) {
            throw new IllegalArgumentException("HumanTaskNode requires at least one decision");
        }
        // Decision names form the closed dispatch keyspace at completion time
        // (PgWorkflowEngine.taskCompleted resolves by name). Duplicates would silently leave the
        // second declaration unreachable while still appearing in decisions_snapshot_json — fail
        // loud at definition time instead.
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (TaskDecision d : decisions) {
            if (!seenNames.add(d.name())) {
                throw new IllegalArgumentException(
                        "HumanTaskNode '" + stepId + "' has duplicate decision name '" + d.name() + "'");
            }
        }
        // Enforce all-or-nothing due-date triplet
        boolean hasDue = (dueDate != null) || (onDueMutatorCallbackId != null) || (dueNextStepId != null);
        boolean fullDue = (dueDate != null) && (onDueMutatorCallbackId != null) && (dueNextStepId != null);
        if (hasDue && !fullDue) {
            throw new IllegalArgumentException(
                    "dueDate, onDueMutatorCallbackId, and dueNextStepId must be set together or all null");
        }
    }

    // --- Nested types ---

    /**
     * Sealed sum type specifying how the initial task assignee is determined.
     *
     * <p>Literal variants ({@link User}, {@link Role}, {@link Queue}) bake the assignee into the
     * plan at definition time. Resolver variants ({@link UserFromState}, {@link RoleFromState},
     * {@link QueueFromState}) invoke a registered callback with the current workflow state at
     * task-creation time to compute the assignee dynamically.
     *
     * <p>Both shapes participate in plan-hash drift detection: literal variants hash to their
     * value; resolver variants hash to the {@link CallbackId#value()}.
     */
    public sealed interface AssignmentSpec
            permits AssignmentSpec.User,
                    AssignmentSpec.UserFromState,
                    AssignmentSpec.Role,
                    AssignmentSpec.RoleFromState,
                    AssignmentSpec.Queue,
                    AssignmentSpec.QueueFromState {

        /**
         * Literal assignment to a specific user id.
         *
         * @param userId the user id; must not be null or blank
         */
        record User(String userId) implements AssignmentSpec {
            /**
             * Validates that {@code userId} is non-null and non-blank.
             *
             * @throws NullPointerException if {@code userId} is null
             * @throws IllegalArgumentException if {@code userId} is blank
             */
            public User {
                Objects.requireNonNull(userId, "userId");
                if (userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
            }
        }

        /**
         * User id resolved dynamically from the workflow state at task-creation time.
         *
         * @param resolverCallbackId callback id for the {@code Function<S, String>} that returns
         *     the user id; must not be null
         */
        record UserFromState(CallbackId resolverCallbackId) implements AssignmentSpec {
            /**
             * Validates that {@code resolverCallbackId} is non-null.
             *
             * @throws NullPointerException if {@code resolverCallbackId} is null
             */
            public UserFromState {
                Objects.requireNonNull(resolverCallbackId, "resolverCallbackId");
            }
        }

        /**
         * Literal assignment to a role.
         *
         * @param roleId the role id; must not be null or blank
         */
        record Role(String roleId) implements AssignmentSpec {
            /**
             * Validates that {@code roleId} is non-null and non-blank.
             *
             * @throws NullPointerException if {@code roleId} is null
             * @throws IllegalArgumentException if {@code roleId} is blank
             */
            public Role {
                Objects.requireNonNull(roleId, "roleId");
                if (roleId.isBlank()) throw new IllegalArgumentException("roleId must not be blank");
            }
        }

        /**
         * Role id resolved dynamically from the workflow state at task-creation time.
         *
         * @param resolverCallbackId callback id for the {@code Function<S, String>} that returns
         *     the role id; must not be null
         */
        record RoleFromState(CallbackId resolverCallbackId) implements AssignmentSpec {
            /**
             * Validates that {@code resolverCallbackId} is non-null.
             *
             * @throws NullPointerException if {@code resolverCallbackId} is null
             */
            public RoleFromState {
                Objects.requireNonNull(resolverCallbackId, "resolverCallbackId");
            }
        }

        /**
         * Literal assignment to a named queue.
         *
         * @param queueName the queue name; must not be null or blank
         */
        record Queue(String queueName) implements AssignmentSpec {
            /**
             * Validates that {@code queueName} is non-null and non-blank.
             *
             * @throws NullPointerException if {@code queueName} is null
             * @throws IllegalArgumentException if {@code queueName} is blank
             */
            public Queue {
                Objects.requireNonNull(queueName, "queueName");
                if (queueName.isBlank()) throw new IllegalArgumentException("queueName must not be blank");
            }
        }

        /**
         * Queue name resolved dynamically from the workflow state at task-creation time.
         *
         * @param resolverCallbackId callback id for the {@code Function<S, String>} that returns
         *     the queue name; must not be null
         */
        record QueueFromState(CallbackId resolverCallbackId) implements AssignmentSpec {
            /**
             * Validates that {@code resolverCallbackId} is non-null.
             *
             * @throws NullPointerException if {@code resolverCallbackId} is null
             */
            public QueueFromState {
                Objects.requireNonNull(resolverCallbackId, "resolverCallbackId");
            }
        }
    }

    /**
     * A single named decision option for a human task.
     *
     * <p>Each decision has a unique name within the task, a payload type (the class whose instance
     * is expected in the {@link dev.vertique.workflow.ops.TaskCompletionCommand}), an applicator
     * callback that merges the payload into the workflow state, and the next step to advance to.
     *
     * @param name the decision name (e.g., {@code "approve"}, {@code "reject"}); must not be blank
     * @param payloadTypeName fully-qualified class name of the expected payload; used by the engine
     *     to coerce externally-ingested payloads; must not be blank
     * @param applicatorCallbackId callback id for the {@code BiFunction<S, P, S>} that merges the
     *     decision payload into the workflow state; must not be null
     * @param nextStepId the plan step id to advance to after this decision is applied; must not be
     *     blank
     */
    public record TaskDecision(
            String name, String payloadTypeName, CallbackId applicatorCallbackId, String nextStepId) {

        /**
         * Validates that all fields are non-null and non-blank (except the callback id which is
         * validated for null only).
         *
         * @throws NullPointerException if any field is null
         * @throws IllegalArgumentException if {@code name}, {@code payloadTypeName}, or
         *     {@code nextStepId} is blank
         */
        public TaskDecision {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            Objects.requireNonNull(payloadTypeName, "payloadTypeName");
            if (payloadTypeName.isBlank()) throw new IllegalArgumentException("payloadTypeName must not be blank");
            Objects.requireNonNull(applicatorCallbackId, "applicatorCallbackId");
            Objects.requireNonNull(nextStepId, "nextStepId");
            if (nextStepId.isBlank()) throw new IllegalArgumentException("nextStepId must not be blank");
        }
    }
}

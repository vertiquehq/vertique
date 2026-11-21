// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * Sealed sum type representing a runtime-literal task assignment value.
 *
 * <p>Used by {@link TaskStore#reassign} and as the resolved assignment persisted on the
 * {@code workflow_tasks} row. Unlike
 * {@link dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec}, which also carries
 * resolver-from-state variants for task creation, {@code TaskAssignment} is always literal — it
 * represents "who is assigned right now" rather than "how to compute the initial assignee".
 *
 * <p>Persisted as a {@code (kind, value)} pair on the {@code workflow_tasks} row's assignment
 * columns; that shape is built and parsed by hand in {@code PgTaskStore} and is independent of
 * this interface's Jackson mapping. When a {@code TaskAssignment} is embedded as a field inside a
 * history payload record (e.g. {@code TaskReassignedHistoryPayload.oldAssignment}), Jackson
 * serializes it as the permit's bare record shape — {@code {"userId":"..."}} /
 * {@code {"roleId":"..."}} / {@code {"queueName":"..."}} — with no wrapper or type discriminator.
 * {@link JsonTypeInfo.Id#DEDUCTION} deserialization picks the correct permit by which field is
 * present, so this wire shape is preserved byte-for-byte while still allowing
 * {@code TaskAssignment} to round-trip through Jackson.
 *
 * <p>Three permits:
 * <ul>
 *   <li>{@link User} — assignment to a specific user identified by their application user id</li>
 *   <li>{@link Role} — assignment to a role; any holder of the role can act on the task</li>
 *   <li>{@link Queue} — assignment to a named queue; typically polled by workers</li>
 * </ul>
 *
 * <p>Each permit's compact constructor rejects null and blank values.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
    @JsonSubTypes.Type(TaskAssignment.User.class),
    @JsonSubTypes.Type(TaskAssignment.Role.class),
    @JsonSubTypes.Type(TaskAssignment.Queue.class)
})
public sealed interface TaskAssignment permits TaskAssignment.User, TaskAssignment.Role, TaskAssignment.Queue {

    /**
     * Tells whether this assignment names one of the caller's principals: the user id of a
     * {@link User} assignment equals the caller's user id, the role of a {@link Role} assignment is one
     * of the caller's roles, or the queue of a {@link Queue} assignment is one of the caller's queues.
     * Values are compared exactly.
     *
     * <p>Only a {@link dev.vertique.security.PrincipalType#USER} principal matches; for any other
     * principal type this returns {@code false}. A {@link TaskFilter#assignedTo()} filter applies the same
     * rule to the assignment: among the tasks that pass the filter's other fields and its
     * archived-instance rule, the query returns exactly those this method matches.
     *
     * <p>This is a helper for the application's own authorization. Nothing in the framework calls it:
     * {@code complete} and {@code reassign} do not compare the actor with the assignment.
     *
     * @param principals the caller's principals; must not be null
     * @return {@code true} when the assignment matches the caller
     * @throws NullPointerException if {@code principals} is null
     */
    default boolean matches(TaskPrincipals principals) {
        Objects.requireNonNull(principals, "principals");
        if (!principals.canMatchAssignments()) {
            return false;
        }
        return switch (this) {
            case User user -> user.userId().equals(principals.userId());
            case Role role -> principals.roles().contains(role.roleId());
            case Queue queue -> principals.queues().contains(queue.queueName());
        };
    }

    /**
     * Assignment to a specific user.
     *
     * @param userId the application's stable identifier for the assignee; must not be null or blank
     */
    record User(String userId) implements TaskAssignment {
        /**
         * Validates that {@code userId} is non-null and non-blank.
         *
         * @throws NullPointerException if {@code userId} is null
         * @throws IllegalArgumentException if {@code userId} is blank
         */
        public User {
            Objects.requireNonNull(userId, "userId");
            if (userId.isBlank()) {
                throw new IllegalArgumentException("userId must not be blank");
            }
        }
    }

    /**
     * Assignment to a role.
     *
     * @param roleId the application's stable identifier for the role; must not be null or blank
     */
    record Role(String roleId) implements TaskAssignment {
        /**
         * Validates that {@code roleId} is non-null and non-blank.
         *
         * @throws NullPointerException if {@code roleId} is null
         * @throws IllegalArgumentException if {@code roleId} is blank
         */
        public Role {
            Objects.requireNonNull(roleId, "roleId");
            if (roleId.isBlank()) {
                throw new IllegalArgumentException("roleId must not be blank");
            }
        }
    }

    /**
     * Assignment to a named queue.
     *
     * @param queueName the name of the queue; must not be null or blank
     */
    record Queue(String queueName) implements TaskAssignment {
        /**
         * Validates that {@code queueName} is non-null and non-blank.
         *
         * @throws NullPointerException if {@code queueName} is null
         * @throws IllegalArgumentException if {@code queueName} is blank
         */
        public Queue {
            Objects.requireNonNull(queueName, "queueName");
            if (queueName.isBlank()) {
                throw new IllegalArgumentException("queueName must not be blank");
            }
        }
    }
}

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
 * {@code TaskAssignment} to round-trip through Jackson (ADR-0048).
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

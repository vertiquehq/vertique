// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.security.PrincipalType;
import java.util.Objects;
import java.util.Set;

/**
 * The principals of a caller that tasks can be assigned to: its user id, its roles and its queues,
 * with the type of the principal they were taken from.
 *
 * <p>A {@link TaskAssignment} matches these principals through {@link TaskAssignment#matches}, and
 * {@link TaskFilter#withAssignedTo(TaskPrincipals)} lists the tasks whose assignment matches them. Only a
 * {@link PrincipalType#USER} principal ever matches: a service, system or anonymous caller matches no
 * assignment, whatever ids, roles or queues it carries.
 *
 * <p>Build the principals from the authenticated security context with a
 * {@link dev.vertique.workflow.actor.TaskPrincipalsMapper}, never from request input: whoever controls
 * these values controls which tasks the caller sees.
 *
 * @param userId the id of the effective principal, compared with {@link TaskAssignment.User#userId()};
 *     must not be null or blank
 * @param principalType the type of the effective principal; must not be null
 * @param roles the role ids, compared with {@link TaskAssignment.Role#roleId()}; copied, immutable
 * @param queues the queue names, compared with {@link TaskAssignment.Queue#queueName()}; copied,
 *     immutable
 */
public record TaskPrincipals(String userId, PrincipalType principalType, Set<String> roles, Set<String> queues) {

    /**
     * Validates the components and copies the sets.
     *
     * @throws NullPointerException if a component or a set element is null
     * @throws IllegalArgumentException if {@code userId} is blank
     */
    public TaskPrincipals {
        Objects.requireNonNull(userId, "userId");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        Objects.requireNonNull(principalType, "principalType");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        queues = Set.copyOf(Objects.requireNonNull(queues, "queues"));
    }

    /**
     * Creates the principals of a {@link PrincipalType#USER} caller.
     *
     * @param userId the user id; must not be null or blank
     * @param roles the role ids; must not be null
     * @param queues the queue names; must not be null
     * @return the principals
     */
    public static TaskPrincipals ofUser(String userId, Set<String> roles, Set<String> queues) {
        return new TaskPrincipals(userId, PrincipalType.USER, roles, queues);
    }

    /**
     * Tells whether these principals can match an assignment at all, which only a
     * {@link PrincipalType#USER} principal can.
     *
     * @return {@code true} when the principal type is {@link PrincipalType#USER}
     */
    public boolean canMatchAssignments() {
        return principalType == PrincipalType.USER;
    }
}

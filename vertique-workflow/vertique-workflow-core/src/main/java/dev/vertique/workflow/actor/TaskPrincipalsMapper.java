// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.security.SecurityContext;
import dev.vertique.workflow.tasks.TaskPrincipals;
import java.util.Objects;
import java.util.Optional;

/**
 * SPI for mapping the security context of a caller to the {@link TaskPrincipals} that tasks can be
 * assigned to.
 *
 * <p>The default is {@link DefaultTaskPrincipalsMapper}: the effective principal's id and type, the
 * {@code ROLE} authorities as roles and the {@code GROUP} authorities as queues. An application whose
 * tokens carry task assignments in other authorities, or that keeps queue membership elsewhere, binds
 * its own implementation:
 *
 * <pre>{@code
 * @Provides
 * static TaskPrincipalsMapper taskPrincipalsMapper() {
 *     return context -> ...;
 * }
 * }</pre>
 *
 * <p>{@code WorkflowCoreModule} declares this type with {@code @BindsOptionalOf}, so an application
 * binding is optional. A consumer injects {@code Optional<TaskPrincipalsMapper>} and resolves it with
 * {@link #orDefault(Optional)}, which returns the application's binding or the default.
 *
 * <p>An implementation derives the principals from the context alone, never from request input, and
 * must not block: it is called on the event loop.
 *
 * @see TaskPrincipals
 * @see dev.vertique.workflow.tasks.TaskAssignment#matches(TaskPrincipals)
 */
@FunctionalInterface
public interface TaskPrincipalsMapper {

    /**
     * Maps the caller's security context to its task principals.
     *
     * @param context the authenticated security context of the caller; must not be {@code null}
     * @return the caller's task principals; never {@code null}
     * @throws NullPointerException if {@code context} is {@code null}
     */
    TaskPrincipals toTaskPrincipals(SecurityContext context);

    /**
     * Returns the application's mapper when it binds one, the {@link DefaultTaskPrincipalsMapper}
     * otherwise. This is the one place a consumer resolves the optional binding.
     *
     * @param bound the injected optional binding; must not be {@code null}
     * @return the mapper to use; never {@code null}
     * @throws NullPointerException if {@code bound} is {@code null}
     */
    static TaskPrincipalsMapper orDefault(Optional<TaskPrincipalsMapper> bound) {
        return Objects.requireNonNull(bound, "bound").orElseGet(DefaultTaskPrincipalsMapper::new);
    }
}

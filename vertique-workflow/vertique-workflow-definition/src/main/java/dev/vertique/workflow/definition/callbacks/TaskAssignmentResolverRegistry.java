// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.Set;

/**
 * Registry of named task-assignment resolvers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, TaskAssignment>} that resolves
 * the {@link TaskAssignment} for a human task from the current workflow state.
 *
 * <p>Population: applications contribute {@link TaskAssignmentResolverContributor}s into the
 * {@code Set<TaskAssignmentResolverContributor>} Dagger multibinding.
 *
 * @see NamedTaskAssignmentResolver
 * @see TaskAssignmentResolverContributor
 * @see TaskAssignment
 */
public interface TaskAssignmentResolverRegistry {

    /**
     * Looks up the named task-assignment resolver for the given id.
     *
     * @param id the resolver id declared in the workflow definition document; non-null
     * @return the registered {@link NamedTaskAssignmentResolver}, never null
     * @throws WorkflowDefinitionException if no resolver is registered with {@code id}
     */
    NamedTaskAssignmentResolver<?> lookup(String id);

    /**
     * Returns all registered resolver ids.
     *
     * @return unmodifiable set of ids; empty if no contributors registered any resolvers
     */
    Set<String> ids();

    /**
     * Returns {@code true} iff a resolver with {@code id} is registered.
     *
     * @param id the resolver id to check; non-null
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean contains(String id);

    /**
     * Mutable builder passed to each {@link TaskAssignmentResolverContributor} during registry
     * construction.
     */
    interface Builder {

        /**
         * Registers a named task-assignment resolver.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param resolver the named task-assignment resolver to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code resolver.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedTaskAssignmentResolver<S> resolver);
    }
}

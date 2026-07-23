// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import java.util.Set;

/**
 * Registry of named subject resolvers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, WorkflowSubjectRef>} that
 * resolves the {@link WorkflowSubjectRef} from the current workflow state. The subject reference
 * is stored on the workflow instance at start time for observability and query filtering.
 *
 * <p>Population: applications contribute {@link SubjectResolverContributor}s into the
 * {@code Set<SubjectResolverContributor>} Dagger multibinding.
 *
 * @see NamedSubjectResolver
 * @see SubjectResolverContributor
 * @see WorkflowSubjectRef
 */
public interface SubjectResolverRegistry {

    /**
     * Looks up the named subject resolver for the given id.
     *
     * @param id the resolver id declared in the workflow definition document; non-null
     * @return the registered {@link NamedSubjectResolver}, never null
     * @throws WorkflowDefinitionException if no resolver is registered with {@code id}
     */
    NamedSubjectResolver<?> lookup(String id);

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
     * Mutable builder passed to each {@link SubjectResolverContributor} during registry
     * construction.
     */
    interface Builder {

        /**
         * Registers a named subject resolver.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param resolver the named subject resolver to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code resolver.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedSubjectResolver<S> resolver);
    }
}

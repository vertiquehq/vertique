// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named correlation-key resolvers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, String>} that computes the value
 * identifying a signal wait from the current workflow state.
 *
 * <p>Population: applications contribute {@link CorrelationKeyResolverContributor}s into the
 * {@code Set<CorrelationKeyResolverContributor>} Dagger multibinding.
 *
 * @see NamedCorrelationKeyResolver
 * @see CorrelationKeyResolverContributor
 */
public interface CorrelationKeyResolverRegistry {

    /**
     * Looks up the named correlation-key resolver for the given id.
     *
     * @param id the resolver id declared in the workflow definition document; non-null
     * @return the registered {@link NamedCorrelationKeyResolver}, never null
     * @throws WorkflowDefinitionException if no resolver is registered with {@code id}
     */
    NamedCorrelationKeyResolver<?> lookup(String id);

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
     * Mutable builder passed to each {@link CorrelationKeyResolverContributor} during registry construction.
     */
    interface Builder {

        /**
         * Registers a named correlation-key resolver.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param resolver the named correlation-key resolver to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code resolver.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedCorrelationKeyResolver<S> resolver);
    }
}

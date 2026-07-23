// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named fail-message factories contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, String>} that derives a
 * human-readable failure message from the current workflow state when a {@code FailNode} is
 * executed.
 *
 * <p>Population: applications contribute {@link FailMessageFactoryContributor}s into the
 * {@code Set<FailMessageFactoryContributor>} Dagger multibinding.
 *
 * @see NamedFailMessageFactory
 * @see FailMessageFactoryContributor
 */
public interface FailMessageFactoryRegistry {

    /**
     * Looks up the named fail-message factory for the given id.
     *
     * @param id the factory id declared in the workflow definition document; non-null
     * @return the registered {@link NamedFailMessageFactory}, never null
     * @throws WorkflowDefinitionException if no factory is registered with {@code id}
     */
    NamedFailMessageFactory<?> lookup(String id);

    /**
     * Returns all registered factory ids.
     *
     * @return unmodifiable set of ids; empty if no contributors registered any factories
     */
    Set<String> ids();

    /**
     * Returns {@code true} iff a factory with {@code id} is registered.
     *
     * @param id the factory id to check; non-null
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean contains(String id);

    /**
     * Mutable builder passed to each {@link FailMessageFactoryContributor} during registry
     * construction.
     */
    interface Builder {

        /**
         * Registers a named fail-message factory.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param factory the named fail-message factory to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code factory.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedFailMessageFactory<S> factory);
    }
}

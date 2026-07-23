// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named state mutators contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, S>} that applies an in-place
 * mutation to the current workflow state. Mutators are useful for compensating steps or
 * state-normalisation actions that do not require an external event.
 *
 * <p>Population: applications contribute {@link StateMutatorContributor}s into the
 * {@code Set<StateMutatorContributor>} Dagger multibinding.
 *
 * @see NamedStateMutator
 * @see StateMutatorContributor
 */
public interface StateMutatorRegistry {

    /**
     * Looks up the named state mutator for the given id.
     *
     * @param id the mutator id declared in the workflow definition document; non-null
     * @return the registered {@link NamedStateMutator}, never null
     * @throws WorkflowDefinitionException if no mutator is registered with {@code id}
     */
    NamedStateMutator<?> lookup(String id);

    /**
     * Returns all registered mutator ids.
     *
     * @return unmodifiable set of ids; empty if no contributors registered any mutators
     */
    Set<String> ids();

    /**
     * Returns {@code true} iff a mutator with {@code id} is registered.
     *
     * @param id the mutator id to check; non-null
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean contains(String id);

    /**
     * Mutable builder passed to each {@link StateMutatorContributor} during registry construction.
     */
    interface Builder {

        /**
         * Registers a named state mutator.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param mutator the named state mutator to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code mutator.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedStateMutator<S> mutator);
    }
}

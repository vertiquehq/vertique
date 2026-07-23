// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named state reducers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code BiFunction<S, Object, S>} that folds an
 * incoming event into the current workflow state, producing the new state. The workflow-definition
 * compiler references reducer ids declared in definition documents; this registry resolves them to
 * the actual functions at load time.
 *
 * <p>Population: applications contribute {@link StateReducerContributor}s into the
 * {@code Set<StateReducerContributor>} Dagger multibinding. The
 * {@link DefaultStateReducerRegistry} singleton aggregates them once at construction time.
 *
 * @see NamedStateReducer
 * @see StateReducerContributor
 */
public interface StateReducerRegistry {

    /**
     * Looks up the named state reducer for the given id.
     *
     * @param id the reducer id declared in the workflow definition document; non-null
     * @return the registered {@link NamedStateReducer}, never null
     * @throws WorkflowDefinitionException if no reducer is registered with {@code id}
     */
    NamedStateReducer<?> lookup(String id);

    /**
     * Returns all registered reducer ids.
     *
     * @return unmodifiable set of ids; empty if no contributors registered any reducers
     */
    Set<String> ids();

    /**
     * Returns {@code true} iff a reducer with {@code id} is registered.
     *
     * @param id the reducer id to check; non-null
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean contains(String id);

    /**
     * Mutable builder passed to each {@link StateReducerContributor} during registry construction.
     */
    interface Builder {

        /**
         * Registers a named state reducer.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param reducer the named state reducer to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code reducer.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedStateReducer<S> reducer);
    }
}

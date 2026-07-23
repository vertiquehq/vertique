// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.BranchResult;
import java.util.Set;

/**
 * Registry of named branch-result reducers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code BiFunction<S, Map<String, BranchResult>, S>}
 * that folds all branch outcomes into the current workflow state after a join node completes.
 * The workflow-definition compiler references reducer ids declared in definition documents.
 *
 * <p>Population: applications contribute {@link BranchResultReducerContributor}s into the
 * {@code Set<BranchResultReducerContributor>} Dagger multibinding.
 *
 * @see NamedBranchResultReducer
 * @see BranchResultReducerContributor
 * @see BranchResult
 */
public interface BranchResultReducerRegistry {

    /**
     * Looks up the named branch-result reducer for the given id.
     *
     * @param id the reducer id declared in the workflow definition document; non-null
     * @return the registered {@link NamedBranchResultReducer}, never null
     * @throws WorkflowDefinitionException if no reducer is registered with {@code id}
     */
    NamedBranchResultReducer<?> lookup(String id);

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
     * Mutable builder passed to each {@link BranchResultReducerContributor} during registry
     * construction.
     */
    interface Builder {

        /**
         * Registers a named branch-result reducer.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param reducer the named branch-result reducer to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code reducer.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedBranchResultReducer<S> reducer);
    }
}

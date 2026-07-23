// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named conditions contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, Boolean>} that evaluates a
 * boolean predicate against the current workflow state. Named conditions provide a safe,
 * non-scripted alternative to inline expressions for workflow routing decisions.
 *
 * <p>Population: applications contribute {@link NamedConditionContributor}s into the
 * {@code Set<NamedConditionContributor>} Dagger multibinding.
 *
 * @see NamedCondition
 * @see NamedConditionContributor
 */
public interface NamedConditionRegistry {

    /**
     * Looks up the named condition for the given id.
     *
     * @param id the condition id declared in the workflow definition document; non-null
     * @return the registered {@link NamedCondition}, never null
     * @throws WorkflowDefinitionException if no condition is registered with {@code id}
     */
    NamedCondition<?> lookup(String id);

    /**
     * Returns all registered condition ids.
     *
     * @return unmodifiable set of ids; empty if no contributors registered any conditions
     */
    Set<String> ids();

    /**
     * Returns {@code true} iff a condition with {@code id} is registered.
     *
     * @param id the condition id to check; non-null
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean contains(String id);

    /**
     * Mutable builder passed to each {@link NamedConditionContributor} during registry
     * construction.
     */
    interface Builder {

        /**
         * Registers a named condition.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param condition the named condition to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code condition.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedCondition<S> condition);
    }
}

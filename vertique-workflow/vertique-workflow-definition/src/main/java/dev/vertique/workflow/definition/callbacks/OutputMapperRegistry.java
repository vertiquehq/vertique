// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named output mappers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, O>} that maps the final workflow
 * state to the output the definition declares. A document names an entry in {@code outputMapper}
 * together with the output type in {@code outputType}.
 *
 * <p>Population: applications contribute {@link OutputMapperContributor}s into the
 * {@code Set<OutputMapperContributor>} Dagger multibinding.
 *
 * @see NamedOutputMapper
 * @see OutputMapperContributor
 */
public interface OutputMapperRegistry {

    /**
     * Looks up the named output mapper for the given id.
     *
     * @param id the mapper id declared in the workflow definition document; non-null
     * @return the registered {@link NamedOutputMapper}, never null
     * @throws WorkflowDefinitionException if no mapper is registered with {@code id}
     */
    NamedOutputMapper<?, ?> lookup(String id);

    /**
     * Returns all registered mapper ids.
     *
     * @return unmodifiable set of ids; empty if no contributors registered any mappers
     */
    Set<String> ids();

    /**
     * Returns {@code true} iff a mapper with {@code id} is registered.
     *
     * @param id the mapper id to check; non-null
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean contains(String id);

    /**
     * Mutable builder passed to each {@link OutputMapperContributor} during registry
     * construction.
     */
    interface Builder {

        /**
         * Registers a named output mapper.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <S> the workflow state type
         * @param <O> the output type
         * @param mapper the named output mapper to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code mapper.id()} is already registered with a
         *     different record
         */
        <S, O> Builder register(NamedOutputMapper<S, O> mapper);
    }
}

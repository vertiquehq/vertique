// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named start-state mappers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<P, S>} that transforms the
 * incoming workflow start payload into the initial workflow state. This registry is separate from
 * {@link PayloadMapperRegistry} because the function signature differs.
 *
 * <p>Population: applications contribute {@link StartStateMapperContributor}s into the
 * {@code Set<StartStateMapperContributor>} Dagger multibinding. The
 * {@link DefaultStartStateMapperRegistry} singleton aggregates them once at construction time.
 *
 * @see NamedStartStateMapper
 * @see StartStateMapperContributor
 */
public interface StartStateMapperRegistry {

    /**
     * Looks up the named start-state mapper for the given id.
     *
     * @param id the mapper id declared in the workflow definition document; non-null
     * @return the registered {@link NamedStartStateMapper}, never null
     * @throws WorkflowDefinitionException if no mapper is registered with {@code id}; the
     *     exception message includes the unknown id and the set of known ids for diagnostics
     */
    NamedStartStateMapper<?, ?> lookup(String id);

    /**
     * Returns all registered mapper ids.
     *
     * <p>Used for diagnostics such as nearest-neighbour suggestions when a lookup fails.
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
     * Mutable builder passed to each {@link StartStateMapperContributor} during registry
     * construction.
     *
     * <p>After all contributors run the builder is sealed; the resulting registry is read-only.
     */
    interface Builder {

        /**
         * Registers a named start-state mapper.
         *
         * <p>Same-record re-registration is a no-op; different record for the same id throws
         * {@link IllegalStateException} at construction time.
         *
         * @param <P> the start payload type
         * @param <S> the workflow state type
         * @param mapper the named start-state mapper to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code mapper.id()} is already registered with a
         *     different record
         */
        <P, S> Builder register(NamedStartStateMapper<P, S> mapper);
    }
}

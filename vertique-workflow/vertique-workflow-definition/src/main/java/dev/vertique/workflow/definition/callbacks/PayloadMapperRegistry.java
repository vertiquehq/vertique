// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;

/**
 * Registry of named payload mappers contributed by applications at startup.
 *
 * <p>Each entry maps a stable string id to a {@code Function<S, Object>} that transforms the
 * current workflow state into the service-call payload for a {@code ServiceDispatchNode}.
 * The workflow-definition compiler references mapper ids declared in definition documents;
 * the registry resolves ids to the actual functions at load time.
 *
 * <p>Population: applications contribute {@link PayloadMapperContributor}s into the
 * {@code Set<PayloadMapperContributor>} Dagger multibinding. The
 * {@link DefaultPayloadMapperRegistry} singleton aggregates them once at construction time.
 *
 * @see NamedPayloadMapper
 * @see PayloadMapperContributor
 */
public interface PayloadMapperRegistry {

    /**
     * Looks up the named payload mapper for the given id.
     *
     * @param id the mapper id declared in the workflow definition document; non-null
     * @return the registered {@link NamedPayloadMapper}, never null
     * @throws WorkflowDefinitionException if no mapper is registered with {@code id}; the
     *     exception message includes the unknown id and the set of known ids for diagnostics
     */
    NamedPayloadMapper<?> lookup(String id);

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
     * Mutable builder passed to each {@link PayloadMapperContributor} during registry construction.
     *
     * <p>After all contributors run the builder is sealed; the resulting registry is read-only.
     */
    interface Builder {

        /**
         * Registers a named payload mapper.
         *
         * <p>If the same {@link NamedPayloadMapper} record (by {@link Object#equals equals}) has
         * already been registered, this call is a no-op. If the same id is registered with a
         * different record, {@link IllegalStateException} is thrown at construction time.
         *
         * @param <S> the workflow state type
         * @param mapper the named payload mapper to register; non-null
         * @return this builder
         * @throws IllegalStateException if {@code mapper.id()} is already registered with a
         *     different record
         */
        <S> Builder register(NamedPayloadMapper<S> mapper);
    }
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code @Singleton} implementation of {@link StateReducerRegistry} populated from the set of
 * {@link StateReducerContributor}s contributed via Dagger multibinding.
 *
 * <p>Same construction-time semantics and conflict policy as
 * {@link DefaultPayloadMapperRegistry}: same-record re-registration is a no-op; different record
 * for the same id throws {@link IllegalStateException}.
 */
@Singleton
public final class DefaultStateReducerRegistry implements StateReducerRegistry {

    // --- State ---

    private final Map<String, NamedStateReducer<?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link StateReducerContributor}s; an empty
     *     set is valid
     */
    @Inject
    public DefaultStateReducerRegistry(Set<StateReducerContributor> contributors) {
        Builder b = new Builder();
        for (StateReducerContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- StateReducerRegistry ---

    @Override
    public NamedStateReducer<?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedStateReducer<?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named state-reducer '" + id + "' is not registered; known ids: " + byId.keySet());
        }
        return result;
    }

    @Override
    public Set<String> ids() {
        return byId.keySet();
    }

    @Override
    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    // --- Builder ---

    /**
     * Mutable builder used during construction and exposed to contributors.
     */
    private static final class Builder implements StateReducerRegistry.Builder {

        private final Map<String, NamedStateReducer<?>> byId = new HashMap<>();

        @Override
        public <S> Builder register(NamedStateReducer<S> reducer) {
            Objects.requireNonNull(reducer, "reducer");
            NamedStateReducer<?> existing = byId.putIfAbsent(reducer.id(), reducer);
            if (existing != null && !existing.equals(reducer)) {
                throw new IllegalStateException("conflicting state-reducer declarations for id '" + reducer.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + reducer + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

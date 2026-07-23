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
 * {@code @Singleton} implementation of {@link StateMutatorRegistry} populated from the set of
 * {@link StateMutatorContributor}s contributed via Dagger multibinding.
 *
 * <p>Same construction-time semantics and conflict policy as
 * {@link DefaultPayloadMapperRegistry}: same-record re-registration is a no-op; different record
 * for the same id throws {@link IllegalStateException}.
 */
@Singleton
public final class DefaultStateMutatorRegistry implements StateMutatorRegistry {

    // --- State ---

    private final Map<String, NamedStateMutator<?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link StateMutatorContributor}s; an empty
     *     set is valid
     */
    @Inject
    public DefaultStateMutatorRegistry(Set<StateMutatorContributor> contributors) {
        Builder b = new Builder();
        for (StateMutatorContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- StateMutatorRegistry ---

    @Override
    public NamedStateMutator<?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedStateMutator<?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named state-mutator '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements StateMutatorRegistry.Builder {

        private final Map<String, NamedStateMutator<?>> byId = new HashMap<>();

        @Override
        public <S> Builder register(NamedStateMutator<S> mutator) {
            Objects.requireNonNull(mutator, "mutator");
            NamedStateMutator<?> existing = byId.putIfAbsent(mutator.id(), mutator);
            if (existing != null && !existing.equals(mutator)) {
                throw new IllegalStateException("conflicting state-mutator declarations for id '" + mutator.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + mutator + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

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
 * {@code @Singleton} implementation of {@link OutputMapperRegistry} populated from the set of
 * {@link OutputMapperContributor}s contributed via Dagger multibinding.
 *
 * <p>Same construction-time semantics and conflict policy as
 * {@link DefaultPayloadMapperRegistry}: same-record re-registration is a no-op; different record
 * for the same id throws {@link IllegalStateException}.
 */
@Singleton
public final class DefaultOutputMapperRegistry implements OutputMapperRegistry {

    // --- State ---

    private final Map<String, NamedOutputMapper<?, ?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link OutputMapperContributor}s; an
     *     empty set is valid
     */
    @Inject
    public DefaultOutputMapperRegistry(Set<OutputMapperContributor> contributors) {
        Builder b = new Builder();
        for (OutputMapperContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- OutputMapperRegistry ---

    @Override
    public NamedOutputMapper<?, ?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedOutputMapper<?, ?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named output-mapper '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements OutputMapperRegistry.Builder {

        private final Map<String, NamedOutputMapper<?, ?>> byId = new HashMap<>();

        @Override
        public <S, O> Builder register(NamedOutputMapper<S, O> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            NamedOutputMapper<?, ?> existing = byId.putIfAbsent(mapper.id(), mapper);
            if (existing != null && !existing.equals(mapper)) {
                throw new IllegalStateException("conflicting output-mapper declarations for id '" + mapper.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + mapper + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

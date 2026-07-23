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
 * {@code @Singleton} implementation of {@link StartStateMapperRegistry} populated from the set of
 * {@link StartStateMapperContributor}s contributed via Dagger multibinding.
 *
 * <p>Construction-time semantics:
 * <ol>
 *   <li>Each contributor is invoked exactly once with a fresh internal {@link Builder}.</li>
 *   <li>Contributors register {@link NamedStartStateMapper} records keyed by id.</li>
 *   <li>After all contributors run the builder is sealed; the resulting map is immutable.</li>
 * </ol>
 *
 * <p>Conflict policy: registering the same id with a different record throws
 * {@link IllegalStateException} at construction; same-record re-registration is a no-op.
 */
@Singleton
public final class DefaultStartStateMapperRegistry implements StartStateMapperRegistry {

    // --- State ---

    private final Map<String, NamedStartStateMapper<?, ?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link StartStateMapperContributor}s; an
     *     empty set is valid
     */
    @Inject
    public DefaultStartStateMapperRegistry(Set<StartStateMapperContributor> contributors) {
        Builder b = new Builder();
        for (StartStateMapperContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- StartStateMapperRegistry ---

    @Override
    public NamedStartStateMapper<?, ?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedStartStateMapper<?, ?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named start-state-mapper '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements StartStateMapperRegistry.Builder {

        private final Map<String, NamedStartStateMapper<?, ?>> byId = new HashMap<>();

        @Override
        public <P, S> Builder register(NamedStartStateMapper<P, S> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            NamedStartStateMapper<?, ?> existing = byId.putIfAbsent(mapper.id(), mapper);
            if (existing != null && !existing.equals(mapper)) {
                throw new IllegalStateException("conflicting start-state-mapper declarations for id '" + mapper.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + mapper + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

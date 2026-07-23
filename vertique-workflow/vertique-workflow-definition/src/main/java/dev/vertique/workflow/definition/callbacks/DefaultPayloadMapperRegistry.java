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
 * {@code @Singleton} implementation of {@link PayloadMapperRegistry} populated from the set of
 * {@link PayloadMapperContributor}s contributed via Dagger multibinding.
 *
 * <p>Construction-time semantics:
 * <ol>
 *   <li>Each contributor is invoked exactly once with a fresh internal {@link Builder}.</li>
 *   <li>Contributors register {@link NamedPayloadMapper} records keyed by id.</li>
 *   <li>After all contributors run the builder is sealed; the resulting map is immutable and
 *       lookups are O(1).</li>
 * </ol>
 *
 * <p>Conflict policy: registering the same id with a different record throws
 * {@link IllegalStateException} at construction; same-record re-registration is a no-op.
 */
@Singleton
public final class DefaultPayloadMapperRegistry implements PayloadMapperRegistry {

    // --- State ---

    private final Map<String, NamedPayloadMapper<?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link PayloadMapperContributor}s; an empty
     *     set is valid
     */
    @Inject
    public DefaultPayloadMapperRegistry(Set<PayloadMapperContributor> contributors) {
        Builder b = new Builder();
        for (PayloadMapperContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- PayloadMapperRegistry ---

    @Override
    public NamedPayloadMapper<?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedPayloadMapper<?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named payload-mapper '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements PayloadMapperRegistry.Builder {

        private final Map<String, NamedPayloadMapper<?>> byId = new HashMap<>();

        @Override
        public <S> Builder register(NamedPayloadMapper<S> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            NamedPayloadMapper<?> existing = byId.putIfAbsent(mapper.id(), mapper);
            if (existing != null && !existing.equals(mapper)) {
                throw new IllegalStateException(
                        "conflicting payload-mapper declarations for id '" + mapper.id() + "': already registered as ["
                                + existing + "], attempted to re-register as [" + mapper + "];"
                                + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

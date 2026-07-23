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
 * {@code @Singleton} implementation of {@link FailMessageFactoryRegistry} populated from the set
 * of {@link FailMessageFactoryContributor}s contributed via Dagger multibinding.
 *
 * <p>Same construction-time semantics and conflict policy as
 * {@link DefaultPayloadMapperRegistry}: same-record re-registration is a no-op; different record
 * for the same id throws {@link IllegalStateException}.
 */
@Singleton
public final class DefaultFailMessageFactoryRegistry implements FailMessageFactoryRegistry {

    // --- State ---

    private final Map<String, NamedFailMessageFactory<?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link FailMessageFactoryContributor}s; an
     *     empty set is valid
     */
    @Inject
    public DefaultFailMessageFactoryRegistry(Set<FailMessageFactoryContributor> contributors) {
        Builder b = new Builder();
        for (FailMessageFactoryContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- FailMessageFactoryRegistry ---

    @Override
    public NamedFailMessageFactory<?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedFailMessageFactory<?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named fail-message-factory '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements FailMessageFactoryRegistry.Builder {

        private final Map<String, NamedFailMessageFactory<?>> byId = new HashMap<>();

        @Override
        public <S> Builder register(NamedFailMessageFactory<S> factory) {
            Objects.requireNonNull(factory, "factory");
            NamedFailMessageFactory<?> existing = byId.putIfAbsent(factory.id(), factory);
            if (existing != null && !existing.equals(factory)) {
                throw new IllegalStateException("conflicting fail-message-factory declarations for id '" + factory.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + factory + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

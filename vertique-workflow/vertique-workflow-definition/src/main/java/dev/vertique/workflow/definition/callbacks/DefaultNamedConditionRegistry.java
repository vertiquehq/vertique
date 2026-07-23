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
 * {@code @Singleton} implementation of {@link NamedConditionRegistry} populated from the set of
 * {@link NamedConditionContributor}s contributed via Dagger multibinding.
 *
 * <p>Same construction-time semantics and conflict policy as
 * {@link DefaultPayloadMapperRegistry}: same-record re-registration is a no-op; different record
 * for the same id throws {@link IllegalStateException}.
 */
@Singleton
public final class DefaultNamedConditionRegistry implements NamedConditionRegistry {

    // --- State ---

    private final Map<String, NamedCondition<?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link NamedConditionContributor}s; an
     *     empty set is valid
     */
    @Inject
    public DefaultNamedConditionRegistry(Set<NamedConditionContributor> contributors) {
        Builder b = new Builder();
        for (NamedConditionContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- NamedConditionRegistry ---

    @Override
    public NamedCondition<?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedCondition<?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named-condition '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements NamedConditionRegistry.Builder {

        private final Map<String, NamedCondition<?>> byId = new HashMap<>();

        @Override
        public <S> Builder register(NamedCondition<S> condition) {
            Objects.requireNonNull(condition, "condition");
            NamedCondition<?> existing = byId.putIfAbsent(condition.id(), condition);
            if (existing != null && !existing.equals(condition)) {
                throw new IllegalStateException("conflicting named-condition declarations for id '" + condition.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + condition + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

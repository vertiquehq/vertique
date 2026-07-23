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
 * {@code @Singleton} implementation of {@link TimerResolverRegistry} populated from the set of
 * {@link TimerResolverContributor}s contributed via Dagger multibinding.
 *
 * <p>Same construction-time semantics and conflict policy as
 * {@link DefaultPayloadMapperRegistry}: same-record re-registration is a no-op; different record
 * for the same id throws {@link IllegalStateException}.
 */
@Singleton
public final class DefaultTimerResolverRegistry implements TimerResolverRegistry {

    // --- State ---

    private final Map<String, NamedTimerResolver<?>> byId;

    // --- Construction ---

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link TimerResolverContributor}s; an empty
     *     set is valid
     */
    @Inject
    public DefaultTimerResolverRegistry(Set<TimerResolverContributor> contributors) {
        Builder b = new Builder();
        for (TimerResolverContributor c : contributors) {
            c.contribute(b);
        }
        this.byId = Map.copyOf(b.byId);
    }

    // --- TimerResolverRegistry ---

    @Override
    public NamedTimerResolver<?> lookup(String id) {
        Objects.requireNonNull(id, "id");
        NamedTimerResolver<?> result = byId.get(id);
        if (result == null) {
            throw new WorkflowDefinitionException(
                    "named timer-resolver '" + id + "' is not registered; known ids: " + byId.keySet());
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
    private static final class Builder implements TimerResolverRegistry.Builder {

        private final Map<String, NamedTimerResolver<?>> byId = new HashMap<>();

        @Override
        public <S> Builder register(NamedTimerResolver<S> resolver) {
            Objects.requireNonNull(resolver, "resolver");
            NamedTimerResolver<?> existing = byId.putIfAbsent(resolver.id(), resolver);
            if (existing != null && !existing.equals(resolver)) {
                throw new IllegalStateException("conflicting timer-resolver declarations for id '" + resolver.id()
                        + "': already registered as [" + existing + "], attempted to re-register as ["
                        + resolver + "];"
                        + " remove the duplicate contributor or align on a single registration");
            }
            return this;
        }
    }
}

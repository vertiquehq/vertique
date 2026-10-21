// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers, for each registered definition scope, id and version, the exact {@link RuntimeWorkflow} instance the
 * durable lifecycle published for it.
 *
 * <p>The public registry has no notion of who published a registration, and two scopes can store identical
 * documents under the same id and version. Attribution is therefore never inferred from equal hashes: a managed
 * mutation acts on a registration only when it is attributed here and the registry still holds that very instance.
 * A runtime that was registered directly, or that replaced an attributed one, has no matching attribution and is
 * reported by {@link #classify} so the caller can refuse to touch it.
 *
 * <p>Every managed publisher records its publication here, the startup step records the registrations that
 * already exist when it starts as default-scope ones, and every managed removal clears the entry it removed. Calls
 * that read or change an id are made under that id's {@link DefinitionApplyQueue} slot.
 */
@Singleton
final class ManagedRegistrations {
    private final ConcurrentMap<VersionKey, RuntimeWorkflow> attributions = new ConcurrentHashMap<>();

    @Inject
    ManagedRegistrations() {}

    /** Records that the lifecycle published {@code runtime} for the key, replacing any older entry. */
    void attribute(String scopeKey, String definitionId, long version, RuntimeWorkflow runtime) {
        attributions.put(new VersionKey(scopeKey, definitionId, version), Objects.requireNonNull(runtime));
    }

    /**
     * Attributes every registration already present in {@code registry} to the default scope, keeping any entry
     * that exists. These are the packaged static documents and application-contributed definitions that were
     * registered while the registry was constructed.
     */
    void attributeExistingToDefaultScope(WorkflowRegistry registry) {
        for (RuntimeWorkflow runtime : registry.allRegistered())
            attributions.putIfAbsent(
                    new VersionKey(
                            WorkflowScopes.DEFAULT,
                            runtime.plan().definitionId(),
                            runtime.plan().definitionVersion()),
                    runtime);
    }

    /** Forgets the entry for this scope, id and version, but only while it still names exactly {@code runtime}. */
    void clear(String scopeKey, String definitionId, long version, RuntimeWorkflow runtime) {
        attributions.computeIfPresent(
                new VersionKey(scopeKey, definitionId, version), (ignored, entry) -> entry == runtime ? null : entry);
    }

    /**
     * Compares what the registry holds for a scope, id and version against the attribution.
     *
     * @param held the runtime the registry holds for the version, or {@code null} when it holds none
     */
    Standing classify(String scopeKey, String definitionId, long version, @Nullable RuntimeWorkflow held) {
        if (held == null) return Standing.ABSENT;
        return attributions.get(new VersionKey(scopeKey, definitionId, version)) == held
                ? Standing.OWN
                : Standing.UNATTRIBUTED;
    }

    /** How the registration held for a scope, id and version relates to what the lifecycle published. */
    enum Standing {
        /** The registry holds nothing for the version. */
        ABSENT,
        /** The registry holds exactly the runtime the lifecycle published for this key. */
        OWN,
        /** The registry holds a runtime the lifecycle did not publish, or one that replaced the published one. */
        UNATTRIBUTED
    }

    private record VersionKey(String scopeKey, String definitionId, long version) {}
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers, for each registered definition id and version, which owner the durable lifecycle published it
 * for and the exact {@link RuntimeWorkflow} instance it published.
 *
 * <p>The public registry has no notion of an owner, and two owners can store identical documents under the
 * same id and version. Ownership is therefore never inferred from equal hashes: a managed mutation acts on a
 * registration only when it is attributed here and the registry still holds that very instance. A runtime
 * that was registered directly, or that replaced an attributed one, has no matching attribution and is
 * reported by {@link #classify} so the caller can refuse to touch it.
 *
 * <p>Every managed publisher records its publication here, the startup step records the registrations that
 * already exist when it starts as global ones, and every managed removal clears the entry it removed. Calls
 * that read or change an id are made under that id's {@link DefinitionApplyQueue} slot.
 */
@Singleton
final class ManagedRegistrations {
    private final ConcurrentMap<VersionKey, Attribution> attributions = new ConcurrentHashMap<>();

    @Inject
    ManagedRegistrations() {}

    /** Records that the lifecycle published {@code runtime} for {@code tenantKey}, replacing any older entry. */
    void attribute(@Nullable String tenantKey, String definitionId, long version, RuntimeWorkflow runtime) {
        attributions.put(
                new VersionKey(definitionId, version), new Attribution(tenantKey, Objects.requireNonNull(runtime)));
    }

    /**
     * Attributes every registration already present in {@code registry} to the global owner, keeping any entry
     * that exists. These are the packaged static documents and application-contributed definitions that were
     * registered while the registry was constructed.
     */
    void attributeExistingToGlobal(WorkflowRegistry registry) {
        for (RuntimeWorkflow runtime : registry.allRegistered())
            attributions.putIfAbsent(
                    new VersionKey(runtime.plan().definitionId(), runtime.plan().definitionVersion()),
                    new Attribution(null, runtime));
    }

    /** Forgets the entry for this id and version, but only while it still names exactly {@code runtime}. */
    void clear(String definitionId, long version, RuntimeWorkflow runtime) {
        VersionKey key = new VersionKey(definitionId, version);
        attributions.computeIfPresent(key, (ignored, entry) -> entry.runtime() == runtime ? null : entry);
    }

    /**
     * Compares what the registry holds for an id and version against the attribution.
     *
     * @param tenantKey the owner the caller acts for, or {@code null} for the global owner
     * @param held the runtime the registry holds for the version, or {@code null} when it holds none
     */
    Standing classify(@Nullable String tenantKey, String definitionId, long version, @Nullable RuntimeWorkflow held) {
        if (held == null) return Standing.ABSENT;
        Attribution entry = attributions.get(new VersionKey(definitionId, version));
        if (entry == null || entry.runtime() != held) return Standing.UNATTRIBUTED;
        return Objects.equals(entry.tenantKey(), tenantKey) ? Standing.OWN : Standing.OTHER_OWNER;
    }

    /** How the registration held for an id and version relates to the owner a managed mutation acts for. */
    enum Standing {
        /** The registry holds nothing for the version. */
        ABSENT,
        /** The registry holds exactly the runtime the lifecycle published for this owner. */
        OWN,
        /** The registry holds exactly the runtime the lifecycle published for a different owner. */
        OTHER_OWNER,
        /** The registry holds a runtime the lifecycle did not publish, or one that replaced the published one. */
        UNATTRIBUTED
    }

    private record VersionKey(String definitionId, long version) {}

    private record Attribution(@Nullable String tenantKey, RuntimeWorkflow runtime) {}
}

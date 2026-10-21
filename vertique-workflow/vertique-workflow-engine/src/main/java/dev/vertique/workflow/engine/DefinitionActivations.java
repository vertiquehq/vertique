// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This node's view of the activation records: the current version of a definition id in each scope that has a
 * record, keyed by scope and id. The lifecycle updates it after the records are committed, or alone when there is no
 * durable registry, and a start without a version reads it to choose the version to run.
 *
 * <p>The view keeps a record even when this node has no code for the version the record names; such a start fails
 * like an explicit start of that version.
 *
 * <p>A start consults only the start scope's record, then the default scope's record, and then
 * {@link #fallback}. Nothing here weights, samples or splits traffic between versions.
 */
@Singleton
final class DefinitionActivations {
    private record Slot(String scopeKey, String definitionId) {}

    private final ConcurrentMap<Slot, DefinitionActivation> records = new ConcurrentHashMap<>();

    @Inject
    DefinitionActivations() {}

    /** Stores the record, replacing the scope's earlier record of the id. */
    void put(DefinitionActivation activation) {
        records.put(new Slot(activation.scopeKey(), activation.definitionId()), activation);
    }

    /** Removes the scope's record of the id; an absent record is a no-op. */
    void remove(String scopeKey, String definitionId) {
        records.remove(new Slot(scopeKey, definitionId));
    }

    /** The records of one scope, or of every scope when {@code scopeKey} is {@code null}, ordered by id and scope. */
    List<DefinitionActivation> list(@Nullable String scopeKey) {
        return records.values().stream()
                .filter(activation -> scopeKey == null || scopeKey.equals(activation.scopeKey()))
                .sorted(Comparator.comparing(DefinitionActivation::definitionId)
                        .thenComparing(DefinitionActivation::scopeKey))
                .toList();
    }

    /**
     * Chooses the runtime a start without a version runs: the version the start scope's record names, else the one
     * the default scope's record names, else the fallback. A record names a version of the default scope or of its
     * own scope, and the runtime is resolved from the registry like an explicit start of that version.
     *
     * @throws WorkflowNoCurrentVersionException when there is no record and the fallback finds no version
     */
    RuntimeWorkflow resolve(WorkflowRegistry registry, String startScope, String definitionId) {
        DefinitionActivation record = records.get(new Slot(startScope, definitionId));
        if (record == null) record = records.get(new Slot(WorkflowScopes.DEFAULT, definitionId));
        if (record == null) return fallback(registry, startScope, definitionId);
        return registry.resolve(new DefinitionKey(record.targetScopeKey(), definitionId, record.version()));
    }

    /**
     * The only choice made without a record: the highest version of the default scope that is not deprecated. It is
     * used while the default scope has no record, and it never looks at the versions of any other scope.
     */
    private static RuntimeWorkflow fallback(WorkflowRegistry registry, String startScope, String definitionId) {
        try {
            return registry.resolveCurrent(definitionId);
        } catch (WorkflowDefinitionMissingException none) {
            throw new WorkflowNoCurrentVersionException(startScope, definitionId);
        }
    }
}

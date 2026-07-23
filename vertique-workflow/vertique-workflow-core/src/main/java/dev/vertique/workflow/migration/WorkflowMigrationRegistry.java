// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of application-contributed {@link WorkflowMigrationHandler} instances.
 *
 * <p>The default implementation, {@link DefaultWorkflowMigrationRegistry}, is populated at
 * Dagger construction time from the {@code Set<WorkflowMigrationHandler<?, ?>>} multibinding
 * declared by {@link WorkflowMigrationModule}. All handlers are validated against the
 * {@link dev.vertique.workflow.registry.WorkflowRegistry} at construction — the registry is
 * read-only after construction.
 *
 * <p>The engine consults this registry during instance migration triggered by
 * {@link dev.vertique.workflow.ops.WorkflowOperations#migrate}.
 */
public interface WorkflowMigrationRegistry {

    /**
     * Finds the handler registered for the given {@code (definitionId, sourceVersion, targetVersion)}
     * triple.
     *
     * @param definitionId workflow definition id; non-null
     * @param sourceVersion the version the instance is currently pinned to
     * @param targetVersion the version to migrate the instance to
     * @return an {@link Optional} containing the matching handler, or {@link Optional#empty()} if
     *     no handler is registered for the triple
     */
    Optional<WorkflowMigrationHandler<?, ?>> find(String definitionId, long sourceVersion, long targetVersion);

    /**
     * Returns an unmodifiable view of all registered handlers across all definition ids and
     * version pairs.
     *
     * @return unmodifiable collection of all handlers; never {@code null}, may be empty
     */
    Collection<WorkflowMigrationHandler<?, ?>> all();
}

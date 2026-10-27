// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link WorkflowMigrationRegistry} implementation that indexes all
 * application-contributed {@link WorkflowMigrationHandler} instances at construction time.
 *
 * <h2>Validation rules at construction</h2>
 * <p>The following rules are enforced immediately, without consulting the workflow registry:
 * <ol>
 *   <li>When {@link WorkflowMigrationHandler#sourceScope()} equals
 *       {@link WorkflowMigrationHandler#targetScope()},
 *       {@link WorkflowMigrationHandler#fromDefinitionVersion()} must be strictly less than
 *       {@link WorkflowMigrationHandler#targetDefinitionVersion()} (downgrades are forbidden
 *       in v1). A handler between two scopes is not ordered by version.</li>
 *   <li>No two handlers may share the same
 *       {@code (sourceScope, definitionId, fromVersion, targetScope, targetVersion)} key.</li>
 * </ol>
 *
 * <h2>Lookup</h2>
 * <p>{@link #find} resolves no plan: a definition version may be stored without being resident, so the lookup answers
 * from the handler index alone. The handler's declared state types are compared with the plans' state types where the
 * migration executes, not here.
 *
 * <p>After construction the internal map is immutable; {@link #find} and {@link #all} are safe
 * to call from any thread without external synchronization.
 */
@Singleton
public class DefaultWorkflowMigrationRegistry implements WorkflowMigrationRegistry {

    /**
     * Internal key for the {@code (sourceScope, definitionId, fromVersion, targetScope, targetVersion)} tuple.
     *
     * @param sourceScope the scope of the source definition version
     * @param definitionId the workflow definition id
     * @param fromVersion the source definition version
     * @param targetScope the scope of the target definition version
     * @param targetVersion the target definition version
     */
    private record MigrationKey(
            String sourceScope, String definitionId, long fromVersion, String targetScope, long targetVersion) {}

    private final Map<MigrationKey, WorkflowMigrationHandler<?, ?>> handlers;

    /**
     * Creates the registry from the contributed handler set.
     *
     * <p>Validates only the rules that do not require the registry (version direction within one scope, no
     * duplicate keys).
     *
     * @param migrationHandlers the set of contributed migration handlers; may be empty
     * @param workflowRegistry accepted for constructor compatibility; the registry is not consulted
     * @throws WorkflowDefinitionException if any handler violates the construction-time rules
     *     (downgrade within one scope or duplicate key)
     */
    @Inject
    public DefaultWorkflowMigrationRegistry(
            Set<WorkflowMigrationHandler<?, ?>> migrationHandlers, WorkflowRegistry workflowRegistry) {
        Map<MigrationKey, WorkflowMigrationHandler<?, ?>> map = new HashMap<>();
        for (WorkflowMigrationHandler<?, ?> handler : migrationHandlers) {
            indexHandler(handler, map);
        }
        this.handlers = Collections.unmodifiableMap(map);
    }

    // --- WorkflowMigrationRegistry ---

    /**
     * Returns the handler registered for the given
     * {@code (sourceScope, definitionId, sourceVersion, targetScope, targetVersion)} key.
     *
     * <p>The handler is returned whether or not either version is resident in the {@link WorkflowRegistry}; the lookup
     * resolves no plan. Whether the handler's declared state types match the plans is checked where the migration
     * executes. {@link Optional#empty()} means only that no handler is registered for the key.
     *
     * @param sourceScope the scope of the source definition version
     * @param definitionId the workflow definition id
     * @param sourceVersion the source definition version
     * @param targetScope the scope of the target definition version
     * @param targetVersion the target definition version
     * @return the matching handler, or {@link Optional#empty()} if none is registered for the key
     */
    @Override
    public Optional<WorkflowMigrationHandler<?, ?>> find(
            String sourceScope, String definitionId, long sourceVersion, String targetScope, long targetVersion) {
        return Optional.ofNullable(
                handlers.get(new MigrationKey(sourceScope, definitionId, sourceVersion, targetScope, targetVersion)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<WorkflowMigrationHandler<?, ?>> all() {
        return handlers.values();
    }

    // --- Private helpers ---

    /**
     * Validates a single handler against construction-time rules and inserts it into {@code map}.
     *
     * <p>Only rules that do not require the registry are enforced here:
     * <ol>
     *   <li>Version direction -- {@code fromVersion < targetVersion} when both scopes are equal.</li>
     *   <li>No duplicate {@code (sourceScope, definitionId, fromVersion, targetScope, targetVersion)} key.</li>
     * </ol>
     *
     * @param handler the handler to index
     * @param map accumulator into which the handler is inserted
     * @throws WorkflowDefinitionException if any construction-time validation rule is violated
     */
    private static void indexHandler(
            WorkflowMigrationHandler<?, ?> handler, Map<MigrationKey, WorkflowMigrationHandler<?, ?>> map) {

        String sourceScope = handler.sourceScope();
        String targetScope = handler.targetScope();
        String defId = handler.definitionId();
        long fromVersion = handler.fromDefinitionVersion();
        long targetVersion = handler.targetDefinitionVersion();

        // Rule: fromVersion < targetVersion within one scope (downgrades forbidden in v1)
        if (sourceScope.equals(targetScope) && fromVersion >= targetVersion) {
            throw new WorkflowDefinitionException("Migration handler "
                    + handler.getClass().getName()
                    + " for definition '" + defId + "' in scope '" + sourceScope + "' has fromVersion=" + fromVersion
                    + " >= targetVersion=" + targetVersion
                    + "; downgrades and same-version migrations are forbidden");
        }

        // Rule: no duplicate (sourceScope, definitionId, fromVersion, targetScope, targetVersion) key
        MigrationKey key = new MigrationKey(sourceScope, defId, fromVersion, targetScope, targetVersion);
        WorkflowMigrationHandler<?, ?> existing = map.putIfAbsent(key, handler);
        if (existing != null) {
            throw new WorkflowDefinitionException("Duplicate migration handler for definition '" + defId
                    + "' from scope '" + sourceScope + "' version " + fromVersion + " to scope '" + targetScope
                    + "' version " + targetVersion
                    + ": " + existing.getClass().getName() + " and "
                    + handler.getClass().getName());
        }
    }
}

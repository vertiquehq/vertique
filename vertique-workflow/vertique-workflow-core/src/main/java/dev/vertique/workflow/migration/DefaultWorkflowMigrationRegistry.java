// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowMigrationStateTypeMismatchException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
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
 * application-contributed {@link WorkflowMigrationHandler} instances at construction time and
 * performs state-type + version compatibility checks lazily at {@link #find} time.
 *
 * <h2>Deferred validation rationale</h2>
 * <p>Validating both source and target versions against {@link WorkflowRegistry#resolvePinned}
 * inside the constructor would make it impossible to register a {@code v1 -> v2} migration handler
 * when only {@code v1} is statically contributed to the Dagger graph at startup time and {@code v2}
 * is loaded later via
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionService#activate}.
 * Dagger constructs all singletons eagerly; if the constructor throws for an absent version the
 * entire graph fails to build.
 *
 * <h2>Validation rules at construction</h2>
 * <p>The following rules are enforced immediately, without consulting the registry:
 * <ol>
 *   <li>{@link WorkflowMigrationHandler#fromDefinitionVersion()} must be strictly less than
 *       {@link WorkflowMigrationHandler#targetDefinitionVersion()} (downgrades are forbidden
 *       in v1).</li>
 *   <li>No two handlers may share the same
 *       {@code (definitionId, fromVersion, targetVersion)} triple.</li>
 * </ol>
 *
 * <h2>Validation rules deferred to {@link #find}</h2>
 * <p>The following rules are checked lazily, on each call to
 * {@code find(definitionId, from, target)}:
 * <ol>
 *   <li>Both {@link WorkflowMigrationHandler#fromDefinitionVersion()} and
 *       {@link WorkflowMigrationHandler#targetDefinitionVersion()} must be resolvable via
 *       {@link WorkflowRegistry#resolvePinned(String, long)}. If either is absent
 *       ({@link WorkflowVersionPinUnavailableException} or
 *       {@link WorkflowDefinitionMissingException} is thrown), {@link Optional#empty()} is
 *       returned. Any other registry exception propagates immediately so it is not silently
 *       swallowed as a benign "not found" (C1 fix).</li>
 *   <li>{@link WorkflowMigrationHandler#sourceStateType()} must exactly match the source plan's
 *       {@link RuntimeWorkflow#stateType()} ({@link Class#equals} comparison, exact match in v1).
 *       Mismatch throws {@link WorkflowMigrationStateTypeMismatchException}.</li>
 *   <li>{@link WorkflowMigrationHandler#targetStateType()} must exactly match the target plan's
 *       {@link RuntimeWorkflow#stateType()}. Same semantics as above.</li>
 * </ol>
 *
 * <p>After construction the internal map is immutable; {@link #find} and {@link #all} are safe
 * to call from any thread without external synchronization.
 */
@Singleton
public class DefaultWorkflowMigrationRegistry implements WorkflowMigrationRegistry {

    /**
     * Internal key for the {@code (definitionId, fromVersion, targetVersion)} triple.
     *
     * @param definitionId the workflow definition id
     * @param fromVersion the source definition version
     * @param targetVersion the target definition version
     */
    private record MigrationKey(String definitionId, long fromVersion, long targetVersion) {}

    private final Map<MigrationKey, WorkflowMigrationHandler<?, ?>> handlers;
    private final WorkflowRegistry workflowRegistry;

    /**
     * Creates the registry from the contributed handler set.
     *
     * <p>Validates only the rules that do not require the registry (version direction, no duplicate
     * triples). State-type and version-availability checks are deferred to {@link #find}.
     *
     * @param migrationHandlers the set of contributed migration handlers; may be empty
     * @param workflowRegistry the workflow registry used at {@link #find} time to resolve plans
     * @throws WorkflowDefinitionException if any handler violates the construction-time rules
     *     (downgrade or duplicate triple)
     */
    @Inject
    public DefaultWorkflowMigrationRegistry(
            Set<WorkflowMigrationHandler<?, ?>> migrationHandlers, WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
        Map<MigrationKey, WorkflowMigrationHandler<?, ?>> map = new HashMap<>();
        for (WorkflowMigrationHandler<?, ?> handler : migrationHandlers) {
            indexHandler(handler, map);
        }
        this.handlers = Collections.unmodifiableMap(map);
    }

    // --- WorkflowMigrationRegistry ---

    /**
     * Looks up the handler for the given {@code (definitionId, sourceVersion, targetVersion)}
     * triple, validates state-type compatibility and version availability at call time, and returns
     * the handler if everything is consistent.
     *
     * <p>Returns {@link Optional#empty()} if:
     * <ul>
     *   <li>No handler is indexed for the triple.</li>
     *   <li>Either the source or target version is not yet registered in the
     *       {@link WorkflowRegistry} — specifically when {@link WorkflowRegistry#resolvePinned}
     *       throws {@link WorkflowVersionPinUnavailableException} or
     *       {@link WorkflowDefinitionMissingException} (version loaded dynamically after
     *       construction). Any other registry exception propagates as-is (C1 fix).</li>
     * </ul>
     *
     * <p>Throws {@link WorkflowMigrationStateTypeMismatchException} (a subclass of
     * {@link WorkflowDefinitionException}) if both versions are registered but the handler's
     * declared state type does not match the plan's state type. This is a configuration error
     * that must be fixed before the migration can proceed.
     *
     * @param definitionId the workflow definition id
     * @param sourceVersion the source definition version
     * @param targetVersion the target definition version
     * @return the matching handler, or {@link Optional#empty()} if not found or either version
     *     is not yet registered
     * @throws WorkflowMigrationStateTypeMismatchException if both versions are registered but the
     *     handler declares an incompatible source or target state type
     */
    @Override
    public Optional<WorkflowMigrationHandler<?, ?>> find(String definitionId, long sourceVersion, long targetVersion) {
        WorkflowMigrationHandler<?, ?> handler =
                handlers.get(new MigrationKey(definitionId, sourceVersion, targetVersion));
        if (handler == null) {
            return Optional.empty();
        }

        // Resolve source plan — if absent, the version is not yet registered (dynamic activation).
        // Only WorkflowVersionPinUnavailableException / WorkflowDefinitionMissingException mean
        // "not registered yet". Any other exception (e.g., internal registry corruption) must
        // propagate so it is not silently swallowed as a benign "not found" (C1 fix).
        RuntimeWorkflow sourcePlan;
        try {
            sourcePlan = workflowRegistry.resolvePinned(definitionId, sourceVersion);
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException ex) {
            return Optional.empty();
        }

        // Resolve target plan — same: absent means not yet registered.
        RuntimeWorkflow targetPlan;
        try {
            targetPlan = workflowRegistry.resolvePinned(definitionId, targetVersion);
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException ex) {
            return Optional.empty();
        }

        // Both versions are registered — validate state types. A mismatch here is a
        // configuration error: the handler was registered with an incorrect state type declaration.
        // Throw a typed WorkflowMigrationStateTypeMismatchException so callers can distinguish
        // this from other WorkflowDefinitionExceptions (C1 fix).
        if (!handler.sourceStateType().equals(sourcePlan.stateType())) {
            throw new WorkflowMigrationStateTypeMismatchException(
                    definitionId,
                    sourceVersion,
                    targetVersion,
                    handler.getClass().getName(),
                    "sourceStateType",
                    handler.sourceStateType(),
                    sourcePlan.stateType());
        }
        if (!handler.targetStateType().equals(targetPlan.stateType())) {
            throw new WorkflowMigrationStateTypeMismatchException(
                    definitionId,
                    sourceVersion,
                    targetVersion,
                    handler.getClass().getName(),
                    "targetStateType",
                    handler.targetStateType(),
                    targetPlan.stateType());
        }

        return Optional.of(handler);
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
     *   <li>Version direction -- {@code fromVersion < targetVersion}.</li>
     *   <li>No duplicate {@code (definitionId, fromVersion, targetVersion)} triple.</li>
     * </ol>
     *
     * @param handler the handler to index
     * @param map accumulator into which the handler is inserted
     * @throws WorkflowDefinitionException if any construction-time validation rule is violated
     */
    private static void indexHandler(
            WorkflowMigrationHandler<?, ?> handler, Map<MigrationKey, WorkflowMigrationHandler<?, ?>> map) {

        String defId = handler.definitionId();
        long fromVersion = handler.fromDefinitionVersion();
        long targetVersion = handler.targetDefinitionVersion();

        // Rule: fromVersion < targetVersion (downgrades forbidden in v1)
        if (fromVersion >= targetVersion) {
            throw new WorkflowDefinitionException("Migration handler "
                    + handler.getClass().getName()
                    + " for definition '" + defId + "' has fromVersion=" + fromVersion
                    + " >= targetVersion=" + targetVersion
                    + "; downgrades and same-version migrations are forbidden");
        }

        // Rule: no duplicate (definitionId, fromVersion, targetVersion) triple
        MigrationKey key = new MigrationKey(defId, fromVersion, targetVersion);
        WorkflowMigrationHandler<?, ?> existing = map.putIfAbsent(key, handler);
        if (existing != null) {
            throw new WorkflowDefinitionException("Duplicate migration handler for definition '" + defId
                    + "' from version " + fromVersion + " to version " + targetVersion
                    + ": " + existing.getClass().getName() + " and "
                    + handler.getClass().getName());
        }
    }
}

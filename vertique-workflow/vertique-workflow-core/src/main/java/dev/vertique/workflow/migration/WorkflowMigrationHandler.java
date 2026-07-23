// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

/**
 * SPI for migrating a workflow instance's state from one definition version to another.
 *
 * <p>An application registers migration handlers by contributing them into the Dagger
 * {@code Set<WorkflowMigrationHandler<?, ?>>} multibinding declared by
 * {@link WorkflowMigrationModule}. The {@link DefaultWorkflowMigrationRegistry} validates
 * contributed handlers in two phases:
 *
 * <h3>At construction (Dagger graph build time)</h3>
 * <ul>
 *   <li>{@link #fromDefinitionVersion()} must be strictly less than
 *       {@link #targetDefinitionVersion()} (downgrades are forbidden in v1).</li>
 *   <li>No two handlers may share the same
 *       {@code (definitionId, fromVersion, targetVersion)} triple.</li>
 * </ul>
 *
 * <h3>Deferred to {@code find()} time</h3>
 * <p>The following rules are checked lazily when the engine resolves a handler, to allow
 * {@code v2} to be loaded and activated dynamically after Dagger graph construction while a
 * {@code v1 → v2} handler is already contributed:
 * <ul>
 *   <li>Both {@link #fromDefinitionVersion()} and {@link #targetDefinitionVersion()} must be
 *       registered in the {@link dev.vertique.workflow.registry.WorkflowRegistry}. If either is
 *       absent, {@code find()} returns {@link java.util.Optional#empty()}.</li>
 *   <li>{@link #sourceStateType()} must exactly match the source plan's
 *       {@link dev.vertique.workflow.registry.RuntimeWorkflow#stateType()}. Mismatch throws
 *       {@link dev.vertique.workflow.exception.WorkflowMigrationStateTypeMismatchException}.</li>
 *   <li>{@link #targetStateType()} must exactly match the target plan's
 *       {@link dev.vertique.workflow.registry.RuntimeWorkflow#stateType()}. Same semantics.</li>
 * </ul>
 *
 * <p>Implementations are typically {@code @Singleton} — they carry no per-instance state.
 *
 * <p>PRD-WF-003 FR-WF-DEF-063 through FR-WF-DEF-068.
 *
 * @param <S> source state type; must match the source plan's state type exactly
 * @param <T> target state type; must match the target plan's state type exactly
 */
public interface WorkflowMigrationHandler<S, T> {

    /**
     * Returns the definition id this handler applies to.
     *
     * @return the workflow definition id; never {@code null}
     */
    String definitionId();

    /**
     * Returns the definition version this handler migrates <em>from</em>.
     *
     * <p>Must be strictly less than {@link #targetDefinitionVersion()}.
     *
     * @return the source definition version
     */
    long fromDefinitionVersion();

    /**
     * Returns the definition version this handler migrates <em>to</em>.
     *
     * @return the target definition version
     */
    long targetDefinitionVersion();

    /**
     * Returns the runtime {@link Class} object for the source state type.
     *
     * <p>Must match the source plan's {@link dev.vertique.workflow.registry.RuntimeWorkflow#stateType()}
     * exactly (using {@link Class#equals}).
     *
     * @return the source state class; never {@code null}
     */
    Class<S> sourceStateType();

    /**
     * Returns the runtime {@link Class} object for the target state type.
     *
     * <p>Must match the target plan's {@link dev.vertique.workflow.registry.RuntimeWorkflow#stateType()}
     * exactly (using {@link Class#equals}).
     *
     * @return the target state class; never {@code null}
     */
    Class<T> targetStateType();

    /**
     * Performs the state migration from the source version to the target version.
     *
     * <p>The engine deserializes {@code sourceState} from the persisted JSON before calling this
     * method. The returned {@link MigrationResult} controls where the instance resumes within the
     * target plan.
     *
     * <h3>Staleness contract</h3>
     * <p>The {@code sourceState} snapshot may become stale before the engine commits the migrated
     * state to durable storage. This happens when a concurrent write (signal, retry, or another
     * migration) modifies the instance between the engine's read and the subsequent write commit.
     * The engine detects staleness at write time via optimistic versioning and rolls back the
     * transaction, typically surfacing a
     * {@link dev.vertique.workflow.exception.WorkflowConflictException} to the caller.
     *
     * <p>Because of this, handlers:
     * <ul>
     *   <li><strong>MUST be deterministic</strong> — given the same {@code sourceState} and
     *       {@link MigrationContext}, this method must always return an equivalent
     *       {@link MigrationResult}. The engine may call it again on a retry after a conflict.</li>
     *   <li><strong>MUST NOT have side effects on durable storage</strong> — writing to a database,
     *       sending a message, or invoking a remote API inside this method can result in duplicate
     *       or orphaned operations when the transaction is rolled back. The engine owns all
     *       persistence and the handler should treat itself as a pure function.</li>
     * </ul>
     *
     * @param sourceState the current workflow state deserialized as {@code S}; never {@code null};
     *     the snapshot may become stale if a concurrent write races the commit
     * @param ctx contextual information about the migration request; never {@code null}
     * @return a non-null {@link MigrationResult} describing the converted state and resume
     *     position within the target plan
     */
    MigrationResult<T> migrate(S sourceState, MigrationContext ctx);
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import io.vertx.core.Future;

/**
 * Public, SQL-free facade for interacting with workflow instances.
 *
 * <p>All methods are asynchronous and return {@link Future}. Implementations must not block the
 * Vert.x event loop.
 *
 * <p>The concrete implementation (in {@code vertique-workflow-postgresql}) opens and manages its
 * own database transaction per call. Callers that need to participate in an existing transaction
 * should use {@code TransactionalWorkflowOperations} instead.
 */
public interface WorkflowOperations {

    /**
     * Starts a new workflow instance for the given command.
     *
     * <p>If a workflow instance with the same {@code idempotencyKey} within the definition scope
     * already exists, the existing instance id is returned without creating a duplicate (idempotent
     * start).
     *
     * @param cmd command describing the definition, payload, and idempotency key
     * @return a {@link Future} that resolves to the id of the created (or existing) workflow
     *     instance
     */
    Future<WorkflowInstanceId> start(StartCommand cmd);

    /**
     * Delivers an external signal to a waiting workflow instance.
     *
     * <p>The signal is applied only if the instance is in {@code WAITING} status waiting for
     * {@code signalName}. The {@code signalDedupKey} prevents duplicate signal delivery.
     *
     * @param id id of the workflow instance to signal
     * @param signalName name of the signal to deliver
     * @param payload signal payload; coerced to the declared payload type if necessary
     * @param signalDedupKey caller-supplied dedup key; no random fallback
     * @return a {@link Future} that completes when the signal has been applied and the engine has
     *     advanced to the next step (or transitioned to a terminal status)
     */
    Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey);

    /**
     * Cancels a running or waiting workflow instance.
     *
     * @param id id of the workflow instance to cancel
     * @param reason human-readable reason for the cancellation; stored in history
     * @return a {@link Future} that completes when the instance has been transitioned to
     *     {@code CANCELLED}
     */
    Future<Void> cancel(WorkflowInstanceId id, String reason);

    /**
     * Retries the current step of a failed workflow instance.
     *
     * @param id id of the {@code FAILED} workflow instance to retry
     * @return a {@link Future} that completes when the retry has been initiated and the engine has
     *     attempted to advance from the current step
     */
    Future<Void> retry(WorkflowInstanceId id);

    /**
     * Returns a read-only view of the workflow instance and its recent history.
     *
     * <p>This is a read-only operation and does not require a write transaction. It succeeds even
     * if the workflow definition version is no longer registered.
     *
     * @param id id of the workflow instance to query
     * @return a {@link Future} that resolves to a {@link WorkflowView} containing the instance
     *     snapshot and recent history
     */
    Future<WorkflowView> query(WorkflowInstanceId id);

    /**
     * Migrates a workflow instance from its current definition version to {@code targetVersion}
     * using an application-registered
     * {@link dev.vertique.workflow.migration.WorkflowMigrationHandler}.
     *
     * <p>Per PRD-WF-003 §7.4:
     * <ul>
     *   <li>The instance must be in a non-terminal status and must not have active
     *       fork-branch state.</li>
     *   <li>A {@link dev.vertique.workflow.migration.WorkflowMigrationHandler} matching
     *       {@code (definitionId, currentVersion, targetVersion)} must be registered.</li>
     *   <li>The handler's source state type must match the resolved source plan's state type;
     *       same for target.</li>
     *   <li>On success a {@code WORKFLOW_MIGRATED} history entry is appended.</li>
     * </ul>
     *
     * <p>This default implementation throws {@link UnsupportedOperationException}. The real
     * implementation lives in {@code vertique-workflow-postgresql} and requires
     * {@code WorkflowMigrationModule} to be installed in the Dagger component.
     *
     * @param id workflow instance to migrate
     * @param targetVersion the definition version to migrate to; must be registered and strictly
     *     greater than the current pin
     * @return a {@link Future} that completes when the instance has been re-pinned and advanced
     * @throws dev.vertique.workflow.migration.WorkflowMigrationHandlerMissingException if no
     *     handler matches {@code (definitionId, currentVersion, targetVersion)}
     * @throws dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException if the
     *     instance is terminal or has active branch state
     * @throws dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException if either
     *     version is unregistered
     * @throws dev.vertique.workflow.exception.WorkflowPlanHashDriftException if the source pin hash
     *     has drifted
     * @throws dev.vertique.workflow.exception.WorkflowMigrationStateTypeMismatchException if the
     *     handler's declared source or target state type does not match the corresponding
     *     registered plan's runtime state type
     */
    default Future<Void> migrate(WorkflowInstanceId id, long targetVersion) {
        return Future.failedFuture(
                new UnsupportedOperationException("migrate() is not implemented by this WorkflowOperations binding;"
                        + " install vertique-workflow-postgresql + WorkflowMigrationModule"
                        + " (or another binding that implements migrate)"));
    }
}

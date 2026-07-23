// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * Transaction-aware variant of {@link WorkflowOperations} that accepts a caller-supplied
 * transaction context.
 *
 * <p>Use this interface when you need to include workflow mutations in the same database
 * transaction as other domain operations (e.g., persisting a signal alongside an inbox dedup
 * record). The engine performs all state mutations using the provided {@code tx} object.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL
 *     implementation)
 */
public interface TransactionalWorkflowOperations<TX> {

    /**
     * Starts a new workflow instance within the given transaction.
     *
     * @param cmd command describing the definition, payload, and idempotency key
     * @param tx the active transaction context
     * @return a {@link Future} that resolves to the id of the created (or existing) workflow
     *     instance
     */
    Future<WorkflowInstanceId> start(StartCommand cmd, TX tx);

    /**
     * Delivers an external signal to a waiting workflow instance within the given transaction.
     *
     * @param id id of the workflow instance to signal
     * @param signalName name of the signal to deliver
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key; no random fallback
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal has been applied
     */
    Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey, TX tx);

    /**
     * Branch-aware variant of {@link #signal} (PRD-WF-002 §D11). Targets a specific fork-group
     * branch via {@code (forkStepId, branchId)}. When both are non-null, the engine looks up the
     * matching branch token and resumes its branch; when both are null, behaviour is identical to
     * {@link #signal(WorkflowInstanceId, String, Object, String, Object)} (instance-level signal);
     * mixed-null is rejected.
     *
     * <p>The default implementation forwards to the instance-level overload, so existing
     * implementations stay green until they opt into branch routing.
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param forkStepId optional fork step id; required when {@code branchId} is set
     * @param branchId optional branch id; required when {@code forkStepId} is set
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal is applied
     */
    default Future<Void> signal(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            TX tx) {
        if (forkStepId == null && branchId == null) {
            return signal(id, signalName, payload, signalDedupKey, tx);
        }
        if (forkStepId == null || branchId == null) {
            return Future.failedFuture(
                    new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                            + forkStepId + ", branchId=" + branchId));
        }
        return Future.failedFuture(
                new UnsupportedOperationException("branch-aware signal is not implemented by this engine"));
    }

    /**
     * Explicit-carrier variant of {@link #signal} (PRD-WF-007, Contract Appendix C4). Accepts an
     * explicit {@link DurableMetadata} durable-context carrier that, when non-null, overrides the
     * ambient capture as the authoritative base for this signal's drive.
     *
     * <p><b>No-silent-drop contract (frozen):</b> the default implementation below delegates to the
     * legacy 7-arg {@link #signal(WorkflowInstanceId, String, Object, String, String, String,
     * Object)} overload <em>only</em> when {@code signalMetadata} is {@code null} (behavior
     * unchanged). When {@code signalMetadata} is non-null and the implementing class does not
     * override this method, the call fails fast with {@link UnsupportedOperationException} instead
     * of silently discarding the supplied carrier. Real bind support requires an override — the
     * engine ({@code WorkflowEngine}) overrides this method with the actual explicit-carrier bind.
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param forkStepId optional fork step id; required when {@code branchId} is set
     * @param branchId optional branch id; required when {@code forkStepId} is set
     * @param signalMetadata optional explicit durable-context carrier overriding the ambient
     *     capture as the bind base; {@code null} preserves today's semantics
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal is applied; fails with
     *     {@link UnsupportedOperationException} when {@code signalMetadata} is non-null and this
     *     method is not overridden by the implementation
     */
    default Future<Void> signal(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable DurableMetadata signalMetadata,
            TX tx) {
        if (signalMetadata == null) {
            return signal(id, signalName, payload, signalDedupKey, forkStepId, branchId, tx);
        }
        return Future.failedFuture(
                new UnsupportedOperationException("signal metadata not supported by this implementation"));
    }

    /**
     * Cancels a running or waiting workflow instance within the given transaction.
     *
     * @param id id of the workflow instance to cancel
     * @param reason human-readable reason for the cancellation
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the instance has been transitioned to
     *     {@code CANCELLED}
     */
    Future<Void> cancel(WorkflowInstanceId id, String reason, TX tx);

    /**
     * Retries the current step of a failed workflow instance within the given transaction.
     *
     * @param id id of the {@code FAILED} workflow instance to retry
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the retry has been initiated
     */
    Future<Void> retry(WorkflowInstanceId id, TX tx);

    /**
     * Migrates a workflow instance from its current definition version to {@code targetVersion}
     * within the given transaction, using an application-registered
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
     * @param tx the active transaction context
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
    default Future<Void> migrate(WorkflowInstanceId id, long targetVersion, TX tx) {
        return Future.failedFuture(new UnsupportedOperationException(
                "migrate() is not implemented by this TransactionalWorkflowOperations binding;"
                        + " install vertique-workflow-postgresql + WorkflowMigrationModule"
                        + " (or another binding that implements migrate)"));
    }
}

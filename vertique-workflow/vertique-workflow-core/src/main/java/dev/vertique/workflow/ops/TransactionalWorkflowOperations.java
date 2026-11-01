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
 * <p>For the existing Vert.x SQL engine binding, {@code tx} must be an
 * {@code io.vertx.sqlclient.SqlConnection} with a pending transaction that was begun through
 * Vert.x, for example by {@code Pool.withTransaction}. A pool, a connection without a Vert.x
 * transaction, a connection whose transaction has completed, a connection on which a textual
 * {@code BEGIN} was issued, and a wrapper around a connection are rejected: the returned
 * {@link Future} fails with an {@link IllegalArgumentException} before any SQL is issued. Callers
 * must await the returned {@link Future} before committing or rolling back the transaction. This
 * interface itself stays generic over {@code TX}.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL
 *     implementation)
 */
public interface TransactionalWorkflowOperations<TX> {

    /**
     * Starts a new workflow instance within the given transaction.
     *
     * <p>As {@link WorkflowOperations#start(StartCommand)}, a start that creates an instance fails with
     * {@link dev.vertique.workflow.exception.WorkflowIntakeRefusedException} while the node refuses new intake, and
     * writes nothing then.
     *
     * @param cmd command describing the definition, payload, and idempotency key
     * @param tx the active transaction context
     * @return a {@link Future} that resolves to the id of the created (or existing) workflow
     *     instance
     */
    Future<WorkflowInstanceId> start(StartCommand cmd, TX tx);

    /**
     * Records an external signal for a workflow instance within the given transaction. The signal is applied later, by
     * an executor, in the order the instance's commands were recorded. As in
     * {@link WorkflowOperations#signal(WorkflowInstanceId, String, Object, String)}, the call also fails with
     * {@link dev.vertique.workflow.exception.WorkflowIntakeRefusedException} while the node refuses new intake, and
     * writes nothing then. A signal that is already durable, such as one the outbox relay delivers, is recorded
     * through {@link RelayedSignals} instead.
     *
     * @param id id of the workflow instance to signal
     * @param signalName name of the signal to deliver
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key; no random fallback
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal has been recorded, not when it has been applied
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
     * @return a {@link Future} that completes when the signal is recorded
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
     * @return a {@link Future} that completes when the signal is recorded; fails with
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
     * Cancels a workflow instance that has not ended within the given transaction, as
     * {@link WorkflowOperations#cancel(WorkflowInstanceId, String)} does: under the instance's execution lock, every
     * unsettled command, timer and task of the instance is cancelled with it.
     *
     * @param id id of the workflow instance to cancel
     * @param reason human-readable reason for the cancellation; cut at 255 code points, with its control characters
     *     replaced, before it is stored
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the instance has been transitioned to
     *     {@code CANCELLED}
     */
    Future<Void> cancel(WorkflowInstanceId id, String reason, TX tx);

    /**
     * Records a retry of a workflow instance within the given transaction and returns once it is recorded; the rules of
     * {@link WorkflowOperations#retry(WorkflowInstanceId)} apply. Nothing runs in the transaction but the recording.
     *
     * @param id id of the workflow instance to recover; it must be {@code FAILED} or hold a dead-lettered command
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the retry is recorded
     */
    Future<Void> retry(WorkflowInstanceId id, TX tx);

    /**
     * Records the migration of a workflow instance from its current definition version to {@code targetVersion} within
     * the given transaction and returns once it is recorded; the rules of
     * {@link WorkflowOperations#migrate(WorkflowInstanceId, long)} apply. Nothing runs in the transaction but the
     * recording.
     *
     * <p>This default implementation throws {@link UnsupportedOperationException}. The real
     * implementation lives in {@code vertique-workflow-postgresql} and requires
     * {@code WorkflowMigrationModule} to be installed in the Dagger component.
     *
     * @param id workflow instance to migrate
     * @param targetVersion the definition version to migrate to
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the migration is recorded
     */
    default Future<Void> migrate(WorkflowInstanceId id, long targetVersion, TX tx) {
        return Future.failedFuture(new UnsupportedOperationException(
                "migrate() is not implemented by this TransactionalWorkflowOperations binding;"
                        + " install vertique-workflow-postgresql + WorkflowMigrationModule"
                        + " (or another binding that implements migrate)"));
    }

    /**
     * Migrates a workflow instance to {@code targetVersion} of the definition of the same id in
     * {@code targetScope} within the given transaction.
     *
     * <p>The rules of {@link WorkflowOperations#migrate(WorkflowInstanceId, String, long)} apply: the migration is
     * recorded here and carried out by an executor.
     *
     * <p>This default implementation throws {@link UnsupportedOperationException}.
     *
     * @param id workflow instance to migrate
     * @param targetScope the scope of the definition to migrate to; the instance's start scope or the default scope
     * @param targetVersion the definition version to migrate to; must be registered in {@code targetScope}
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the migration is recorded
     */
    default Future<Void> migrate(WorkflowInstanceId id, String targetScope, long targetVersion, TX tx) {
        return Future.failedFuture(new UnsupportedOperationException(
                "migrate() is not implemented by this TransactionalWorkflowOperations binding;"
                        + " install vertique-workflow-postgresql + WorkflowMigrationModule"
                        + " (or another binding that implements migrate)"));
    }

    /**
     * Records a signal for the one open instance that waits on a key, within the given transaction. The signal is
     * applied later, by an executor, in the order the instance's commands were recorded.
     *
     * <p>The delivery finds the open wait of the signal's contract, signal name and key, narrowed by the signal's
     * definition id and scope when it names them, and records the signal for that wait's instance. It fails with
     * {@link dev.vertique.workflow.exception.WorkflowIntakeRefusedException} while the node refuses new intake, and
     * writes nothing then.
     *
     * <p>This default implementation fails the returned future with {@link UnsupportedOperationException}.
     *
     * @param signal the signal, addressed by contract, signal name and key
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal has been recorded, not when it has been applied
     */
    default Future<CorrelatedSignalReceipt> signalByKey(CorrelatedSignal signal, TX tx) {
        return Future.failedFuture(new UnsupportedOperationException(
                "signalByKey() is not implemented by this TransactionalWorkflowOperations binding"));
    }

    /**
     * Records a signal for the one live instance that carries a business key, within the given transaction. The signal
     * is applied later, by an executor, in the order the instance's commands were recorded. It fails with
     * {@link dev.vertique.workflow.exception.WorkflowIntakeRefusedException} while the node refuses new intake, and
     * writes nothing then.
     *
     * <p>This default implementation fails the returned future with {@link UnsupportedOperationException}.
     *
     * @param signal the signal; its key is the business key of the instance
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal has been recorded, not when it has been applied
     */
    default Future<CorrelatedSignalReceipt> signalByBusinessKey(CorrelatedSignal signal, TX tx) {
        return Future.failedFuture(new UnsupportedOperationException(
                "signalByBusinessKey() is not implemented by this TransactionalWorkflowOperations binding"));
    }
}

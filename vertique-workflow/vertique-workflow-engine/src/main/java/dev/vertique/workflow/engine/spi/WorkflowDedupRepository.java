// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository SPI for workflow deduplication records.
 *
 * <p>Generic over the transaction-handle type {@code TX} so the workflow engine can depend on this
 * interface without binding to a specific database client.
 *
 * <p>Covers start, signal, task-completion/reassignment, and service-dispatch deduplication. The
 * {@code claimOrResolve*} methods use a race-safe atomic upsert: exactly one concurrent caller
 * observes {@code didInsert}/{@code inserted = true} and is responsible for proceeding with the
 * deduplicated operation; the rest treat it as an idempotent no-op (or a conflict when the recorded
 * fingerprint differs).
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface WorkflowDedupRepository<TX> {

    // --- Start dedup ---

    /**
     * Reads the start dedup row of a start scope, definition id and idempotency key without writing.
     *
     * @param scopeKey       the start scope of the start
     * @param definitionId   the workflow definition id; used as the dedup namespace
     * @param idempotencyKey the caller-supplied idempotency key
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the id of the instance the committed row names, or
     *         {@link Optional#empty()} when no row is visible
     */
    Future<Optional<WorkflowInstanceId>> findStart(String scopeKey, String definitionId, String idempotencyKey, TX tx);

    /**
     * Atomically claims a start dedup slot or resolves an existing one, recording the definition
     * version fingerprint.
     *
     * @param scopeKey       the start scope of the start
     * @param definitionId   the workflow definition id; used as the dedup namespace
     * @param idempotencyKey the caller-supplied idempotency key
     * @param proposedNewId  the workflow instance id this caller proposes to use if it wins
     * @param fingerprint    the version fingerprint to record, e.g. {@code "definitionVersion=1"}
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the result; {@code didInsert=true} when this caller won
     */
    Future<StartDedupResult> claimOrResolveStart(
            String scopeKey,
            String definitionId,
            String idempotencyKey,
            WorkflowInstanceId proposedNewId,
            String fingerprint,
            TX tx);

    // --- Signal dedup ---

    /**
     * Looks up an existing signal dedup record for the given workflow instance and dedup key.
     *
     * @param workflowId     the workflow instance id (used as the dedup scope)
     * @param signalDedupKey the caller-supplied signal dedup key
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the persisted {@link UUID} if the signal was already
     *         applied, or {@link Optional#empty()} if not
     */
    Future<Optional<UUID>> findSignal(WorkflowInstanceId workflowId, String signalDedupKey, TX tx);

    /**
     * Records that a signal has been applied to the given workflow instance.
     *
     * @param workflowId     the workflow instance id (used as the dedup scope)
     * @param signalDedupKey the caller-supplied signal dedup key
     * @param tx             the active transaction to use
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> insertSignal(WorkflowInstanceId workflowId, String signalDedupKey, TX tx);

    /**
     * Atomically claims a signal dedup slot or resolves an existing one. Race-safe replacement for
     * the {@link #findSignal} + {@link #insertSignal} two-step.
     *
     * @param workflowId     the workflow instance id (used as the dedup scope)
     * @param signalDedupKey the caller-supplied signal dedup key
     * @param tx             the active transaction to use
     * @return a {@link Future} of {@code true} when this caller won the race, {@code false} when the
     *         dedup row already existed
     */
    Future<Boolean> claimOrResolveSignal(WorkflowInstanceId workflowId, String signalDedupKey, TX tx);

    /**
     * Variant of {@link #claimOrResolveSignal} that accepts a caller-supplied {@code (scope, key)}
     * pair directly. Used by the branch-aware signal path, which folds branch identity into the
     * dedup key so two sibling branches sharing the same caller-supplied dedup key cannot collide.
     *
     * @param scope      dedup scope (typically the workflow id string)
     * @param key        dedup key (typically a hash combining branch identity + dedup key)
     * @param workflowId workflow instance id (for the FK column)
     * @param tx         the active transaction
     * @return a {@link Future} of {@code true} when this caller won the race, {@code false} when the
     *         dedup row already existed
     */
    Future<Boolean> claimOrResolveSignalScoped(String scope, String key, WorkflowInstanceId workflowId, TX tx);

    // --- Correlated dedup ---

    /**
     * Reads, without writing, the committed or own {@code correlated} dedup row of a namespace and key.
     *
     * @param namespace the hashed namespace of the contract, signal name and scope
     * @param key       the hashed dedup key
     * @param tx        the active transaction to use
     * @return a {@link Future} containing the id of the instance the row names, or {@link Optional#empty()} when no
     *         row is visible; the default implementation returns a failed future holding an
     *         {@link UnsupportedOperationException}, for a repository that does not support signals delivered by key
     */
    default Future<Optional<WorkflowInstanceId>> findCorrelated(String namespace, String key, TX tx) {
        return Future.failedFuture(
                new UnsupportedOperationException("This dedup repository does not support signals delivered by key"));
    }

    /**
     * Atomically claims a {@code correlated} dedup slot or resolves an existing one. The row is unique per namespace
     * and key across instances, and goes with the instance it names.
     *
     * @param namespace  the hashed namespace of the contract, signal name and scope
     * @param key        the hashed dedup key
     * @param workflowId the matched instance; stored as the FK reference
     * @param tx         the active transaction to use
     * @return a {@link Future} of {@code true} when this call inserted the row, {@code false} when it already existed;
     *         the default implementation returns a failed future holding an {@link UnsupportedOperationException}, for
     *         a repository that does not support signals delivered by key
     */
    default Future<Boolean> claimOrResolveCorrelated(
            String namespace, String key, WorkflowInstanceId workflowId, TX tx) {
        return Future.failedFuture(
                new UnsupportedOperationException("This dedup repository does not support signals delivered by key"));
    }

    // --- Task dedup ---

    /**
     * Reads, without writing, the fingerprint of the committed completion of a task under an idempotency key.
     *
     * @param taskId         the task id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the fingerprint the committed completion was recorded with, or empty when no
     *     completion of the task under that key has committed
     */
    Future<Optional<String>> findTaskCompletion(UUID taskId, String idempotencyKey, TX tx);

    /**
     * Atomically claims or resolves a task-completion dedup slot, including fingerprint comparison.
     *
     * @param taskId         the task id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param workflowId     the workflow instance id; stored as the FK reference
     * @param fingerprint    the fingerprint of the completion command
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the dedup claim result
     */
    Future<DedupClaim> claimOrResolveTaskCompletion(
            UUID taskId, String idempotencyKey, WorkflowInstanceId workflowId, String fingerprint, TX tx);

    /**
     * Atomically claims or resolves a task-reassignment dedup slot, including fingerprint
     * comparison. Uses a distinct dedup kind so completion and reassignment dedup rows for the same
     * task id never collide.
     *
     * @param taskId         the task id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param workflowId     the workflow instance id; stored as the FK reference
     * @param fingerprint    the fingerprint of the reassignment command
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the dedup claim result
     */
    Future<DedupClaim> claimOrResolveTaskReassignment(
            UUID taskId, String idempotencyKey, WorkflowInstanceId workflowId, String fingerprint, TX tx);

    // --- Dispatch dedup ---

    /**
     * Atomically claims a branch service-dispatch dedup slot or resolves an existing one. Returns
     * {@code true} iff this caller inserted the row and should proceed with routing the side-effect
     * intent; {@code false} iff a prior committed transaction already held the row and the caller
     * should skip routing (the dispatch was already recorded).
     *
     * @param workflowId the parent workflow instance id
     * @param scope      the canonical dispatch scope
     * @param key        the canonical dispatch key
     * @param tx         the active transaction
     * @return a {@link Future} of {@code true} when this caller won the race
     */
    Future<Boolean> claimOrResolveDispatch(WorkflowInstanceId workflowId, String scope, String key, TX tx);
}

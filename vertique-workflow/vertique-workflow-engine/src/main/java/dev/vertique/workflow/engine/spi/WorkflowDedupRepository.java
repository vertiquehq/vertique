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
     * Atomically claims a start dedup slot or resolves an existing one, recording the definition
     * version fingerprint for idempotency-conflict detection.
     *
     * @param definitionId   the workflow definition id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param proposedNewId  the workflow instance id this caller proposes to use if it wins
     * @param fingerprint    the version fingerprint to record, e.g. {@code "definitionVersion=1"}
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the result; {@code didInsert=true} when this caller won
     */
    Future<StartDedupResult> claimOrResolveStart(
            String definitionId, String idempotencyKey, WorkflowInstanceId proposedNewId, String fingerprint, TX tx);

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

    // --- Task dedup ---

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

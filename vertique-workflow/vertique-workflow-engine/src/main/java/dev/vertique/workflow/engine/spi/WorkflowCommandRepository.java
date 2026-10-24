// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository SPI for the recorded command log.
 *
 * <p>Generic over the transaction-handle type {@code TX} like the other repositories, so the engine does not bind to
 * a database client.
 *
 * <p>The read members open no transaction on a caller's connection and hold no lock: each borrows one pooled
 * connection for one statement and returns it. They judge eligibility against the database clock, through the one
 * eligibility rule of the executor protocol: a command is claimable when it is at the head of its queue, not held
 * back and available.
 *
 * <p>The lock and sequence members run inside a caller's transaction. Every transaction that changes an instance
 * takes the instance's execution lock before its first workflow change, and holds it until it ends, so one
 * transaction at a time changes an instance and appends to its history.
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface WorkflowCommandRepository<TX> {

    /**
     * Counts the commands that keep an instance from being idle: commands in flight, that is claimed under a live
     * lease, and commands that are claimable now.
     *
     * @param instance the instance to count, or {@code null} for every instance
     * @return a {@link Future} containing the number of runnable commands
     */
    Future<Integer> countRunnable(@Nullable WorkflowInstanceId instance);

    /**
     * Lists the deferred commands that are not claimable now, ordered by instance and sequence.
     *
     * @param instance the instance to read, or {@code null} for every instance
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findDeferred(@Nullable WorkflowInstanceId instance);

    /**
     * Lists the parked commands, ordered by instance and sequence.
     *
     * @param instance the instance to read, or {@code null} for every instance
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findParked(@Nullable WorkflowInstanceId instance);

    /**
     * Lists the pending and claimed commands that are not claimable now, ordered by instance and sequence: timers
     * before their fire time, backed-off commands, commands behind a blocking head and held-back timers.
     *
     * @param instance the instance to read, or {@code null} for every instance
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findNotYetAvailable(@Nullable WorkflowInstanceId instance);

    /**
     * Takes the instance's execution lock for the caller's transaction, waiting for another transaction that holds it
     * for at most the configured lock timeout. The timeout applies to this one acquisition; the caller's own
     * {@code lock_timeout} is unchanged afterwards. Taking a lock the transaction already holds returns at once.
     *
     * <p>The lock is released when the transaction ends. A wait that times out, or a deadlock between transactions,
     * fails the future with a retryable persistence failure, and the database aborts the transaction.
     *
     * @param instance the instance to lock; it need not exist yet
     * @param tx       the caller's transaction handle
     * @return a {@link Future} that completes once the transaction holds the lock
     */
    Future<Void> lockInstance(WorkflowInstanceId instance, TX tx);

    /**
     * Takes the instance's execution lock for the caller's transaction only if it is free, and never waits.
     *
     * @param instance the instance to lock
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing {@code true} when the transaction now holds the lock and {@code false} when
     *     another transaction holds it
     */
    Future<Boolean> tryLockInstance(WorkflowInstanceId instance, TX tx);

    /**
     * Allocates the next position in the instance's command queue. The queue row is created on first touch, so the
     * first allocation is 1; a rolled-back transaction leaves no trace and its position is allocated again.
     *
     * <p>The caller must hold the instance's execution lock; the allocation is not safe without it.
     *
     * @param instance the instance whose queue advances
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing the allocated position
     */
    Future<Long> nextQueueSequence(WorkflowInstanceId instance, TX tx);

    /**
     * Records a {@code START} command for a new instance in the caller's transaction: the next position of the
     * instance's queue, state {@code PENDING}, recorded and available at the database clock now, with the start
     * payload stored as the command's payload.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param instance       the new instance
     * @param idempotencyKey the caller's idempotency key, kept for display
     * @param payloadJson    the start payload as JSON, or {@code null} when the start has none
     * @param targetJson     the caller-supplied subject reference of the start as JSON, or {@code null} when the
     *                       caller supplied none
     * @param tx             the caller's transaction handle
     * @return a {@link Future} containing the command's row id
     */
    Future<Long> recordStart(
            WorkflowInstanceId instance,
            String idempotencyKey,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            TX tx);

    /**
     * Reads, without locks, the commands an executor could claim now, oldest availability first. Judges eligibility
     * on the database clock through the one eligibility rule.
     *
     * @param limit the most heads to return; at least 1
     * @return a {@link Future} containing the heads
     */
    Future<List<CommandHead>> peek(int limit);

    /**
     * Claims a peeked command in one short transaction of its own: tries the instance's execution lock, locks the
     * command row, checks again that it is claimable, spends an attempt, sets a new token and a lease and marks it
     * {@code CLAIMED}. When the lock is busy or the command is no longer claimable, nothing is written.
     *
     * @param head         the peeked head
     * @param claimedBy    the executor instance that claims
     * @param leaseSeconds how long the claim holds the command
     * @return a {@link Future} containing the claim, or empty when the lock was busy or the command was not claimable
     */
    Future<Optional<ClaimedCommand>> claim(CommandHead head, String claimedBy, int leaseSeconds);

    /**
     * Locks the claimed command's row and checks, in the segment's transaction, that the claim still stands: the
     * command is {@code CLAIMED} with this token and at the head of its queue. When it does not, nothing is written.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param claim the claim
     * @param tx    the segment's transaction handle
     * @return a {@link Future} containing the command, or empty when the fence failed
     */
    Future<Optional<SegmentCommand>> fence(ClaimedCommand claim, TX tx);

    /**
     * Completes the claimed command in the segment's transaction: state {@code COMPLETE}, token and lease cleared,
     * settled now. The write is fenced on the command being {@code CLAIMED} with this token.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated; {@code 0} means the claim no longer stands
     */
    Future<Integer> complete(long commandId, UUID token, TX tx);

    /**
     * Settles the claimed command as rejected, in the segment's transaction, because it no longer applies. The write
     * is fenced like {@link #complete}.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated
     */
    Future<Integer> reject(long commandId, UUID token, TX tx);

    /**
     * Releases a claim whose segment did not run, in one statement that takes no execution lock: back to
     * {@code PENDING}, the spent attempt restored, token and lease cleared. Writes only while the command is still
     * {@code CLAIMED} with this token.
     *
     * @param claim the claim to release
     * @return a {@link Future} containing {@code true} when the claim was released and {@code false} when it no
     *     longer stood
     */
    Future<Boolean> release(ClaimedCommand claim);
}

// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;

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
}

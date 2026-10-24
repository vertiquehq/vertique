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
 * <p>The read members below open no transaction on a caller's connection and hold no lock: each borrows one pooled
 * connection for one statement and returns it. They judge eligibility against the database clock, through the one
 * eligibility rule of the executor protocol: a command is claimable when it is at the head of its queue, not held
 * back and available.
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
}

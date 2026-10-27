// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import io.vertx.core.Future;

/**
 * Moves a human task between {@code OPEN} and {@code COMPLETING}, with the recorded completion that owns the
 * reservation.
 *
 * <p>Recording a completion reserves its task; the executor that applies the completion either completes the task or,
 * when the completion is rejected, reopens it. Both members are compare-and-set: they write only when the task is in
 * the expected state and answer {@code false} otherwise, so a stale or duplicate caller changes nothing.
 *
 * <p>INTERNAL: framework wiring between the engine and a database adapter. It is not application API.
 *
 * @param <C> the transaction-handle type
 */
public interface TaskReservations<C> {

    /**
     * Reserves an {@code OPEN} task for a recorded completion: the task becomes {@code COMPLETING} and stores the
     * completion command as its owner.
     *
     * @param taskId    the task's id
     * @param commandId the id of the completion command that reserves the task
     * @param tx        the caller's transaction handle
     * @return a future of {@code true} when the task was {@code OPEN} and is now reserved by the command, {@code false}
     *     when it was in any other state
     */
    Future<Boolean> markCompleting(String taskId, long commandId, C tx);

    /**
     * Releases the reservation of a rejected completion: the task becomes {@code OPEN} again and its owner is cleared.
     *
     * @param taskId    the task's id
     * @param commandId the id of the completion command that owns the reservation
     * @param tx        the caller's transaction handle
     * @return a future of {@code true} when the task was {@code COMPLETING} and owned by the command, {@code false}
     *     when it was in any other state or owned by another command
     */
    Future<Boolean> reopen(String taskId, long commandId, C tx);
}

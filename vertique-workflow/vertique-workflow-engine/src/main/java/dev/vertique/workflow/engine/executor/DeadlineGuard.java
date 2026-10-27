// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * INTERNAL: the deadline guard of the executor protocol. A signal is never applied at a wait whose timeout it was
 * recorded at or after: a signal is deadline-blocked at a wait while a timeout command of that wait, fired at or before
 * the signal's recording time, is still pending, deferred, claimed or dead-lettered.
 *
 * <p>The guard reads the timeout commands the repository holds. A timeout command stores the wait it belongs to as its
 * target: the wait's step id and, for a wait of a branch, the branch id.
 */
public final class DeadlineGuard {

    private final WorkflowCommandRepository<SqlClient> commands;

    /**
     * Creates the guard over the command repository.
     *
     * @param commands the command repository
     */
    public DeadlineGuard(WorkflowCommandRepository<SqlClient> commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Keeps the parked signals that are not deadline-blocked at the wait, in their order.
     *
     * @param instance   the instance
     * @param waitStepId the step of the wait
     * @param branchId   the branch of a branch wait, or {@code null}
     * @param parked     the parked signals the wait accepts
     * @param tx         the segment's transaction handle
     * @return the signals that may be applied at the wait
     */
    Future<List<ParkedSignal>> unblocked(
            WorkflowInstanceId instance,
            String waitStepId,
            @Nullable String branchId,
            List<ParkedSignal> parked,
            SqlClient tx) {
        if (parked.isEmpty()) {
            return Future.succeededFuture(parked);
        }
        return commands.deadlineBlocked(
                        instance,
                        waitStepId,
                        branchId,
                        parked.stream().map(ParkedSignal::commandId).toList(),
                        tx)
                .map((Set<Long> blocked) -> parked.stream()
                        .filter(signal -> !blocked.contains(signal.commandId()))
                        .toList());
    }

    /**
     * Tells whether one signal is deadline-blocked at a wait.
     *
     * @param instance   the instance
     * @param waitStepId the step of the wait
     * @param branchId   the branch of a branch wait, or {@code null}
     * @param signalId   the row id of the signal command
     * @param tx         the segment's transaction handle
     * @return {@code true} when the signal must not be applied at the wait
     */
    public Future<Boolean> blocked(
            WorkflowInstanceId instance, String waitStepId, @Nullable String branchId, long signalId, SqlClient tx) {
        return commands.deadlineBlocked(instance, waitStepId, branchId, List.of(signalId), tx)
                .map(blocked -> blocked.contains(signalId));
    }
}
